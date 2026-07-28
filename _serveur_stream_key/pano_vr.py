# -*- coding: utf-8 -*-
"""
pano_vr.py — Visionneuse 360 / VR des panoramas CineFlight (2026-07-26, révisé 2026-07-28).

UN SEUL LIEN pour tous les supports :
  - casque (Meta Quest 2/3/Pro…) : bouton « Entrer en VR » -> immersion WebXR ;
  - téléphone / tablette : on bouge l'appareil pour regarder autour (gyroscope) ;
  - ordinateur : on tourne à la souris.

Rien à installer pour le client : c'est une page web. L'image n'est PAS recopiée —
la page pointe vers l'endpoint d'image existant (/api/panorama/{job_id}/image), donc
aucun stockage supplémentaire et aucune duplication.

⚠ RÉVISION DU 2026-07-28 — ÉTAT D'ATTENTE.
Depuis que l'assemblage est délégué à l'atelier PC, un lien est remis au client AVANT que
l'image existe : le serveur reçoit les photos, l'atelier assemble vingt minutes plus tard.
La page affichait alors « Panorama introuvable — ce lien a peut-être expiré ». C'était
vrai et trompeur : une attente normale prenait l'apparence d'une panne, sur un lien qu'on
venait d'envoyer à un client.
→ La page distingue maintenant TROIS états, en interrogeant `/vr/{id}/info` :
   prêt        : on affiche le panorama ;
   en cours    : on l'annonce, avec le nombre de photos reçues, et on se recharge seul ;
   introuvable : le lien est réellement mauvais.

Routes :
    GET /vr/{job_id}        -> page de visualisation
    GET /vr/{job_id}/info   -> {etat, photos, image_url, viewer_url}

INSTALLATION (VPS) : déposer dans le dossier de l'app web, puis dans app.py :
    from pano_vr import router as pano_vr_router
    app.include_router(pano_vr_router)
"""

import os

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter()

# A-Frame est chargé depuis un CDN. Version épinglée : une mise à jour surprise du CDN
# ne doit pas casser la visionneuse d'un client en pleine démonstration.
AFRAME = "https://aframe.io/releases/1.5.0/aframe.min.js"

# Même racine que le reste de la chaîne. Une seconde façon de la calculer finirait par
# diverger — c'est ce qui a coûté le plus cher dans ce projet.
_RACINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pano_jobs")

# Rechargement de la page d'attente. Assez long pour ne pas marteler le serveur, assez
# court pour qu'un client qui laisse l'onglet ouvert voie le panorama apparaître seul.
RECHARGE_S = 45


def _etat(job_id: str):
    """(etat, nombre_de_photos). Lit le DISQUE, jamais un état en mémoire."""
    sur = os.path.basename(job_id)
    d = os.path.join(_RACINE, sur)
    if os.path.isfile(os.path.join(d, "panorama.jpg")):
        return "pret", 0
    entree = os.path.join(d, "in")
    if os.path.isdir(entree):
        try:
            n = len([f for f in os.listdir(entree)
                     if os.path.isfile(os.path.join(entree, f))])
        except OSError:
            n = 0
        if n > 0:
            return "en_cours", n
    return "introuvable", 0


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
  #panneau {{ position:fixed; inset:0; display:none; flex-direction:column; align-items:center;
    justify-content:center; color:#fff; background:#0b0f14; z-index:20; text-align:center;
    padding:24px; }}
  #panneau .t {{ font-size:22px; font-weight:600; margin-bottom:14px; }}
  #panneau .s {{ font-size:15px; color:#9fb0c0; max-width:520px; line-height:1.5; }}
  #panneau .compte {{ font-size:13px; color:#6b7d8d; margin-top:18px; }}
  .roue {{ width:34px; height:34px; margin-bottom:20px; border:3px solid #24303c;
    border-top-color:#3A9BFF; border-radius:50%; animation:tourne 1s linear infinite; }}
  @keyframes tourne {{ to {{ transform:rotate(360deg); }} }}
</style>
</head>
<body>
<div id="chargement"><div class="t">Chargement du panorama…</div>
  <div class="s">Un panorama pèse plusieurs Mo — quelques secondes selon la connexion.</div></div>

<div id="panneau"><div class="roue" id="roue"></div>
  <div class="t" id="ptitre"></div>
  <div class="s" id="ptexte"></div>
  <div class="compte" id="pcompte"></div></div>

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

  // L'IMAGE MANQUE : on DEMANDE POURQUOI au lieu de supposer. Un assemblage en cours et
  // un lien mort produisent la même erreur de chargement ; seul le serveur sait les
  // distinguer, et le client mérite de savoir laquelle des deux le concerne.
  img.addEventListener('error', function () {{
    document.getElementById('chargement').style.display = 'none';
    var p = document.getElementById('panneau');
    var titre = document.getElementById('ptitre');
    var texte = document.getElementById('ptexte');
    var compte = document.getElementById('pcompte');
    var roue = document.getElementById('roue');
    p.style.display = 'flex';
    titre.textContent = "Un instant…";
    texte.textContent = "";
    fetch('/vr/{job_id}/info').then(function (r) {{ return r.json(); }}).then(function (j) {{
      if (j.etat === 'en_cours') {{
        titre.textContent = "Assemblage en cours";
        texte.textContent = "Les photos sont bien arrivées. Le panorama est en train "
          + "d'être assemblé — comptez une vingtaine de minutes. Cette page se "
          + "rafraîchit toute seule, vous pouvez la laisser ouverte.";
        compte.textContent = j.photos + " photos reçues";
        setTimeout(function () {{ location.reload(); }}, {RECHARGE_S} * 1000);
      }} else if (j.etat === 'pret') {{
        // L'image est là mais n'a pas pu être chargée : réseau, ou cache abîmé.
        titre.textContent = "Chargement interrompu";
        texte.textContent = "Le panorama existe mais n'a pas pu être téléchargé. "
          + "Vérifiez votre connexion et rechargez la page.";
        roue.style.display = 'none';
      }} else {{
        titre.textContent = "Panorama introuvable";
        texte.textContent = "Ce lien ne correspond à aucun panorama sur le serveur.";
        roue.style.display = 'none';
      }}
    }}).catch(function () {{
      titre.textContent = "Panorama indisponible";
      texte.textContent = "Le serveur n'a pas répondu. Réessayez dans un instant.";
      roue.style.display = 'none';
    }});
  }});
</script>
</body>
</html>"""


@router.get("/vr/{job_id}", response_class=HTMLResponse)
def visionneuse(job_id: str):
    # La page est servie dans tous les cas : c'est elle qui interroge /info et affiche
    # l'état. Refuser ici avec un 404 priverait le client du message d'attente, qui est
    # justement ce qu'il a besoin de lire.
    return HTMLResponse(_page(job_id))


@router.get("/vr/{job_id}/info")
def info(job_id: str):
    etat, photos = _etat(job_id)
    return JSONResponse({"etat": etat, "photos": photos,
                         "image_url": f"/api/panorama/{job_id}/image",
                         "viewer_url": f"/vr/{job_id}",
                         # Conservé pour ne pas casser un appelant existant.
                         "present": etat == "pret"})
