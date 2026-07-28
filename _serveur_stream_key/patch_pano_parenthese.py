# -*- coding: utf-8 -*-
"""
patch_pano_parenthese.py — l'assemblage ne démarrait plus du tout (2026-07-28).

LE DÉFAUT. Une parenthèse fermante placée un caractère trop loin :

    args=(job_id, din, out_jpg, int(largeur_max, _pano_angles))     ← CASSÉ
    args=(job_id, din, out_jpg, int(largeur_max), _pano_angles)     ← JUSTE

`int()` recevait la liste des angles comme SECOND argument — la base numérique — et levait
`TypeError: 'list' object cannot be interpreted as an integer`. L'exception tombait juste
avant le démarrage du fil de calcul : les photos étaient reçues et écrites sur le disque,
mais aucun assemblage ne commençait jamais.

⚠ COMMENT ÇA A PU PASSER. Le fichier est parfaitement VALIDE en Python — `int(a, b)` est
une signature légitime. `python3 -m py_compile` répond donc OK, et c'est ce contrôle-là qui
avait servi à valider le déploiement de `patch_api_angles.py`. Même leçon que le fichier
vide déclaré valide, et que le code de retour de RealityScan : **la compilation ne prouve
rien sur le comportement.** Seul un essai de bout en bout l'aurait montré.

⚠ SYMPTÔME TROMPEUR : côté app, la tâche restait dans la file « assemblages en attente »,
ce qui ressemblait à un problème de téléphone ou de drone. Le serveur, lui, répondait 500
et écrivait la trace dans son journal — invisible tant qu'on ne le lisait pas.

IDEMPOTENT, sauvegarde `app.py.avant_parenthese`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_parenthese.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

CASSE = "int(largeur_max, _pano_angles)"
JUSTE = "int(largeur_max), _pano_angles"


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if CASSE not in src:
        if JUSTE in src:
            print("RIEN A FAIRE : la parenthese est deja corrigee.")
            return 0
        print("ECHEC : ni la forme cassee ni la forme juste ne sont presentes.")
        print("        app.py differe de ce qui etait prevu — RIEN modifie.")
        return 1

    n = src.count(CASSE)
    out = src.replace(CASSE, JUSTE)
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_parenthese")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : %d occurrence(s) corrigee(s)." % n)
    print("Sauvegarde : app.py.avant_parenthese")
    print()
    print("⚠ La compilation ne suffit PAS a valider ce correctif : la forme cassee")
    print("  compilait aussi. Verifie par un vrai assemblage, et regarde le journal :")
    print("    systemctl restart cineflight")
    print("    journalctl -u cineflight -f | grep -viE 'clicker|geobarriere|corridor'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
