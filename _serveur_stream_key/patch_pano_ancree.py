# -*- coding: utf-8 -*-
"""
patch_pano_ancree.py — affiner les positions SANS pouvoir faire pivoter la sphère (2026-07-28).

LE DÉFAUT. La chaîne n'offrait qu'un choix binaire, et les deux termes sont mauvais :

    angles_seuls      les images sont placées d'après le cap et l'inclinaison rapportés
                      par le drone, sans aucun ajustement. La sphère est DROITE, mais à
                      1° près — et sur 8192 px de large, 1° fait déjà 23 pixels — les
                      images ne se superposent pas exactement. Constaté sur le panorama
                      `eca54f3db18e` : les sentiers se DÉDOUBLENT aux jointures.

    angles_optimises  l'optimiseur reprend tout et aligne parfaitement les images entre
                      elles, mais rien ne l'empêche de faire tourner l'ENSEMBLE. Mesuré
                      trois fois : sphère basculée, horizon enroulé au-dessus de la tête.

LA TROISIÈME VOIE, qui est la manière standard sous Hugin. On inscrit dans le projet la
liste des paramètres à optimiser — cap, inclinaison et roulis — pour TOUTES les images SAUF
la première. L'image d'ancrage devient immobile par construction : l'optimiseur corrige les
écarts des unes par rapport aux autres, sans aucun moyen de faire pivoter l'ensemble.

On obtient l'horizon droit ET les jointures continues, au lieu de choisir entre les deux.

⚠ CE QUE ÇA NE CORRIGE PAS : les écarts d'EXPOSITION. Ils viennent de la prise de vue —
2,48 EV mesurés sur ce même panorama, en oscillation avec l'azimut, signature d'une
exposition restée automatique. C'est un défaut de l'app, traité séparément.

⚠ LA NOUVELLE VOIE PASSE EN TÊTE, `angles_seuls` reste juste derrière. Si l'optimisation
échoue — points d'appui insuffisants, images empilées — on retombe sur le placement brut,
qui a au moins le mérite d'être droit.

IDEMPOTENT, sauvegarde `cine_panorama_stitch.py.avant_ancree`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_ancree.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "cine_panorama_stitch.py")

# ── 1. La fonction, insérée avant `_tenter_montage` ──────────────────────────────
ANCRE_FONCTION = "def _tenter_montage(pto_src, work, out_tif, larg, haut, etiquette, log):"

FONCTION = '''def _ancrer_et_optimiser(pto, log):
    """
    Optimise les positions en LAISSANT L'IMAGE 0 IMMOBILE. True si l'optimisation a tourne.

    Le format .pto porte des lignes `v` qui enumerent les parametres a optimiser. Une image
    dont le cap, l'inclinaison et le roulis n'y figurent PAS est laissee telle quelle par
    l'optimiseur : c'est l'ancrage. On ecrit donc `v yN pN rN` pour toutes les images sauf
    la premiere, puis on lance `autooptimiser -n`, qui n'optimise que ce qui est declare.

    ⚠ `-n` ET RIEN D'AUTRE. `-a` reoptimise tout, `-l` remet l'horizon a plat et `-s`
    recalcule le cadrage : chacun peut faire pivoter la sphere, ce qu'on cherche justement
    a empecher. C'est la combinaison `-a -m -l -s` qui avait produit l'horizon en tente.
    """
    try:
        with open(pto, encoding="utf-8", errors="replace") as f:
            lignes = f.read().splitlines()
    except Exception as ex:
        log("!! voie ancree : lecture du projet impossible (%s)" % ex)
        return False

    # On repart de zero cote variables d'optimisation : celles qui traineraient
    # decideraient a notre place de ce qui bouge.
    gardees = [l for l in lignes if not (l == "v" or l.startswith("v "))]
    indices = [i for i, l in enumerate(gardees) if l.startswith("i ")]
    if len(indices) < 3:
        log("!! voie ancree : %d images seulement, optimisation sans objet" % len(indices))
        return False

    # Une ligne par image, sauf la premiere. Hugin ecrit lui-meme sous cette forme.
    variables = ["v y%d p%d r%d" % (n, n, n) for n in range(1, len(indices))]
    apres = indices[-1] + 1
    nouvelles = gardees[:apres] + variables + gardees[apres:]
    try:
        with open(pto, "w", encoding="utf-8") as f:
            f.write("\\n".join(nouvelles) + "\\n")
    except Exception as ex:
        log("!! voie ancree : ecriture du projet impossible (%s)" % ex)
        return False

    log("voie ancree : %d images libres, image 0 VERROUILLEE (sphere non pivotable)"
        % (len(indices) - 1))
    try:
        _run(["autooptimiser", "-n", "-o", pto, pto], log)
        return True
    except Exception as ex:
        log("!! voie ancree : autooptimiser a echoue (%s) — on garde le placement brut"
            % str(ex).split(chr(10))[0])
        return False


'''

# ── 2. La voie, insérée AVANT `angles_seuls` pour être essayée en premier ────────
ANCRE_VOIE = '                tentatives.append(("angles_seuls", sans_opt))'

VOIE = '''                # ── VOIE ANCREE (2026-07-28), essayee EN PREMIER ──────────────
                # Elle part de `sans_opt`, qui porte deja les positions du drone ET la
                # photometrie egalisee, et n'y ajoute qu'un affinage des positions
                # relatives — image 0 verrouillee, donc sphere non pivotable.
                # Elle vise le defaut mesure sur `eca54f3db18e` : sentiers dedoubles aux
                # jointures, faute d'ajustement fin entre images voisines.
                affines = os.path.join(work, "angles_affines.pto")
                shutil.copyfile(sans_opt, affines)
                if _ancrer_et_optimiser(affines, log):
                    tentatives.append(("angles_affines", affines))
                tentatives.append(("angles_seuls", sans_opt))'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "_ancrer_et_optimiser" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    manque = []
    if ANCRE_FONCTION not in src:
        manque.append("la definition de _tenter_montage")
    if ANCRE_VOIE not in src:
        manque.append("l'ajout de la voie angles_seuls")
    if manque:
        print("ECHEC : introuvable — %s." % ", ".join(manque))
        print("        Le fichier differe de ce qui etait prevu, RIEN modifie.")
        return 1
    if src.count(ANCRE_VOIE) != 1:
        print("ECHEC : %d occurrences de l'ancre de voie, une seule attendue."
              % src.count(ANCRE_VOIE))
        return 1

    out = src.replace(ANCRE_FONCTION, FONCTION + ANCRE_FONCTION, 1)
    out = out.replace(ANCRE_VOIE, VOIE, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_ancree")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : voie ancree ajoutee, essayee avant angles_seuls.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_ancree")
    print()
    print("L'atelier telecharge ce script a chaque travail : aucun redemarrage.")
    print("ATTENDU dans la sortie de l'atelier :")
    print("  'voie ancree : 60 images libres, image 0 VERROUILLEE'")
    print("  'VOIE D ASSEMBLAGE : angles_affines'")
    print("  -> horizon droit ET sentiers continus")
    print("Si l'optimisation echoue, on retombe sur angles_seuls : droit, mais decale.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
