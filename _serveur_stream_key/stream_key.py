# -*- coding: utf-8 -*-
"""
stream_key.py — Router FastAPI pour CineFlight (serveur cineflight.ca).

Gere, par compte (identifie par le JWT) :
  - stream_key : cle de diffusion YouTube (SECRET, jamais reaffichee)
  - regie_url  : URL complete de la regie / distributeur (destination alternative)
  - watch_url  : lien de visionnage PUBLIC (ce que les amis ouvrent pour regarder)

Routes AUTHENTIFIEES (JWT) :
    GET  /api/stream_key -> { stream_key, server_url, regie_url, watch_url, live_page }
                            (404 si rien enregistre)
    POST /api/stream_key -> body { stream_key?, regie_url?, watch_url? } -> { ok: true }

Route PUBLIQUE (aucune auth) :
    GET  /live/{utilisateur} -> page HTML avec le direct YouTube integre du compte.
         ISOLE PAR COMPTE : chaque utilisateur a SON lien stable
         (ex. cineflight.ca/live/christian). Aucun melange entre comptes,
         meme si 8 personnes diffusent en meme temps.
         Affiche "aucune diffusion" si le compte n'a pas de watch_url.

SECURITE :
  - stream_key est un secret : jamais journalise, renvoye au seul proprietaire (JWT).
  - watch_url est PUBLIC par nature (fait pour etre partage).
  - {utilisateur} est valide par une liste blanche de caracteres (anti-injection).

INSTALLATION : deposer dans cineflight_web/ (a cote de cine_auth.py) puis dans app.py :
    from stream_key import router as stream_key_router
    app.include_router(stream_key_router)
"""

import os
import re
import json
import html as _html
import threading
import time as _time
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from fastapi.responses import HTMLResponse, Response
from pydantic import BaseModel

try:
    from cine_auth import get_user_courant
except Exception:
    try:
        from cineflight_web.cine_auth import get_user_courant
    except Exception as _e:
        raise ImportError(
            "Impossible d'importer get_user_courant depuis cine_auth."
        ) from _e

SERVER_URL_DEFAUT = "rtmps://a.rtmps.youtube.com/live2"

router = APIRouter(tags=["stream_key"])

_FICHIER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stream_keys.json")
_DOSSIER_IMG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "live_images")
os.makedirs(_DOSSIER_IMG, exist_ok=True)
_MAX_IMG = 5 * 1024 * 1024   # 5 Mo max par image
_verrou = threading.Lock()

# Cle d'index secondaire : on stocke aussi le username pour retrouver un compte
# par son nom dans l'URL publique /live/{utilisateur}.
_RE_USER = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")


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
    if isinstance(user, dict):
        return str(user.get("id") or user.get("username"))
    return str(user)


def _username(user) -> str:
    if isinstance(user, dict):
        return str(user.get("username") or user.get("id") or "")
    return str(user)


def _lire_par_uid(uid):
    with _verrou:
        v = _charger().get(str(uid))
        if isinstance(v, str):      # retrocompat : ancienne forme = cle YouTube nue
            return {"stream_key": v}
        return v or {}


def _lire(user):
    return _lire_par_uid(_uid(user))


def _ecrire(user, champs):
    with _verrou:
        data = _charger()
        cur = data.get(_uid(user))
        if isinstance(cur, str):
            cur = {"stream_key": cur}
        if not isinstance(cur, dict):
            cur = {}
        cur.update(champs)
        # On memorise le username pour la recherche par /live/{utilisateur}.
        cur["username"] = _username(user)
        data[_uid(user)] = cur
        _sauver(data)


def _watch_pour_utilisateur(nom):
    """Cherche le watch_url d'un compte par son username (insensible a la casse)."""
    nom_l = nom.strip().lower()
    with _verrou:
        for v in _charger().values():
            if isinstance(v, dict) and str(v.get("username", "")).lower() == nom_l:
                return v.get("watch_url") or None
    return None


def extraire_id_youtube(url: str):
    """Extrait l'ID video (11 car.) depuis n'importe quelle forme de lien YouTube."""
    if not url:
        return None
    url = url.strip()
    motifs = [
        r"(?:youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})",
        r"(?:youtu\.be/)([A-Za-z0-9_-]{11})",
        r"(?:youtube\.com/live/)([A-Za-z0-9_-]{11})",
        r"(?:youtube\.com/embed/)([A-Za-z0-9_-]{11})",
        r"(?:youtube\.com/shorts/)([A-Za-z0-9_-]{11})",
    ]
    for m in motifs:
        r = re.search(m, url)
        if r:
            return r.group(1)
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", url):
        return url
    return None


class Entree(BaseModel):
    stream_key: str | None = None
    regie_url: str | None = None
    watch_url: str | None = None
    live_time: str | None = None    # heure du direct (texte libre, ex. 2026-07-25 19:00)
    live_title: str | None = None   # titre du live (optionnel)
    live_desc: str | None = None    # description du live (optionnel)


@router.get("/api/stream_key")
def get_stream_key(user: dict = Depends(get_user_courant)):
    d = _lire(user)
    if not any(d.get(k) for k in ("stream_key", "regie_url", "watch_url")):
        raise HTTPException(status_code=404, detail="aucune donnee")
    return {
        "stream_key": d.get("stream_key", ""),
        "server_url": SERVER_URL_DEFAUT,
        "regie_url": d.get("regie_url", ""),
        "watch_url": d.get("watch_url", ""),
        "live_time": d.get("live_time", ""),
        "live_title": d.get("live_title", ""),
        "live_desc": d.get("live_desc", ""),
        # Lien public stable a partager (isole par compte).
        "live_page": "/live/" + _username(user),
    }


@router.post("/api/stream_key")
def post_stream_key(entree: Entree, user: dict = Depends(get_user_courant)):
    champs = {}
    if entree.stream_key is not None:
        champs["stream_key"] = entree.stream_key.strip()
    if entree.regie_url is not None:
        champs["regie_url"] = entree.regie_url.strip()
    if entree.watch_url is not None:
        champs["watch_url"] = entree.watch_url.strip()
    if entree.live_time is not None:
        champs["live_time"] = entree.live_time.strip()
    if entree.live_title is not None:
        champs["live_title"] = entree.live_title.strip()[:120]
    if entree.live_desc is not None:
        champs["live_desc"] = entree.live_desc.strip()[:500]
    if not champs:
        raise HTTPException(status_code=400, detail="rien a enregistrer")
    _ecrire(user, champs)
    return {"ok": True, "live_page": "/live/" + _username(user)}


# ── Image d'attente (banniere) : upload authentifie + service public ──────────────
def _chemin_img(uid) -> str:
    return os.path.join(_DOSSIER_IMG, str(uid) + ".jpg")


@router.post("/api/live_image")
async def upload_live_image(fichier: UploadFile = File(...), user: dict = Depends(get_user_courant)):
    # Accepte JPEG ou PNG ; on stocke tel quel (extension .jpg par simplicite d'affichage).
    ct = (fichier.content_type or "").lower()
    if ct not in ("image/jpeg", "image/jpg", "image/png"):
        raise HTTPException(status_code=400, detail="format non supporte (JPEG ou PNG)")
    data = await fichier.read()
    if not data:
        raise HTTPException(status_code=400, detail="fichier vide")
    if len(data) > _MAX_IMG:
        raise HTTPException(status_code=400, detail="image trop lourde (max 5 Mo)")
    with open(_chemin_img(_uid(user)), "wb") as f:
        f.write(data)
    _ecrire(user, {"has_image": True})
    return {"ok": True}


@router.delete("/api/live_image")
def supprimer_live_image(user: dict = Depends(get_user_courant)):
    p = _chemin_img(_uid(user))
    if os.path.exists(p):
        os.remove(p)
    _ecrire(user, {"has_image": False})
    return {"ok": True}


@router.get("/live_image/{utilisateur}")
def get_live_image(utilisateur: str):
    if not _RE_USER.match(utilisateur or ""):
        raise HTTPException(status_code=404, detail="introuvable")
    infos = _infos_utilisateur(utilisateur)
    uid = None
    # Retrouver l'uid a partir du username pour localiser le fichier.
    with _verrou:
        for k, v in _charger().items():
            if isinstance(v, dict) and str(v.get("username", "")).lower() == utilisateur.strip().lower():
                uid = k
                break
    if uid is None:
        raise HTTPException(status_code=404, detail="introuvable")
    p = _chemin_img(uid)
    if not os.path.exists(p):
        raise HTTPException(status_code=404, detail="pas d'image")
    with open(p, "rb") as f:
        data = f.read()
    return Response(content=data, media_type="image/jpeg",
                    headers={"Cache-Control": "no-cache"})


# ── PAGE PUBLIQUE /live/{utilisateur} : direct YouTube integre, isole par compte ──
_PAGE_LIVE = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CineFlight — En direct</title>
<style>
  :root{ --bg:#0a1929; --panel:#0F2A4D; --bord:#1c3a5e; --txt:#eaf2fb; --sous:#9fb3c8; --accent:#4FC3F7; }
  *{ box-sizing:border-box; }
  body{ margin:0; background:var(--bg); color:var(--txt);
        font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
        min-height:100vh; display:flex; flex-direction:column; align-items:center; }
  header{ width:100%; text-align:center; padding:18px 12px 10px; }
  header .titre{ font-size:20px; font-weight:700; letter-spacing:.5px; }
  header .titre .cine{ color:var(--accent); }
  header .qui{ margin-top:4px; font-size:13px; color:var(--sous); }
  .direct{ display:inline-flex; align-items:center; gap:7px; margin-top:8px;
           font-size:12px; color:#ff5a5a; font-weight:700; text-transform:uppercase; letter-spacing:.08em; }
  .direct .pt{ width:9px; height:9px; border-radius:50%; background:#ff3b3b; animation:clign 1.2s infinite; }
  @keyframes clign{ 0%,100%{opacity:1} 50%{opacity:.25} }
  .cadre{ width:100%; max-width:960px; padding:0 12px; }
  .ratio{ position:relative; width:100%; padding-top:56.25%; background:#000;
          border-radius:12px; overflow:hidden; border:1px solid var(--bord); }
  .ratio iframe{ position:absolute; inset:0; width:100%; height:100%; border:0; }
  /* Ecran d'attente : degrade bleu CineFlight (plus d'ecran noir avant le live). */
  .vide{ position:absolute; inset:0; display:flex; flex-direction:column;
         align-items:center; justify-content:center; text-align:center; padding:24px;
         background:radial-gradient(circle at 50% 35%, #14487f 0%, #0d2f57 45%, #081b33 100%); }
  .vide .logo{ font-size:24px; font-weight:800; letter-spacing:.5px; margin-bottom:6px; }
  .vide .logo .cine{ color:var(--accent); }
  /* Drone SVG qui vole partout sur la page (positionne par JS, au-dessus du fond). */
  #drone{ position:fixed; left:0; top:0; width:88px; height:auto; z-index:5; pointer-events:none;
          filter:drop-shadow(0 10px 16px rgba(0,0,0,.5)); will-change:transform; }
  #drone .helice{ transform-origin:center; animation:tourner .12s linear infinite; }
  @keyframes tourner{ from{ transform:rotate(0deg);} to{ transform:rotate(360deg);} }
  /* Selecteur de langue FR / EN. */
  #lang{ position:fixed; top:12px; right:14px; z-index:9; display:flex; gap:0;
         border:1px solid var(--bord); border-radius:8px; overflow:hidden; background:rgba(15,42,77,.6); }
  #lang button{ padding:6px 12px; border:0; background:transparent; color:var(--sous);
                font-size:12px; font-weight:700; cursor:pointer; }
  #lang button.on{ background:#1565C0; color:#fff; }
  .vide .att{ display:inline-flex; align-items:center; gap:9px; font-size:14px; font-weight:600;
              color:#cfe0f2; margin-bottom:6px; }
  .vide .att .pt{ width:10px; height:10px; border-radius:50%; background:#4FC3F7; animation:clign 1.2s infinite; }
  .vide h2{ margin:0 0 10px; font-size:18px; }
  .banniere{ max-width:min(560px,86%); max-height:38vh; width:auto; border-radius:12px;
             margin:4px 0 14px; border:1px solid rgba(79,195,247,.35);
             box-shadow:0 10px 30px rgba(0,0,0,.5); object-fit:contain; }
  .ltitre{ font-size:22px; font-weight:800; color:#fff; margin:2px 0 8px; max-width:560px; }
  .ldesc{ font-size:14px; color:#cfe0f2; margin:2px 0 10px; max-width:520px; line-height:1.45; white-space:pre-wrap; }
  .vide .heure{ margin:2px 0 6px; font-size:14px; color:#8fd0ff; font-weight:600; }
  /* Compte a rebours. */
  .rebours{ display:flex; gap:10px; margin:6px 0 12px; }
  .rebours .bloc{ min-width:62px; padding:10px 6px; background:rgba(79,195,247,.10);
                  border:1px solid rgba(79,195,247,.30); border-radius:10px; }
  .rebours .num{ font-size:26px; font-weight:800; color:#fff; font-variant-numeric:tabular-nums; }
  .rebours .lab{ font-size:10px; text-transform:uppercase; letter-spacing:.08em; color:#9fc7e6; margin-top:2px; }
  .vide p{ margin:0; color:var(--sous); font-size:14px; max-width:420px; }
  .actions{ margin:16px 0 30px; text-align:center; }
  .btn{ display:inline-block; padding:11px 20px; border-radius:9px; text-decoration:none;
        background:#1565C0; color:#fff; font-size:14px; font-weight:600; border:0; cursor:pointer; }
  .btn.sec{ background:transparent; color:var(--accent); border:1px solid var(--bord); margin-left:8px; }
  footer{ margin-top:auto; padding:14px; color:#5c7290; font-size:12px; }
</style>
%AUTO_REFRESH%
</head>
<body>
  <div id="lang"><button data-l="fr">FR</button><button data-l="en">EN</button></div>
  <header>
    <div class="titre"><span class="cine">Cine</span>Flight</div>
    <div class="qui">%QUI%</div>
    %BANDEAU_DIRECT%
  </header>
  <div class="cadre">
    <div class="ratio">
      %CONTENU%
    </div>
  </div>
  <div class="actions">%ACTIONS%</div>
  <footer><span data-fr="Diffusion propulsee par CineFlight" data-en="Streaming powered by CineFlight">Diffusion propulsee par CineFlight</span></footer>
  <script>
  (function(){
    function applique(l){
      document.documentElement.lang=l;
      document.querySelectorAll('[data-fr]').forEach(function(e){
        var t=e.getAttribute('data-'+l); if(t!==null) e.textContent=t;
      });
      document.querySelectorAll('#lang button').forEach(function(b){
        b.classList.toggle('on', b.getAttribute('data-l')===l);
      });
      try{ localStorage.setItem('cf_lang', l); }catch(e){}
    }
    var pref=null; try{ pref=localStorage.getItem('cf_lang'); }catch(e){}
    var nav=(navigator.language||'fr').toLowerCase();
    var lang=pref || (nav.indexOf('en')===0 ? 'en' : 'fr');
    document.querySelectorAll('#lang button').forEach(function(b){
      b.onclick=function(){ applique(b.getAttribute('data-l')); };
    });
    applique(lang);
  })();
  </script>
</body>
</html>"""


def _infos_utilisateur(nom):
    """Renvoie tout le dict d'un compte par username (insensible a la casse)."""
    nom_l = nom.strip().lower()
    with _verrou:
        for v in _charger().values():
            if isinstance(v, dict) and str(v.get("username", "")).lower() == nom_l:
                return v
    return {}


@router.get("/live/{utilisateur}", response_class=HTMLResponse)
def page_live(utilisateur: str):
    # Anti-injection : n'accepte qu'un nom d'utilisateur au format attendu.
    if not _RE_USER.match(utilisateur or ""):
        raise HTTPException(status_code=404, detail="introuvable")

    infos = _infos_utilisateur(utilisateur)
    watch = infos.get("watch_url") or None
    heure = (infos.get("live_time") or "").strip()
    titre = (infos.get("live_title") or "").strip()
    desc = (infos.get("live_desc") or "").strip()
    a_image = bool(infos.get("has_image"))
    vid = extraire_id_youtube(watch) if watch else None
    u = _html.escape(utilisateur)
    qui = ('<span data-fr="Diffusion de ' + u + '" data-en="Broadcast by ' + u + '">'
           'Diffusion de ' + u + '</span>')

    if vid:
        contenu = (
            '<iframe id="lecteur" src="https://www.youtube.com/embed/' + vid +
            '?autoplay=1&rel=0" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" '
            'allowfullscreen></iframe>'
        )
        bandeau = ('<div class="direct"><span class="pt"></span> '
                   '<span data-fr="En direct" data-en="Live">En direct</span></div>')
        # Bouton plein ecran (met tout le cadre video en plein ecran) + ouvrir sur YouTube.
        actions = (
            '<button class="btn" onclick="cfPleinEcran()" '
            'data-fr="Plein ecran" data-en="Fullscreen">Plein ecran</button>'
            '<a class="btn sec" href="https://www.youtube.com/watch?v=' + vid +
            '" target="_blank" rel="noopener" '
            'data-fr="Ouvrir sur YouTube" data-en="Open on YouTube">Ouvrir sur YouTube</a>'
            "<script>function cfPleinEcran(){var c=document.querySelector('.ratio')||document.getElementById('lecteur');"
            "if(!c)return; var f=c.requestFullscreen||c.webkitRequestFullscreen||c.msRequestFullscreen;"
            "if(f){f.call(c);} else { var l=document.getElementById('lecteur'); if(l&&l.requestFullscreen) l.requestFullscreen(); }}</script>"
        )
        auto = ""  # live present : pas besoin de rafraichir
    else:
        # Ecran d'attente bleu CineFlight : drone qui plane + compte a rebours vers l'heure.
        heure_js = _html.escape(heure).replace("'", " ")
        he = _html.escape(heure)
        ligne_heure = ('<div class="heure"><span data-fr="Direct prevu : ' + he +
                       '" data-en="Live scheduled : ' + he + '">Direct prevu : ' + he +
                       '</span></div>') if heure else ''
        rebours = (
            '<div class="rebours" id="reb" style="display:none">'
            '<div class="bloc"><div class="num" id="rj">00</div><div class="lab" data-fr="Jours" data-en="Days">Jours</div></div>'
            '<div class="bloc"><div class="num" id="rh">00</div><div class="lab" data-fr="Heures" data-en="Hours">Heures</div></div>'
            '<div class="bloc"><div class="num" id="rm">00</div><div class="lab" data-fr="Min" data-en="Min">Min</div></div>'
            '<div class="bloc"><div class="num" id="rs">00</div><div class="lab" data-fr="Sec" data-en="Sec">Sec</div></div>'
            '</div>'
        )
        # Titre + description du live (optionnels). Echappes (securite : contenu affiche public).
        bloc_titre = ('<div class="ltitre">' + _html.escape(titre) + '</div>') if titre else ''
        bloc_desc = ('<div class="ldesc">' + _html.escape(desc) + '</div>') if desc else ''
        # Banniere image (optionnelle) : servie par /live_image/{user}. ?t= casse le cache (par minute).
        bloc_img = ('<img class="banniere" src="/live_image/' + u + '?t=' + str(int(_time.time() // 60)) +
                    '" alt="">') if a_image else ''
        drone_svg = (
            '<svg id="drone" viewBox="0 0 120 90" xmlns="http://www.w3.org/2000/svg">'
            '<g stroke="#4FC3F7" stroke-width="3" fill="none">'
            '<line x1="60" y1="52" x2="24" y2="26"/><line x1="60" y1="52" x2="96" y2="26"/>'
            '<line x1="60" y1="52" x2="24" y2="78"/><line x1="60" y1="52" x2="96" y2="78"/>'
            '</g>'
            '<g fill="#0d2f57" stroke="#4FC3F7" stroke-width="2">'
            '<circle class="helice" cx="24" cy="26" r="13"/><circle class="helice" cx="96" cy="26" r="13"/>'
            '<circle class="helice" cx="24" cy="78" r="13"/><circle class="helice" cx="96" cy="78" r="13"/>'
            '</g>'
            '<g class="helice" stroke="#8fd0ff" stroke-width="2"><line x1="16" y1="26" x2="32" y2="26"/><line x1="24" y1="18" x2="24" y2="34"/></g>'
            '<g class="helice" stroke="#8fd0ff" stroke-width="2"><line x1="88" y1="26" x2="104" y2="26"/><line x1="96" y1="18" x2="96" y2="34"/></g>'
            '<g class="helice" stroke="#8fd0ff" stroke-width="2"><line x1="16" y1="78" x2="32" y2="78"/><line x1="24" y1="70" x2="24" y2="86"/></g>'
            '<g class="helice" stroke="#8fd0ff" stroke-width="2"><line x1="88" y1="78" x2="104" y2="78"/><line x1="96" y1="70" x2="96" y2="86"/></g>'
            '<rect x="44" y="44" width="32" height="18" rx="6" fill="#123a63" stroke="#4FC3F7" stroke-width="2.5"/>'
            '<circle cx="60" cy="53" r="4" fill="#8fd0ff"/>'
            '</svg>'
        )
        contenu = (
            '<div class="vide">'
            '<div class="logo"><span class="cine">Cine</span>Flight</div>'
            '<div class="att"><span class="pt"></span> '
            '<span data-fr="En attente du direct" data-en="Waiting for the live">En attente du direct</span></div>'
            '<h2 data-fr="Le direct va bientot commencer" data-en="The live stream will start soon">Le direct va bientot commencer</h2>'
            + bloc_img + bloc_titre + ligne_heure + rebours + bloc_desc +
            '<p data-fr="La video apparaitra ici automatiquement des que la diffusion commence. Cette page se met a jour toute seule, reste ouverte." '
            'data-en="The video will appear here automatically as soon as the live starts. This page refreshes on its own, keep it open.">'
            'La video apparaitra ici automatiquement des que la diffusion commence. Cette page se met a jour toute seule, reste ouverte.</p>'
            '</div>'
            + drone_svg +
            # JS 1 : vol fluide du drone sur toute la page, avec sorties d'ecran et retour.
            "<script>(function(){var el=document.getElementById('drone'); if(!el) return;"
            "var W=innerWidth,H=innerHeight; addEventListener('resize',function(){W=innerWidth;H=innerHeight;});"
            "var x=W*0.5,y=H*0.4,ang=Math.random()*6.28,sp=1.6,tang=ang,tsp=sp,last=0,chg=0;"
            "function step(ts){ if(!last)last=ts; var dt=Math.min(40,ts-last); last=ts;"
            "if(ts>chg){ chg=ts+1500+Math.random()*2500; tang=Math.random()*6.28; tsp=1.1+Math.random()*1.8; }"
            "var da=tang-ang; while(da>Math.PI)da-=6.28; while(da<-Math.PI)da+=6.28;"
            "ang+=da*0.02; sp+=(tsp-sp)*0.02;"
            "x+=Math.cos(ang)*sp*dt*0.06; y+=Math.sin(ang)*sp*dt*0.06;"
            "var M=110;"  # marge : le drone peut sortir puis revient de l'autre cote
            "if(x<-M)x=W+M; if(x>W+M)x=-M; if(y<-M)y=H+M; if(y>H+M)y=-M;"
            "var tilt=Math.cos(ang)*12;"  # petite inclinaison selon la direction
            "el.style.transform='translate('+x+'px,'+y+'px) rotate('+tilt+'deg)';"
            "requestAnimationFrame(step);} requestAnimationFrame(step);})();</script>"
            # JS 2 : compte a rebours vers live_time (formats "2026-07-25 19:00" ou avec T).
            "<script>(function(){var t='" + heure_js + "';"
            "if(!t){return;} var q=new Date(t.replace(' ','T'));"
            "if(isNaN(q.getTime())){return;}"
            "var reb=document.getElementById('reb'); if(reb) reb.style.display='flex';"
            "function p(n){return (n<10?'0':'')+n;}"
            "function tick(){var d=q-new Date(); if(d<=0){location.reload(); return;}"
            "var s=Math.floor(d/1000); var j=Math.floor(s/86400); s-=j*86400;"
            "var h=Math.floor(s/3600); s-=h*3600; var m=Math.floor(s/60); s-=m*60;"
            "document.getElementById('rj').textContent=p(j);"
            "document.getElementById('rh').textContent=p(h);"
            "document.getElementById('rm').textContent=p(m);"
            "document.getElementById('rs').textContent=p(s);}"
            "tick(); setInterval(tick,1000);})();</script>"
        )
        bandeau = ''
        actions = ''
        # Pas encore de live : la page se recharge toutes les 30 s pour capter le debut.
        auto = '<meta http-equiv="refresh" content="30">'

    html = (_PAGE_LIVE
            .replace("%AUTO_REFRESH%", auto)
            .replace("%QUI%", qui)
            .replace("%BANDEAU_DIRECT%", bandeau)
            .replace("%CONTENU%", contenu)
            .replace("%ACTIONS%", actions))
    return HTMLResponse(content=html)
