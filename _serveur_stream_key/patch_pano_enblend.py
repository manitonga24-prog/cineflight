# -*- coding: utf-8 -*-
"""
patch_pano_enblend.py — 2e passe du correctif « sphère complète » (2026-07-27).

CE QUE LA 1re PASSE A MANQUÉ. `patch_pano_sphere.py` impose bien la projection à
`pano_modify` (`--fov=360x180 --canvas=8192x4096`), et pourtant la sortie mesurée est
**8192×2321**. Raison : `enblend` ne connaît pas le canevas du projet. Il compose les
couches remappées et produit une image de la taille de LEUR UNION — donc recadrée au
contenu, ce qui ramène exactement le défaut qu'on venait de corriger.

→ `enblend -f LARGEURxHAUTEUR` impose la taille de sortie. Les couches gardent leurs
décalages, donc chacune se retrouve à la bonne HAUTEUR dans la sphère : le zénith non
photographié reste vide, et `_remplir_ciel` le comble ensuite.

LEÇON, à retenir pour la suite : forcer une étape d'une chaîne ne force pas les suivantes.
Ici on avait vérifié la commande émise (elle était juste) au lieu de vérifier le RÉSULTAT
(la taille du fichier). Seule la mesure du produit fini a montré l'écart.

IDEMPOTENT, sauvegarde `.avant_enblend`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_enblend.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")

ANCIEN = '        _run(["enblend", "--compression=LZW", "-o", pano_tif] + couches, log)'

NOUVEAU = '''        # CANEVAS COMPLET AU MONTAGE (correctif 2026-07-27, 2e passe).
        # enblend produit sinon une image de la taille de l'UNION des couches : la sortie
        # tombait en 8192x2321 malgre un canevas de projet en 8192x4096. `-f LxH` impose
        # la taille ; les decalages des couches les placent a la bonne hauteur.
        _run(["enblend", "--compression=LZW", "-f", "%dx%d" % (_larg, _haut),
              "-o", pano_tif] + couches, log)'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "CANEVAS COMPLET AU MONTAGE" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0

    # Dependance explicite : _larg/_haut viennent de la 1re passe. Sans elle, le patch
    # produirait un NameError a l'execution — donc on refuse plutot que de le laisser
    # exploser au premier panorama.
    if "_larg = min(int(largeur_max), 8192)" not in src:
        print("ECHEC : patch_pano_sphere.py n'a pas ete applique (variables _larg/_haut"
              " absentes) — RIEN modifie. Applique-le d'abord.")
        return 1

    if ANCIEN not in src:
        print("ECHEC : la ligne enblend attendue est introuvable — RIEN modifie.")
        return 1

    out = src.replace(ANCIEN, NOUVEAU, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_enblend")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : enblend produit desormais le canevas COMPLET (2:1).")
    print("Sauvegarde : cine_panorama_stitch.py.avant_enblend")
    print("VERIFIE LE RESULTAT, pas la commande : la sortie doit etre 8192x4096.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
