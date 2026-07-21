# -*- coding: utf-8 -*-
"""
stream_key.py — Router FastAPI /api/stream_key pour CineFlight (serveur cineflight.ca).

Permet a l'utilisateur de saisir sa cle de diffusion YouTube UNE FOIS sur le web
(onglet Parametres > Diffusion), et a l'app Android de la RECUPERER automatiquement
(canal authentifie JWT), sans copier-coller sur le telephone.

Calque sur la structure REELLE du serveur :
  - FastAPI + APIRouter (comme cine_missions_store.py)
  - auth via la dependance get_user_courant de cine_auth.py
    (valide le Bearer JWT et renvoie {"id": ..., "username": ...})

Routes :
    GET  /api/stream_key   -> { "stream_key": "...", "server_url": "..." }  (404 si absente)
    POST /api/stream_key   -> body { "stream_key": "..." }                 -> { "ok": true }

INSTALLATION :
  1) Deposer ce fichier dans cineflight_web/ (a cote de cine_auth.py).
  2) Dans app.py, la ou les autres routers sont montes, ajouter :
         from stream_key import router as stream_key_router
         app.include_router(stream_key_router)
     (si app.py importe avec le prefixe paquet, utiliser
         from cineflight_web.stream_key import router as stream_key_router )

SECURITE : la cle est un secret ; jamais journalisee en clair, renvoyee au seul
proprietaire (identifie par le JWT).
"""

import os
import json
import threading
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

# Reutilise EXACTEMENT la dependance d'auth du serveur (cine_auth.get_user_courant).
try:
    from cine_auth import get_user_courant
except Exception:
    try:
        from cineflight_web.cine_auth import get_user_courant
    except Exception as _e:
        raise ImportError(
            "Impossible d'importer get_user_courant depuis cine_auth. "
            "Ajuste l'import en haut de stream_key.py selon ton arborescence."
        ) from _e

# URL serveur RTMPS YouTube par defaut renvoyee a l'app (l'app garde son defaut si absent).
SERVER_URL_DEFAUT = "rtmps://a.rtmps.youtube.com/live2"

router = APIRouter(prefix="/api/stream_key", tags=["stream_key"])

# ── Stockage simple par fichier JSON (a cote de ce module). ──────────────────────────
_FICHIER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stream_keys.json")
_verrou = threading.Lock()


def _charger():
    if not os.path.exists(_FICHIER):
        return {}
    try:
        with open(_FICHIER, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _sauver(data):
    tmp = _FICHIER + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, _FICHIER)


def _uid(user) -> str:
    # get_user_courant renvoie {"id": ..., "username": ...}
    if isinstance(user, dict):
        return str(user.get("id") or user.get("username"))
    return str(user)


def _lire_cle(user):
    with _verrou:
        return _charger().get(_uid(user))


def _ecrire_cle(user, cle):
    with _verrou:
        data = _charger()
        data[_uid(user)] = cle
        _sauver(data)


class CleEntree(BaseModel):
    stream_key: str


@router.get("")
def get_stream_key(user: dict = Depends(get_user_courant)):
    cle = _lire_cle(user)
    if not cle:
        raise HTTPException(status_code=404, detail="aucune cle")
    return {"stream_key": cle, "server_url": SERVER_URL_DEFAUT}


@router.post("")
def post_stream_key(entree: CleEntree, user: dict = Depends(get_user_courant)):
    cle = (entree.stream_key or "").strip()
    if not cle:
        raise HTTPException(status_code=400, detail="stream_key manquante")
    _ecrire_cle(user, cle)
    return {"ok": True}
