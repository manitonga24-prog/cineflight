# -*- coding: utf-8 -*-
"""
patch_travaux_ouvrier.py — file de travaux pour un ORDINATEUR OUVRIER (2026-07-27).

LE PROBLÈME. Le droplet (1 vCPU, 1 Go) ne peut pas reconstruire un modèle 3D : il accepte
les photos et met l'état à `en_attente_ressources`. Elles y restent jusqu'à ce que
quelqu'un aille les chercher à la main.

LA SOLUTION. Le PC de Christian — RTX 3090 — devient l'atelier. Ce patch ajoute au serveur
deux points d'entrée que ce PC interroge :

    GET /api/travaux                  -> les jeux de photos en attente de calcul
    GET /api/travaux/{id}/photos.zip  -> leurs photos, en une archive

⚠ SENS DE LA CONNEXION. C'est le PC qui APPELLE le serveur, jamais l'inverse. Aucun port à
ouvrir chez Christian, aucune adresse fixe, rien à exposer sur Internet — c'est précisément
ce qui bloquait pour SSH. Le pare-feu de son fournisseur n'a rien à savoir.

⚠ JETON PARTAGÉ. Ces routes exposent des photos de clients : elles exigent un en-tête
`X-Cine-Ouvrier`. Le jeton est lu dans `ouvrier_token.txt` (chmod 600, hors git). Sans
fichier, les routes répondent 503 — fermé par défaut, jamais ouvert par oubli.

IDEMPOTENT, sauvegarde `app.py.avant_ouvrier`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_travaux_ouvrier.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

BLOC = '''

# ─────────────────────────────────────────────────────────────────────────────
#  FILE DE TRAVAUX POUR L'ORDINATEUR OUVRIER (2026-07-27)
#  Voir patch_travaux_ouvrier.py. Le PC appelle, le serveur repond : aucun port
#  a ouvrir cote atelier.
# ─────────────────────────────────────────────────────────────────────────────
from starlette.requests import Request as _OuvrierRequest
_OUVRIER_JETON_FICHIER = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      "ouvrier_token.txt")


def _ouvrier_jeton():
    """Jeton attendu, ou None si le fichier n'existe pas (routes fermees)."""
    try:
        with open(_OUVRIER_JETON_FICHIER, "r") as f:
            j = f.read().strip()
        return j or None
    except Exception:
        return None


def _ouvrier_verifier(request):
    from fastapi import HTTPException
    attendu = _ouvrier_jeton()
    if not attendu:
        # FERME PAR DEFAUT : sans jeton installe, la porte n'existe pas. Un oubli de
        # configuration ne doit jamais ouvrir l'acces aux photos des clients.
        raise HTTPException(503, "file de travaux non configuree sur ce serveur")
    if request.headers.get("X-Cine-Ouvrier") != attendu:
        raise HTTPException(403, "jeton invalide")


@app.get("/api/travaux")
async def ouvrier_travaux(request: _OuvrierRequest):
    """Jeux de photos en attente de calcul, du plus ancien au plus recent."""
    _ouvrier_verifier(request)
    import json as _j, glob as _g, time as _t
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d")
    fichier = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d.json")
    try:
        with open(fichier, "r", encoding="utf-8") as f:
            tout = _j.load(f)
    except Exception:
        tout = {}
    res = []
    for mid, e in tout.items():
        # On ne propose QUE ce qui attend vraiment : ni les modeles deja livres, ni ceux
        # qu'un autre poste est en train de calculer.
        if e.get("etat") not in ("en_attente_ressources", "en_attente_ouvrier"):
            continue
        if os.path.exists(os.path.join(base, mid, "modele.glb")):
            continue
        photos = _g.glob(os.path.join(base, mid, "images", "*"))
        if not photos:
            continue
        res.append({
            "id": mid,
            "titre": e.get("titre") or "Modele 3D",
            "photos": len(photos),
            "octets": sum(os.path.getsize(p) for p in photos),
            "cree": e.get("cree"),
            "url_photos": "/api/travaux/%s/photos.zip" % mid,
            "url_depot": "/api/modele3d/%s/modele" % mid,
        })
    res.sort(key=lambda x: x.get("cree") or 0)
    return JSONResponse({"travaux": res})


@app.get("/api/travaux/{mid}/photos.zip")
async def ouvrier_photos(mid: str, request: _OuvrierRequest):
    """Les photos d'un travail, en une archive. Lecture seule : rien n'est efface."""
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    from fastapi.responses import StreamingResponse
    import glob as _g, io as _io, zipfile as _z
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d")
    dossier = os.path.join(base, mid, "images")
    photos = sorted(_g.glob(os.path.join(dossier, "*")))
    if not photos:
        raise HTTPException(404, "aucune photo pour ce travail")

    def flux():
        # Archive construite EN MEMOIRE PAR MORCEAUX : un zip de 2 Go tiendrait pas dans
        # le gigaoctet de cette machine s'il fallait le composer d'un bloc.
        tampon = _io.BytesIO()
        with _z.ZipFile(tampon, "w", _z.ZIP_STORED) as z:
            for p in photos:
                z.write(p, os.path.basename(p))
                tampon.seek(0)
                bloc = tampon.read()
                tampon.seek(0)
                tampon.truncate(0)
                if bloc:
                    yield bloc
        reste = tampon.getvalue()
        if reste:
            yield reste

    return StreamingResponse(flux(), media_type="application/zip", headers={
        "Content-Disposition": 'attachment; filename="%s.zip"' % mid})
'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "/api/travaux" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    for besoin in ("_PANO_DIR", "JSONResponse"):
        if besoin not in src:
            print("ECHEC : %s absent d'app.py — fichier inattendu, RIEN modifie." % besoin)
            return 1

    out = src.rstrip("\n") + "\n" + BLOC
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_ouvrier")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : /api/travaux et /api/travaux/{id}/photos.zip ajoutes.")
    print("Sauvegarde : app.py.avant_ouvrier")
    print()
    print("INSTALLER LE JETON (sans lui, les routes repondent 503) :")
    print("  openssl rand -hex 24 > /root/cineflight_web/ouvrier_token.txt")
    print("  chmod 600 /root/cineflight_web/ouvrier_token.txt")
    print("  echo ouvrier_token.txt >> /root/cineflight_web/.gitignore")
    print("  cat /root/cineflight_web/ouvrier_token.txt   # a recopier sur le PC")
    print()
    print("Puis :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
