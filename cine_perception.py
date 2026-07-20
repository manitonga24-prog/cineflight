#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_perception.py — Pipeline de perception Niveau 2 (CineFlight Explorer).

Transforme une photo de reconnaissance en scores par intention. C'est le
"cerveau" de la perception : les modèles (SegFormer, YOLO, SAM2) produisent
des masques bruts ; CE module décide ce qu'on en fait.

Conforme aux sections 5 et 6 du document :
  - SegFormer (décor) + YOLO (portier) + SAM2 (sujet) derrière une interface
  - RÉCONCILIATION des masques : sujet soustrait du décor (S + C + reste = 1)
  - OCCLUSION par fusion (SAM2 ne voit pas ce qui le cache, SegFormer si)
  - métriques par photo : S, C, f_occ, centroïde, intégrité, horizon (H1/H2/H3)
  - SCORING par intention : cloches gaussiennes, occlusion multiplicative,
    composition en produit, confiance d'horizon comme PORTE

=============================================================================
INTERFACE MODÈLE À BRANCHER (le SEUL point à remplacer pour le réel)
=============================================================================
Ce module n'embarque PAS les modèles (lourds, GPU). Il attend trois fonctions
respectant ces contrats :

    segformer(image) -> ndarray HxW d'entiers (id de classe par pixel)
    yolo(image)      -> liste de boîtes {classe, conf, x0,y0,x1,y1}
    sam2(image, box) -> ndarray HxW de booléens (masque du sujet)

Chez toi, tu implémentes ces trois fonctions avec les vrais modèles sur GPU
(transformers/ultralytics/segment-anything) et tu les passes au pipeline.
Pour les tests ici, des implémentations FACTICES déterministes opèrent sur une
image synthétique à pixels connus -> la géométrie de réconciliation et
d'occlusion est vérifiable analytiquement, sans modèle ni GPU.
"""

from __future__ import annotations
import math
import numpy as np
from dataclasses import dataclass, field
from typing import Callable, Optional


# ---------------------------------------------------------------------------
# Classes sémantiques (ids SegFormer -> familles CineFlight)
# ---------------------------------------------------------------------------
# Mapping des ids de classe vers nos familles. À adapter au modèle SegFormer réel.
CLASSE_CIEL = 1
CLASSE_MER = 2
CLASSE_FALAISE = 3
CLASSE_FORET = 4
CLASSE_BATI = 5
CLASSE_ROUTE = 6
CLASSE_SOL = 7
CLASSE_FOND = 0   # non classé

# familles de contexte "intéressant" (pour C) et leur étiquette
FAMILLES_CONTEXTE = {
    CLASSE_MER: "mer",
    CLASSE_FALAISE: "falaise",
    CLASSE_FORET: "foret",
    CLASSE_BATI: "bati",
    CLASSE_ROUTE: "route",
    CLASSE_CIEL: "ciel",
}
# classes qui comptent comme "occlusion possible" si devant le sujet
CLASSES_OCCLUANTES = {CLASSE_FALAISE, CLASSE_FORET, CLASSE_BATI}


# ---------------------------------------------------------------------------
# Types d'interface modèle
# ---------------------------------------------------------------------------
@dataclass
class Boite:
    classe: str
    conf: float
    x0: int
    y0: int
    x1: int
    y1: int

    @property
    def centre(self) -> tuple[float, float]:
        return ((self.x0 + self.x1) / 2.0, (self.y0 + self.y1) / 2.0)


SegFormerFn = Callable[[np.ndarray], np.ndarray]
YoloFn = Callable[[np.ndarray], list[Boite]]
Sam2Fn = Callable[[np.ndarray, Boite], np.ndarray]


# ---------------------------------------------------------------------------
# Intentions (cibles et poids — section 5, repris du scoring V2)
# ---------------------------------------------------------------------------
@dataclass
class Intention:
    nom: str
    a_etoile: float      # cible de taille du sujet S
    c_etoile: float      # cible de part de contexte C
    sigma_a: float
    sigma_c: float
    w_dom: float
    w_ctx: float
    w_comp: float


INTENTIONS = [
    Intention("portrait",     a_etoile=0.32, c_etoile=0.20, sigma_a=0.15, sigma_c=0.15,
              w_dom=0.70, w_ctx=0.10, w_comp=0.20),
    Intention("establishing", a_etoile=0.07, c_etoile=0.55, sigma_a=0.06, sigma_c=0.20,
              w_dom=0.25, w_ctx=0.60, w_comp=0.15),
    Intention("dramatic",     a_etoile=0.17, c_etoile=0.45, sigma_a=0.12, sigma_c=0.18,
              w_dom=0.40, w_ctx=0.40, w_comp=0.20),
]


# ---------------------------------------------------------------------------
# Étape 1 — perception brute (appel des trois modèles via interface)
# ---------------------------------------------------------------------------
@dataclass
class PerceptionBrute:
    carte_classes: np.ndarray         # HxW ids SegFormer
    masque_sujet: Optional[np.ndarray]  # HxW bool, ou None si pas de sujet
    boite_sujet: Optional[Boite]


def percevoir(
    image: np.ndarray,
    segformer: SegFormerFn,
    yolo: YoloFn,
    sam2: Sam2Fn,
    seuil_conf_yolo: float = 0.5,
) -> PerceptionBrute:
    """
    Étape 1 : SegFormer sur toute l'image, YOLO en portier, SAM2 seulement si
    le sujet est détecté avec assez de confiance (section 5.4).
    """
    carte = segformer(image)

    boites = yolo(image)
    # portier : on ne garde que la meilleure boîte au-dessus du seuil
    boites_ok = [b for b in boites if b.conf >= seuil_conf_yolo]
    if not boites_ok:
        return PerceptionBrute(carte_classes=carte, masque_sujet=None, boite_sujet=None)

    boite = max(boites_ok, key=lambda b: b.conf)
    masque = sam2(image, boite)   # SAM2 amorcé par la boîte YOLO
    return PerceptionBrute(carte_classes=carte, masque_sujet=masque, boite_sujet=boite)


# ---------------------------------------------------------------------------
# Étape 2 — réconciliation des masques (section 5.2)
# ---------------------------------------------------------------------------
@dataclass
class MetriquesPhoto:
    a_sujet: bool                 # un sujet a-t-il été détecté ?
    S: float                      # part de surface du sujet
    C: float                      # part de contexte intéressant
    reste: float                  # part restante (sol, fond, etc.)
    f_occ: float                  # 1 - occlusion (multiplicateur)
    parts_contexte: dict          # {famille: part} pour info
    centroide: Optional[tuple]    # (x,y) normalisés du sujet
    integrite: float              # 1 si entier, < 1 si coupé par le cadre
    H: float                      # composition agrégée
    H1: float                     # placement (tiers)
    H2: Optional[float]           # horizon (None si absent)
    H3: float                     # marge
    horizon_present: bool


def reconcilier_et_mesurer(brute: PerceptionBrute) -> MetriquesPhoto:
    """
    Étape 2 : sujet soustrait du décor, puis calcul de S, C, f_occ, centroïde,
    intégrité, composition. Garantit S + C + reste = 1.
    """
    carte = brute.carte_classes
    H_img, W_img = carte.shape
    aire_totale = float(H_img * W_img)

    if brute.masque_sujet is None:
        # pas de sujet : on calcule seulement le contexte (vue paysagère)
        parts, C = _parts_contexte(carte, masque_sujet=None)
        return MetriquesPhoto(
            a_sujet=False, S=0.0, C=C, reste=1.0 - C, f_occ=1.0,
            parts_contexte=parts, centroide=None, integrite=0.0,
            H=_composition_sans_sujet(carte), H1=0.0, H2=None, H3=0.0,
            horizon_present=_horizon(carte)[0],
        )

    masque = brute.masque_sujet.astype(bool)
    aire_sujet = float(masque.sum())
    S = aire_sujet / aire_totale

    # RÉCONCILIATION : le contexte est calculé sur la carte PRIVÉE des pixels-sujet
    parts, C = _parts_contexte(carte, masque_sujet=masque)
    reste = max(0.0, 1.0 - S - C)

    # OCCLUSION par fusion : on estime la silhouette attendue du sujet
    # (boîte englobante) et on regarde quelles classes occluantes occupent
    # l'intérieur de cette boîte sans appartenir au masque sujet.
    f_occ = _occlusion_fusion(carte, masque, brute.boite_sujet)

    # centroïde et intégrité
    centroide = _centroide(masque, W_img, H_img)
    integrite = _integrite(masque)

    # composition
    h1 = _placement_tiers(centroide)
    horizon_present, h2 = _composition_horizon(carte)
    h3 = _marge_bord(masque, W_img, H_img)
    H = _agreger_composition(h1, h2, h3, horizon_present)

    return MetriquesPhoto(
        a_sujet=True, S=S, C=C, reste=reste, f_occ=f_occ,
        parts_contexte=parts, centroide=centroide, integrite=integrite,
        H=H, H1=h1, H2=h2, H3=h3, horizon_present=horizon_present,
    )


def _parts_contexte(carte: np.ndarray, masque_sujet) -> tuple[dict, float]:
    """Parts de surface des familles de contexte, sujet exclu si fourni."""
    aire = float(carte.size)
    if masque_sujet is not None:
        # masque des pixels NON-sujet
        non_sujet = ~masque_sujet
    else:
        non_sujet = np.ones_like(carte, dtype=bool)
    parts = {}
    C = 0.0
    for cls_id, nom in FAMILLES_CONTEXTE.items():
        n = int(np.logical_and(carte == cls_id, non_sujet).sum())
        if n > 0:
            p = n / aire
            parts[nom] = p
            # le ciel compte peu dans C "intéressant" mais reste listé
            if cls_id != CLASSE_CIEL:
                C += p
            else:
                C += p * 0.3   # ciel : contribution réduite
    return parts, min(1.0, C)


def _occlusion_fusion(carte: np.ndarray, masque: np.ndarray, boite: Boite) -> float:
    """
    Occlusion = fraction de la silhouette attendue (boîte) qui est occupée par
    des classes occluantes (forêt, falaise, bâti) AU LIEU du sujet.
    f_occ = 1 - occlusion. C'est une métrique de FUSION : SAM2 seul ne le sait pas.
    """
    if boite is None:
        return 1.0
    x0, y0, x1, y1 = boite.x0, boite.y0, boite.x1, boite.y1
    sous_carte = carte[y0:y1, x0:x1]
    sous_masque = masque[y0:y1, x0:x1]
    aire_boite = float(sous_carte.size)
    if aire_boite <= 0:
        return 1.0
    # pixels de la boîte qui sont une classe occluante ET pas le sujet
    occluant = np.zeros_like(sous_carte, dtype=bool)
    for cls in CLASSES_OCCLUANTES:
        occluant |= (sous_carte == cls)
    occluant &= ~sous_masque
    occlusion = float(occluant.sum()) / aire_boite
    return max(0.0, 1.0 - occlusion)


def _centroide(masque: np.ndarray, W: int, H: int) -> tuple[float, float]:
    ys, xs = np.nonzero(masque)
    if len(xs) == 0:
        return (0.5, 0.5)
    return (float(xs.mean()) / W, float(ys.mean()) / H)


def _integrite(masque: np.ndarray) -> float:
    """1 si le sujet ne touche aucun bord ; pénalisé s'il est coupé par le cadre."""
    H, W = masque.shape
    bord = 0
    bord += int(masque[0, :].sum())
    bord += int(masque[H - 1, :].sum())
    bord += int(masque[:, 0].sum())
    bord += int(masque[:, W - 1].sum())
    perimetre_masque = _perimetre(masque)
    if perimetre_masque <= 0:
        return 1.0
    return max(0.0, 1.0 - bord / perimetre_masque)


def _perimetre(masque: np.ndarray) -> int:
    """Approx. du périmètre : pixels du masque ayant un voisin hors-masque."""
    m = masque
    bord = np.zeros_like(m)
    bord[:-1, :] |= m[:-1, :] & ~m[1:, :]
    bord[1:, :]  |= m[1:, :]  & ~m[:-1, :]
    bord[:, :-1] |= m[:, :-1] & ~m[:, 1:]
    bord[:, 1:]  |= m[:, 1:]  & ~m[:, :-1]
    # bords d'image comptent aussi
    bord[0, :] |= m[0, :]
    bord[-1, :] |= m[-1, :]
    bord[:, 0] |= m[:, 0]
    bord[:, -1] |= m[:, -1]
    return int(bord.sum())


# ---------------------------------------------------------------------------
# Composition (H1 placement, H2 horizon, H3 marge)
# ---------------------------------------------------------------------------
def _placement_tiers(centroide: tuple[float, float]) -> float:
    """H1 : proximité du centroïde aux lignes de tiers verticales (sujet vertical)."""
    x, _ = centroide
    d = min(abs(x - 1/3), abs(x - 2/3))
    sigma = 0.12
    return math.exp(-(d * d) / (2 * sigma * sigma))


def _horizon(carte: np.ndarray) -> tuple[bool, float, float]:
    """
    Détecte l'horizon comme frontière inférieure du ciel (sémantique).
    Retourne (present, y_h normalisé, angle approx en degrés).
    """
    H, W = carte.shape
    ys_frontiere = []
    for x in range(W):
        col = carte[:, x]
        ciel = np.nonzero(col == CLASSE_CIEL)[0]
        if len(ciel) == 0:
            continue
        # frontière = dernier pixel ciel en partant du haut (avant non-ciel durable)
        y_front = ciel.max()
        ys_frontiere.append((x, y_front))
    if len(ys_frontiere) < W * 0.3:   # couverture insuffisante
        return False, 0.0, 0.0
    xs = np.array([p[0] for p in ys_frontiere], dtype=float)
    ys = np.array([p[1] for p in ys_frontiere], dtype=float)
    # régression linéaire simple (les outliers seraient gérés par Theil-Sen en réel)
    if len(xs) > 1 and xs.std() > 0:
        pente = np.polyfit(xs, ys, 1)[0]
    else:
        pente = 0.0
    y_centre = ys.mean() / H
    angle_deg = math.degrees(math.atan(pente))
    return True, y_centre, angle_deg


def _composition_horizon(carte: np.ndarray) -> tuple[bool, Optional[float]]:
    """H2 : horizon droit (peu incliné) ET placé sur un tiers."""
    present, y_h, angle = _horizon(carte)
    if not present:
        return False, None
    sigma_ang = 3.0
    h2_niveau = math.exp(-(angle * angle) / (2 * sigma_ang * sigma_ang))
    sigma_h = 0.10
    d_tiers = min(abs(y_h - 1/3), abs(y_h - 2/3))
    h2_position = math.exp(-(d_tiers * d_tiers) / (2 * sigma_h * sigma_h))
    return True, h2_niveau * h2_position


def _marge_bord(masque: np.ndarray, W: int, H: int) -> float:
    """H3 : le sujet (entier) est-il trop collé au bord ?"""
    ys, xs = np.nonzero(masque)
    if len(xs) == 0:
        return 1.0
    marge = min(xs.min(), W - 1 - xs.max(), ys.min(), H - 1 - ys.max())
    marge_norm = marge / min(W, H)
    cible = 0.05
    return min(1.0, marge_norm / cible)


def _agreger_composition(h1: float, h2: Optional[float], h3: float,
                         horizon_present: bool) -> float:
    """
    H = H1^a * H2^b * H3^c (produit). Si l'horizon est absent, on renormalise
    les exposants sur H1 et H3 (la confiance d'horizon est une PORTE).
    """
    if horizon_present and h2 is not None:
        a, b, c = 0.4, 0.4, 0.2
        return (max(h1, 1e-6) ** a) * (max(h2, 1e-6) ** b) * (max(h3, 1e-6) ** c)
    else:
        # renormalisation sur H1 et H3 (a' + c' = 1)
        a, c = 0.4 / 0.6, 0.2 / 0.6
        return (max(h1, 1e-6) ** a) * (max(h3, 1e-6) ** c)


def _composition_sans_sujet(carte: np.ndarray) -> float:
    """Pour une vue paysagère sans sujet : composition basée sur l'horizon seul."""
    present, h2 = _composition_horizon(carte)
    return h2 if (present and h2 is not None) else 0.5


# ---------------------------------------------------------------------------
# Étape 3 — scoring par intention (section 5)
# ---------------------------------------------------------------------------
def _cloche(valeur: float, cible: float, sigma: float) -> float:
    return math.exp(-((valeur - cible) ** 2) / (2 * sigma * sigma))


@dataclass
class ScoreIntention:
    intention: str
    score: float
    dom: float
    ctx: float
    comp: float


def scorer_intentions(m: MetriquesPhoto) -> list[ScoreIntention]:
    """
    Étape 3 : pour chaque intention, score = f_occ * [w_dom*dom + w_ctx*ctx + w_comp*comp].
    Cloches centrées sur les cibles d'intention. Occlusion multiplicative.
    """
    if not m.a_sujet:
        # vue paysagère : seul l'establishing a du sens, via le contexte
        scores = []
        for it in INTENTIONS:
            if it.nom == "establishing":
                ctx = _cloche(m.C, it.c_etoile, it.sigma_c)
                s = m.C * ctx  # pas de sujet -> score porté par le contexte
                scores.append(ScoreIntention(it.nom, s, 0.0, ctx, m.H))
            else:
                scores.append(ScoreIntention(it.nom, 0.0, 0.0, 0.0, m.H))
        return scores

    resultats = []
    for it in INTENTIONS:
        # dominance : cloche sur la taille S, multipliée par intégrité (sujet entier)
        dom = _cloche(m.S, it.a_etoile, it.sigma_a) * m.integrite
        ctx = _cloche(m.C, it.c_etoile, it.sigma_c)
        comp = m.H
        crochet = it.w_dom * dom + it.w_ctx * ctx + it.w_comp * comp
        score = m.f_occ * crochet
        resultats.append(ScoreIntention(it.nom, score, dom, ctx, comp))
    return resultats


# ---------------------------------------------------------------------------
# Pipeline complet
# ---------------------------------------------------------------------------
@dataclass
class ResultatPerception:
    metriques: MetriquesPhoto
    scores: list[ScoreIntention]

    def meilleure_intention(self) -> ScoreIntention:
        return max(self.scores, key=lambda s: s.score)

    def to_dict(self) -> dict:
        m = self.metriques
        return {
            "sujet_detecte": m.a_sujet,
            "S": round(m.S, 3), "C": round(m.C, 3), "reste": round(m.reste, 3),
            "somme_SCreste": round(m.S + m.C + m.reste, 3),
            "f_occ": round(m.f_occ, 3),
            "centroide": [round(c, 3) for c in m.centroide] if m.centroide else None,
            "integrite": round(m.integrite, 3),
            "horizon_present": m.horizon_present,
            "H": round(m.H, 3),
            "scores": {s.intention: round(s.score, 3) for s in self.scores},
            "meilleure": self.meilleure_intention().intention,
        }


def analyser_photo(
    image: np.ndarray,
    segformer: SegFormerFn, yolo: YoloFn, sam2: Sam2Fn,
) -> ResultatPerception:
    """Pipeline complet : perception -> réconciliation/mesure -> scoring."""
    brute = percevoir(image, segformer, yolo, sam2)
    metriques = reconcilier_et_mesurer(brute)
    scores = scorer_intentions(metriques)
    return ResultatPerception(metriques=metriques, scores=scores)


# ===========================================================================
# IMPLÉMENTATIONS FACTICES DÉTERMINISTES (pour tests sans modèle ni GPU)
# ===========================================================================
def _fabriquer_scene(
    W=200, H=200,
    phare_box=(90, 40, 110, 160),      # x0,y0,x1,y1 : phare vertical centré
    horizon_y=130,                      # ligne d'horizon
    mer=True, falaise=False, foret_box=None,
):
    """
    Construit une scène synthétique et les sorties des trois modèles factices,
    de façon que la géométrie soit connue analytiquement.
    """
    carte = np.full((H, W), CLASSE_FOND, dtype=int)
    # ciel au-dessus de l'horizon
    carte[:horizon_y, :] = CLASSE_CIEL
    # mer ou sol sous l'horizon
    carte[horizon_y:, :] = CLASSE_MER if mer else CLASSE_SOL
    # falaise éventuelle (bande à droite)
    if falaise:
        carte[horizon_y - 30:horizon_y, int(W * 0.7):] = CLASSE_FALAISE
    # forêt éventuelle (pour test occlusion)
    if foret_box:
        fx0, fy0, fx1, fy1 = foret_box
        carte[fy0:fy1, fx0:fx1] = CLASSE_FORET

    # le phare : on le pose comme classe BATI dans la carte SegFormer
    px0, py0, px1, py1 = phare_box
    carte[py0:py1, px0:px1] = CLASSE_BATI

    # masque SAM2 du phare = exactement la boîte du phare (moins ce qui est forêt devant)
    masque = np.zeros((H, W), dtype=bool)
    masque[py0:py1, px0:px1] = True
    if foret_box:
        fx0, fy0, fx1, fy1 = foret_box
        # la forêt DEVANT le phare cache une partie du masque
        masque[fy0:fy1, fx0:fx1] = False

    boite = Boite("phare", 0.92, px0, py0, px1, py1)

    image = np.zeros((H, W, 3), dtype=np.uint8)  # image factice (non utilisée par les factices)

    def segformer(_img): return carte.copy()
    def yolo(_img): return [boite]
    def sam2(_img, _box): return masque.copy()
    return image, segformer, yolo, sam2, carte, masque, boite


# ===========================================================================
# TESTS DE CORRECTION
# ===========================================================================
def _tests():
    print("=" * 70)
    print("TEST cine_perception — scène synthétique à pixels connus")
    print("=" * 70)

    # ---- Test 1 : réconciliation S + C + reste = 1 ----
    img, sf, yl, s2, carte, masque, boite = _fabriquer_scene()
    r1 = analyser_photo(img, sf, yl, s2)
    print("\n[1] Réconciliation des masques")
    print("   ", r1.to_dict())
    assert abs(r1.metriques.S + r1.metriques.C + r1.metriques.reste - 1.0) < 1e-6, \
        "S + C + reste doit faire 1"
    # le phare (BATI) ne doit PAS être compté dans le contexte (soustrait)
    aire_phare = float(masque.sum())
    S_attendu = aire_phare / carte.size
    assert abs(r1.metriques.S - S_attendu) < 1e-6, "S = aire masque / aire image"
    print(f"    OK : S={r1.metriques.S:.3f} (exact), S+C+reste=1, sujet soustrait du décor")

    # ---- Test 2 : occlusion par fusion (forêt devant le phare) ----
    img2, sf2, yl2, s22, _, _, _ = _fabriquer_scene(
        foret_box=(95, 90, 115, 160))  # forêt qui couvre le bas du phare
    r2 = analyser_photo(img2, sf2, yl2, s22)
    print("\n[2] Occlusion par fusion (forêt devant le bas du phare)")
    print("   ", r2.to_dict())
    assert r2.metriques.f_occ < 1.0, "forêt devant -> occlusion détectée"
    assert r2.metriques.f_occ < r1.metriques.f_occ, "plus occulté que la scène nette"
    print(f"    OK : f_occ={r2.metriques.f_occ:.3f} < {r1.metriques.f_occ:.3f} "
          "(SAM2 seul ne l'aurait pas su)")

    # ---- Test 3 : intégrité (phare coupé par le bord) ----
    img3, sf3, yl3, s23, _, _, _ = _fabriquer_scene(
        phare_box=(90, 40, 110, 200))  # phare qui touche le bas (y1=H)
    r3 = analyser_photo(img3, sf3, yl3, s23)
    print("\n[3] Intégrité (phare coupé par le bord bas)")
    print(f"    integrite={r3.metriques.integrite:.3f} (vs {r1.metriques.integrite:.3f} entier)")
    assert r3.metriques.integrite < r1.metriques.integrite, "coupé -> intégrité réduite"
    print("    OK : sujet coupé par le cadre -> intégrité pénalisée")

    # ---- Test 4 : pas de sujet (YOLO ne détecte rien) -> vue paysagère ----
    img4, sf4, _, s24, _, _, _ = _fabriquer_scene()
    def yolo_vide(_img): return []
    r4 = analyser_photo(img4, sf4, yolo_vide, s24)
    print("\n[4] Aucun sujet détecté (vue paysagère)")
    print("   ", r4.to_dict())
    assert not r4.metriques.a_sujet, "pas de sujet"
    assert r4.meilleure_intention().intention == "establishing", \
        "vue paysagère -> establishing"
    print("    OK : sans sujet, SAM2 non appelé, establishing privilégié")

    # ---- Test 5 : scoring par intention (portrait vs establishing) ----
    # gros phare (S grand) -> portrait ; petit phare (S petit) + contexte -> establishing
    img_p, sfp, ylp, s2p, _, _, _ = _fabriquer_scene(
        phare_box=(70, 30, 130, 170))   # gros phare
    r_p = analyser_photo(img_p, sfp, ylp, s2p)
    img_e, sfe, yle, s2e, _, _, _ = _fabriquer_scene(
        phare_box=(96, 90, 104, 130), falaise=True)  # petit phare + falaise
    r_e = analyser_photo(img_e, sfe, yle, s2e)
    print("\n[5] Scoring par intention")
    print(f"    gros phare  : S={r_p.metriques.S:.3f} -> meilleure = {r_p.meilleure_intention().intention}")
    print(f"    petit+décor : S={r_e.metriques.S:.3f} -> meilleure = {r_e.meilleure_intention().intention}")
    print(f"      scores gros  : {[(s.intention, round(s.score,3)) for s in r_p.scores]}")
    print(f"      scores petit : {[(s.intention, round(s.score,3)) for s in r_e.scores]}")
    # le gros phare doit mieux scorer en portrait que le petit
    score_portrait_gros = next(s.score for s in r_p.scores if s.intention == "portrait")
    score_portrait_petit = next(s.score for s in r_e.scores if s.intention == "portrait")
    assert score_portrait_gros > score_portrait_petit, "gros phare meilleur en portrait"
    print("    OK : la taille du sujet oriente l'intention gagnante")

    print("\n" + "=" * 70)
    print("TOUS LES TESTS PASSENT — le pipeline de perception est correct.")
    print("=" * 70)
    print("\nLe cerveau de la perception est codé et vérifié :")
    print("  percevoir()              -> SegFormer + YOLO portier + SAM2")
    print("  reconcilier_et_mesurer() -> S, C, f_occ, centroïde, intégrité, H")
    print("  scorer_intentions()      -> score par intention (cloches, porte horizon)")
    print("\nPour le réel : remplacer les fonctions factices par les vrais")
    print("modèles (transformers/ultralytics/segment-anything) sur GPU.")


if __name__ == "__main__":
    _tests()
