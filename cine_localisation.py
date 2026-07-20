#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_localisation.py — Pont perception -> triangulation -> position 3D du sujet.

Transforme un LOT de photos de reconnaissance (prises sous différents angles
du même sujet) en T = position 3D réelle du sujet (lat, lon, alt), autour de
laquelle les recettes génèrent leurs trajectoires.

CHAÎNE COMPLÈTE :
    photo SD DJI  ─┬─> XMP (position drone + attitude gimbal)  ─┐
                   └─> perception (YOLO-World+SAM2) -> centroïde ┤
                                                                 ├─> Observation
    [N photos]  ─────────────────────────────────────────────────┘
                          -> trianguler() -> T (lat, lon, alt) + confiance

SOURCE DE TÉLÉMÉTRIE : le bloc XMP DJI écrit dans chaque JPEG pleine résolution
de la carte SD (namespace http://www.dji.com/drone-dji/1.0/). Champs lus :
    GpsLatitude / GpsLongitude       -> position horizontale du drone
    AbsoluteAltitude                 -> altitude (au-dessus du niveau de la mer)
    GimbalYawDegree                  -> cap de visée (compas)
    GimbalPitchDegree                -> tilt de visée (négatif = vers le bas)

⚠ Les photos "low_quality" de l'app DJI Fly N'ONT PAS ce XMP : il faut les
photos ORIGINALES pleine résolution de la carte SD.

⚠ CONVENTIONS À CALIBRER sur une vraie photo SD (voir lire_xmp_dji) :
   - signe/référence du GimbalYawDegree (nord magnétique vs vrai)
   - FOV réel du Mini 3 (défaut 73°x53°, à confirmer)
"""

from __future__ import annotations
import re
import math
from dataclasses import dataclass
from typing import Optional

from cine_triangulation import Observation, trianguler, ResultatTriangulation


# ===========================================================================
# 1. LECTURE DE LA TÉLÉMÉTRIE XMP DJI
# ===========================================================================
@dataclass
class TelemetriePhoto:
    """Télémétrie extraite du XMP d'une photo DJI."""
    lat: float
    lon: float
    alt_abs_m: float           # altitude absolue (au-dessus du niveau de la mer)
    alt_rel_m: Optional[float] # altitude relative au décollage (si présente)
    gimbal_yaw_deg: float      # cap de visée
    gimbal_pitch_deg: float    # tilt de visée (négatif = bas)
    source: str = "xmp_dji"


# noms de champs DJI (plusieurs orthographes vues selon firmwares)
_CHAMPS_DJI = {
    "lat":   ["GpsLatitude", "GpsLatitude", "Latitude"],
    "lon":   ["GpsLongitude", "GpsLongtitude", "Longitude"],   # "Longtitude" = faute DJI historique
    "altabs":["AbsoluteAltitude"],
    "altrel":["RelativeAltitude"],
    "yaw":   ["GimbalYawDegree"],
    "pitch": ["GimbalPitchDegree"],
}


def _chercher_champ(xmp: str, noms: list) -> Optional[float]:
    """Cherche le 1er champ présent parmi 'noms' dans le bloc XMP, retourne float."""
    for nom in noms:
        # deux formes : attribut  drone-dji:Champ="valeur"  ou élément <drone-dji:Champ>valeur</...>
        m = re.search(nom + r'\s*=\s*"([-+0-9.]+)"', xmp)
        if not m:
            m = re.search(r'[:>]' + nom + r'[^>]*>([-+0-9.]+)', xmp)
        if not m:
            m = re.search(nom + r'[">:= ]+([-+]?[0-9]+\.?[0-9]*)', xmp)
        if m:
            try:
                return float(m.group(1))
            except ValueError:
                continue
    return None


def lire_xmp_dji(chemin_photo: str) -> Optional[TelemetriePhoto]:
    """
    Extrait la télémétrie du bloc XMP DJI d'une photo. None si pas de XMP
    (ex. photo low_quality de l'app, ou image non-DJI).
    """
    with open(chemin_photo, "rb") as f:
        data = f.read()

    debut = data.find(b"<x:xmpmeta")
    fin = data.find(b"</x:xmpmeta>")
    if debut < 0 or fin < 0:
        return None
    xmp = data[debut:fin + 12].decode("utf-8", errors="ignore")

    lat = _chercher_champ(xmp, _CHAMPS_DJI["lat"])
    lon = _chercher_champ(xmp, _CHAMPS_DJI["lon"])
    altabs = _chercher_champ(xmp, _CHAMPS_DJI["altabs"])
    altrel = _chercher_champ(xmp, _CHAMPS_DJI["altrel"])
    yaw = _chercher_champ(xmp, _CHAMPS_DJI["yaw"])
    pitch = _chercher_champ(xmp, _CHAMPS_DJI["pitch"])

    # champs indispensables pour un rayon de visée : position + cap + tilt
    if None in (lat, lon, yaw, pitch) or (altabs is None and altrel is None):
        return None

    return TelemetriePhoto(
        lat=lat, lon=lon,
        alt_abs_m=altabs if altabs is not None else 0.0,
        alt_rel_m=altrel,
        gimbal_yaw_deg=yaw, gimbal_pitch_deg=pitch)


def diagnostiquer_xmp(chemin_photo: str) -> dict:
    """
    Affiche TOUS les champs DJI trouvés dans le XMP (pour calibration).
    À lancer sur une vraie photo SD pour voir les noms/valeurs réels.
    """
    with open(chemin_photo, "rb") as f:
        data = f.read()
    debut = data.find(b"<x:xmpmeta")
    fin = data.find(b"</x:xmpmeta>")
    if debut < 0 or fin < 0:
        return {"xmp_present": False}
    xmp = data[debut:fin + 12].decode("utf-8", errors="ignore")
    # extraire tous les champs drone-dji:* et leurs valeurs
    champs = {}
    for m in re.finditer(r'(?:drone-dji:|:)?([A-Za-z]+)\s*=\s*"([-+0-9.A-Za-z/]+)"', xmp):
        nom, val = m.group(1), m.group(2)
        if any(k in nom for k in ("Gps", "Altitude", "Gimbal", "Flight", "Latitude", "Longitude", "Longtitude")):
            champs[nom] = val
    return {"xmp_present": True, "champs": champs, "xmp_brut": xmp[:1500]}


# ===========================================================================
# 2. CONSTRUCTION D'UNE OBSERVATION (télémétrie + centroïde perception)
# ===========================================================================
def construire_observation(
    telemetrie: TelemetriePhoto,
    centroide_x: float, centroide_y: float,
    altitude_mode: str = "absolue",
    alt_sol_m: Optional[float] = None,
    fov_h_deg: float = 73.0, fov_v_deg: float = 53.0,
) -> Observation:
    """
    Assemble une Observation pour la triangulation.

    altitude_mode :
      "absolue"  -> utilise alt_abs_m (au-dessus du niveau de la mer). Cohérent
                    si le sujet T est aussi exprimé en altitude absolue.
      "agl"      -> alt_abs_m - alt_sol_m (nécessite alt_sol_m depuis le DEM).
                    Donne l'altitude au-dessus du sol au point du drone.
    Pour la triangulation, l'important est la COHÉRENCE entre toutes les
    observations : on garde "absolue" par défaut (toutes au même référentiel).
    """
    if altitude_mode == "agl":
        if alt_sol_m is None:
            raise ValueError("altitude_mode='agl' exige alt_sol_m (depuis le DEM)")
        alt = telemetrie.alt_abs_m - alt_sol_m
    else:
        alt = telemetrie.alt_abs_m

    return Observation(
        lat=telemetrie.lat, lon=telemetrie.lon, alt_m=alt,
        cap_deg=telemetrie.gimbal_yaw_deg,
        tilt_deg=telemetrie.gimbal_pitch_deg,
        centroide_x=centroide_x, centroide_y=centroide_y,
        fov_h_deg=fov_h_deg, fov_v_deg=fov_v_deg)


# ===========================================================================
# 3. CHAÎNE COMPLÈTE : lot de photos -> T
# ===========================================================================
@dataclass
class PhotoReco:
    """Une photo de reconnaissance + son centroïde sujet (de la perception)."""
    chemin: str
    centroide_x: float
    centroide_y: float


def localiser_sujet(
    photos: list[PhotoReco],
    fov_h_deg: float = 73.0, fov_v_deg: float = 53.0,
    test_robustesse: bool = True,
) -> ResultatTriangulation:
    """
    Pipeline complet : pour chaque photo, lit la télémétrie XMP + utilise le
    centroïde fourni -> Observation. Puis triangule T.

    Les centroïdes viennent de la perception (analyser_photo). On les passe ici
    déjà calculés pour découpler : la perception (GPU) tourne séparément, la
    localisation (géométrie pure) ne dépend pas du GPU.
    """
    observations = []
    rejets = []
    for p in photos:
        tel = lire_xmp_dji(p.chemin)
        if tel is None:
            rejets.append((p.chemin, "pas de télémétrie XMP (photo low_quality ?)"))
            continue
        obs = construire_observation(
            tel, p.centroide_x, p.centroide_y,
            fov_h_deg=fov_h_deg, fov_v_deg=fov_v_deg)
        observations.append(obs)

    if rejets:
        print(f"[cine_localisation] {len(rejets)} photo(s) rejetée(s) :")
        for chemin, raison in rejets:
            print(f"    {chemin} : {raison}")

    return trianguler(observations, test_robustesse=test_robustesse)


# ===========================================================================
# 4. PIPELINE INTÉGRÉ perception + localisation (si GPU disponible)
# ===========================================================================
def localiser_avec_perception(chemins_photos: list, charger_modeles_fn=None):
    """
    Bout-en-bout : charge les modèles, fait la perception sur chaque photo pour
    extraire le centroïde, lit la télémétrie, triangule. Nécessite le GPU.

    charger_modeles_fn : passer charger_modeles de cine_perception_reel (injecté
    pour ne pas importer les modèles lourds quand on n'en a pas besoin).
    """
    import numpy as np
    from PIL import Image
    from cine_perception import analyser_photo

    if charger_modeles_fn is None:
        from cine_perception_reel import charger_modeles as charger_modeles_fn

    seg, yolo, sam = charger_modeles_fn()

    photos = []
    for chemin in chemins_photos:
        img = np.array(Image.open(chemin).convert("RGB"))
        res = analyser_photo(img, seg, yolo, sam)
        d = res.to_dict()
        if not d.get("sujet_detecte") or d.get("centroide") is None:
            print(f"[cine_localisation] {chemin} : aucun sujet détecté, ignorée")
            continue
        cx, cy = d["centroide"]
        photos.append(PhotoReco(chemin=chemin, centroide_x=cx, centroide_y=cy))

    if len(photos) < 3:
        print(f"[cine_localisation] seulement {len(photos)} photo(s) avec sujet "
              f"(min 3 pour trianguler)")

    return localiser_sujet(photos, test_robustesse=True)


# ===========================================================================
# TEST de calibration XMP (à lancer sur une vraie photo SD)
# ===========================================================================
if __name__ == "__main__":
    import sys, json
    if len(sys.argv) > 1:
        chemin = sys.argv[1]
        print(f"=== Diagnostic XMP : {chemin} ===")
        diag = diagnostiquer_xmp(chemin)
        if not diag["xmp_present"]:
            print("PAS de bloc XMP -> probablement une photo low_quality de l'app.")
            print("Utilise une photo ORIGINALE pleine résolution de la carte SD.")
        else:
            print("Champs DJI trouvés :")
            for nom, val in diag["champs"].items():
                print(f"    {nom} = {val}")
            print("\n--- Télémétrie interprétée ---")
            tel = lire_xmp_dji(chemin)
            if tel:
                print(json.dumps(tel.__dict__, indent=2, ensure_ascii=False))
            else:
                print("Champs indispensables manquants (lat/lon/yaw/pitch/alt).")
    else:
        print("Usage : python cine_localisation.py <photo_SD_dji.jpg>")
        print("(diagnostique le XMP d'une vraie photo SD pour calibration)")
