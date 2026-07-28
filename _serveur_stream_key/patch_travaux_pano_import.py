# -*- coding: utf-8 -*-
"""
patch_travaux_pano_import.py — `File` n'existait pas dans app.py (2026-07-28).

LE DÉFAUT. `patch_travaux_pano.py` déclarait :

    async def ouvrier_pano_resultat(mid: str, file: UploadFile = File(...)):

`UploadFile` est bien importé dans app.py — j'avais vérifié. `File`, non. Or ces deux noms
sont évalués À L'IMPORT DU MODULE, pas à l'appel : le service tombait au démarrage avec
`NameError: name 'File' is not defined`, et nginx rendait 502 sur tout le site.

⚠ C'est la MÊME famille que le piège FastAPI du 2026-07-27 avec `Request`, déjà documentée :
« un faux d'essai doit reproduire aussi les IMPORTS du fichier cible ». J'ai contrôlé un des
deux noms et supposé l'autre.

⚠⚠ ET POURQUOI LE DIAGNOSTIC A TRAÎNÉ. `python3 -c "import app" 2>&1 | tail -25` a répondu
« tout va bien » DEUX FOIS, avec le Python système puis avec celui du venv. C'était faux :
la trace part sur stderr (non tamponné) et les `print` sur stdout (tamponné quand on
redirige). Les prints se vident À LA SORTIE et repoussent le traceback hors des dernières
lignes. RÈGLE : pour voir une erreur d'import, jeter stdout —
    python -c "import app" > /dev/null
et non l'inverse.

CE QUE ÇA CHANGE. On importe explicitement les deux noms sous alias, comme on l'avait fait
pour `Request`, et la signature les emploie.

IDEMPOTENT, sauvegarde `app.py.avant_import_pano`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_travaux_pano_import.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

ANCRE = '_PANO_RACINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pano_jobs")'
IMPORT = ('from fastapi import File as _OuvrierFile, UploadFile as _OuvrierUpload\n'
          '_PANO_RACINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pano_jobs")')

SIGNATURE_CASSEE = "async def ouvrier_pano_resultat(mid: str, file: UploadFile = File(...)):"
SIGNATURE_JUSTE = ("async def ouvrier_pano_resultat("
                   "mid: str, file: _OuvrierUpload = _OuvrierFile(...)):")


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "_OuvrierUpload" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if ANCRE not in src or SIGNATURE_CASSEE not in src:
        print("ECHEC : applique d'abord patch_travaux_pano.py.")
        print("        Le bloc attendu est absent — RIEN modifie.")
        return 1

    out = src.replace(ANCRE, IMPORT, 1).replace(SIGNATURE_CASSEE, SIGNATURE_JUSTE, 1)
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_import_pano")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : File et UploadFile importes explicitement.")
    print("Sauvegarde : app.py.avant_import_pano")
    print()
    print("VERIFIE L'IMPORT EN JETANT STDOUT (voir l'en-tete de ce fichier) :")
    print("  /root/cineflight_web/.venv/bin/python -c 'import app' > /dev/null")
    print("Silence = bon. Puis :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
