# -*- coding: utf-8 -*-
"""
patch_pano_photometrie.py — rétablit la correction photométrique (2026-07-27).

RÉGRESSION QUE CE PATCH RÉPARE. L'ancienne chaîne lançait `autooptimiser -a -m -l -s` :
`-m` y faisait l'**optimisation photométrique**, qui égalise les différences de luminosité,
de couleur et de vignettage entre les images en s'appuyant sur leurs zones de recouvrement.
En passant à la voie « angles seuls » — pour empêcher l'optimiseur GÉOMÉTRIQUE d'empiler
ou de basculer les photos — j'ai supprimé toute optimisation, y compris celle-là.
Résultat : plus rien ne corrige la luminosité. Le verrouillage d'exposition de l'app masque
le problème aujourd'hui, mais il n'y a plus de filet si la lumière change pendant les six
minutes d'un panorama « Ciel complet ».

CE QUI EST AJOUTÉ. `autooptimiser -m` sur la voie « angles », APRÈS l'écriture des
positions. Le `-m` ne touche QUE les paramètres photométriques : les positions écrites
depuis la boussole et la nacelle restent intactes. C'est précisément ce qu'on veut —
corriger la lumière sans laisser retoucher la géométrie.

⚠ Un échec de cette étape ne doit RIEN casser : la photométrie est un confort, la géométrie
est le produit. Si `-m` échoue, on continue avec le projet tel quel.

IDEMPOTENT, sauvegarde `.avant_photometrie`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_photometrie.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")

ANCIEN = '''                tentatives.append(("angles_seuls", sans_opt))'''

NOUVEAU = '''                # PHOTOMETRIE (correctif 2026-07-27, patch_pano_photometrie.py).
                # `-m` egalise luminosite, couleur et vignettage entre images en
                # s'appuyant sur les recouvrements. Il ne touche PAS aux positions :
                # celles qu'on vient d'ecrire depuis la boussole et la nacelle restent
                # intactes. Sans lui, plus rien ne corrigeait la lumiere depuis le
                # passage a la voie « angles seuls ».
                # Un echec ici n'arrete rien : la photometrie est un confort, la
                # geometrie est le produit.
                try:
                    _run(["autooptimiser", "-m", "-o", sans_opt, sans_opt], log)
                    log("photometrie egalisee (autooptimiser -m)")
                except Exception as ex:
                    log("!! photometrie non appliquee (%s) : on continue" % ex)
                tentatives.append(("angles_seuls", sans_opt))'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "patch_pano_photometrie.py" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if ANCIEN not in src:
        print("ECHEC : la cascade attendue est introuvable — RIEN modifie.")
        print("        (patch_pano_repli.py et patch_pano_ordre.py doivent etre appliques)")
        return 1

    out = src.replace(ANCIEN, NOUVEAU, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_photometrie")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : correction photometrique retablie sur la voie « angles ».")
    print("Sauvegarde : cine_panorama_stitch.py.avant_photometrie")
    return 0


if __name__ == "__main__":
    sys.exit(main())
