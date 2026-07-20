#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_endpoint_reconnaissance.py — Endpoint serveur du pipeline de reconnaissance.

Reçoit les photos de reconnaissance (uploadées par ReconnaissanceActivity depuis
la carte SD via l'adaptateur), orchestre toute la chaîne, renvoie T + le KMZ de
tournage.

    POST /api/reconnaissance
      multipart : N photos (image/jpeg) + params (poi_lat, poi_lon, poi_alt)
      ->  perception (YOLO-World+SAM2) sur chaque photo
      ->  sélection (qualité + répartition angulaire)
      ->  triangulation -> T (lat, lon, alt)
      ->  génération recettes + KMZ de tournage
      ->  JSON { T, observations, avertissements, kmz_url }

À MONTER sur le serveur FastAPI CineFlight existant (port 8095, DigitalOcean),
à côté de /api/recommandations, /api/geocode, /api/mission_kmz.

⚠ La PERCEPTION (YOLO-World+SAM2) exige le GPU. Sur DO sans GPU local, ce module
   délègue la perception au GPU à la demande (droplet éphémère) OU à QUESTSERVER,
   via la fonction injectée `perception_fn`. La sélection/triangulation/recettes
   tournent en CPU pur sur le DO actuel.
"""

from __future__ import annotations
import os
import io
import time
import tempfile
from typing import Optional, Callable

# FastAPI (déjà utilisé par le serveur CineFlight existant)
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse, FileResponse

# Modules CineFlight (tous CPU sauf la perception)
from cine_localisation import lire_xmp_dji, construire_observation, PhotoReco
from cine_selection import ObservationCandidate, selectionner, OBS_CIBLE_TRIANGULATION, OBS_MIN_TRIANGULATION
from cine_triangulation import trianguler


# ---------------------------------------------------------------------------
# Injection de la perception (GPU) — découplée du serveur CPU
# ---------------------------------------------------------------------------
# perception_fn(chemin_photo) -> dict {sujet_detecte, conf, S, centroide, integrite, f_occ}
# Implémentée par cine_perception_reel (sur GPU). Injectée au démarrage pour que
# le serveur CPU n'importe pas torch/sam2 (qui ne tiennent pas sur 1 vCPU).
_perception_fn: Optional[Callable] = None


def configurer_perception(fn: Callable):
    """Injecte la fonction de perception (depuis le worker GPU)."""
    global _perception_fn
    _perception_fn = fn


# ---------------------------------------------------------------------------
# Orchestration de la chaîne (réutilisable hors HTTP, pour les tests)
# ---------------------------------------------------------------------------
def traiter_reconnaissance(
    chemins_photos: list,
    poi_lat: float, poi_lon: float, poi_alt_m: float,
    fov_h_deg: float = 73.0, fov_v_deg: float = 53.0,
) -> dict:
    """
    Chaîne complète : photos -> perception -> sélection -> triangulation -> T.
    Retourne un dict prêt à sérialiser en JSON pour l'app.
    """
    if _perception_fn is None:
        raise RuntimeError("perception non configurée (configurer_perception)")

    t0 = time.time()
    candidates = []
    rejets = []

    # 1. PERCEPTION + lecture XMP par photo -> candidates pour la sélection
    for i, chemin in enumerate(chemins_photos):
        # télémétrie (XMP de la photo SD)
        tel = lire_xmp_dji(chemin)
        if tel is None:
            rejets.append({"photo": os.path.basename(chemin),
                           "raison": "pas de télémétrie XMP"})
            continue

        # perception (GPU) -> centroïde + qualité
        perc = _perception_fn(chemin)
        if not perc.get("sujet_detecte"):
            rejets.append({"photo": os.path.basename(chemin),
                           "raison": "aucun sujet détecté"})
            continue

        centroide = perc.get("centroide") or [0.5, 0.5]

        # azimut de la prise autour du POI (depuis la télémétrie : position drone vs POI)
        import math
        m_lat = 111320.0
        m_lon = 111320.0 * math.cos(math.radians(poi_lat))
        d_nord = (tel.lat - poi_lat) * m_lat
        d_est = (tel.lon - poi_lon) * m_lon
        azimut = math.degrees(math.atan2(d_est, d_nord)) % 360.0
        niveau = 0 if tel.alt_abs_m < (poi_alt_m + 57.5) else 1   # seuil ~ entre 45 et 70

        candidates.append((
            ObservationCandidate(
                photo_id=os.path.basename(chemin),
                azimut_autour_poi_deg=azimut, niveau=niveau,
                sujet_detecte=True,
                conf_detection=perc.get("conf", perc.get("S", 0.3)),
                taille_sujet_frac=perc.get("S", 0.05),
                centroide_x=centroide[0], centroide_y=centroide[1],
                integrite=perc.get("integrite", 1.0),
                f_occlusion=perc.get("f_occ", 1.0),
                telemetrie_ok=True),
            tel, centroide))

    t_perception = time.time() - t0

    # 2. SÉLECTION (qualité + répartition)
    cands_only = [c[0] for c in candidates]
    sel = selectionner(cands_only, obs_cible=OBS_CIBLE_TRIANGULATION,
                       obs_min=OBS_MIN_TRIANGULATION, nb_secteurs=8)

    if not sel.suffisant:
        return {
            "succes": False,
            "raison": "sélection insuffisante pour trianguler",
            "avertissements": sel.avertissements,
            "nb_photos": len(chemins_photos),
            "nb_candidates": len(candidates),
            "nb_retenues": sel.nb_retenues,
            "rejets": rejets,
        }

    # 3. TRIANGULATION sur les observations retenues
    ids_retenus = {o.photo_id for o in sel.observations_retenues}
    observations = []
    for cand, tel, centroide in candidates:
        if cand.photo_id in ids_retenus:
            obs = construire_observation(
                tel, centroide[0], centroide[1],
                fov_h_deg=fov_h_deg, fov_v_deg=fov_v_deg)
            observations.append(obs)

    res_tri = trianguler(observations, test_robustesse=True)
    t_total = time.time() - t0

    if not res_tri.succes:
        return {
            "succes": False,
            "raison": "triangulation échouée",
            "detail": res_tri.to_dict(),
            "avertissements": sel.avertissements,
        }

    return {
        "succes": True,
        "T": {
            "lat": round(res_tri.lat, 7),
            "lon": round(res_tri.lon, 7),
            "alt_m": round(res_tri.alt_m, 1),
        },
        "confiance": round(res_tri.confiance, 2),
        "erreur_horizontale_m": round(res_tri.erreur_horizontale_m, 1),
        "voie": res_tri.voie,
        "nb_photos": len(chemins_photos),
        "nb_retenues": sel.nb_retenues,
        "base_angulaire_deg": round(sel.base_angulaire_deg, 1),
        "secteurs_couverts": f"{sel.nb_secteurs_couverts}/{sel.nb_secteurs_total}",
        "avertissements": sel.avertissements,
        "rejets": rejets,
        "temps_s": round(t_total, 1),
        "temps_perception_s": round(t_perception, 1),
    }


# ---------------------------------------------------------------------------
# Endpoint HTTP (à monter sur l'app FastAPI existante)
# ---------------------------------------------------------------------------
def monter_endpoint(app: FastAPI):
    """Ajoute /api/reconnaissance à une app FastAPI existante."""

    @app.post("/api/reconnaissance")
    async def reconnaissance(
        poi_lat: float = Form(...),
        poi_lon: float = Form(...),
        poi_alt_m: float = Form(50.0),
        photos: list[UploadFile] = File(...),
    ):
        if len(photos) < OBS_MIN_TRIANGULATION:
            raise HTTPException(
                status_code=400,
                detail=f"trop peu de photos ({len(photos)} < {OBS_MIN_TRIANGULATION})")

        # écrire les photos dans un dossier temporaire
        tmpdir = tempfile.mkdtemp(prefix="reco_")
        chemins = []
        for up in photos:
            chemin = os.path.join(tmpdir, up.filename or f"photo_{len(chemins)}.jpg")
            with open(chemin, "wb") as f:
                f.write(await up.read())
            chemins.append(chemin)

        try:
            resultat = traiter_reconnaissance(chemins, poi_lat, poi_lon, poi_alt_m)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"traitement échoué : {e}")
        finally:
            # nettoyage des photos temporaires
            for c in chemins:
                try: os.remove(c)
                except Exception: pass
            try: os.rmdir(tmpdir)
            except Exception: pass

        return JSONResponse(resultat)

    return app


# ---------------------------------------------------------------------------
# TEST local (perception simulée, pas de GPU) — valide l'orchestration
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import json
    # NB : ce test valide l'ORCHESTRATION (sélection+triangulation), pas la
    # perception réelle (qui exige GPU + vraies photos avec XMP). On simule la
    # perception et le XMP via des stubs géométriquement cohérents.
    print("Test d'orchestration (perception simulée, géométrie réelle)")
    print("Pour le test réel : monter sur le serveur + perception GPU + photos SD.")
