# -*- coding: utf-8 -*-
"""
patch_pano_ancree_rang.py — la voie ancrée redescend derrière `angles_seuls` (2026-07-28).

CE QUE LA MESURE A DIT. `patch_pano_ancree.py` plaçait la voie ancrée en tête, au motif
qu'elle devait donner « l'horizon droit ET les jointures continues ». Éprouvée sur le
panorama `eca54f3db18e`, mêmes photos, même préréglage :

    angles_seuls     ciel comblé  **9,0 %**   sphère droite, sentiers dédoublés
    angles_affines   ciel comblé  **27,8 %**  sphère droite, mais couverture effondrée

À l'écran, le tiers supérieur est un aplat gris : plus aucune photographie, du remplissage
pur. L'ancrage a bien empêché la sphère de pivoter — c'était son objet — mais il a laissé
l'optimiseur DISPERSER les images les unes par rapport aux autres.

POURQUOI, VRAISEMBLABLEMENT — et ce n'est PAS vérifié. J'ai libéré trois paramètres par
image : cap, inclinaison et roulis. Or seul le CAP est réellement bruité : il vient de la
boussole. L'inclinaison et le roulis viennent de la NACELLE, qui est mécaniquement précise.
Les libérer laisse les images dériver verticalement, ce qui creuse des trous que rien ne
vient combler. Une variante n'optimisant QUE le lacet reste à éprouver.

CE QUE FAIT CE CORRECTIF. `angles_seuls` repasse en tête — c'est la voie MESURÉE la
meilleure. La voie ancrée reste en second : si le placement brut échoue un jour, elle vaut
mieux que `angles_optimises`, qui fait pivoter la sphère. On ne supprime pas un outil
qu'on vient de construire ; on le remet à son rang, celui que la mesure lui donne.

Ordre résultant : angles_seuls -> angles_affines -> angles_optimises -> auto.

IDEMPOTENT, sauvegarde `cine_panorama_stitch.py.avant_rang`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_ancree_rang.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "cine_panorama_stitch.py")

AVANT = '''                affines = os.path.join(work, "angles_affines.pto")
                shutil.copyfile(sans_opt, affines)
                if _ancrer_et_optimiser(affines, log):
                    tentatives.append(("angles_affines", affines))
                tentatives.append(("angles_seuls", sans_opt))'''

APRES = '''                # ⚠ ORDRE ETABLI PAR LA MESURE, PAS PAR LE RAISONNEMENT (2026-07-28).
                # Sur le meme jeu de 61 photos : angles_seuls -> 9,0 % de ciel comble ;
                # angles_affines -> 27,8 %, avec le tiers superieur en aplat gris.
                # L'ancrage empeche bien la sphere de pivoter, mais laisse l'optimiseur
                # disperser les images. Le placement brut passe donc en premier.
                tentatives.append(("angles_seuls", sans_opt))
                # La voie ancree reste en SECOND : si le placement brut echoue un jour,
                # elle vaut mieux que `angles_optimises`, qui fait pivoter la sphere.
                affines = os.path.join(work, "angles_affines.pto")
                shutil.copyfile(sans_opt, affines)
                if _ancrer_et_optimiser(affines, log):
                    tentatives.append(("angles_affines", affines))'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "ORDRE ETABLI PAR LA MESURE" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if AVANT not in src:
        print("ECHEC : le bloc attendu est introuvable.")
        print("        Applique d'abord patch_pano_ancree.py, ou le fichier differe")
        print("        de ce qui etait prevu. RIEN modifie.")
        return 1

    out = src.replace(AVANT, APRES, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_rang")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : angles_seuls repasse en tete, angles_affines en second.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_rang")
    print()
    print("L'atelier telecharge ce script a chaque travail : aucun redemarrage.")
    print("Pour rejouer le panorama :")
    print("  rm /root/cineflight_web/_pano_jobs/eca54f3db18e/panorama.jpg")
    print("On doit retrouver la voie angles_seuls et 9,0 % de ciel comble.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
