# -*- coding: utf-8 -*-
"""
diag_enblend.py — trouver la couche qui fait planter enblend (2026-07-28).

CE QU'ON SAIT DÉJÀ, PAR LA MESURE
    - La géométrie stricte (`angles_seuls`) est JUSTE : l'œil jumeau du même vol donne
      9,0 % de ciel comblé et un horizon correct.
    - `enblend` se plante dessus de façon REPRODUCTIBLE (`0xC0000005`), trois fois de suite.
    - Ce n'est PAS passager : rejouer la même commande replante à l'identique.
    - Ce n'est PAS la couture : `--no-optimize` et
      `--primary-seam-generator=nearest-feature-transform` échouent aussi.
    - Un plantage natif ne laisse ni sortie ni message : l'outil ne dira rien de plus.

HYPOTHÈSE À ÉPROUVER ICI : une ou plusieurs couches produites par `nona` sont dégénérées —
typiquement une image du haut projetée sur une surface quasi nulle au pôle. `enblend` les
avale jusqu'à ce que l'une d'elles le tue.

MÉTHODE. On rejoue la chaîne jusqu'aux TIFF remappés, dans un dossier PERSISTANT, puis on
cherche par bissection le plus petit préfixe de couches qui provoque le plantage. Environ
six exécutions d'`enblend` suffisent pour 61 couches, au lieu de soixante-et-une.

⚠ ON NE CONCLUT PAS SUR UNE SEULE EXÉCUTION. Le plantage doit être confirmé sur le jeu
complet AVANT de chercher un coupable — sinon on bissecterait un hasard.

Usage :
    python diag_enblend.py <dossier_du_travail>            # chaîne complète
    python diag_enblend.py <dossier_du_travail> --rapide   # saute cpfind (~15 min de moins)

Le dossier est celui de l'atelier, par exemple :
    C:\\Users\\Admin\\CineFlight_Atelier\\pano_eca54f3db18e
Il doit contenir `in\\` et `cine_panorama_stitch.py`.
"""

import glob
import json
import os
import shutil
import subprocess
import sys
import time

LARGEUR, HAUTEUR = 8192, 4096


def log(m):
    print(m, flush=True)


def run(cmd, tolere_echec=False):
    """Rend (code, secondes). N'affiche que l'essentiel : les lignes de commande de
    Hugin font des milliers de caractères et noient la sortie."""
    court = [os.path.basename(str(cmd[0]))] + [
        str(x) for x in cmd[1:] if not str(x).lower().endswith((".tif", ".jpg"))]
    log("  $ %s  (+%d fichiers)" % (" ".join(court),
                                    sum(1 for x in cmd if str(x).lower().endswith((".tif", ".jpg")))))
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    d = time.time() - t0
    if r.returncode != 0 and not tolere_echec:
        log("  ECHEC code %d apres %.0f s" % (r.returncode, d))
        if r.stderr.strip():
            log("  stderr : %s" % r.stderr.strip()[-800:])
        raise SystemExit(1)
    return r.returncode, d


def bin_hugin():
    perso = os.environ.get("CINE_HUGIN", "").strip('"')
    if perso and os.path.isfile(os.path.join(perso, "enblend.exe")):
        return perso
    for base in (r"C:\Program Files", r"C:\Program Files (x86)"):
        if not os.path.isdir(base):
            continue
        for nom in os.listdir(base):
            if nom.lower().startswith("hugin"):
                b = os.path.join(base, nom, "bin")
                if os.path.isfile(os.path.join(b, "enblend.exe")):
                    return b
    raise SystemExit("Hugin introuvable. Definis CINE_HUGIN.")


def preparer(travail, work, rapide):
    """Rejoue la chaîne jusqu'aux couches remappées. Rend la liste des TIFF."""
    photos = sorted(glob.glob(os.path.join(travail, "in", "*")))
    if len(photos) < 4:
        raise SystemExit("Moins de 4 photos dans %s\\in" % travail)
    log("Photos : %d" % len(photos))

    base = os.path.join(work, "base.pto")
    run(["pto_gen", "-o", base] + photos)
    if rapide:
        log("  (cpfind saute : mode rapide)")
    else:
        run(["cpfind", "--multirow", "--celeste", "-o", base, base])
        run(["cpclean", "-o", base, base])

    # POSITIONS : on emploie la FONCTION DU SERVEUR, pas une réimplémentation. Deux
    # écritures de positions divergeraient, et on chercherait alors un défaut qui
    # n'existe que dans l'outil de diagnostic.
    sys.path.insert(0, travail)
    import cine_panorama_stitch as stitch
    fa = os.path.join(travail, "angles.json")
    if not os.path.exists(fa):
        raise SystemExit("angles.json absent : ce diagnostic porte sur la voie angles_seuls.")
    angles = json.load(open(fa))
    log("Angles : %d entrees" % len(angles))
    pto = os.path.join(work, "angles_seuls.pto")
    shutil.copyfile(base, pto)
    if not stitch._ecrire_positions(pto, angles, log):
        raise SystemExit("l'ecriture des positions a echoue")

    # ⚠ PHOTOMÉTRIE — ÉTAPE OUBLIÉE AU PREMIER JET, ET C'ÉTAIT LE DÉFAUT DE L'INSTRUMENT.
    # La production applique `autooptimiser -m` avant de remapper. Elle modifie les
    # paramètres d'exposition de chaque image, donc les couches que produit `nona`.
    # Sans elle, le diagnostic fusionnait des couches DIFFÉRENTES de celles qui plantent :
    # mon essai manuel « réussi » ne portait pas sur les mêmes données que l'atelier.
    # Un instrument qui ne reproduit pas la chaîne réelle mesure autre chose.
    if rapide:
        log("  ⚠ autooptimiser -m saute (mode rapide) : les couches NE SERONT PAS")
        log("    celles de la production. A n'employer que pour degrossir.")
    else:
        run(["autooptimiser", "-m", "-o", pto, pto])

    run(["pano_modify", "--projection=2", "--fov=360x180",
         "--canvas=%dx%d" % (LARGEUR, HAUTEUR),
         "--crop=0,%d,0,%d" % (LARGEUR, HAUTEUR), "-o", pto, pto])
    prefixe = os.path.join(work, "remap")
    run(["nona", "-m", "TIFF_m", "-o", prefixe, pto])
    couches = sorted(glob.glob(prefixe + "*.tif"))
    log("Couches produites : %d" % len(couches))
    return couches


def essai_enblend(couches, work, etiquette, options=()):
    """
    Rend True si enblend ABOUTIT sur ce sous-ensemble avec ces options.

    ⚠ ON VÉRIFIE LE FICHIER, PAS LE CODE DE RETOUR. Mesuré le 2026-07-28 :
    `--wrap=horizontal` seul rend un code nul ET une sortie de HUIT OCTETS. Un code de
    sortie satisfait n'est pas un panorama.
    """
    sortie = os.path.join(work, "essai_%s.tif" % etiquette)
    if os.path.exists(sortie):
        os.remove(sortie)
    code, d = run(["enblend"] + list(options) +
                  ["--compression=LZW", "-f", "%dx%d" % (LARGEUR, HAUTEUR),
                   "-o", sortie] + couches, tolere_echec=True)
    taille = os.path.getsize(sortie) if os.path.exists(sortie) else 0
    ok = code == 0 and taille > 1_000_000
    log("    %d couches%s -> %s (code %d, %.0f Mo, %.0f s)"
        % (len(couches), (" " + " ".join(options)) if options else "",
           "OK" if ok else "ECHEC", code, taille / 1e6, d))
    return ok


# Les combinaisons à éprouver, de la plus légère à la plus dégradée. On s'arrête à la
# PREMIÈRE qui aboutit : inutile de dégrader plus que nécessaire.
COMBINAISONS = [
    ("d'origine", ()),
    ("wrap", ("--wrap=horizontal",)),
    ("no-optimize", ("--no-optimize",)),
    ("wrap + no-optimize", ("--wrap=horizontal", "--no-optimize")),
    ("wrap + no-optimize + nft", ("--wrap=horizontal", "--no-optimize",
                                  "--primary-seam-generator=nearest-feature-transform")),
    ("wrap + fine-mask", ("--wrap=horizontal", "--fine-mask")),
    ("wrap + no-optimize + coarse 16", ("--wrap=horizontal", "--no-optimize",
                                        "--coarse-mask=16")),
]


def main(args):
    if not args:
        print(__doc__)
        return 1
    travail = args[0].rstrip("\\/")
    rapide = "--rapide" in args
    os.environ["PATH"] = bin_hugin() + os.pathsep + os.environ.get("PATH", "")
    work = os.path.join(travail, "_diag")
    os.makedirs(work, exist_ok=True)
    log("Dossier de travail CONSERVE : %s\n" % work)

    couches = preparer(travail, work, rapide)

    log("\n--- tailles des couches ---")
    tailles = [(os.path.basename(c), os.path.getsize(c)) for c in couches]
    petites = [t for t in tailles if t[1] < 20_000]
    log("  la plus petite : %s (%d octets)" % min(tailles, key=lambda t: t[1]))
    log("  la plus grande : %s (%d octets)" % max(tailles, key=lambda t: t[1]))
    if petites:
        log("  ⚠ %d couche(s) SUSPECTE(S) sous 20 Ko — candidates a la degenerescence :" % len(petites))
        for n, t in petites:
            log("      %s : %d octets" % (n, t))
    else:
        log("  aucune couche anormalement petite")

    log("\n--- 1. quelle combinaison d'options aboutit ? ---")
    log("    (on s'arrete a la premiere qui marche : inutile de degrader davantage)")
    gagnante = None
    for nom, options in COMBINAISONS:
        log("  essai : %s" % nom)
        if essai_enblend(couches, work, nom.replace(" ", "_").replace("+", ""), options):
            gagnante = (nom, options)
            break
    if gagnante:
        log("\n=== RESULTAT ===")
        log("COMBINAISON QUI ABOUTIT : %s" % gagnante[0])
        log("  arguments : %s" % (" ".join(gagnante[1]) or "(aucun)"))
        if gagnante[0] == "d'origine":
            log("\n⚠ LE JEU COMPLET PASSE SANS RIEN CHANGER. Le plantage n'est donc pas")
            log("  reproductible ici : ne conclus rien, et compare les couches de ce")
            log("  dossier avec celles de la production.")
        else:
            log("\nA reporter dans cine_panorama_stitch.py, commande enblend.")
        return 0

    log("\nAUCUNE combinaison n'aboutit. On cherche la couche fautive.")
    log("\n--- 2. bissection : plus petit prefixe qui plante ---")
    # Invariant : `bon` passe, `mauvais` plante. On resserre.
    bon, mauvais = 0, len(couches)
    while mauvais - bon > 1:
        milieu = (bon + mauvais) // 2
        if essai_enblend(couches[:milieu], work, "p%d" % milieu):
            bon = milieu
        else:
            mauvais = milieu
    coupable = couches[mauvais - 1]
    log("\n=== RESULTAT ===")
    log("Les %d premieres couches passent." % bon)
    log("En ajoutant la suivante, enblend se plante.")
    log("COUPABLE : %s (%d octets)" % (os.path.basename(coupable), os.path.getsize(coupable)))
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        im = Image.open(coupable)
        log("  dimensions %dx%d, mode %s" % (im.size[0], im.size[1], im.mode))
    except Exception as e:
        log("  (image illisible par Pillow : %s — indice en soi)" % e)
    log("\nLa couche isolee reste dans %s : on peut l'ouvrir et la regarder." % work)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
