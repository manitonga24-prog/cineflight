# -*- coding: utf-8 -*-
"""
patch_pano_wrap.py — dire à enblend que la sphère se referme (2026-07-28).

CE QUI MANQUAIT. La commande de fusion était :

    enblend --compression=LZW -f 8192x4096 -o pano.tif <61 couches>

Aucune option `--wrap`. Or sur un équirectangulaire 360°, **le bord gauche et le bord droit
sont le même méridien**. Sans le lui dire, `enblend` traite le raccord comme un bord
d'image : il y calcule des lignes de couture qui pointent hors du cadre.

COMMENT ON L'A TROUVÉ — et pourquoi trois hypothèses ont échoué avant.

    1. « le plantage est passager »        → RÉFUTÉ : rejouer la commande replante
                                              à l'identique.
    2. « c'est l'optimisation de couture » → RÉFUTÉ : `--no-optimize` seul echoue aussi.
    3. « c'est une couche dégénérée »      → RÉFUTÉ : la plus petite fait 1,16 Mo.

    La BISSECTION a tranché là où les suppositions tournaient en rond
    (`_atelier/diag_enblend.py`, 7 exécutions au lieu de 61) :

        60 premières couches -> OK
        + la 61ᵉ             -> PLANTAGE

    La coupable est `remap0060.tif`, **8192 × 683** : la largeur ENTIÈRE du canevas. C'est
    la signature d'une couche AU PÔLE — au zénith, une seule photo s'étale sur les 360° de
    longitude. C'est la prise que le préréglage « ciel complet » ajoute en dernier.

    Avec `--wrap=horizontal`, `enblend` cesse de planter en silence et AVERTIT :
    `seam-line end point outside of cost-image`. La couture sortait bien du cadre.

⚠ LA CAUSE EST UNE CONJONCTION. `--wrap` seul ne suffit pas (mesuré : sortie de 8 octets),
`--no-optimize` seul non plus. **Les deux ensemble produisent les 61 couches fusionnées**
— 69 Mo, 8192×4096, vérifié.

CE QUE FAIT CE CORRECTIF. Il ajoute `--wrap=horizontal` à la commande de BASE, toujours.
Les paliers de secours déjà en place (`patch_pano_enblend_secours.py`) ajouteront
`--no-optimize` si la fusion plante encore — ce qui sera le cas sur un jeu comportant une
couche au pôle, et seulement sur celui-là.

⚠ CE N'EST PAS QU'UN CONTOURNEMENT DE PLANTAGE. `--wrap` est le réglage JUSTE pour un
équirectangulaire. Tous les panoramas assemblés jusqu'ici — même ceux qui n'ont jamais
planté — avaient leur raccord 0°/360° mélangé comme un bord d'image. Invisible sur un ciel
uni, visible sur une ligne d'horizon nette ou une façade qui traverse le raccord.

IDEMPOTENT, sauvegarde `cine_panorama_stitch.py.avant_wrap`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_wrap.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "cine_panorama_stitch.py")

# Ancre sur UNE SEULE ligne : l'indentation de la continuation ne peut pas etre devinee
# de facon fiable, et une ancre multiligne fausse ferait echouer le correctif.
AVANT = '["enblend", "--compression=LZW", "-f", "%dx%d" % (larg, haut),'
APRES = ('["enblend", "--wrap=horizontal", "--compression=LZW", '
         '"-f", "%dx%d" % (larg, haut),')


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "--wrap=horizontal" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if AVANT not in src:
        print("ECHEC : la commande enblend attendue est introuvable.")
        print("        Le fichier differe de ce qui etait prevu, RIEN modifie.")
        return 1
    if src.count(AVANT) != 1:
        print("ECHEC : %d occurrences de la commande enblend, une seule attendue."
              % src.count(AVANT))
        return 1

    out = src.replace(AVANT, APRES, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_wrap")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : --wrap=horizontal ajoute a la fusion.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_wrap")
    print()
    print("L'atelier telecharge ce script a chaque travail : aucun redemarrage.")
    print("Pour rejouer le panorama fautif :")
    print("  rm /root/cineflight_web/_pano_jobs/eca54f3db18e/panorama.jpg")
    print()
    print("ATTENDU dans la sortie de l'atelier :")
    print("  '!! enblend a PLANTE ... nouvelle tentative : --no-optimize'")
    print("  '   abouti avec : --no-optimize'")
    print("  'VOIE D'ASSEMBLAGE : angles_seuls'   <- la geometrie stricte est sauvee")
    print("  'Fill sky' proche de 9 %, comme l'oeil jumeau 277a3c293f89")
    return 0


if __name__ == "__main__":
    sys.exit(main())
