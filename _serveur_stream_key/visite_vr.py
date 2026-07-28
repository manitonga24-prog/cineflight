# -*- coding: utf-8 -*-
"""
visite_vr.py — VISITE VIRTUELLE aérienne : plusieurs panoramas 360 reliés (2026-07-26).

Le spectateur se TÉLÉPORTE d'un point de vue à l'autre — principe d'une visite
immobilière, en aérien. Un seul lien fonctionne partout :
  - casque (Quest) : immersion WebXR, on regarde un point au sol et on le fixe pour y aller ;
  - téléphone : gyroscope + boutons ;
  - ordinateur : souris + boutons.

STOCKAGE : une visite = un JSON {titre, points:[{job_id, nom}]} dans visites.json.
Les IMAGES ne sont pas recopiées : chaque point pointe vers /api/panorama/{job_id}/image,
déjà servi par l'assembleur. Rien à dupliquer, rien à purger en double.

Routes :
    POST /api/visite            -> crée une visite {titre, points:[{job_id,nom}]} -> {visite_id}
    GET  /visite/{visite_id}    -> page de visualisation (VR / mobile / PC)
    GET  /api/visite/{id}       -> JSON de la visite (diagnostic)

INSTALLATION : déposer dans le dossier de l'app web puis, dans app.py :
    from visite_vr import router as visite_vr_router
    app.include_router(visite_vr_router)
"""

import json
import os
import threading
import uuid

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter()

_FICHIER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "visites.json")
_lock = threading.Lock()

AFRAME = "https://aframe.io/releases/1.5.0/aframe.min.js"


def _lire():
    try:
        with open(_FICHIER, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _ecrire(d):
    tmp = _FICHIER + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False)
    os.replace(tmp, _FICHIER)      # écriture atomique : jamais de fichier à moitié écrit


@router.post("/api/visite")
async def creer_visite(request: Request):
    try:
        b = await request.json()
    except Exception:
        raise HTTPException(400, "corps JSON illisible")
    points = b.get("points") or []
    if not isinstance(points, list) or len(points) < 2:
        raise HTTPException(422, "une visite demande au moins 2 points")
    propres = []
    for i, p in enumerate(points):
        jid = (p or {}).get("job_id")
        if not jid:
            raise HTTPException(422, "point %d : job_id manquant" % (i + 1))
        propres.append({"job_id": str(jid), "nom": str((p or {}).get("nom") or "Point %d" % (i + 1))})
    vid = uuid.uuid4().hex[:12]
    with _lock:
        d = _lire()
        d[vid] = {"titre": str(b.get("titre") or "Visite CineFlight"), "points": propres}
        _ecrire(d)
    print("[VISITE] creee %s : %d points" % (vid, len(propres)), flush=True)
    return {"visite_id": vid, "viewer_url": "/visite/%s" % vid, "points": len(propres)}


@router.get("/api/visite/{visite_id}")
def lire_visite(visite_id: str):
    with _lock:
        v = _lire().get(visite_id)
    if not v:
        raise HTTPException(404, "visite inconnue")
    return JSONResponse(v)


@router.get("/visite/{visite_id}", response_class=HTMLResponse)
def page_visite(visite_id: str):
    with _lock:
        v = _lire().get(visite_id)
    if not v:
        return HTMLResponse("<h2 style='font-family:sans-serif;padding:24px'>"
                            "Visite introuvable — le lien a peut-être expiré.</h2>", status_code=404)
    pts = json.dumps(v["points"], ensure_ascii=False)
    titre = v.get("titre", "Visite CineFlight")
    return HTMLResponse(f"""<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>{titre} — CineFlight</title>
<script src="{AFRAME}"></script>
<style>
  html,body{{margin:0;background:#000;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,
    "Segoe UI",Roboto,sans-serif}}
  #hud{{position:fixed;left:0;right:0;bottom:16px;text-align:center;z-index:6}}
  #hud button{{background:#0b6b5f;color:#fff;border:0;border-radius:22px;padding:12px 18px;
    margin:4px;font-size:15px;font-weight:600;box-shadow:0 3px 10px rgba(0,0,0,.5)}}
  #hud button.actif{{background:#00c853;color:#062}}
  #titre{{position:fixed;top:14px;left:0;right:0;text-align:center;color:#fff;font-size:17px;
    font-weight:600;text-shadow:0 2px 6px #000;z-index:6}}
  #chargement{{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;
    color:#fff;background:#0b0f14;z-index:10;font-size:18px}}
</style>
</head>
<body>
<div id="chargement">Chargement de la visite…</div>
<div id="titre">{titre}</div>

<a-scene id="scene" loading-screen="enabled:false" vr-mode-ui="enabled:true"
         renderer="colorManagement:true; antialias:true" style="display:none">
  <a-sky id="ciel" rotation="0 -90 0"></a-sky>
  <a-entity camera look-controls="magicWindowTrackingEnabled:true" position="0 1.6 0">
    <!-- Curseur de VISÉE : en casque, on regarde un repère et on le fixe pour s'y rendre. -->
    <a-entity cursor="fuse:true; fuseTimeout:1200" position="0 0 -1"
              geometry="primitive:ring; radiusInner:0.012; radiusOuter:0.018"
              material="color:#00E676; shader:flat"></a-entity>
  </a-entity>
  <a-entity id="reperes"></a-entity>
</a-scene>

<div id="hud"></div>
<script>
  var POINTS = {pts};
  var idx = 0;
  var ciel = document.getElementById('ciel');
  var hud  = document.getElementById('hud');
  var scene = document.getElementById('scene');

  function urlImage(p) {{ return '/api/panorama/' + p.job_id + '/image'; }}

  function aller(i) {{
    if (i < 0 || i >= POINTS.length) return;
    idx = i;
    ciel.setAttribute('src', urlImage(POINTS[i]));
    majHud();
  }}

  function majHud() {{
    hud.innerHTML = '';
    POINTS.forEach(function (p, i) {{
      var b = document.createElement('button');
      b.textContent = p.nom;
      if (i === idx) b.className = 'actif';
      b.onclick = function () {{ aller(i); }};
      hud.appendChild(b);
    }});
  }}

  // Repères FLOTTANTS dans la scène : en casque, on ne peut pas cliquer un bouton HTML,
  // donc chaque autre point de vue est aussi une cible que l'on fixe du regard.
  function construireReperes() {{
    var conteneur = document.getElementById('reperes');
    conteneur.innerHTML = '';
    var n = POINTS.length;
    POINTS.forEach(function (p, i) {{
      if (i === idx) return;
      var angle = (i / n) * Math.PI * 2;
      var x = Math.sin(angle) * 6, z = -Math.cos(angle) * 6;
      var e = document.createElement('a-entity');
      e.setAttribute('position', x + ' -1.2 ' + z);
      e.setAttribute('geometry', 'primitive:circle; radius:0.7');
      e.setAttribute('material', 'color:#00E676; opacity:0.75; side:double; shader:flat');
      e.setAttribute('rotation', '-90 0 0');
      e.addEventListener('click', function () {{ aller(i); construireReperes(); }});
      var t = document.createElement('a-text');
      t.setAttribute('value', p.nom);
      t.setAttribute('align', 'center');
      t.setAttribute('color', '#FFFFFF');
      t.setAttribute('position', x + ' -0.6 ' + z);
      t.setAttribute('look-at', '[camera]');
      t.setAttribute('scale', '2 2 2');
      conteneur.appendChild(e); conteneur.appendChild(t);
    }});
  }}

  ciel.addEventListener('materialtextureloaded', function () {{
    document.getElementById('chargement').style.display = 'none';
    scene.style.display = 'block';
    construireReperes();
  }});

  aller(0);
</script>
</body>
</html>""")
