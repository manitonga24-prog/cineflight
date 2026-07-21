# -*- coding: utf-8 -*-
"""
stream_key.py — Endpoint /api/stream_key pour CineFlight.

Permet a l'utilisateur de SAISIR sa cle de diffusion YouTube UNE FOIS sur le web
(Parametres), et a l'app Android de la RECUPERER automatiquement (canal authentifie JWT),
sans copier-coller sur le telephone.

Routes :
    GET  /api/stream_key   -> { "stream_key": "...", "server_url": "..." }  (ou 404 si absente)
    POST /api/stream_key   -> body JSON { "stream_key": "..." }            -> { "ok": true }

A ADAPTER a ton code :
    1) _utilisateur_courant(req) : remplace par TA lecture du JWT (celle de /api/missions).
    2) _lire_cle / _ecrire_cle : ici un simple fichier JSON. Remplace par ta base de donnees
       si tu en as une (une colonne "stream_key" sur le compte, c'est l'ideal).

SECURITE :
    - Ne renvoie la cle QU'A son proprietaire (identifie par le JWT).
    - Ne journalise JAMAIS la cle en clair.
"""

import os
import json
import threading
from flask import Blueprint, request, jsonify

bp_stream_key = Blueprint("stream_key", __name__)

# URL serveur RTMPS YouTube par defaut renvoyee a l'app (l'app garde son propre defaut si absent).
SERVER_URL_DEFAUT = "rtmps://a.rtmps.youtube.com/live2"

# --- Stockage simple par fichier JSON (a remplacer par ta base de donnees) -----------------
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


def _lire_cle(user_id):
    with _verrou:
        return _charger().get(str(user_id))


def _ecrire_cle(user_id, cle):
    with _verrou:
        data = _charger()
        data[str(user_id)] = cle
        _sauver(data)


# --- Identification de l'utilisateur depuis le JWT -----------------------------------------
# >>> REMPLACE le corps de cette fonction par TA logique existante (celle de /api/missions). <<<
def _utilisateur_courant(req):
    """
    Doit renvoyer un identifiant d'utilisateur (str/int) a partir de l'en-tete
    Authorization: Bearer <JWT>, ou None si non authentifie.

    Exemple typique (a adapter a ta lib JWT) :

        import jwt  # PyJWT
        auth = req.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return None
        token = auth[7:]
        try:
            payload = jwt.decode(token, TA_CLE_SECRETE, algorithms=["HS256"])
            return payload.get("sub") or payload.get("user_id") or payload.get("username")
        except Exception:
            return None
    """
    # Placeholder minimal (a NE PAS laisser en prod) : renvoie None => 401.
    auth = req.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    # TODO: decoder le JWT et renvoyer l'identifiant reel. Voir l'exemple ci-dessus.
    return None


# --- Routes --------------------------------------------------------------------------------
@bp_stream_key.route("/api/stream_key", methods=["GET"])
def get_stream_key():
    user = _utilisateur_courant(request)
    if user is None:
        return jsonify({"error": "non authentifie"}), 401
    cle = _lire_cle(user)
    if not cle:
        return jsonify({"error": "aucune cle"}), 404
    return jsonify({"stream_key": cle, "server_url": SERVER_URL_DEFAUT}), 200


@bp_stream_key.route("/api/stream_key", methods=["POST"])
def post_stream_key():
    user = _utilisateur_courant(request)
    if user is None:
        return jsonify({"error": "non authentifie"}), 401
    data = request.get_json(silent=True) or {}
    cle = (data.get("stream_key") or "").strip()
    if not cle:
        return jsonify({"error": "stream_key manquante"}), 400
    _ecrire_cle(user, cle)
    return jsonify({"ok": True}), 200
