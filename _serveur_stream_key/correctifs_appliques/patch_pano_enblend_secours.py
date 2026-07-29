# -*- coding: utf-8 -*-
"""
patch_pano_enblend_secours.py — garder la géométrie, simplifier la couture (2026-07-28).

CE QUE LA MESURE A APPRIS. `patch_pano_reessai.py` rejouait la commande à l'identique après
un plantage. Éprouvé sur le travail `eca54f3db18e` : **la seconde tentative a replanté au
même endroit**, code `0xC0000005`. Le plantage d'`enblend` n'est donc pas passager sur ce
jeu de couches — il est reproductible. Rejouer sans rien changer ne sert à rien.

⚠ ET LA CONSÉQUENCE ÉTAIT GRAVE. La voie stricte étant abandonnée, le repli
`angles_optimises` reprenait la main et `autooptimiser -n` faisait pivoter la sphère :
34,9 % de ciel comblé contre 9,0 % sur l'œil jumeau du même vol. On perdait une géométrie
JUSTE à cause d'un outil de FUSION qui tombe.

CE QUE CE CORRECTIF FAIT. Sur un plantage d'`enblend`, on ne change ni les positions ni la
voie : on refait la même fusion avec une couture plus simple, par paliers.

    1. `--no-optimize`
       Désactive l'optimisation des lignes de couture — recuit simulé et Dijkstra. C'est
       le site de plantage le plus courant d'enblend, et ce qu'on perd est cosmétique :
       des jointures un peu moins bien placées, pas une géométrie fausse.

    2. `--no-optimize --primary-seam-generator=nearest-feature-transform`
       Remplace en plus le générateur de couture principal par l'algorithme historique,
       plus simple que la coupe de graphe employée par défaut.

Ce n'est qu'après ces deux paliers que la voie est abandonnée. Le principe : **une couture
imparfaite vaut infiniment mieux qu'une sphère basculée**, parce que la première se voit à
peine et que la seconde est inutilisable — surtout sur une paire stéréo, où deux yeux
assemblés différemment ne fusionnent pas.

⚠ CE CORRECTIF PEUT NE PAS SUFFIRE. Un plantage d'enblend peut aussi venir d'une couche
dégénérée — une image remappée sur une surface nulle au pôle, par exemple. Si les deux
paliers échouent, la ligne `REPLI D'ASSEMBLAGE` le dira, et il faudra chercher du côté des
couches produites par `nona`, pas du côté de la fusion.

⚠ REQUIERT `patch_pano_reessai.py` : ce correctif remplace le bloc de seconde tentative
qu'il a introduit.

IDEMPOTENT, sauvegarde `cine_panorama_stitch.py.avant_secours`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_enblend_secours.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "cine_panorama_stitch.py")

AVANT = '''        log("!! %s a PLANTE (code %d) — seconde tentative" % (cmd[0], r.returncode))
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0:
            log("   la seconde tentative a abouti")'''

APRES = '''        # MESURE DU 2026-07-28 : rejouer la MEME commande a replante a l'identique.
        # Le plantage est reproductible, pas passager. On garde donc la geometrie — les
        # positions du drone sont justes — et on simplifie la COUTURE par paliers.
        # Une jointure moins bien placee ne se voit presque pas ; une sphere basculee est
        # inutilisable, et sur une paire stereo elle ruine les deux yeux.
        if "enblend" in os.path.basename(str(cmd[0])).lower():
            secours = [
                list(cmd) + ["--no-optimize"],
                list(cmd) + ["--no-optimize",
                             "--primary-seam-generator=nearest-feature-transform"],
            ]
        else:
            secours = [list(cmd)]
        for variante in secours:
            ajout = " ".join(str(x) for x in variante[len(cmd):]) or "meme commande"
            log("!! %s a PLANTE (code %d) — nouvelle tentative : %s"
                % (cmd[0], r.returncode, ajout))
            r = subprocess.run(variante, capture_output=True, text=True)
            if r.returncode == 0:
                log("   abouti avec : %s" % ajout)
                break
            if not _est_plantage(r.returncode):
                # L'outil JUGE et refuse : les paliers suivants n'y changeraient rien.
                break'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "primary-seam-generator" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if "_est_plantage" not in src:
        print("ECHEC : applique d'abord patch_pano_reessai.py — RIEN modifie."); return 1
    if AVANT not in src:
        print("ECHEC : le bloc de seconde tentative est introuvable.")
        print("        Le fichier differe de ce qui etait prevu, RIEN modifie.")
        return 1

    out = src.replace(AVANT, APRES, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_secours")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : couture simplifiee par paliers avant d'abandonner la geometrie.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_secours")
    print()
    print("L'atelier telecharge ce script a chaque travail : aucun redemarrage.")
    print("Pour rejouer le panorama fautif :")
    print("  rm /root/cineflight_web/_pano_jobs/eca54f3db18e/panorama.jpg")
    print()
    print("A SURVEILLER dans la sortie de l'atelier :")
    print("  '   abouti avec : --no-optimize'      -> la geometrie stricte est sauvee")
    print("  '!! REPLI D'ASSEMBLAGE'               -> les deux paliers ont echoue,")
    print("                                          chercher du cote des couches nona")
    return 0


if __name__ == "__main__":
    sys.exit(main())
