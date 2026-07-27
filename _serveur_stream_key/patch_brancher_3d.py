# -*- coding: utf-8 -*-
"""
patch_brancher_3d.py — branche stereo_vr et modele3d dans app.py (2026-07-27).

IDEMPOTENT : relancé deux fois, il n'ajoute rien en double.
FAIL-CLOSED : si l'ancre `visite_vr_router` est absente, il ne touche à RIEN et le dit.
Une sauvegarde `app.py.avant_3d` est écrite avant toute modification.

Usage sur le VPS :
    cd /root/cineflight_web && python3 patch_brancher_3d.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

IMPORT_ANCRE = "from visite_vr import router as visite_vr_router"
INCLUDE_ANCRE = "app.include_router(visite_vr_router)"

AJOUTS = [
    ("from stereo_vr import router as stereo_vr_router",
     "app.include_router(stereo_vr_router)"),
    ("from modele3d import router as modele3d_router",
     "app.include_router(modele3d_router)"),
]


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable dans", os.path.dirname(CHEMIN))
        return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if INCLUDE_ANCRE not in src or IMPORT_ANCRE not in src:
        # On ne devine PAS où insérer : un routeur branché au mauvais endroit produit des
        # routes fantômes très difficiles à diagnostiquer. Mieux vaut ne rien faire.
        print("ECHEC : ancre visite_vr_router absente d'app.py — RIEN n'a été modifié.")
        return 1

    for f in ("stereo_vr.py", "modele3d.py"):
        if not os.path.exists(os.path.join(os.path.dirname(CHEMIN), f)):
            print("ECHEC :", f, "n'est pas dans le dossier — télécharge-le d'abord.")
            return 1

    deja = 0
    out = src
    for imp, inc in AJOUTS:
        if imp in out and inc in out:
            deja += 1
            continue
        if imp not in out:
            out = out.replace(IMPORT_ANCRE, IMPORT_ANCRE + "\n" + imp, 1)
        if inc not in out:
            out = out.replace(INCLUDE_ANCRE, INCLUDE_ANCRE + "\n" + inc, 1)

    if out == src:
        print("RIEN A FAIRE : les %d routeurs sont déjà branchés." % deja)
        return 0

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_3d")
    # Vérification de syntaxe AVANT d'écrire : un app.py cassé rendrait le site injoignable
    # et il faudrait le réparer depuis la console, exactement ce qu'on cherche à éviter.
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le résultat ne compile pas (%s) — app.py laissé INTACT." % e)
        return 1
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : routeurs branchés. Sauvegarde dans app.py.avant_3d")
    print("Redémarre maintenant :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
