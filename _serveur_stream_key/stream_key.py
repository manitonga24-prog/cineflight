# -*- coding: utf-8 -*-
"""
stream_key.py — Router FastAPI /api/stream_key pour CineFlight (serveur cineflight.ca).

Permet a l'utilisateur de SAISIR sa cle de diffusion YouTube UNE FOIS sur le web
(Parametres), et a l'app Android de la RECUPERER automatiquement (canal authentifie JWT),
sans copier-coller sur le telephone.

Calque sur la structure REELLE du serveur :
  - FastAPI + APIRouter (comme cine_missions_store.py)
  - authentification via la dependance get_current_user de cine_auth.py
    (elle renvoie {"id": ..., "username": ...} et valide le Bearer JWT)

Routes :
    GET  /api/stream_key   -> { "stream_key": "...", "server_url": "..." }  (404 si absente)
    POST /api/stream_key   -> body { "stream_key": "..." }                 -> { "ok": true }

INSTALLATION (dans app.py, la ou les autres routers sont montes) :
    from stream_key import router as stream_key_router
    app.include_router(stream_key_router)

A VERIFIER / ADAPTER (2 points seulement) :
  1) l'import de get_current_user : ajuste le chemin si besoin (voir ci-dessous).
  2) le stockage : ici un fichier JSON simple. Si tu as une base (users), stocke plutot
     la cle dans une colonne du compte (voir _lire_cle / _ecrire_cle).

SECURITE :
  - Ne renvoie la cle QU'A son proprietaire (identifie par le JWT).
  - Ne journalise JAMAIS la cle en clair.
"""

import os
import json
import threading
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

# ── AUTH : on reutilise EXACTEMENT la dependance existante du serveur ────────────────
# get_current_user (cine_auth.py) valide le Bearer JWT et renvoie {"id", "username"}.
# Ajuste l'import selon l'arborescence reelle (essais courants ci-dessous).
try:
    from cine_auth import get_current_user            # si lance depuis cineflight_web/
except Exception:
    try:
        from cineflight_web.cine_auth import get_current_user  # si lance depuis la racine
    except Exception as _e:
        raise ImportError(
            "Impossible d'importer get_current_user depuis cine_auth. "
            "Ajuste l'import en haut de stream_key.py selon ton arborescence."
        ) from _e

# URL serveur RTMPS YouTube par defaut renvoyee a l'app (l'app garde son defaut si absent).
SERVER_URL_DEFAUT = "rtmps://a.rtmps.youtube.com/live2"

router = APIRouter(prefix="/api/stream_key", tags=["stream_key"])


# ── Stockage simple par fichier JSON (a remplacer par ta base de donnees) ────────────
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


def _cle_utilisateur(user) -> str:
    # user = {"id": ..., "username": ...} renvoye par get_current_user.
    return str(user.get("id") if isinstance(user, dict) else user)


def _lire_cle(user):
    with _verrou:
        return _charger().get(_cle_utilisateur(user))


def _ecrire_cle(user, cle):
    with _verrou:
        data = _charger()
        data[_cle_utilisateur(user)] = cle
        _sauver(data)


# ── Modele de corps POST ─────────────────────────────────────────────────────────────
class CleEntree(BaseModel):
    stream_key: str


# ── Routes ───────────────────────────────────────────────────────────────────────────
@router.get("")
def get_stream_key(user=Depends(get_current_user)):
    cle = _lire_cle(user)
    if not cle:
        raise HTTPException(status_code=404, detail="aucune cle")
    return {"stream_key": cle, "server_url": SERVER_URL_DEFAUT}


@router.post("")
def post_stream_key(entree: CleEntree, user=Depends(get_current_user)):
    cle = (entree.stream_key or "").strip()
    if not cle:
        raise HTTPException(status_code=400, detail="stream_key manquante")
    _ecrire_cle(user, cle)
    return {"ok": True}
