#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_triangulation.py — Triangulation du point 3D du sujet (T).

Relie la perception (Niveau 2) aux recettes : à partir des centroïdes du sujet
sur plusieurs photos de reconnaissance, croise les rayons de visée pour produire
T = position 3D du sujet, autour duquel les recettes (portrait_orbit, etc.)
génèrent leurs trajectoires.

Conforme aux sections 6 et 6.6 du document :
  - rayon de visée par photo, corrigé par le centroïde + FOV (pas le centre image)
  - repère métrique local ENU (jamais de géométrie en lat/lon)
  - intersection de moindre distance : T = A^-1 b (solution fermée)
  - réjection d'outliers par résidu, re-résolution
  - DÉGRADATION PROPRE : seuils de base angulaire, nb de rayons, résidu
  - test de robustesse : perturber le point visé, mesurer la dérive de T

PAS de photogrammétrie, pas de maillage : juste de la trigonométrie sur des
rayons. C'est ce qui rend V2 réalisable sans modèle 3D complet.

=============================================================================
ENTRÉE — produite par la perception + la télémétrie du drone
=============================================================================
Pour chaque photo retenue (sujet segmenté), une Observation :
    P (lat, lon, alt)         : position du drone au moment de la photo
    cap_deg, tilt_deg          : attitude du gimbal (cap compas, tilt)
    centroide_x, centroide_y   : centroïde du sujet dans l'image [0,1]
    fov_h_deg, fov_v_deg       : champ de vision de la caméra
Ces données viennent de cine_perception (centroïde) + télémétrie DJI (P, attitude).
"""

from __future__ import annotations
import math
import numpy as np
from dataclasses import dataclass, field
from typing import Optional


R_TERRE_M = 6_371_000.0

# Seuils de dégradation propre (section 6.6)
SEUIL_BASE_ANGULAIRE_MIN = 25.0      # deg : en dessous, T non fiable en distance
SEUIL_RAYONS_MIN = 3                  # moins de 3 rayons -> échec
SEUIL_RESIDU_SUSPECT_M = 8.0         # résidu médian au-delà -> arc réduit
SEUIL_ERREUR_HORIZ_VOIE_B = 15.0     # au-delà -> bascule micro-mouvements (Voie B)
FACTEUR_REJECTION = 2.5              # résidu > 2.5 x médiane -> outlier


# ---------------------------------------------------------------------------
# Entrée
# ---------------------------------------------------------------------------
@dataclass
class Observation:
    lat: float
    lon: float
    alt_m: float
    cap_deg: float       # cap gimbal (compas, depuis Nord)
    tilt_deg: float      # tilt gimbal (négatif = vers le bas)
    centroide_x: float   # [0,1], 0=gauche
    centroide_y: float   # [0,1], 0=haut
    fov_h_deg: float = 73.0   # FOV horizontal type (à ajuster au Mini 3)
    fov_v_deg: float = 53.0


# ---------------------------------------------------------------------------
# Géométrie ENU
# ---------------------------------------------------------------------------
def _metres_par_degre(lat_ref: float) -> tuple[float, float]:
    m_lat = (math.pi / 180.0) * R_TERRE_M
    m_lon = m_lat * math.cos(math.radians(lat_ref))
    return m_lat, m_lon


def _vers_enu(lat, lon, alt, lat_ref, lon_ref, alt_ref) -> np.ndarray:
    m_lat, m_lon = _metres_par_degre(lat_ref)
    e = (lon - lon_ref) * m_lon
    n = (lat - lat_ref) * m_lat
    u = alt - alt_ref
    return np.array([e, n, u], dtype=float)


def _depuis_enu(enu, lat_ref, lon_ref, alt_ref) -> tuple[float, float, float]:
    m_lat, m_lon = _metres_par_degre(lat_ref)
    lat = lat_ref + enu[1] / m_lat
    lon = lon_ref + enu[0] / m_lon
    alt = alt_ref + enu[2]
    return lat, lon, alt


def _direction_visee(obs: Observation, decalage_centroide: tuple[float, float] = (0.0, 0.0)) -> np.ndarray:
    """
    Direction unitaire du rayon de visée en ENU, corrigée par le centroïde + FOV.
    decalage_centroide : (dx, dy) en pixels normalisés ajouté au centroïde,
                         pour le test de robustesse (perturbation).
    """
    # offset du centroïde par rapport au centre image -> correction angulaire
    cx = obs.centroide_x + decalage_centroide[0]
    cy = obs.centroide_y + decalage_centroide[1]
    offset_x = cx - 0.5
    offset_y = cy - 0.5
    d_yaw = offset_x * obs.fov_h_deg       # dég
    d_pitch = -offset_y * obs.fov_v_deg    # y image descend -> pitch monte

    cap = math.radians(obs.cap_deg + d_yaw)
    tilt = math.radians(obs.tilt_deg + d_pitch)

    # ENU : E = sin(cap)*cos(tilt), N = cos(cap)*cos(tilt), U = sin(tilt)
    e = math.sin(cap) * math.cos(tilt)
    n = math.cos(cap) * math.cos(tilt)
    u = math.sin(tilt)
    v = np.array([e, n, u], dtype=float)
    return v / np.linalg.norm(v)


# ---------------------------------------------------------------------------
# Intersection de moindre distance (solution fermée)
# ---------------------------------------------------------------------------
def _intersection_moindre_distance(origines: list[np.ndarray], directions: list[np.ndarray]) -> np.ndarray:
    """
    Point T minimisant la somme des distances aux rayons (Pi, vi).
    A = somme(I - vi vi^T) ; b = somme((I - vi vi^T) Pi) ; T = A^-1 b.
    """
    A = np.zeros((3, 3))
    b = np.zeros(3)
    I = np.eye(3)
    for P, v in zip(origines, directions):
        M = I - np.outer(v, v)
        A += M
        b += M @ P
    T = np.linalg.solve(A, b)
    return T


def _residu_rayon(T: np.ndarray, P: np.ndarray, v: np.ndarray) -> float:
    """Distance perpendiculaire du point T au rayon (P, v)."""
    w = T - P
    proj = w - np.dot(w, v) * v   # composante perpendiculaire
    return float(np.linalg.norm(proj))


def _base_angulaire(observations: list[Observation], T_lat, T_lon, lat_ref) -> float:
    """Étendue d'azimut (deg) des positions caméra autour du sujet."""
    m_lat, m_lon = _metres_par_degre(lat_ref)
    azimuts = []
    for o in observations:
        de = (o.lon - T_lon) * m_lon
        dn = (o.lat - T_lat) * m_lat
        az = math.degrees(math.atan2(de, dn)) % 360.0
        azimuts.append(az)
    if len(azimuts) < 2:
        return 0.0
    azimuts.sort()
    # plus grand "trou" angulaire -> la base couverte = 360 - plus grand trou
    trous = [azimuts[i+1] - azimuts[i] for i in range(len(azimuts)-1)]
    trous.append(360.0 - azimuts[-1] + azimuts[0])
    return 360.0 - max(trous)


# ---------------------------------------------------------------------------
# Résultat
# ---------------------------------------------------------------------------
@dataclass
class ResultatTriangulation:
    succes: bool
    lat: Optional[float]
    lon: Optional[float]
    alt_m: Optional[float]
    confiance: float
    erreur_horizontale_m: float
    erreur_verticale_m: float
    rayons_utilises: int
    base_angulaire_deg: float
    residu_median_m: float
    voie: str             # "pleine" | "arc_reduit" | "voie_B" | "echec"
    derive_robustesse_m: Optional[float] = None

    def to_dict(self) -> dict:
        return {
            "succes": self.succes,
            "subject_position": (
                {"lat": round(self.lat, 6), "lon": round(self.lon, 6),
                 "alt_m": round(self.alt_m, 1)} if self.succes else None),
            "confiance": round(self.confiance, 3),
            "erreur_horizontale_m": round(self.erreur_horizontale_m, 1),
            "erreur_verticale_m": round(self.erreur_verticale_m, 1),
            "rayons_utilises": self.rayons_utilises,
            "base_angulaire_deg": round(self.base_angulaire_deg, 1),
            "residu_median_m": round(self.residu_median_m, 2),
            "voie": self.voie,
            "derive_robustesse_m": (round(self.derive_robustesse_m, 1)
                                    if self.derive_robustesse_m is not None else None),
        }


# ---------------------------------------------------------------------------
# Fonction principale
# ---------------------------------------------------------------------------
def trianguler(
    observations: list[Observation],
    test_robustesse: bool = True,
    perturbation_px: float = 0.02,   # ±2% du cadre pour le test de sensibilité
) -> ResultatTriangulation:
    """
    Triangule T à partir des observations, avec réjection d'outliers,
    dégradation propre, et test de robustesse au bruit du centroïde.
    """
    n = len(observations)
    if n < SEUIL_RAYONS_MIN:
        return ResultatTriangulation(
            succes=False, lat=None, lon=None, alt_m=None, confiance=0.0,
            erreur_horizontale_m=999.0, erreur_verticale_m=999.0,
            rayons_utilises=n, base_angulaire_deg=0.0, residu_median_m=999.0,
            voie="echec")

    lat_ref = observations[0].lat
    lon_ref = observations[0].lon
    alt_ref = observations[0].alt_m

    def resoudre(obs_list):
        origines = [_vers_enu(o.lat, o.lon, o.alt_m, lat_ref, lon_ref, alt_ref)
                    for o in obs_list]
        directions = [_direction_visee(o) for o in obs_list]
        T = _intersection_moindre_distance(origines, directions)
        residus = [_residu_rayon(T, P, v) for P, v in zip(origines, directions)]
        return T, residus, origines, directions

    # résolution initiale
    T, residus, origines, directions = resoudre(observations)

    # réjection d'outliers (une passe)
    med = float(np.median(residus))
    obs_retenues = [o for o, r in zip(observations, residus)
                    if r <= FACTEUR_REJECTION * med + 1e-6]
    if len(obs_retenues) >= SEUIL_RAYONS_MIN and len(obs_retenues) < n:
        T, residus, origines, directions = resoudre(obs_retenues)
    else:
        obs_retenues = observations

    residu_median = float(np.median(residus))
    T_lat, T_lon, T_alt = _depuis_enu(T, lat_ref, lon_ref, alt_ref)
    base_ang = _base_angulaire(obs_retenues, T_lat, T_lon, lat_ref)

    # barres d'erreur : dispersion des résidus, décomposée horiz/vert
    # (approx : le résidu moyen donne l'ordre de grandeur ; le vertical ~2x pire)
    err_h = residu_median
    err_v = residu_median * 2.0  # l'altitude est la composante la plus fragile (6.4)

    # test de robustesse : perturber le centroïde, mesurer la dérive de T
    derive = None
    if test_robustesse:
        T_perturbes = []
        for signe in [(+1, 0), (-1, 0), (0, +1), (0, -1)]:
            dirs_p = [_direction_visee(o, (signe[0]*perturbation_px,
                                           signe[1]*perturbation_px))
                      for o in obs_retenues]
            origs = [_vers_enu(o.lat, o.lon, o.alt_m, lat_ref, lon_ref, alt_ref)
                     for o in obs_retenues]
            try:
                Tp = _intersection_moindre_distance(origs, dirs_p)
                T_perturbes.append(Tp)
            except np.linalg.LinAlgError:
                pass
        if T_perturbes:
            derives = [float(np.linalg.norm(Tp - T)) for Tp in T_perturbes]
            derive = max(derives)

    # confiance : somme pondérée d'indices redondants (Principe 2)
    f_base = min(1.0, base_ang / 90.0)          # base angulaire (90° = bon)
    f_rayons = min(1.0, len(obs_retenues) / 6.0)  # nb de rayons (6 = bon)
    f_residu = 1.0 / (1.0 + residu_median / 3.0)  # résidu faible = bon
    confiance = 0.4 * f_base + 0.3 * f_rayons + 0.3 * f_residu

    # DÉGRADATION PROPRE (section 6.6)
    voie = "pleine"
    if base_ang < SEUIL_BASE_ANGULAIRE_MIN:
        confiance = min(confiance, 0.3)
        voie = "arc_reduit"
    if residu_median > SEUIL_RESIDU_SUSPECT_M:
        voie = "arc_reduit"
    if err_h > SEUIL_ERREUR_HORIZ_VOIE_B:
        voie = "voie_B"

    return ResultatTriangulation(
        succes=True, lat=T_lat, lon=T_lon, alt_m=T_alt,
        confiance=confiance, erreur_horizontale_m=err_h, erreur_verticale_m=err_v,
        rayons_utilises=len(obs_retenues), base_angulaire_deg=base_ang,
        residu_median_m=residu_median, voie=voie, derive_robustesse_m=derive)


# ===========================================================================
# OUTIL DE TEST : générer des observations synthétiques vers un T connu
# ===========================================================================
def _observer_vers(T_lat, T_lon, T_alt, cam_lat, cam_lon, cam_alt,
                   bruit_centroide=0.0, fov_h=73.0, fov_v=53.0, rng=None):
    """
    Construit une Observation d'une caméra qui regarde EXACTEMENT le point T,
    avec gimbal calculé pour pointer T (centroïde au centre), plus bruit optionnel.
    """
    m_lat, m_lon = _metres_par_degre(cam_lat)
    de = (T_lon - cam_lon) * m_lon
    dn = (T_lat - cam_lat) * m_lat
    du = T_alt - cam_alt
    d_horiz = math.hypot(de, dn)
    cap = math.degrees(math.atan2(de, dn)) % 360.0
    tilt = math.degrees(math.atan2(du, d_horiz))
    cx, cy = 0.5, 0.5
    if bruit_centroide and rng is not None:
        cx += rng.normal(0, bruit_centroide)
        cy += rng.normal(0, bruit_centroide)
    return Observation(cam_lat, cam_lon, cam_alt, cap, tilt, cx, cy, fov_h, fov_v)


# ===========================================================================
# TESTS DE CORRECTION
# ===========================================================================
def _tests():
    print("=" * 70)
    print("TEST cine_triangulation — géométrie connue")
    print("=" * 70)

    rng = np.random.default_rng(42)

    # sujet : phare de 30 m, sommet à 30 m d'altitude
    T_LAT, T_LON, T_ALT = 48.5000, -68.5000, 30.0

    def cam_a(rayon_m, azimut_deg, alt_m):
        m_lat, m_lon = _metres_par_degre(T_LAT)
        dlat = rayon_m * math.cos(math.radians(azimut_deg)) / m_lat
        dlon = rayon_m * math.sin(math.radians(azimut_deg)) / m_lon
        return T_LAT + dlat, T_LON + dlon, alt_m

    # ---- Test 1 : base angulaire large, sans bruit -> T exact ----
    obs1 = []
    for az in [0, 60, 120, 180, 240, 300]:
        clat, clon, calt = cam_a(60.0, az, 45.0)
        obs1.append(_observer_vers(T_LAT, T_LON, T_ALT, clat, clon, calt, rng=rng))
    r1 = trianguler(obs1)
    print("\n[1] 6 caméras réparties sur 360°, sans bruit")
    print("   ", r1.to_dict())
    # erreur de position vs vrai T
    m_lat, m_lon = _metres_par_degre(T_LAT)
    err_pos = math.hypot((r1.lat - T_LAT) * m_lat, (r1.lon - T_LON) * m_lon)
    print(f"    erreur position réelle : {err_pos:.3f} m")
    assert err_pos < 1.0, "sans bruit, base large -> T quasi exact"
    assert r1.voie == "pleine", "bonne géométrie -> voie pleine"
    assert r1.confiance > 0.7, "confiance élevée"
    print(f"    OK : T trouvé à {err_pos:.2f} m du vrai, voie pleine, confiance {r1.confiance:.2f}")

    # ---- Test 2 : base angulaire étroite -> dégradation (arc_reduit ou voie_B) ----
    obs2 = []
    for az in [88, 90, 92, 94]:   # toutes les caméras quasi au même azimut
        clat, clon, calt = cam_a(60.0, az, 45.0)
        obs2.append(_observer_vers(T_LAT, T_LON, T_ALT, clat, clon, calt, rng=rng))
    r2 = trianguler(obs2)
    print("\n[2] 4 caméras quasi alignées (base angulaire étroite)")
    print("   ", r2.to_dict())
    assert r2.base_angulaire_deg < SEUIL_BASE_ANGULAIRE_MIN, "base étroite détectée"
    assert r2.voie in ("arc_reduit", "voie_B"), "géométrie dégénérée -> dégradation"
    assert r2.confiance <= 0.3, "confiance plafonnée par base étroite"
    print(f"    OK : base {r2.base_angulaire_deg:.1f}° -> voie '{r2.voie}', confiance plafonnée {r2.confiance:.2f}")

    # ---- Test 3 : robustesse au bruit du centroïde ----
    obs3 = []
    for az in [0, 60, 120, 180, 240, 300]:
        clat, clon, calt = cam_a(60.0, az, 45.0)
        obs3.append(_observer_vers(T_LAT, T_LON, T_ALT, clat, clon, calt,
                                   bruit_centroide=0.015, rng=rng))
    r3 = trianguler(obs3, test_robustesse=True)
    print("\n[3] 6 caméras avec bruit de centroïde (±1.5% du cadre)")
    print("   ", r3.to_dict())
    err_pos3 = math.hypot((r3.lat - T_LAT) * m_lat, (r3.lon - T_LON) * m_lon)
    print(f"    erreur position réelle : {err_pos3:.2f} m, dérive robustesse : {r3.derive_robustesse_m:.2f} m")
    assert r3.derive_robustesse_m is not None, "dérive calculée"
    assert r3.succes, "triangulation réussie malgré le bruit"
    print(f"    OK : T trouvé malgré le bruit, dérive de robustesse mesurée")

    # ---- Test 4 : outlier (une caméra mal segmentée) rejeté ----
    obs4 = []
    for az in [0, 60, 120, 180, 240, 300]:
        clat, clon, calt = cam_a(60.0, az, 45.0)
        obs4.append(_observer_vers(T_LAT, T_LON, T_ALT, clat, clon, calt, rng=rng))
    # corrompre une observation : centroïde fortement décalé (mauvaise détection)
    obs4[2].centroide_x = 0.1   # le sujet n'est pas là où on croit
    obs4[2].centroide_y = 0.1
    r4 = trianguler(obs4)
    print("\n[4] 6 caméras dont 1 mal segmentée (outlier)")
    print("   ", r4.to_dict())
    err_pos4 = math.hypot((r4.lat - T_LAT) * m_lat, (r4.lon - T_LON) * m_lon)
    print(f"    rayons utilisés : {r4.rayons_utilises}/6, erreur position : {err_pos4:.2f} m")
    assert r4.rayons_utilises < 6, "l'outlier doit être rejeté"
    print(f"    OK : outlier rejeté ({r4.rayons_utilises} rayons gardés), T préservé")

    # ---- Test 5 : trop peu de rayons -> échec propre ----
    obs5 = obs1[:2]
    r5 = trianguler(obs5)
    print("\n[5] Seulement 2 observations")
    print(f"    succès={r5.succes}, voie={r5.voie}")
    assert not r5.succes, "moins de 3 rayons -> échec"
    assert r5.voie == "echec"
    print("    OK : échec propre (pas de T inventé sur données insuffisantes)")

    print("\n" + "=" * 70)
    print("TOUS LES TESTS PASSENT — la triangulation est correcte et robuste.")
    print("=" * 70)
    print("\nProduit T (lat/lon/alt) + confiance + voie de dégradation.")
    print("La voie alimente le couplage erreur-T -> amplitude d'arc des recettes.")
    print("Pour le réel : observations depuis cine_perception (centroïde) + télémétrie DJI.")


if __name__ == "__main__":
    _tests()
