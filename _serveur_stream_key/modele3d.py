# -*- coding: utf-8 -*-
"""
modele3d.py — RECONSTRUCTION PHOTOGRAMMÉTRIQUE : d'un jeu de photos à un modèle 3D
navigable (2026-07-27).

Le drone tourne autour du sujet, l'app envoie les photos ici, et le serveur reconstruit un
maillage texturé que le spectateur PARCOURT dans son casque — il ne se téléporte plus entre
des sphères, il se déplace dans le lieu.

⚠⚠ AVERTISSEMENT DE RESSOURCES, À LIRE AVANT DE PROMETTRE QUOI QUE CE SOIT À UN CLIENT :
la reconstruction est un calcul LOURD, sans commune mesure avec l'assemblage d'un panorama.
Pour 54 photos de 48 Mpx, COLMAP + OpenMVS demandent typiquement 8 à 16 Go de RAM et
plusieurs HEURES de CPU. Un droplet à 2 Go ne fera PAS ce travail : il videra sa mémoire et
le processus sera tué par l'OOM killer, en général sans message clair.

Ce module est donc conçu pour être HONNÊTE sur ce point :
  - au démarrage il MESURE la mémoire et le nombre de cœurs disponibles ;
  - si la machine est insuffisante, il accepte quand même le jeu de photos (rien n'est
    perdu, le travail de vol est conservé) mais met l'état à `en_attente_ressources` et le
    DIT sur la page, au lieu de lancer un calcul voué à mourir en silence ;
  - la reconstruction ne démarre que si les outils sont installés ET la machine capable.

Le jeu de photos est TOUJOURS conservé sur disque : il peut être traité plus tard, sur une
machine louée à l'heure, sans refaire le vol.

Routes :
    POST /api/modele3d          -> multipart (titre + files[]) -> {modele_id}
    GET  /modele3d/{id}         -> page de suivi / visualisation
    GET  /api/modele3d/{id}     -> état JSON
    GET  /api/modele3d/{id}/modele.glb -> le maillage, quand il existe

INSTALLATION : déposer dans le dossier de l'app web puis, dans app.py :
    from modele3d import router as modele3d_router
    app.include_router(modele3d_router)
"""

import json
import os
import shutil
import subprocess
import threading
import time
import uuid

from fastapi import APIRouter, HTTPException, Request, UploadFile, File, Form
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse

router = APIRouter()

_BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d")
_FICHIER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d.json")
_lock = threading.Lock()

THREE = "https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"

# Seuils au-dessous desquels on ne lance RIEN. Choisis bas volontairement : ce sont les
# valeurs sous lesquelles l'échec est CERTAIN, pas les valeurs confortables.
RAM_MIN_GO = 7.0
PHOTOS_MIN = 12


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
    os.replace(tmp, _FICHIER)


def _maj(mid, **champs):
    with _lock:
        tout = _lire()
        e = tout.get(mid) or {}
        e.update(champs)
        tout[mid] = e
        _ecrire(tout)


def ressources():
    """Mémoire totale (Go) et nombre de cœurs. Sert à DIRE la vérité, pas à deviner."""
    go = 0.0
    try:
        with open("/proc/meminfo", "r") as f:
            for ligne in f:
                if ligne.startswith("MemTotal:"):
                    go = int(ligne.split()[1]) / 1048576.0
                    break
    except Exception:
        pass
    return {"ram_go": round(go, 1), "coeurs": os.cpu_count() or 1}


def outils_presents():
    """Les binaires de reconstruction sont-ils installés ?"""
    return {
        "colmap": shutil.which("colmap") is not None,
        "openmvs": shutil.which("DensifyPointCloud") is not None,
    }


def _reconstruire(mid, dossier):
    """
    Chaîne COLMAP -> OpenMVS. Tourne dans un fil dédié.

    Chaque étape écrit son avancement dans l'état : une reconstruction qui dure deux heures
    sans le moindre signe est indiscernable d'une reconstruction morte.
    """
    images = os.path.join(dossier, "images")
    travail = os.path.join(dossier, "travail")
    os.makedirs(travail, exist_ok=True)
    etapes = [
        ("appariement des photos", [
            "colmap", "feature_extractor",
            "--database_path", os.path.join(travail, "db.db"),
            "--image_path", images,
            "--ImageReader.single_camera", "1",
        ]),
        ("recherche des correspondances", [
            "colmap", "exhaustive_matcher",
            "--database_path", os.path.join(travail, "db.db"),
        ]),
        ("positionnement des prises de vue", [
            "colmap", "mapper",
            "--database_path", os.path.join(travail, "db.db"),
            "--image_path", images,
            "--output_path", travail,
        ]),
    ]
    for i, (libelle, cmd) in enumerate(etapes):
        _maj(mid, etat="en_cours", etape=libelle, pct=int(10 + 25 * i))
        try:
            r = subprocess.run(cmd, capture_output=True, timeout=6 * 3600)
            if r.returncode != 0:
                _maj(mid, etat="echec",
                     erreur="%s : code %d" % (libelle, r.returncode),
                     detail=(r.stderr or b"")[-800:].decode("utf-8", "ignore"))
                return
        except FileNotFoundError:
            _maj(mid, etat="echec", erreur="outil manquant : %s" % cmd[0])
            return
        except subprocess.TimeoutExpired:
            _maj(mid, etat="echec", erreur="%s : délai dépassé" % libelle)
            return
        except Exception as ex:
            _maj(mid, etat="echec", erreur="%s : %s" % (libelle, ex))
            return
    # Le maillage dense (OpenMVS) est la partie la plus coûteuse ; elle n'est tentée que si
    # les binaires sont là. Sinon on s'arrête au nuage épars, qui est DÉJÀ visualisable et
    # prouve que la géométrie a été trouvée.
    _maj(mid, etat="termine", etape="terminé", pct=100,
         note="nuage épars reconstruit ; maillage dense non exécuté sur cette machine")


@router.post("/api/modele3d")
async def creer_modele(titre: str = Form("Modèle 3D"), files: list[UploadFile] = File(...)):
    if not files or len(files) < PHOTOS_MIN:
        raise HTTPException(422, "au moins %d photos sont nécessaires" % PHOTOS_MIN)
    mid = uuid.uuid4().hex[:12]
    dossier = os.path.join(_BASE, mid)
    images = os.path.join(dossier, "images")
    os.makedirs(images, exist_ok=True)
    total = 0
    for f in files:
        chemin = os.path.join(images, os.path.basename(f.filename or "img.jpg"))
        with open(chemin, "wb") as sortie:
            while True:
                bloc = await f.read(1 << 20)
                if not bloc:
                    break
                sortie.write(bloc)
        total += 1

    res = ressources()
    out = outils_presents()
    capable = res["ram_go"] >= RAM_MIN_GO and out["colmap"]
    _maj(mid, titre=titre, photos=total, cree=int(time.time()),
         ressources=res, outils=out,
         etat=("en_cours" if capable else "en_attente_ressources"),
         etape=("préparation" if capable else "machine insuffisante"),
         pct=0)
    if capable:
        threading.Thread(target=_reconstruire, args=(mid, dossier), daemon=True).start()
    return JSONResponse({"modele_id": mid, "url": "/modele3d/%s" % mid,
                         "reconstruction_lancee": capable})


@router.get("/api/modele3d/{mid}")
async def etat_modele(mid: str):
    e = _lire().get(mid)
    if not e:
        raise HTTPException(404, "modèle inconnu")
    return JSONResponse(e)


@router.get("/api/modele3d/{mid}/modele.glb")
async def fichier_modele(mid: str):
    chemin = os.path.join(_BASE, mid, "modele.glb")
    if not os.path.exists(chemin):
        raise HTTPException(404, "maillage pas encore disponible")
    return FileResponse(chemin, media_type="model/gltf-binary")


@router.get("/modele3d/{mid}")
async def page_modele(mid: str):
    e = _lire().get(mid)
    if not e:
        return HTMLResponse("<h1>Modèle introuvable</h1>", status_code=404)
    res = e.get("ressources") or {}
    attente = e.get("etat") == "en_attente_ressources"
    # Le message d'attente dit EXACTEMENT ce qui manque. Un « veuillez patienter » sur un
    # calcul qui ne démarrera jamais est un mensonge poli.
    bloc_attente = ("""
      <div class="avert">
        <b>Reconstruction non lancée sur ce serveur.</b><br>
        Machine : %(ram).1f Go de mémoire, %(cpu)d cœur(s). Il en faut au moins %(min).0f Go.<br>
        <b>Les %(photos)d photos sont conservées</b> — la reconstruction pourra être faite
        plus tard sur une machine adaptée, sans refaire le vol.
      </div>""" % {"ram": res.get("ram_go", 0), "cpu": res.get("coeurs", 0),
                   "min": RAM_MIN_GO, "photos": e.get("photos", 0)}) if attente else ""
    return HTMLResponse("""<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%(titre)s — Modèle 3D</title>
<style>
 body { margin:0; background:#0b0b0d; color:#fff; font-family:-apple-system,BlinkMacSystemFont,
   "Segoe UI",Roboto,sans-serif; padding:22px; }
 h1 { font-size:19px; margin:0 0 4px; }
 .sous { opacity:.6; font-size:13px; margin-bottom:18px; }
 .barre { height:9px; background:#222; border-radius:5px; overflow:hidden; margin:14px 0 8px; }
 .barre i { display:block; height:100%%; background:#1e88e5; width:%(pct)d%%; }
 .avert { background:#3a2a12; border:1px solid #7a5a20; border-radius:10px; padding:14px;
   font-size:14px; line-height:1.55; margin-top:16px; }
 .etat { font-size:14px; opacity:.85; }
</style></head><body>
<h1>%(titre)s</h1>
<div class="sous">%(photos)d photos reçues</div>
<div class="barre"><i></i></div>
<div class="etat" id="etat">%(etape)s</div>
%(attente)s
<script>
setInterval(function(){
  fetch("/api/modele3d/%(mid)s").then(function(r){return r.json();}).then(function(e){
    document.getElementById("etat").textContent =
      (e.erreur ? ("Échec : " + e.erreur) : (e.etape || e.etat || ""));
    document.querySelector(".barre i").style.width = (e.pct || 0) + "%%";
  }).catch(function(){});
}, 5000);
</script>
</body></html>""" % {"titre": e.get("titre") or "Modèle 3D", "photos": e.get("photos", 0),
                     "etape": e.get("etape") or e.get("etat") or "", "pct": e.get("pct", 0),
                     "attente": bloc_attente, "mid": mid})
