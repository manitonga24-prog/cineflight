# -*- coding: utf-8 -*-
"""
patch_travaux_pano.py — les PANORAMAS passent aussi par l'atelier PC (2026-07-28).

POURQUOI. Mesuré ce matin : `cpfind` sur 61 photos a occupé 1,26 Go et QUINZE MINUTES de
processeur sur un droplet qui n'a qu'un cœur — et ce n'était que la recherche des points
communs, la première phase. Pendant ce temps le serveur ne sert plus personne.

Or ce serveur est dimensionné pour RECEVOIR, SERVIR et MONTRER. Le calcul appartient à la
machine qui a la puissance : le PC de Christian, qui fait déjà la reconstruction 3D.

CE QUE ÇA AJOUTE — mêmes conventions que la file 3D, même jeton `X-Cine-Ouvrier` :

    GET  /api/travaux_pano                 -> panoramas en attente d'assemblage
    GET  /api/travaux_pano/{id}/liste      -> noms et tailles des photos
    GET  /api/travaux_pano/{id}/photo/{n}  -> une photo
    GET  /api/travaux_pano/{id}/angles     -> angles de prise de vue, ou 404
    POST /api/travaux_pano/{id}/resultat   -> dépôt du panorama assemblé
    GET  /api/atelier/assembleur           -> le script d'assemblage LUI-MÊME

⚠ POURQUOI SERVIR LE SCRIPT. `cine_panorama_stitch.py` a reçu cinq correctifs successifs
(sphère complète, `enblend -f`, guidage par angles, ordre des voies, photométrie). En
recopier une version sur le PC, c'est créer une seconde source de vérité qui divergera au
premier correctif suivant. L'atelier télécharge donc la version COURANTE à chaque travail.

⚠ RIEN N'EST EFFACÉ. Les photos restent sur le serveur ; un travail reste proposé tant
qu'aucun `panorama.jpg` n'est déposé. Un atelier éteint ne fait perdre aucun vol.

IDEMPOTENT, sauvegarde `app.py.avant_travaux_pano`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_travaux_pano.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

BLOC = '''

# ─────────────────────────────────────────────────────────────────────────────
#  FILE DE TRAVAUX PANORAMA POUR L'ATELIER PC (2026-07-28)
#  Voir patch_travaux_pano.py. Le serveur ne calcule plus les panoramas.
# ─────────────────────────────────────────────────────────────────────────────
_PANO_RACINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pano_jobs")


def _pano_dossier(mid):
    from fastapi import HTTPException
    # SECURITE : jamais de chemin, seulement un identifiant.
    sur = os.path.basename(mid)
    d = os.path.join(_PANO_RACINE, sur)
    if not os.path.isdir(d):
        raise HTTPException(404, "travail inconnu")
    return d


@app.get("/api/travaux_pano")
async def ouvrier_travaux_pano(request: _OuvrierRequest):
    """Panoramas dont les photos sont la mais dont l'image finale n'existe pas."""
    _ouvrier_verifier(request)
    import glob as _g
    res = []
    for d in sorted(_g.glob(os.path.join(_PANO_RACINE, "*"))):
        if not os.path.isdir(d):
            continue
        if os.path.exists(os.path.join(d, "panorama.jpg")):
            continue          # deja assemble : rien a faire
        photos = _g.glob(os.path.join(d, "in", "*"))
        if len(photos) < 4:
            continue          # travail vide ou avorte : on ne le propose pas
        mid = os.path.basename(d)
        res.append({
            "id": mid,
            "photos": len(photos),
            "octets": sum(os.path.getsize(p) for p in photos),
            "cree": int(os.path.getmtime(os.path.join(d, "in"))),
            "angles": os.path.exists(os.path.join(d, "angles.json")),
            "url_depot": "/api/travaux_pano/%s/resultat" % mid,
        })
    res.sort(key=lambda x: x["cree"])
    return JSONResponse({"travaux": res})


@app.get("/api/travaux_pano/{mid}/liste")
async def ouvrier_pano_liste(mid: str, request: _OuvrierRequest):
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    import glob as _g
    d = os.path.join(_pano_dossier(mid), "in")
    fichiers = sorted(_g.glob(os.path.join(d, "*")))
    if not fichiers:
        raise HTTPException(404, "aucune photo pour ce travail")
    return JSONResponse({"photos": [
        {"nom": os.path.basename(p), "octets": os.path.getsize(p)} for p in fichiers]})


@app.get("/api/travaux_pano/{mid}/photo/{nom}")
async def ouvrier_pano_photo(mid: str, nom: str, request: _OuvrierRequest):
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    from fastapi.responses import FileResponse
    d = os.path.join(_pano_dossier(mid), "in")
    sur = os.path.basename(nom)
    chemin = os.path.join(d, sur)
    if not os.path.isfile(chemin) or os.path.dirname(os.path.abspath(chemin)) != os.path.abspath(d):
        raise HTTPException(404, "photo introuvable")
    return FileResponse(chemin, media_type="image/jpeg", filename=sur)


@app.get("/api/travaux_pano/{mid}/angles")
async def ouvrier_pano_angles(mid: str, request: _OuvrierRequest):
    """
    Angles de prise de vue, s'ils ont ete deposes par l'app.

    Ce sont eux qui font atterrir les deux panoramas d'une paire stereo dans le MEME
    repere. Sans eux Hugin choisit une orientation par panorama, et les deux yeux
    divergent — ecart mesure de 2,6 degres, cinq fois la tolerance de fusion.
    """
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    from fastapi.responses import FileResponse
    f = os.path.join(_pano_dossier(mid), "angles.json")
    if not os.path.isfile(f):
        raise HTTPException(404, "pas d'angles pour ce travail")
    return FileResponse(f, media_type="application/json")


@app.post("/api/travaux_pano/{mid}/resultat")
async def ouvrier_pano_resultat(mid: str, file: UploadFile = File(...)):
    """
    Depot du panorama assemble.

    ⚠ ON VERIFIE LE PRODUIT, PAS LA PROMESSE. Un JPEG qui n'est pas en rapport 2:1 n'est
    pas une sphere complete : la visionneuse le plaquerait en supposant 360x180 et le
    paysage sortirait a la mauvaise hauteur. C'est exactement le defaut qu'`enblend -f` a
    corrige, et qu'une commande juste avait laisse passer.
    """
    from fastapi import HTTPException
    d = _pano_dossier(mid)
    tmp = os.path.join(d, "panorama_partiel.jpg")
    with open(tmp, "wb") as sortie:
        while True:
            bloc = await file.read(1 << 20)
            if not bloc:
                break
            sortie.write(bloc)
    taille = os.path.getsize(tmp)
    if taille < 100_000:
        os.remove(tmp)
        raise HTTPException(422, "fichier trop petit (%d octets) : assemblage incomplet" % taille)
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        w, h = Image.open(tmp).size
    except Exception as e:
        os.remove(tmp)
        raise HTTPException(422, "image illisible : %s" % e)
    if abs(w - 2 * h) > 2:
        os.remove(tmp)
        raise HTTPException(422, "rapport %dx%d : ce n'est pas une sphere complete (2:1)" % (w, h))
    # Renommage ATOMIQUE en dernier : tant qu'il echoue, le travail reste propose.
    os.replace(tmp, os.path.join(d, "panorama.jpg"))
    return JSONResponse({"id": mid, "largeur": w, "hauteur": h, "octets": taille,
                         "url": "/vr/%s" % mid})


@app.get("/api/atelier/assembleur")
async def ouvrier_assembleur(request: _OuvrierRequest):
    """
    Le script d'assemblage lui-meme, dans sa version COURANTE.

    ⚠ UNE SEULE SOURCE DE VERITE. `cine_panorama_stitch.py` a recu cinq correctifs
    successifs ; en recopier une version sur le PC creerait une seconde source qui
    divergerait au premier correctif suivant. L'atelier le retelecharge a chaque travail.
    """
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    from fastapi.responses import FileResponse
    f = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")
    if not os.path.isfile(f):
        raise HTTPException(404, "assembleur introuvable sur ce serveur")
    return FileResponse(f, media_type="text/x-python", filename="cine_panorama_stitch.py")
'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "/api/travaux_pano" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    for besoin, pourquoi in (
        ("_ouvrier_verifier", "patch_travaux_ouvrier.py n'a pas ete applique"),
        ("_OuvrierRequest", "l'import Request de l'atelier manque"),
        ("UploadFile", "FastAPI UploadFile n'est pas importe dans app.py"),
        ("JSONResponse", "JSONResponse n'est pas importe dans app.py"),
    ):
        if besoin not in src:
            print("ECHEC : %s — %s. RIEN modifie." % (besoin, pourquoi)); return 1

    out = src.rstrip("\n") + "\n" + BLOC
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_travaux_pano")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : file de travaux PANORAMA ajoutee.")
    print("Sauvegarde : app.py.avant_travaux_pano")
    print()
    print("⚠ La compilation ne prouve rien (voir patch_pano_parenthese.py).")
    print("  Verifie par un appel reel apres redemarrage :")
    print("    systemctl restart cineflight")
    print("    curl -s -H \"X-Cine-Ouvrier: $(cat ouvrier_token.txt)\" \\")
    print("         http://127.0.0.1:8095/api/travaux_pano")
    return 0


if __name__ == "__main__":
    sys.exit(main())
