# -*- coding: utf-8 -*-
"""
patch_pano_ordre.py — les positions du drone D'ABORD, l'optimiseur seulement en secours
(2026-07-27).

CE QUE LA MESURE A MONTRÉ. Deux panoramas d'une même paire stéréo, mêmes angles, assemblés
avec `autooptimiser -n` par-dessus les positions écrites :
  - l'un a échoué net (`enblend: excessive image overlap detected` — deux photos empilées) ;
  - l'autre a produit une géométrie FAUSSE : sol en pointe de tente, sphère fortement
    basculée. enblend n'a rien signalé, l'image est simplement inutilisable.
Le seul assemblage propre de la journée est celui passé par les positions SEULES.

POURQUOI. L'optimiseur ajuste les positions pour satisfaire les points de correspondance
trouvés par `cpfind`. Sur les rangées hautes, le ciel n'a aucune texture : les points y
sont rares et faux. Partant de positions justes, il les déplace pour coller à du bruit.

DÉCISION. Quand l'app fournit les angles, on les utilise TELS QUELS. Le cap vient de la
boussole du drone et l'inclinaison de la nacelle : ce sont des mesures d'instruments, plus
fiables ici qu'une optimisation sur des points douteux. L'optimisation reste dans la
cascade, mais APRÈS — comme secours, si les positions brutes échouaient.

⚠ CE QUE ÇA NE RÈGLE PAS : le nadir resté noir sur l'œil gauche, et les 53 % de ciel
comblé (le preset ne monte qu'à +30°). Deux sujets distincts.

IDEMPOTENT, sauvegarde `.avant_ordre`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_ordre.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")

ANCIEN = '''                try:
                    _run(["autooptimiser", "-n", "-o", avec_opt, avec_opt], log)
                    tentatives.append(("angles_optimises", avec_opt))
                except Exception as ex:
                    log("!! autooptimiser a echoue (%s) : on garde les positions brutes" % ex)
                # Positions du drone SEULES : boussole + nacelle, sans optimisation.
                # C'est le repli qui sauve les cas ou l'optimiseur empile des photos.
                tentatives.append(("angles_seuls", sans_opt))'''

NOUVEAU = '''                # POSITIONS DU DRONE D'ABORD (correctif 2026-07-27, voir
                # patch_pano_ordre.py). Mesure : sur deux panoramas de la meme paire,
                # `autooptimiser` a fait echouer l'un (photos empilees) et bascule l'autre
                # (sol en pointe de tente). Le ciel n'a pas de texture, donc les points de
                # correspondance des rangees hautes sont faux ; l'optimiseur deplace de
                # bonnes positions pour coller a du bruit. Boussole + nacelle sont des
                # mesures d'instruments : on leur fait confiance.
                tentatives.append(("angles_seuls", sans_opt))
                # L'optimisation reste disponible, mais en SECOURS seulement.
                try:
                    _run(["autooptimiser", "-n", "-o", avec_opt, avec_opt], log)
                    tentatives.append(("angles_optimises", avec_opt))
                except Exception as ex:
                    log("!! autooptimiser a echoue (%s) : sans consequence, il n'est"
                        " plus qu'un secours" % ex)'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "POSITIONS DU DRONE D'ABORD" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if ANCIEN not in src:
        print("ECHEC : le bloc de cascade attendu est introuvable — RIEN modifie.")
        print("        (patch_pano_repli.py doit avoir ete applique d'abord)")
        return 1

    out = src.replace(ANCIEN, NOUVEAU, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_ordre")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : ordre de la cascade -> angles_seuls, puis angles_optimises, puis auto.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_ordre")
    print("\nREASSEMBLER LES DEUX YEUX pour qu'ils partagent enfin le meme repere :")
    print("  nohup python3 reassembler_pano.py 0f578b6cfb3a df292049e3fd"
          " > /root/reassemblage.log 2>&1 &")
    return 0


if __name__ == "__main__":
    sys.exit(main())
