# -*- coding: utf-8 -*-
"""
patch_travaux_fichiers.py — servir les photos UNE PAR UNE, pas en archive (2026-07-28).

CE QUI ÉTAIT CASSÉ. L'endpoint `photos.zip` construisait l'archive en mémoire par
morceaux : après chaque photo, il lisait le tampon puis le TRONQUAIT. Or `ZipFile` suit
sa position d'écriture pour composer le répertoire central — remettre le tampon à zéro
détruit ces décalages. Le flux devenait invalide, et le téléchargement mourait en route
(`ChunkedEncodingError` à 97 Mo sur 249).

C'était une mauvaise idée dès le départ : une archive en flux n'a ni taille annoncée, ni
reprise possible, et une seule erreur emporte tout le transfert.

CE QUI LA REMPLACE :

    GET /api/travaux/{id}/liste          -> noms et tailles des photos
    GET /api/travaux/{id}/photo/{nom}    -> une photo, avec sa taille annoncée

Chaque fichier se télécharge seul, se retente seul, et le client sait où il en est. C'est
le même principe que le rapatriement depuis le drone, qui a déjà fait ses preuves.

⚠ SÉCURITÉ : le nom demandé est réduit à son basename et doit exister dans le dossier du
travail. Sans ça, `../../etc/passwd` sortirait du répertoire.

IDEMPOTENT, sauvegarde `app.py.avant_fichiers`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_travaux_fichiers.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

BLOC = '''

@app.get("/api/travaux/{mid}/liste")
async def ouvrier_liste(mid: str, request: _OuvrierRequest):
    """Noms et tailles des photos d'un travail. Le client telecharge ensuite une a une."""
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    import glob as _g
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d")
    dossier = os.path.join(base, mid, "images")
    fichiers = sorted(_g.glob(os.path.join(dossier, "*")))
    if not fichiers:
        raise HTTPException(404, "aucune photo pour ce travail")
    return JSONResponse({"photos": [
        {"nom": os.path.basename(p), "octets": os.path.getsize(p)} for p in fichiers]})


@app.get("/api/travaux/{mid}/photo/{nom}")
async def ouvrier_photo(mid: str, nom: str, request: _OuvrierRequest):
    """Une photo. Taille annoncee, donc barre de progression fiable et reprise possible."""
    _ouvrier_verifier(request)
    from fastapi import HTTPException
    from fastapi.responses import FileResponse
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modeles3d")
    dossier = os.path.join(base, mid, "images")
    # SECURITE : on ne garde que le nom de fichier, jamais un chemin. Un « ../ » sortirait
    # du dossier du travail et servirait n'importe quel fichier du serveur.
    sur = os.path.basename(nom)
    chemin = os.path.join(dossier, sur)
    if not os.path.isfile(chemin) or os.path.dirname(os.path.abspath(chemin)) != os.path.abspath(dossier):
        raise HTTPException(404, "photo introuvable")
    return FileResponse(chemin, media_type="image/jpeg", filename=sur)
'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "/api/travaux/{mid}/liste" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if "_ouvrier_verifier" not in src:
        print("ECHEC : patch_travaux_ouvrier.py n'a pas ete applique — RIEN modifie.")
        return 1

    out = src.rstrip("\n") + "\n" + BLOC
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_fichiers")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : /liste et /photo/{nom} ajoutes.")
    print("     L'ancien /photos.zip reste en place mais n'est plus utilise.")
    print("Sauvegarde : app.py.avant_fichiers")
    print("Redemarre :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
