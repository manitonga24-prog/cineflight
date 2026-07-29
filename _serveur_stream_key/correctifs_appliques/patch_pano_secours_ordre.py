# -*- coding: utf-8 -*-
"""
patch_pano_secours_ordre.py — les options de secours étaient inertes (2026-07-28).

LE DÉFAUT, DANS MON PROPRE CORRECTIF. `patch_pano_enblend_secours.py` construisait les
paliers ainsi :

    secours = [ list(cmd) + ["--no-optimize"], ... ]

Or `cmd` se termine par les SOIXANTE-ET-UN NOMS DE FICHIERS d'entrée. L'option était donc
ajoutée APRÈS eux :

    enblend --wrap=horizontal --compression=LZW -f 8192x4096 -o pano.tif \\
            remap0000.tif ... remap0060.tif --no-optimize

`enblend` ne l'applique pas à ce moment-là : la génération des masques est déjà configurée.
**Les deux paliers rejouaient donc la commande d'origine.** Ils ne pouvaient pas réussir,
et leur échec a fait abandonner une géométrie JUSTE au profit d'une sphère basculée —
trois fois de suite, une heure de calcul à chaque fois.

CE QUI LE PROUVE, MESURÉ SUR LES COUCHES DE PRODUCTION (`_atelier/diag_enblend.py`) :

    commande d'origine                  -> plantage 0xC0000005
    --wrap=horizontal                   -> plantage
    --no-optimize                       -> **OK, 69 Mo**
    --wrap=horizontal --no-optimize     -> **OK, 69 Mo**

`--no-optimize` fonctionne — quand il est réellement transmis. Et `--wrap` n'est ni la
cause ni un obstacle : il reste, parce qu'il est le réglage juste pour un équirectangulaire.

CE QUE FAIT CE CORRECTIF. Les options s'insèrent APRÈS L'EXÉCUTABLE, avant tout le reste,
et l'étiquette affichée au journal décrit ce qui a réellement été ajouté.

⚠ LEÇON. J'avais vérifié dans un bac à sable que les options étaient bien placées — mais
pour la COMMANDE DE BASE, pas pour les variantes construites par le correctif. Vérifier le
cas qu'on a en tête et conclure pour tous les autres : c'est la troisième fois aujourd'hui.

IDEMPOTENT, sauvegarde `cine_panorama_stitch.py.avant_ordre`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_secours_ordre.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "cine_panorama_stitch.py")

AVANT = '''        if "enblend" in os.path.basename(str(cmd[0])).lower():
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

APRES = '''        # ⚠ LES OPTIONS S'INSERENT APRES L'EXECUTABLE, JAMAIS A LA FIN.
        # `cmd` se termine par les noms de fichiers d'entree ; ajouter une option apres
        # eux revient a ne pas l'appliquer — la generation des masques est deja
        # configuree. C'etait le defaut du 2026-07-28 : les deux paliers rejouaient la
        # commande d'origine, echouaient forcement, et faisaient abandonner une geometrie
        # juste au profit d'une sphere basculee.
        # MESURE sur les couches de production : `--no-optimize` reellement transmis
        # produit les 61 couches fusionnees (69 Mo), la ou la commande d'origine plante.
        if "enblend" in os.path.basename(str(cmd[0])).lower():
            secours = [
                (["--no-optimize"], [cmd[0], "--no-optimize"] + list(cmd[1:])),
                (["--no-optimize", "--primary-seam-generator=nearest-feature-transform"],
                 [cmd[0], "--no-optimize",
                  "--primary-seam-generator=nearest-feature-transform"] + list(cmd[1:])),
            ]
        else:
            secours = [([], list(cmd))]
        for options, variante in secours:
            ajout = " ".join(options) or "meme commande"
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

    if "for options, variante in secours:" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if AVANT not in src:
        print("ECHEC : le bloc de secours attendu est introuvable.")
        print("        Applique d'abord patch_pano_enblend_secours.py, ou le fichier")
        print("        differe de ce qui etait prevu. RIEN modifie.")
        return 1

    out = src.replace(AVANT, APRES, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_ordre")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : les options de secours sont desormais reellement transmises.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_ordre")
    print()
    print("L'atelier telecharge ce script a chaque travail : aucun redemarrage.")
    print("ATTENDU cette fois :")
    print("  '!! enblend a PLANTE ... nouvelle tentative : --no-optimize'")
    print("  '   abouti avec : --no-optimize'")
    print("  'VOIE D ASSEMBLAGE : angles_seuls'")
    print("  aucun 'REPLI D ASSEMBLAGE'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
