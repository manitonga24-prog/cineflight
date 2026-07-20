#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
worker_perception.py — Worker de perception GPU (QUESTSERVER, RTX 3090).

Petit serveur FastAPI qui expose la perception (YOLO-World + SAM2 + SegFormer)
pour que le serveur DO (sans GPU) puisse la déléguer.

    POST /perception   (multipart : 1 photo)
      -> charge les modèles (1 fois, gardés en VRAM)
      -> analyser_photo (YOLO-World + SAM2 + SegFormer)
      -> {sujet_detecte, conf, S, centroide, integrite, f_occ}

LANCEMENT sur QUESTSERVER (où sont les modèles + le 3090) :
    cd C:\\cineflight_android\\CineFlightSolo
    python -m uvicorn worker_perception:app --host 0.0.0.0 --port 8200

Les modèles se chargent à la 1re requête (lent ~10-30 s), puis restent en VRAM
(requêtes suivantes rapides ~1-2 s/photo).

POUR QUE LE SERVEUR DO L'ATTEIGNE : QUESTSERVER étant derrière une box, exposer
via un tunnel (ngrok : `ngrok http 8200`) et donner l'URL publique au DO. Pour
la VALIDATION LOCALE, pas besoin : voir cine_test_reconnaissance.py qui appelle
la perception en direct, sans réseau.
"""

from __future__ import annotations
import io
import tempfile
import os

from fastapi import FastAPI, UploadFile, File
from fastapi.responses import JSONResponse

app = FastAPI(title="CineFlight Perception Worker", version="1.0")

# Modèles chargés une seule fois (paresseux : à la 1re requête)
_modeles = None


def _charger_si_besoin():
    global _modeles
    if _modeles is None:
        from cine_perception_reel import charger_modeles
        print("[worker] chargement des modèles sur GPU (lent la 1re fois)...")
        _modeles = charger_modeles()
        print("[worker] modèles prêts en VRAM")
    return _modeles


def perception_sur_fichier(chemin: str) -> dict:
    """
    Analyse une photo et retourne le dict attendu par l'endpoint DO.
    Réutilise analyser_photo (cine_perception) avec les modèles réels GPU.
    """
    import numpy as np
    from PIL import Image
    from cine_perception import analyser_photo

    seg, yolo, sam = _charger_si_besoin()
    img = np.array(Image.open(chemin).convert("RGB"))
    res = analyser_photo(img, seg, yolo, sam)
    d = res.to_dict()

    # mapper vers le format attendu par cine_endpoint_reconnaissance
    centroide = d.get("centroide")
    return {
        "sujet_detecte": bool(d.get("a_sujet", False)) and centroide is not None,
        "conf": float(d.get("S", 0.0)),          # proxy de confiance = taille relative
        "S": float(d.get("S", 0.0)),
        "centroide": list(centroide) if centroide else [0.5, 0.5],
        "integrite": float(d.get("integrite", 1.0)),
        "f_occ": float(d.get("f_occ", 1.0)),
    }


@app.get("/sante")
def sante():
    """Indique si les modèles sont chargés."""
    return {"ok": True, "modeles_charges": _modeles is not None}


@app.post("/perception")
async def perception(photo: UploadFile = File(...)):
    """Reçoit une photo, renvoie le centroïde + qualité (perception GPU réelle)."""
    suffixe = os.path.splitext(photo.filename or "p.jpg")[1] or ".jpg"
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=suffixe)
    try:
        tmp.write(await photo.read())
        tmp.close()
        resultat = perception_sur_fichier(tmp.name)
        return JSONResponse(resultat)
    finally:
        try:
            os.remove(tmp.name)
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn
    print("Worker de perception — lance sur le port 8200")
    print("Les modèles se chargent à la 1re requête /perception")
    uvicorn.run(app, host="0.0.0.0", port=8200)
