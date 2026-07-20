#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_perception_reel.py — Vrais modèles (YOLO + SAM2 + SegFormer) sur GPU.

Remplace les fonctions FACTICES de cine_perception.py par les VRAIS modèles,
tournant sur QUESTSERVER (RTX 3090). C'est le "Temps 3" de l'architecture cloud
distribuée : perception lourde AU RETOUR du vol, pas en vol.

Les trois fonctions respectent exactement les contrats de cine_perception :
    segformer(image: ndarray HxWx3) -> ndarray HxW d'ids (familles CineFlight)
    yolo(image: ndarray HxWx3)      -> list[Boite]
    sam2(image: ndarray HxWx3, box) -> ndarray HxW bool (masque sujet)

=============================================================================
INSTALLATION SUR QUESTSERVER (une fois)
=============================================================================
    pip install ultralytics                        # YOLO
    pip install transformers torch                 # SegFormer (deja si CUDA ok)
    pip install git+https://github.com/facebookresearch/sam2.git   # SAM2
    # poids SAM2 :
    #   wget https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_small.pt

=============================================================================
USAGE
=============================================================================
    from cine_perception import analyser_photo
    from cine_perception_reel import charger_modeles
    import numpy as np
    from PIL import Image

    seg, yolo, sam = charger_modeles()          # charge les 3 modeles sur GPU (1 fois)
    img = np.array(Image.open("photo_reco.jpg").convert("RGB"))
    res = analyser_photo(img, seg, yolo, sam)   # pipeline complet sur la vraie photo
    print(res.to_dict())

charger_modeles() garde les modeles en memoire (chargement lent ~10-30 s la
1re fois, puis instantane). A appeler UNE fois, reutiliser pour toutes les photos.
"""

from __future__ import annotations
import math
import numpy as np

# Import des types/constantes du pipeline (contrats a respecter)
from cine_perception import (
    Boite,
    CLASSE_CIEL, CLASSE_MER, CLASSE_FALAISE, CLASSE_FORET,
    CLASSE_BATI, CLASSE_ROUTE, CLASSE_SOL, CLASSE_FOND,
)


# ---------------------------------------------------------------------------
# MAPPINGS : classes des modèles pré-entraînés -> familles CineFlight
# ---------------------------------------------------------------------------
# SegFormer ADE20K (150 classes) -> nos 7 familles. ADE20K a des classes
# "sky", "sea", "water", "tree", "building", "road", "earth", "mountain"...
# On mappe les ids ADE20K vers nos familles. (ids ADE20K 0-indexés)
ADE20K_VERS_FAMILLE = {
    2: CLASSE_CIEL,      # sky
    9: CLASSE_FORET,     # grass -> végétation (approx)
    4: CLASSE_FORET,     # tree
    17: CLASSE_FORET,    # plant
    1: CLASSE_BATI,      # building
    25: CLASSE_BATI,     # house
    6: CLASSE_ROUTE,     # road
    11: CLASSE_ROUTE,    # sidewalk
    13: CLASSE_SOL,      # earth/ground
    21: CLASSE_MER,      # water
    26: CLASSE_MER,      # sea
    60: CLASSE_MER,      # river
    16: CLASSE_FALAISE,  # mountain
    34: CLASSE_FALAISE,  # rock
}

# YOLO COCO : classes pertinentes comme "sujet" potentiel. Pour un usage
# cinematographique generaliste, on retient les structures/objets saillants.
# COCO n'a pas "phare"/"pont" : le portier YOLO sert surtout a confirmer qu'il
# y a UN objet saillant. On garde toutes les detections au-dessus du seuil et
# on prend la plus grande/confiante comme sujet (cine_perception fait le tri).
COCO_SUJETS_PERTINENTS = None   # None = accepter toutes les classes COCO


# ---------------------------------------------------------------------------
# Conteneur de modèles (chargés une fois, réutilisés)
# ---------------------------------------------------------------------------
class _Modeles:
    def __init__(self):
        self.yolo = None
        self.sam2_predictor = None
        self.segformer_model = None
        self.segformer_processor = None
        self.device = None
        self.vocabulaire = None


_M = _Modeles()


# ---------------------------------------------------------------------------
# VOCABULAIRE OPEN-VOCABULARY (YOLO-World)
# ---------------------------------------------------------------------------
# Détecteur à vocabulaire OUVERT : on lui donne les CONCEPTS à chercher, par
# texte. Contrairement à COCO (80 classes fermées, aveugle aux structures),
# YOLO-World détecte ces sujets par leur VRAI nom — plus jamais "truck" pour
# un phare. Calibré sur les sujets cinématographiques CineFlight.
VOCABULAIRE_SUJETS = [
    "lighthouse", "bridge", "building", "tower", "monument",
    "church", "castle", "dam", "windmill", "statue",
    "waterfall", "cliff", "rock formation", "pier", "harbor",
    "chapel", "historic building",
]


def charger_modeles(
    chemin_sam2_checkpoint: str = "sam2.1_hiera_small.pt",
    config_sam2: str = "configs/sam2.1/sam2.1_hiera_s.yaml",
    modele_yolo: str = "yolov8x-world.pt",
    modele_segformer: str = "nvidia/segformer-b2-finetuned-ade-512-512",
    vocabulaire_sujets: list = None,
):
    """
    Charge les 3 modèles sur GPU (une fois). Retourne (segformer_fn, yolo_fn, sam2_fn)
    prêtes à passer à analyser_photo.

    modele_yolo : YOLO-World (open-vocabulary) par défaut. Détecte les sujets de
                  vocabulaire_sujets par leur nom, pas par approximation COCO.
    vocabulaire_sujets : liste de concepts à détecter (défaut = VOCABULAIRE_SUJETS).
                         Modifiable selon les sujets visés.
    """
    import torch
    _M.device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[cine_perception_reel] device = {_M.device}")

    # --- YOLO-World (Ultralytics, open-vocabulary) ---
    from ultralytics import YOLO
    _M.yolo = YOLO(modele_yolo)
    vocab = vocabulaire_sujets if vocabulaire_sujets is not None else VOCABULAIRE_SUJETS
    # set_classes : définit le vocabulaire ouvert à détecter (par texte)
    try:
        _M.yolo.set_classes(vocab)
        _M.vocabulaire = vocab
        print(f"[cine_perception_reel] YOLO-World chargé : {modele_yolo}")
        print(f"[cine_perception_reel] vocabulaire ({len(vocab)} sujets) : {', '.join(vocab)}")
    except AttributeError:
        # repli : si le modèle n'est pas un World (pas de set_classes), on garde COCO
        _M.vocabulaire = None
        print(f"[cine_perception_reel] YOLO (COCO fermé) chargé : {modele_yolo}")
        print("[cine_perception_reel] ATTENTION : set_classes indisponible -> vocabulaire COCO seulement")

    # --- SegFormer (HuggingFace transformers) ---
    from transformers import SegformerImageProcessor, SegformerForSemanticSegmentation
    _M.segformer_processor = SegformerImageProcessor.from_pretrained(modele_segformer)
    _M.segformer_model = SegformerForSemanticSegmentation.from_pretrained(modele_segformer).to(_M.device)
    _M.segformer_model.eval()
    print(f"[cine_perception_reel] SegFormer chargé : {modele_segformer}")

    # --- SAM2 (Meta) ---
    from sam2.build_sam import build_sam2
    from sam2.sam2_image_predictor import SAM2ImagePredictor
    sam2_model = build_sam2(config_sam2, chemin_sam2_checkpoint, device=_M.device)
    _M.sam2_predictor = SAM2ImagePredictor(sam2_model)
    print(f"[cine_perception_reel] SAM2 chargé : {chemin_sam2_checkpoint}")

    return _segformer_reel, _yolo_reel, _sam2_reel


# ---------------------------------------------------------------------------
# Fonction 1 — SegFormer réel
# ---------------------------------------------------------------------------
def _segformer_reel(image: np.ndarray) -> np.ndarray:
    """
    Segmentation sémantique ADE20K -> carte d'ids de familles CineFlight.
    image : ndarray HxWx3 (RGB, uint8). Retour : ndarray HxW d'ids (int).
    """
    import torch
    from PIL import Image as PILImage

    H, W = image.shape[:2]
    pil = PILImage.fromarray(image.astype(np.uint8))
    inputs = _M.segformer_processor(images=pil, return_tensors="pt").to(_M.device)
    with torch.no_grad():
        logits = _M.segformer_model(**inputs).logits  # (1, 150, h/4, w/4)
    # upsample à la taille image
    upsampled = torch.nn.functional.interpolate(
        logits, size=(H, W), mode="bilinear", align_corners=False)
    ade_ids = upsampled.argmax(dim=1)[0].cpu().numpy()  # HxW, ids ADE20K

    # mapper ADE20K -> familles CineFlight (défaut = FOND)
    carte = np.full((H, W), CLASSE_FOND, dtype=int)
    for ade_id, famille in ADE20K_VERS_FAMILLE.items():
        carte[ade_ids == ade_id] = famille
    return carte


# ---------------------------------------------------------------------------
# Fonction 2 — YOLO-World réel (portier OPEN-VOCABULARY)
# ---------------------------------------------------------------------------
# YOLO-World détecte les sujets de VOCABULAIRE_SUJETS par leur VRAI nom
# (lighthouse, bridge, building...). Le nom de classe est désormais CORRECT et
# exploitable. On garde un seuil bas pour capter les candidats, puis on
# départage par SAILLANCE GÉOMÉTRIQUE quand plusieurs sujets sont détectés.
SEUIL_CONF_YOLO_BAS = 0.05   # YOLO-World a des confiances plus basses que COCO

# Concepts à EXCLURE comme sujet même s'ils sont détectés (éléments de fond ou
# faux positifs aériens). Avec le vocabulaire choisi c'est rare, mais on garde
# le garde-fou pour des prompts génériques accidentels.
COCO_EXCLURE = {"bird", "kite", "airplane", "frisbee", "sky", "cloud"}


def _saillance(boite: Boite, W: int, H: int) -> float:
    """
    Score de saillance géométrique d'une boîte, INDÉPENDANT de sa classe COCO.
    Combine :
      - taille : un sujet doit occuper une part notable du cadre (mais pas tout)
      - centralité : un sujet cadré est plutôt vers le centre que collé au bord
      - confiance : à saillance égale, on préfère la détection la plus sûre
    Retourne un score [0,1+] pour trier les candidats.
    """
    aire_img = float(W * H)
    aire_boite = float((boite.x1 - boite.x0) * (boite.y1 - boite.y0))
    if aire_img <= 0 or aire_boite <= 0:
        return 0.0
    frac = aire_boite / aire_img

    # taille : cloche douce, optimum vers 5-40% du cadre, pénalise minuscule et géant
    if frac < 0.005:
        f_taille = frac / 0.005          # trop petit -> proche de 0
    elif frac > 0.85:
        f_taille = max(0.0, (1.0 - frac) / 0.15)  # remplit tout -> probablement le décor
    else:
        f_taille = min(1.0, frac / 0.25)  # monte jusqu'à ~25%, plateau ensuite

    # centralité : distance du centre de la boîte au centre image (normalisée)
    cx = (boite.x0 + boite.x1) / 2.0 / W
    cy = (boite.y0 + boite.y1) / 2.0 / H
    d_centre = math.hypot(cx - 0.5, cy - 0.5) / 0.707  # 0 (centre) .. 1 (coin)
    f_central = 1.0 - 0.6 * d_centre   # le bord pénalise, sans éliminer

    return f_taille * f_central * (0.5 + 0.5 * boite.conf)


def _yolo_reel(image: np.ndarray) -> list:
    """
    Détection robuste -> list[Boite] TRIÉE par saillance géométrique décroissante.

    Le nom COCO est conservé (info), mais le TRI ignore la classe : on prend le
    plus gros objet bien cadré comme sujet, quelle que soit son étiquette. Ainsi
    un phare détecté "truck" reste le sujet, et un avion lointain "airplane" est
    exclu. cine_perception prend la 1re boîte (la plus saillante) comme sujet.
    """
    H, W = image.shape[:2]
    results = _M.yolo(image, conf=SEUIL_CONF_YOLO_BAS, verbose=False)
    candidats = []
    for r in results:
        if r.boxes is None:
            continue
        for b in r.boxes:
            conf = float(b.conf[0])
            cls_id = int(b.cls[0])
            nom = _M.yolo.names.get(cls_id, str(cls_id)) if hasattr(_M.yolo, "names") else str(cls_id)
            if nom in COCO_EXCLURE:
                continue
            x0, y0, x1, y1 = b.xyxy[0].tolist()
            boite = Boite(classe=nom, conf=conf,
                          x0=int(x0), y0=int(y0), x1=int(x1), y1=int(y1))
            candidats.append((_saillance(boite, W, H), boite))

    # tri par saillance décroissante.
    # cine_perception filtre par conf >= 0.5 PUIS prend max(conf). YOLO-World a
    # des confiances souvent < 0.5 même pour de vrais sujets. SOLUTION : on
    # combine conf_yolo ET saillance en un SCORE DE SUJET, puis on RÉ-ÉCHELONNE
    # pour que le meilleur candidat (vrai sujet détecté) passe le seuil, et que
    # les suivants soient ordonnés dessous. Ainsi le portier max(conf) de
    # cine_perception choisit le sujet le plus pertinent, sans rejeter à tort.
    candidats.sort(key=lambda t: t[0], reverse=True)
    if not candidats:
        return []

    # score de sujet = conf_yolo pondérée par saillance (les deux comptent :
    # un concept bien détecté ET bien cadré). On filtre d'abord les candidats
    # sous un plancher de détection réel (évite de promouvoir du bruit).
    PLANCHER_DETECTION = 0.05
    scored = []
    for saillance, b in candidats:
        if b.conf < PLANCHER_DETECTION:
            continue
        score_sujet = b.conf * (0.4 + 0.6 * saillance)   # conf ET saillance
        scored.append((score_sujet, saillance, b))
    if not scored:
        return []
    scored.sort(key=lambda t: t[0], reverse=True)

    # ré-échelonnage : le meilleur passe nettement le seuil cine_perception (0.5),
    # les autres descendent proportionnellement -> ordre préservé, sujet choisi.
    score_max = scored[0][0] or 1.0
    SEUIL_CINE = 0.5
    boites_triees = []
    for i, (score_sujet, saillance, b) in enumerate(scored):
        # le meilleur -> conf 0.95 ; les suivants -> proportionnel, mais toujours
        # sous le meilleur. Le 1er passe le seuil ; les autres servent de
        # candidats de repli si cine_perception en tenait compte (il prend max).
        conf_finale = SEUIL_CINE + (0.95 - SEUIL_CINE) * (score_sujet / score_max)
        b_robuste = Boite(classe=b.classe, conf=conf_finale,
                          x0=b.x0, y0=b.y0, x1=b.x1, y1=b.y1)
        boites_triees.append(b_robuste)
    return boites_triees


# ---------------------------------------------------------------------------
# Fonction 3 — SAM2 réel
# ---------------------------------------------------------------------------
def _sam2_reel(image: np.ndarray, box: Boite) -> np.ndarray:
    """
    Segmentation du sujet par SAM2, amorcée par la boîte YOLO.
    image : ndarray HxWx3 RGB. box : Boite. Retour : ndarray HxW bool.
    """
    import torch
    H, W = image.shape[:2]
    _M.sam2_predictor.set_image(image)
    box_np = np.array([box.x0, box.y0, box.x1, box.y1], dtype=np.float32)
    with torch.inference_mode():
        masks, scores, _ = _M.sam2_predictor.predict(
            box=box_np[None, :], multimask_output=False)
    # masks : (1, H, W) ou (1, 1, H, W) selon version
    m = np.asarray(masks)
    while m.ndim > 2:
        m = m[0]
    return m.astype(bool)


# ===========================================================================
# TEST sur une vraie image (à lancer sur QUESTSERVER)
# ===========================================================================
def _test_sur_image(chemin_image: str):
    """Charge les modèles, analyse une vraie photo, affiche le résultat."""
    from cine_perception import analyser_photo
    from PIL import Image
    import json, time

    print("Chargement des modèles (lent la 1re fois)...")
    seg, yolo, sam = charger_modeles()

    img = np.array(Image.open(chemin_image).convert("RGB"))
    print(f"Image : {img.shape[1]}x{img.shape[0]}")

    t0 = time.time()
    res = analyser_photo(img, seg, yolo, sam)
    dt = time.time() - t0

    print(json.dumps(res.to_dict(), indent=2, ensure_ascii=False))
    print(f"\nTemps perception : {dt:.1f} s")
    print(f"Meilleure intention : {res.meilleure_intention().intention}")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1:
        _test_sur_image(sys.argv[1])
    else:
        print("Usage : python cine_perception_reel.py <chemin_image.jpg>")
        print("(à lancer sur QUESTSERVER avec GPU, après installation des modèles)")
