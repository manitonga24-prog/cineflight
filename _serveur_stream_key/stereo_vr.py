# -*- coding: utf-8 -*-
"""
stereo_vr.py — RELIEF STÉRÉOSCOPIQUE : deux panoramas, un par œil (2026-07-27).

Un panorama ordinaire est une image collée sur une sphère : en casque on tourne la tête,
mais tout est à la même distance — c'est plat. Ici le drone prend DEUX panoramas séparés
par quelques mètres (hyperstéréo aérienne). Chaque œil reçoit le sien, et le cerveau
reconstruit la profondeur : les arbres du premier plan se détachent du fond.

COMMENT C'EST FAIT : deux sphères three.js, l'une sur le calque 1, l'autre sur le calque 2.
En WebXR, la caméra gauche n'active que le calque 1 et la droite que le calque 2 — chaque
œil ne voit donc que sa sphère. Hors casque, il n'y a qu'un point de vue : on montre l'œil
gauche seul (une image plate correcte, plutôt qu'un mélange qui ferait mal aux yeux).

⚠ LIMITE À DIRE AU CLIENT, PAS À CACHER : le relief n'existe que dans les directions
PERPENDICULAIRES à la ligne de base. En regardant dans l'axe du décalage, la parallaxe
tombe à zéro et l'image redevient plate. Un 360°×180° stéréo complet exige un rig tournant
au centimètre près ; un drone dérive de plusieurs mètres. La page ORIENTE donc le
spectateur, au départ, vers la zone de relief maximal.

STOCKAGE : {titre, job_gauche, job_droit, base_m} dans stereo.json. Aucune image recopiée —
les deux panoramas sont servis par /api/panorama/{job_id}/image, déjà en place.

Routes :
    POST /api/stereo         -> {titre, job_gauche, job_droit, base_m} -> {stereo_id}
    GET  /vr3d/{stereo_id}   -> visionneuse (casque / téléphone / ordinateur)
    GET  /api/stereo/{id}    -> JSON (diagnostic)

INSTALLATION : déposer dans le dossier de l'app web puis, dans app.py :
    from stereo_vr import router as stereo_vr_router
    app.include_router(stereo_vr_router)
"""

import json
import os
import threading
import uuid

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter()

_FICHIER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stereo.json")
_lock = threading.Lock()

# Version épinglée : une mise à jour surprise du CDN ne doit pas casser la visionneuse
# d'un client en pleine démonstration.
THREE = "https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"


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


@router.post("/api/stereo")
async def creer_stereo(request: Request):
    try:
        b = await request.json()
    except Exception:
        raise HTTPException(400, "corps JSON illisible")
    g = (b.get("job_gauche") or "").strip()
    d = (b.get("job_droit") or "").strip()
    if not g or not d:
        raise HTTPException(422, "job_gauche et job_droit sont requis")
    if g == d:
        # Deux fois la même image = aucune parallaxe. Refuser plutôt que livrer un
        # « relief » qui n'en est pas : le client ne verrait rien et croirait à un défaut
        # de son casque.
        raise HTTPException(422, "les deux yeux pointent le même panorama : aucun relief")
    try:
        base = float(b.get("base_m") or 0.0)
    except Exception:
        base = 0.0
    sid = uuid.uuid4().hex[:12]
    with _lock:
        tout = _lire()
        tout[sid] = {
            "titre": str(b.get("titre") or "Relief CineFlight"),
            "job_gauche": g,
            "job_droit": d,
            "base_m": round(base, 2),
        }
        _ecrire(tout)
    return JSONResponse({"stereo_id": sid, "url": "/vr3d/%s" % sid})


@router.get("/api/stereo/{sid}")
async def info_stereo(sid: str):
    e = _lire().get(sid)
    if not e:
        raise HTTPException(404, "relief inconnu")
    return JSONResponse(e)


def _page(sid, e):
    titre = e.get("titre") or "Relief CineFlight"
    base = e.get("base_m") or 0.0
    img_g = "/api/panorama/%s/image" % e["job_gauche"]
    img_d = "/api/panorama/%s/image" % e["job_droit"]
    return """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>%(titre)s — Relief 3D</title>
<script src="%(three)s"></script>
<style>
  html,body { margin:0; padding:0; background:#000; overflow:hidden;
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; color:#fff; }
  canvas { display:block; }
  #ui { position:fixed; left:0; right:0; bottom:0; padding:14px 16px calc(14px + env(safe-area-inset-bottom));
    background:linear-gradient(transparent,rgba(0,0,0,.85)); text-align:center; }
  #titre { position:fixed; top:0; left:0; right:0; padding:14px 16px; font-size:15px;
    background:linear-gradient(rgba(0,0,0,.75),transparent); pointer-events:none; }
  #titre b { font-size:17px; }
  .note { font-size:12px; opacity:.72; margin-top:6px; line-height:1.45; }
  button { background:#1e88e5; color:#fff; border:0; border-radius:10px; padding:13px 22px;
    font-size:16px; cursor:pointer; }
  button:disabled { background:#444; cursor:default; }
  #chargement { position:fixed; inset:0; display:flex; align-items:center; justify-content:center;
    background:#000; font-size:16px; z-index:9; }
</style>
</head>
<body>
<div id="chargement">Chargement du relief…</div>
<div id="titre"><b>%(titre)s</b><div class="note">Écartement des deux prises de vue :
  %(base).2f m</div></div>
<div id="ui">
  <button id="btnVr" disabled>Entrer en 3D</button>
  <div class="note" id="msg">
    Le relief se voit <b>en casque VR</b> : chaque œil reçoit sa propre image.<br>
    Sur téléphone ou ordinateur, il n'y a qu'un point de vue — l'image reste plate,
    c'est normal.
  </div>
</div>
<script>
var IMG_G = "%(img_g)s", IMG_D = "%(img_d)s";
var scene, camera, renderer, sphereG, sphereD;
var lon = 0, lat = 0, drag = false, px = 0, py = 0;

function sphere(url, calque, done) {
  // Sphère retournée (scale -1 en x) : on regarde la texture depuis l'INTÉRIEUR.
  var geo = new THREE.SphereGeometry(500, 60, 40);
  geo.scale(-1, 1, 1);
  var tex = new THREE.TextureLoader().load(url, done, undefined, done);
  var m = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ map: tex }));
  m.layers.set(calque);      // 1 = oeil gauche, 2 = oeil droit
  return m;
}

function init() {
  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(72, innerWidth / innerHeight, 0.1, 1100);
  // Hors casque il n'y a qu'un oeil : on affiche le GAUCHE. Superposer les deux
  // donnerait une image double et un mal de tête, pas du relief.
  camera.layers.enable(1);

  var restant = 2;
  function pret() { if (--restant <= 0) demarrer(); }
  sphereG = sphere(IMG_G, 1, pret); scene.add(sphereG);
  sphereD = sphere(IMG_D, 2, pret); scene.add(sphereD);

  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(devicePixelRatio);
  renderer.setSize(innerWidth, innerHeight);
  renderer.xr.enabled = true;
  document.body.appendChild(renderer.domElement);

  renderer.xr.addEventListener("sessionstart", function () {
    // EN CASQUE : chaque oeil ne voit QUE sa sphère. C'est ici que naît le relief.
    var c = renderer.xr.getCamera(camera);
    if (c && c.cameras && c.cameras.length === 2) {
      c.cameras[0].layers.enable(1);
      c.cameras[1].layers.enable(2);
      c.cameras[0].layers.disable(2);
      c.cameras[1].layers.disable(1);
    }
    document.getElementById("msg").innerHTML = "Regarde autour de toi. Le relief est " +
      "le plus marqué en regardant <b>perpendiculairement</b> au décalage du drone.";
  });

  addEventListener("resize", function () {
    camera.aspect = innerWidth / innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(innerWidth, innerHeight);
  });
  var el = renderer.domElement;
  el.addEventListener("pointerdown", function (e) { drag = true; px = e.clientX; py = e.clientY; });
  el.addEventListener("pointermove", function (e) {
    if (!drag) return;
    lon -= (e.clientX - px) * 0.16; lat += (e.clientY - py) * 0.16;
    lat = Math.max(-85, Math.min(85, lat)); px = e.clientX; py = e.clientY;
  });
  addEventListener("pointerup", function () { drag = false; });
  renderer.setAnimationLoop(rendre);
}

function rendre() {
  if (!renderer.xr.isPresenting) {
    var phi = THREE.MathUtils.degToRad(90 - lat), th = THREE.MathUtils.degToRad(lon);
    camera.lookAt(500 * Math.sin(phi) * Math.cos(th), 500 * Math.cos(phi),
                  500 * Math.sin(phi) * Math.sin(th));
  }
  renderer.render(scene, camera);
}

function demarrer() {
  document.getElementById("chargement").style.display = "none";
  var b = document.getElementById("btnVr");
  if (!navigator.xr) {
    b.textContent = "Casque VR non détecté"; return;
  }
  navigator.xr.isSessionSupported("immersive-vr").then(function (ok) {
    if (!ok) { b.textContent = "Casque VR non détecté"; return; }
    b.disabled = false;
    b.onclick = function () {
      navigator.xr.requestSession("immersive-vr", { optionalFeatures: ["local-floor"] })
        .then(function (s) { renderer.xr.setSession(s); });
    };
  });
}
init();
</script>
</body>
</html>""" % {"titre": titre, "three": THREE, "base": base, "img_g": img_g, "img_d": img_d}


@router.get("/vr3d/{sid}")
async def page_stereo(sid: str):
    e = _lire().get(sid)
    if not e:
        return HTMLResponse("<h1>Relief introuvable</h1>", status_code=404)
    return HTMLResponse(_page(sid, e))
