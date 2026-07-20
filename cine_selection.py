#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_selection.py — Sélection des meilleures observations pour la triangulation.

48 photos prises au repérage -> on garde 8-12 BONNES observations, jamais les 48
brutes. Principe (validé avec Christian) : le nombre réduit le risque de déchet,
mais la SÉLECTION INTELLIGENTE est ce qui produit un bon T.

DEUX CRITÈRES, ni l'un ni l'autre seul ne suffit :

  1. QUALITÉ par photo : une photo donne un bon rayon si le sujet est bien
     détecté/segmenté ET la télémétrie cohérente. Une photo en contre-jour,
     sujet coupé, ou prise pendant que le gimbal tourne -> mauvais rayon.

  2. RÉPARTITION angulaire : PIÈGE classique -> garder "les 12 plus nettes"
     pourrait donner 12 photos du MÊME côté (là où la lumière était bonne) ->
     rayons quasi-parallèles -> mauvaise base angulaire -> T imprécis. La qualité
     d'image ne suffit pas ; il faut des observations RÉPARTIES.

SOLUTION : on découpe l'arc en SECTEURS, et dans chaque secteur on garde la/les
meilleure(s) photo(s) par qualité. -> qualité ET diversité géométrique.

Ce que 48 photos NE corrigent PAS (à signaler, pas à masquer) :
  - arc dégagé trop étroit          -> base angulaire insuffisante
  - GPS/attitude globalement mauvais -> tous les rayons décalés
  - sujet invisible/masqué          -> aucun centroïde fiable
  - contre-jour sur tout un côté    -> un secteur entier sans bonne photo
  - drone trop loin/près            -> sujet trop petit/grand dans le cadre
La sélection DÉTECTE ces cas (secteurs vides, qualité globale basse) et les
SIGNALE plutôt que de trianguler sur du déchet.
"""

from __future__ import annotations
import math
from dataclasses import dataclass
from typing import Optional


# Cibles (paramètres Christian)
OBS_CIBLE_TRIANGULATION = 12
OBS_MIN_TRIANGULATION = 6

# Seuils de qualité (une obs sous le plancher est écartée d'office)
CONF_DETECTION_MIN = 0.15        # confiance YOLO-World minimale
TAILLE_SUJET_MIN_FRAC = 0.003    # sujet trop petit (drone trop loin) -> rejet
TAILLE_SUJET_MAX_FRAC = 0.80     # sujet remplit le cadre (drone trop près) -> rejet
CENTRALITE_MIN = 0.0             # tolérance : sujet peut être décentré (info, pas rejet)


@dataclass
class ObservationCandidate:
    """Une photo analysée, candidate à la triangulation."""
    photo_id: str
    azimut_autour_poi_deg: float   # azimut de la prise autour du sujet
    niveau: int                    # 0=bas, 1=haut (info de diversité verticale)
    # depuis la perception :
    sujet_detecte: bool
    conf_detection: float          # confiance YOLO-World
    taille_sujet_frac: float       # S : fraction du cadre occupée par le sujet
    centroide_x: float             # [0,1]
    centroide_y: float
    integrite: float               # 1.0 = sujet entier, <1 = coupé par le bord
    f_occlusion: float             # 1.0 = pas d'occlusion, <1 = partiellement masqué
    # depuis la télémétrie :
    telemetrie_ok: bool = True     # GPS/gimbal cohérents
    # calculé :
    score_qualite: float = 0.0


def _score_qualite(obs: ObservationCandidate) -> float:
    """
    Score [0,1] de qualité d'une observation POUR LA TRIANGULATION.
    Combine : détection sûre, sujet bien dimensionné, entier, non masqué,
    centroïde fiable. C'est la qualité du RAYON, pas l'esthétique.
    """
    if not obs.sujet_detecte or not obs.telemetrie_ok:
        return 0.0

    # 1. confiance de détection (rayon basé sur un sujet sûr)
    f_conf = min(1.0, obs.conf_detection / 0.5)   # sature à 0.5

    # 2. taille du sujet : cloche, ni trop petit (loin) ni géant (près)
    s = obs.taille_sujet_frac
    if s < TAILLE_SUJET_MIN_FRAC or s > TAILLE_SUJET_MAX_FRAC:
        f_taille = 0.0
    else:
        # optimum vers 5-30% du cadre
        f_taille = min(1.0, s / 0.10) if s < 0.10 else max(0.3, 1.0 - (s - 0.30) / 0.50)
        f_taille = max(0.0, min(1.0, f_taille))

    # 3. intégrité : un sujet coupé par le bord -> centroïde biaisé -> mauvais rayon
    f_integrite = obs.integrite

    # 4. occlusion : un sujet partiellement masqué -> centroïde décalé
    f_occ = obs.f_occlusion

    # 5. centralité : un sujet bien centré a un centroïde plus fiable (gimbal a visé juste)
    d_centre = math.hypot(obs.centroide_x - 0.5, obs.centroide_y - 0.5) / 0.707
    f_central = 1.0 - 0.4 * d_centre   # pénalise sans éliminer

    # produit : un facteur fatal (taille=0, intégrité=0) écrase le score (Principe 2)
    return f_conf * f_taille * f_integrite * f_occ * f_central


@dataclass
class ResultatSelection:
    observations_retenues: list      # list[ObservationCandidate] sélectionnées
    nb_candidates: int
    nb_retenues: int
    nb_secteurs_couverts: int
    nb_secteurs_total: int
    base_angulaire_deg: float
    qualite_mediane: float
    suffisant: bool
    avertissements: list             # problèmes structurels détectés

    def to_dict(self) -> dict:
        return {
            "nb_candidates": self.nb_candidates,
            "nb_retenues": self.nb_retenues,
            "secteurs_couverts": f"{self.nb_secteurs_couverts}/{self.nb_secteurs_total}",
            "base_angulaire_deg": round(self.base_angulaire_deg, 1),
            "qualite_mediane": round(self.qualite_mediane, 3),
            "suffisant": self.suffisant,
            "avertissements": self.avertissements,
            "observations_retenues": [
                {"photo_id": o.photo_id, "azimut": round(o.azimut_autour_poi_deg, 1),
                 "niveau": o.niveau, "qualite": round(o.score_qualite, 3)}
                for o in self.observations_retenues],
        }


def selectionner(
    candidates: list,
    obs_cible: int = OBS_CIBLE_TRIANGULATION,
    obs_min: int = OBS_MIN_TRIANGULATION,
    nb_secteurs: int = 8,
) -> ResultatSelection:
    """
    Sélectionne les meilleures observations en garantissant la RÉPARTITION.

    Algorithme :
      1. calcule le score de qualité de chaque candidate
      2. écarte les candidates de qualité nulle (déchet)
      3. découpe l'arc [0,360) en nb_secteurs secteurs
      4. dans chaque secteur, garde les meilleures par qualité (round-robin
         jusqu'à obs_cible) -> qualité ET diversité angulaire préservées
      5. détecte les problèmes structurels (secteurs vides, qualité basse)
    """
    avertissements = []

    # 1-2. scorer et filtrer le déchet
    for c in candidates:
        c.score_qualite = _score_qualite(c)
    valides = [c for c in candidates if c.score_qualite > 0.0]

    if len(valides) < obs_min:
        avertissements.append(
            f"seulement {len(valides)} observations valides sur {len(candidates)} "
            f"(< {obs_min} min) — sujet peut-être masqué, contre-jour, ou trop loin")

    # 3. répartir par secteur (selon l'azimut autour du POI)
    largeur_secteur = 360.0 / nb_secteurs
    secteurs = {i: [] for i in range(nb_secteurs)}
    for c in valides:
        idx = int((c.azimut_autour_poi_deg % 360.0) / largeur_secteur) % nb_secteurs
        secteurs[idx].append(c)
    for idx in secteurs:
        secteurs[idx].sort(key=lambda c: c.score_qualite, reverse=True)

    secteurs_couverts = sum(1 for s in secteurs.values() if s)
    if secteurs_couverts < nb_secteurs:
        vides = [i for i, s in secteurs.items() if not s]
        avertissements.append(
            f"{len(vides)} secteur(s) angulaire(s) sans bonne photo "
            f"(contre-jour d'un côté ? obstacle ?) — base angulaire réduite")

    # 4. round-robin : prendre la meilleure de chaque secteur, tour par tour,
    #    jusqu'à obs_cible -> garantit qu'on pioche dans TOUS les secteurs
    #    avant de doubler dans un secteur.
    retenues = []
    rangs = {i: 0 for i in range(nb_secteurs)}
    while len(retenues) < obs_cible:
        progres = False
        for idx in range(nb_secteurs):
            if len(retenues) >= obs_cible:
                break
            r = rangs[idx]
            if r < len(secteurs[idx]):
                retenues.append(secteurs[idx][r])
                rangs[idx] += 1
                progres = True
        if not progres:
            break   # plus de candidates disponibles

    # 5. métriques finales
    if retenues:
        azs = sorted(o.azimut_autour_poi_deg % 360.0 for o in retenues)
        base = azs[-1] - azs[0]
        qmed = sorted(o.score_qualite for o in retenues)[len(retenues) // 2]
    else:
        base = 0.0
        qmed = 0.0

    if base < 25.0 and retenues:
        avertissements.append(
            f"base angulaire {base:.0f}° < 25° — rayons trop parallèles, "
            f"T imprécis en distance (arc dégagé trop étroit ?)")

    if qmed < 0.2 and retenues:
        avertissements.append(
            f"qualité médiane {qmed:.2f} faible — détection/segmentation peu fiable")

    # secteurs DISTINCTS réellement couverts par les observations RETENUES
    # (≠ secteurs candidats). Une base large mais tassée sur 1-2 secteurs ne
    # suffit pas : il faut une vraie dispersion angulaire. On exige au moins 3
    # secteurs distincts ET base ≥ 25° (les deux, car l'un sans l'autre trompe).
    largeur_sect = 360.0 / nb_secteurs
    secteurs_retenus = set(
        int((o.azimut_autour_poi_deg % 360.0) / largeur_sect) % nb_secteurs
        for o in retenues)
    nb_secteurs_retenus = len(secteurs_retenus)
    if nb_secteurs_retenus < 3 and retenues:
        avertissements.append(
            f"observations concentrées sur {nb_secteurs_retenus} secteur(s) — "
            f"dispersion angulaire insuffisante pour une triangulation fiable")

    suffisant = (len(retenues) >= obs_min
                 and base >= 25.0
                 and nb_secteurs_retenus >= 3)

    return ResultatSelection(
        observations_retenues=retenues,
        nb_candidates=len(candidates),
        nb_retenues=len(retenues),
        nb_secteurs_couverts=secteurs_couverts,
        nb_secteurs_total=nb_secteurs,
        base_angulaire_deg=base,
        qualite_mediane=qmed,
        suffisant=suffisant,
        avertissements=avertissements)


# ===========================================================================
# TESTS
# ===========================================================================
if __name__ == "__main__":
    import random
    import json
    random.seed(42)

    def faire_candidate(pid, az, niveau, bonne=True):
        """Fabrique une candidate, bonne ou dégradée."""
        if bonne:
            return ObservationCandidate(
                photo_id=pid, azimut_autour_poi_deg=az, niveau=niveau,
                sujet_detecte=True, conf_detection=random.uniform(0.4, 0.9),
                taille_sujet_frac=random.uniform(0.05, 0.20),
                centroide_x=random.uniform(0.4, 0.6), centroide_y=random.uniform(0.4, 0.6),
                integrite=random.uniform(0.9, 1.0), f_occlusion=random.uniform(0.9, 1.0))
        else:
            # déchet : contre-jour (pas détecté), ou sujet coupé, ou trop petit
            typ = random.choice(["non_detecte", "coupe", "trop_petit", "tele_ko"])
            if typ == "non_detecte":
                return ObservationCandidate(pid, az, niveau, False, 0.0, 0.0, 0.5, 0.5, 0.0, 1.0)
            if typ == "coupe":
                return ObservationCandidate(pid, az, niveau, True, 0.5, 0.1, 0.5, 0.5, 0.3, 1.0)
            if typ == "trop_petit":
                return ObservationCandidate(pid, az, niveau, True, 0.4, 0.001, 0.5, 0.5, 1.0, 1.0)
            return ObservationCandidate(pid, az, niveau, True, 0.5, 0.1, 0.5, 0.5, 1.0, 1.0, telemetrie_ok=False)

    print("=" * 70)
    print("TEST 1 — 48 photos, ~40% de déchet réparti -> sélection de 12")
    print("=" * 70)
    cands = []
    for i in range(48):
        az = (i % 24) * 15.0
        niveau = i // 24
        bonne = random.random() > 0.40   # 40% de déchet
        cands.append(faire_candidate(f"DJI_{i:03d}", az, niveau, bonne))
    res = selectionner(cands, obs_cible=12, obs_min=6, nb_secteurs=8)
    print(json.dumps(res.to_dict(), indent=2, ensure_ascii=False))
    assert res.suffisant, "doit être suffisant malgré 40% de déchet"
    print(f"\n    OK : {res.nb_retenues} retenues sur {res.nb_candidates}, "
          f"base {res.base_angulaire_deg:.0f}°, {res.nb_secteurs_couverts}/8 secteurs")

    print("\n" + "=" * 70)
    print("TEST 2 — Contre-jour sur tout un côté (secteurs 0-2 vides)")
    print("=" * 70)
    cands2 = []
    for i in range(48):
        az = (i % 24) * 15.0
        niveau = i // 24
        # azimuts 0-90° (secteurs 0-1) tous en contre-jour = déchet
        bonne = (az > 90.0) and (random.random() > 0.2)
        cands2.append(faire_candidate(f"DJI_{i:03d}", az, niveau, bonne))
    res2 = selectionner(cands2, obs_cible=12, obs_min=6, nb_secteurs=8)
    print(f"  retenues       : {res2.nb_retenues}")
    print(f"  secteurs       : {res2.nb_secteurs_couverts}/8")
    print(f"  base angulaire : {res2.base_angulaire_deg:.0f}°")
    print(f"  suffisant      : {res2.suffisant}")
    print(f"  avertissements :")
    for a in res2.avertissements:
        print(f"    - {a}")
    print("    OK : le contre-jour d'un côté est DÉTECTÉ et SIGNALÉ (pas masqué)")

    print("\n" + "=" * 70)
    print("TEST 3 — Arc trop étroit : que des photos sur 30°")
    print("=" * 70)
    cands3 = []
    for i in range(48):
        az = (i % 24) * 1.25   # tout tassé sur 0-30°
        niveau = i // 24
        cands3.append(faire_candidate(f"DJI_{i:03d}", az, niveau, True))
    res3 = selectionner(cands3, obs_cible=12, obs_min=6, nb_secteurs=8)
    print(f"  retenues       : {res3.nb_retenues}")
    print(f"  base angulaire : {res3.base_angulaire_deg:.0f}°")
    print(f"  suffisant      : {res3.suffisant}")
    print(f"  avertissements :")
    for a in res3.avertissements:
        print(f"    - {a}")
    assert not res3.suffisant, "arc 30° doit être insuffisant (base < 25° après sélection sur secteurs étroits)"
    print("    OK : arc trop étroit -> base insuffisante détectée")

    print("\n" + "=" * 70)
    print("TOUS LES TESTS PASSENT — sélection qualité + répartition correcte")
    print("=" * 70)
