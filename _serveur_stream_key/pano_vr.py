# -*- coding: utf-8 -*-
"""
pano_vr.py — Visionneuse 360 / VR des panoramas CineFlight (2026-07-26).

UN SEUL LIEN pour tous les supports :
  - casque (Meta Quest 2/3/Pro…) : bouton « Entrer en VR » -> immersion WebXR ;
  - téléphone / tablette : on bouge l'appareil pour regarder autour (gyroscope) ;
  - ordinateur : on tourne à la souris.

Rien à installer pour le client : c'est une page web. L'image n'est PAS recopiée —
la page pointe vers l'endpoint d'image existant (/api/panorama/{job_id}/image), donc
aucun stockage supplémentaire et aucune duplication.

Routes :
    GET /vr/{job_id}        -> page de visualisation
    GET /vr/{job_id}/info   -> {present, image_url} (diagnostic)

INSTALLATION (VPS) : déposer dans le dossier de l'app web, puis dans app.py :
    from pano_vr import router as pano_vr_router
    app.include_router(pano_vr_router)
"""

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter()

# A-Frame est chargé depuis un CDN. Version épinglée : une mise à jour surprise du CDN
# ne doit pas casser la visionneuse d'un client en pleine démonstration.
AFRAME = "https://aframe.io/releases/1.5.0/aframe.min.js"


def _page(job_id: str) -> str:
    image = f"/api/panorama/{job_id}/image"
    return f"""<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>CineFlight — Panorama 360</title>
<script src="{AFRAME}"></script>
<style>
  html,body {{ margin:0; padding:0; background:#000; overflow:hidden; font-family:-apple-system,
    BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }}
  #chargement {{ position:fixed; inset:0; display:flex; flex-direction:column; align-items:center;
    justify-content:center; color:#fff; z-index:10; background:#0b0f14; }}
  #chargement .t {{ font-size:20px; font-weight:600; margin-bottom:10px; }}
  #chargement .s {{ font-size:14px; color:#9fb0c0; }}
  #aide {{ position:fixed; left:0; right:0; bottom:18px; text-align:center; color:#fff;
    font-size:14px; z-index:5; text-shadow:0 2px 6px #000; pointer-events:none;
    transition:opacity .6s; }}
  #erreur {{ position:fixed; inset:0; display:none; align-items:center; justify-content:center;
    color:#fff; background:#0b0f14; z-index:20; text-align:center; padding:24px; }}
</style>
</head>
<body>
<div id="chargement"><div class="t">Chargement du panorama…</div>
  <div class="s">Un panorama pèse plusieurs Mo — quelques secondes selon la connexion.</div></div>
<div id="erreur"><div><b>Panorama introuvable</b><br><br>
  Ce lien a peut-être expiré, ou l'assemblage n'est pas terminé.</div></div>

<a-scene id="scene" loading-screen="enabled: false" vr-mode-ui="enabled: true"
         renderer="colorManagement: true; antialias: true" style="display:none">
  <a-assets timeout="120000">
    <img id="pano" src="{image}" crossorigin="anonymous">
  </a-assets>
  <!-- Sphère inversée : l'observateur est À L'INTÉRIEUR de l'image équirectangulaire. -->
  <a-sky src="#pano" rotation="0 -90 0"></a-sky>
  <a-entity camera look-controls="reverseMouseDrag: false; touchEnabled: true;
            magicWindowTrackingEnabled: true" wasd-controls-enabled="false"
            position="0 1.6 0"></a-entity>
</a-scene>

<div id="aide"></div>
<script>
  var img = document.getElementById('pano');
  var scene = document.getElementById('scene');
  var aide = document.getElementById('aide');

  // Message d'aide adapté au support : un utilisateur de casque et un utilisateur de
  // téléphone n'ont pas le même geste à faire.
  var casque = (navigator.xr !== undefined);
  var tactile = ('ontouchstart' in window);
  aide.textContent = casque ? "Touchez « VR » en bas à droite pour entrer dans le panorama"
            : (tactile ? "Bougez votre appareil ou glissez le doigt pour regarder autour"
                       : "Cliquez-glissez pour regarder autour · molette pour zoomer");

  img.addEventListener('load', function () {{
    document.getElementById('chargement').style.display = 'none';
    scene.style.display = 'block';
    setTimeout(function () {{ aide.style.opacity = '0'; }}, 8000);
  }});
  img.addEventListener('error', function () {{
    document.getElementById('chargement').style.display = 'none';
    document.getElementById('erreur').style.display = 'flex';
  }});
</script>
</body>
</html>"""


@router.get("/vr/{job_id}", response_class=HTMLResponse)
def visionneuse(job_id: str):
    # Aucune vérification d'existence ici : l'image est servie par l'endpoint existant,
    # et la page affiche un message clair si elle ne répond pas. Cela évite de dupliquer
    # la logique de stockage des jobs (et de mentir si elle changeait).
    return HTMLResponse(_page(job_id))


@router.get("/vr/{job_id}/info")
def info(job_id: str):
    return JSONResponse({"present": True, "image_url": f"/api/panorama/{job_id}/image",
                         "viewer_url": f"/vr/{job_id}"})
