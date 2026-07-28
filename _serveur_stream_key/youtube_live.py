# -*- coding: utf-8 -*-
"""
youtube_live.py — Creation AUTOMATIQUE d'un direct YouTube pour CineFlight.

OBJECTIF (cote utilisateur : ZERO configuration)
  L'app appuie sur "Diffuser" -> appelle POST /api/creer_live sur CE serveur.
  Le serveur cree le direct via l'API YouTube (avec le compte Google du proprietaire
  de la chaine, autorise UNE seule fois), recupere :
    - la CLE de diffusion RTMP (a envoyer au drone)
    - l'URL de VISIONNAGE (watch_url, a afficher sur /live/{utilisateur})
  puis ecrit ces infos dans stream_keys.json (le meme stockage que stream_key.py).
  Resultat : la page /live/christian s'affiche toute seule, l'app recoit la cle.

MODELE OAUTH
  Creer un live YouTube EXIGE un token OAuth utilisateur (ni cle API, ni compte de
  service : YouTube les refuse pour publier). On autorise UNE fois via navigateur :
    GET  /api/youtube/oauth/start     -> redirige vers l'ecran Google
    GET  /api/youtube/oauth/callback  -> Google renvoie un code ; on l'echange contre
                                         un refresh_token stocke cote serveur (durable).
  Ensuite, le serveur renouvelle seul l'access_token a partir du refresh_token.

SECURITE
  - client_secret et refresh_token sont des SECRETS : lus depuis l'environnement /
    fichier local, jamais journalises ni renvoyes a l'app.
  - la cle de diffusion est renvoyee au seul proprietaire (JWT), comme stream_key.py.

INSTALLATION (a cote de stream_key.py, dans cineflight_web/)
  1) Deposer youtube_live.py.
  2) Dans app.py :
        from youtube_live import router as youtube_live_router
        app.include_router(youtube_live_router)
  3) Variables d'environnement (ou fichier youtube_oauth.json genere au callback) :
        YT_CLIENT_ID      = "1051543423619-....apps.googleusercontent.com"
        YT_CLIENT_SECRET  = "GOCSPX-...."
        YT_REDIRECT_URI   = "https://cineflight.ca/api/youtube/oauth/callback"
  4) Ouvrir UNE fois dans le navigateur (connecte a la chaine YouTube voulue) :
        https://cineflight.ca/api/youtube/oauth/start
     -> autoriser -> le refresh_token est stocke. C'est fait pour toujours.

DEPENDANCE : requests (pip install requests). Aucune lib Google lourde requise.
"""

import os
import json
import time
import threading
import urllib.parse

import requests
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import RedirectResponse, HTMLResponse

try:
    from cine_auth import get_user_courant
except Exception:
    try:
        from cineflight_web.cine_auth import get_user_courant
    except Exception as _e:
        raise ImportError(
            "Impossible d'importer get_user_courant depuis cine_auth."
        ) from _e

# Le stockage des cles/watch_url est partage avec stream_key.py : on reutilise
# ses helpers d'ecriture pour ne PAS dupliquer la logique de stockage par compte.
try:
    from stream_key import _ecrire as _ecrire_compte, _username as _username_compte
except Exception:
    try:
        from cineflight_web.stream_key import _ecrire as _ecrire_compte, _username as _username_compte
    except Exception as _e:
        raise ImportError(
            "youtube_live.py doit etre a cote de stream_key.py (partage du stockage)."
        ) from _e

router = APIRouter(tags=["youtube_live"])

# ── Configuration OAuth (depuis l'environnement) ──────────────────────────────
CLIENT_ID = os.environ.get("YT_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("YT_CLIENT_SECRET", "").strip()
REDIRECT_URI = os.environ.get(
    "YT_REDIRECT_URI", "https://cineflight.ca/api/youtube/oauth/callback"
).strip()

SCOPE = "https://www.googleapis.com/auth/youtube"
_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
_TOKEN_URL = "https://oauth2.googleapis.com/token"
_API = "https://www.googleapis.com/youtube/v3"

# Le refresh_token (durable) est range dans un fichier local a cote du module.
_FICHIER_TOKEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "youtube_oauth.json")
_verrou = threading.Lock()


# ── Stockage du refresh_token ────────────────────────────────────────────────
def _charger_token():
    if not os.path.exists(_FICHIER_TOKEN):
        return {}
    try:
        with open(_FICHIER_TOKEN, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _sauver_token(data):
    tmp = _FICHIER_TOKEN + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, _FICHIER_TOKEN)
    try:
        os.chmod(_FICHIER_TOKEN, 0o600)  # secret : lecture proprietaire seul
    except OSError:
        pass


def _refresh_token_present() -> bool:
    with _verrou:
        return bool(_charger_token().get("refresh_token"))


def _obtenir_access_token() -> str:
    """Echange le refresh_token contre un access_token frais (valable ~1 h)."""
    with _verrou:
        data = _charger_token()
    refresh = data.get("refresh_token")
    if not refresh:
        raise HTTPException(
            status_code=503,
            detail="YouTube non autorise. Ouvre /api/youtube/oauth/start une fois.",
        )
    r = requests.post(_TOKEN_URL, data={
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "refresh_token": refresh,
        "grant_type": "refresh_token",
    }, timeout=20)
    if r.status_code != 200:
        raise HTTPException(status_code=502, detail="Echec du renouvellement du token YouTube.")
    return r.json().get("access_token", "")


# ── OAuth : autorisation UNE fois via navigateur ─────────────────────────────
@router.get("/api/youtube/oauth/start")
def oauth_start():
    if not CLIENT_ID or not CLIENT_SECRET:
        raise HTTPException(status_code=500, detail="YT_CLIENT_ID / YT_CLIENT_SECRET manquants.")
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",     # necessaire pour recevoir un refresh_token
        "prompt": "consent",          # force l'emission d'un refresh_token
    }
    return RedirectResponse(_AUTH_URL + "?" + urllib.parse.urlencode(params))


@router.get("/api/youtube/oauth/callback", response_class=HTMLResponse)
def oauth_callback(code: str = Query(default=""), error: str = Query(default="")):
    if error:
        return HTMLResponse("<h3>Autorisation refusee : " + error + "</h3>", status_code=400)
    if not code:
        return HTMLResponse("<h3>Code manquant.</h3>", status_code=400)
    r = requests.post(_TOKEN_URL, data={
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "code": code,
        "grant_type": "authorization_code",
        "redirect_uri": REDIRECT_URI,
    }, timeout=20)
    if r.status_code != 200:
        return HTMLResponse("<h3>Echec de l'echange du code (" + str(r.status_code) + ").</h3>",
                            status_code=502)
    tok = r.json()
    refresh = tok.get("refresh_token")
    if not refresh:
        # Deja autorise sans "prompt=consent" -> pas de nouveau refresh_token renvoye.
        return HTMLResponse(
            "<h3>Aucun refresh_token renvoye. Revoque l'acces de l'app dans ton compte "
            "Google (myaccount.google.com/permissions) puis reessaie /oauth/start.</h3>",
            status_code=400,
        )
    with _verrou:
        _sauver_token({"refresh_token": refresh})
    return HTMLResponse(
        "<h2>YouTube autorise pour CineFlight.</h2>"
        "<p>C'est fait, une seule fois. Tu peux fermer cette page. "
        "Les directs se creeront desormais automatiquement.</p>"
    )


# ── Creation d'un direct : appelee par l'app quand on appuie sur "Diffuser" ──
def _yt_post(access_token, chemin, params, body):
    r = requests.post(
        _API + chemin,
        params=params,
        headers={"Authorization": "Bearer " + access_token,
                 "Content-Type": "application/json"},
        data=json.dumps(body),
        timeout=25,
    )
    if r.status_code not in (200, 201):
        # On remonte un message sur (sans secret) ; le detail YouTube aide au debug.
        detail = ""
        try:
            detail = r.json().get("error", {}).get("message", "")
        except Exception:
            detail = r.text[:200]
        raise HTTPException(status_code=502, detail="YouTube: " + (detail or str(r.status_code)))
    return r.json()


@router.post("/api/creer_live")
def creer_live(user: dict = Depends(get_user_courant)):
    """Cree un liveStream + liveBroadcast, les lie, ecrit cle + watch_url sur le compte.
       Renvoie a l'app la cle de diffusion (pour le drone)."""
    if not _refresh_token_present():
        raise HTTPException(
            status_code=503,
            detail="YouTube non autorise cote serveur. Ouvre /api/youtube/oauth/start une fois.",
        )
    access = _obtenir_access_token()
    titre = "CineFlight - " + str(_username_compte(user) or "direct")

    # 1) liveStream : donne la CLE de diffusion (cdn.ingestionInfo.streamName) + URL RTMP.
    stream = _yt_post(access, "/liveStreams",
        params={"part": "snippet,cdn,contentDetails"},
        body={
            "snippet": {"title": titre},
            "cdn": {
                "frameRate": "variable",
                "ingestionType": "rtmp",
                "resolution": "variable",
            },
            "contentDetails": {"isReusable": True},
        })
    ingestion = stream.get("cdn", {}).get("ingestionInfo", {})
    stream_key = ingestion.get("streamName", "")
    ingestion_addr = ingestion.get("ingestionAddress", "")  # ex rtmp://a.rtmp.youtube.com/live2
    stream_id = stream.get("id", "")
    if not stream_key:
        raise HTTPException(status_code=502, detail="YouTube n'a pas renvoye de cle de diffusion.")

    # 2) liveBroadcast : donne l'ID video -> URL de VISIONNAGE publique.
    broadcast = _yt_post(access, "/liveBroadcasts",
        params={"part": "snippet,status,contentDetails"},
        body={
            "snippet": {
                "title": titre,
                # Debut prevu "maintenant" : YouTube l'exige, mais le direct demarre
                # reellement des que le drone pousse le flux.
                "scheduledStartTime": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
            },
            "status": {
                "privacyStatus": "unlisted",   # non liste : accessible par lien, pas dans les recherches
                "selfDeclaredMadeForKids": False,
            },
            "contentDetails": {
                "enableAutoStart": True,       # passe "en direct" tout seul quand le flux arrive
                "enableAutoStop": True,
            },
        })
    video_id = broadcast.get("id", "")
    if not video_id:
        raise HTTPException(status_code=502, detail="YouTube n'a pas renvoye d'ID de diffusion.")

    # 3) Lier le broadcast au stream (sinon le flux du drone n'alimente pas la video).
    _yt_post(access, "/liveBroadcasts/bind",
        params={"part": "id,contentDetails", "id": video_id, "streamId": stream_id},
        body={})

    watch_url = "https://www.youtube.com/watch?v=" + video_id
    serveur_rtmp = ingestion_addr or "rtmp://a.rtmp.youtube.com/live2"

    # 4) Ecrire sur le compte (meme stockage que stream_key.py) : la page /live/{user}
    #    s'affichera automatiquement ; l'app recuperera aussi ces valeurs via GET /api/stream_key.
    _ecrire_compte(user, {
        "stream_key": stream_key,
        "watch_url": watch_url,
        "server_url": serveur_rtmp,
    })

    # 5) Renvoyer a l'app ce qu'il faut pour configurer le drone.
    return {
        "ok": True,
        "stream_key": stream_key,
        "server_url": serveur_rtmp,
        "watch_url": watch_url,
        "live_page": "/live/" + str(_username_compte(user)),
    }
