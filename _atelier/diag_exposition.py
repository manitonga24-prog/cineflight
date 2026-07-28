# -*- coding: utf-8 -*-
"""
diag_exposition.py — d'où viennent les blocs de couleur ? (2026-07-28)

LE SYMPTÔME. Le panorama `eca54f3db18e` est géométriquement juste mais montre des
RECTANGLES d'exposition différente : chaque photo apparaît en bloc. Sans optimisation des
lignes de couture — nécessaire pour qu'`enblend` ne se plante pas sur la couche du pôle —
la jointure passe en ligne droite, et le fondu multibande ne masque que de PETITS écarts.

DEUX CAUSES POSSIBLES, remèdes opposés :
  A. l'exposition a varié À LA PRISE DE VUE  -> le verrou de la caméra n'a pas tenu ;
     aucune option d'assemblage ne le rattrapera, c'est au VOL qu'il faut corriger ;
  B. l'exposition était stable et c'est la correction photométrique qui échoue -> le
     remède est dans la chaîne d'assemblage.

⚠ POURQUOI PAS L'EXIF. Première version de cet outil : lire le temps de pose des photos.
Elle ne pouvait pas marcher — l'app RÉDUIT les photos à 3500 px avant de les envoyer, en
les réencodant, ce qui efface les métadonnées. Le fichier que l'atelier reçoit n'a plus
d'exposition inscrite. Encore un instrument qui mesurait autre chose que ce qu'on croyait.

CE QU'ON LIT À LA PLACE. Après `autooptimiser -m`, le fichier de projet Hugin porte, pour
chaque image, le paramètre `Eev` — l'exposition ESTIMÉE, en valeurs d'exposition. C'est une
meilleure mesure que l'EXIF : elle décrit ce que Hugin a réellement dû corriger pour
égaliser les images entre elles.

Usage :
    python diag_exposition.py <dossier_du_travail>

Il faut que `_diag\\angles_seuls.pto` existe — c'est-à-dire avoir lancé `diag_enblend.py`
sans `--rapide` au préalable.
"""

import glob
import os
import re
import sys


def lire_eev(pto):
    """[(nom d'image, Eev)] lus dans le fichier de projet Hugin."""
    res = []
    with open(pto, encoding="utf-8", errors="replace") as f:
        for ligne in f:
            if not ligne.startswith("i "):
                continue
            m = re.search(r"\bEev([-+0-9.eE]+)", ligne)
            n = re.search(r'\bn"([^"]+)"', ligne)
            if m:
                try:
                    res.append((os.path.basename(n.group(1)) if n else "?",
                                float(m.group(1))))
                except ValueError:
                    pass
    return res


def main(args):
    if not args:
        print(__doc__)
        return 1
    dossier = args[0].rstrip("\\/")
    candidats = [os.path.join(dossier, "_diag", "angles_seuls.pto")]
    candidats += sorted(glob.glob(os.path.join(dossier, "_diag", "*.pto")))
    pto = next((c for c in candidats if os.path.isfile(c)), None)
    if not pto:
        print("Aucun fichier .pto dans %s\\_diag" % dossier)
        print("Lance d'abord :  python diag_enblend.py %s" % dossier)
        return 1
    print("Projet lu : %s\n" % pto)

    valeurs = lire_eev(pto)
    if not valeurs:
        print("Aucun parametre Eev : `autooptimiser -m` n'a pas tourne sur ce projet.")
        print("Relance diag_enblend.py SANS --rapide.")
        return 1

    evs = [v for _, v in valeurs]
    moy = sum(evs) / len(evs)
    print("%-24s %8s %8s" % ("image", "Eev", "ecart"))
    for n, v in valeurs:
        marque = "  <<<" if abs(v - moy) > 0.5 else ""
        print("%-24s %8.3f %8.3f%s" % (n, v, v - moy, marque))

    ecart = max(evs) - min(evs)
    print("\n--- verdict ---")
    print("images                 : %d" % len(valeurs))
    print("Eev le plus bas        : %.3f" % min(evs))
    print("Eev le plus haut       : %.3f" % max(evs))
    print("ECART TOTAL            : %.2f EV" % ecart)
    # ⚠ SEUIL ISSU DE LA DOCUMENTATION HUGIN, pas d'une intuition : au-dela d'environ
    # 3 EV le melange des jointures ne suffit plus. On est ici bien plus severe, parce
    # que la couture n'est PAS optimisee et ne peut donc pas contourner les ecarts.
    print()
    if ecart < 0.3:
        print("EXPOSITION STABLE a la prise de vue. Le verrou a tenu.")
        print("Les blocs visibles ne viennent PAS du vol mais de l'assemblage : c'est la")
        print("couture non optimisee qui les revele. Remede du cote du script.")
    elif ecart < 1.0:
        print("VARIATION LEGERE. Le verrou a globalement tenu. Une couture optimisee la")
        print("masquerait sans peine ; c'est son absence qui la rend visible.")
    else:
        print("VARIATION FRANCHE : l'exposition a change PENDANT la capture.")
        print("C'est la cause principale des blocs. Aucune option d'assemblage ne la")
        print("rattrapera entierement — a corriger AU VOL (verrou d'exposition).")
        print("Les images marquees <<< sont les plus ecartees de la moyenne.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
