# -*- coding: utf-8 -*-
"""
reassembler_pano.py — réassemble des panoramas DÉJÀ capturés, sans refaire le vol (2026-07-27).

Les photos envoyées par l'app restent sur le serveur dans `_pano_jobs/<id>/in/`. Quand
l'assembleur est corrigé, on peut donc régénérer les panoramas à l'identique — le lien
`/vr/<id>` et la paire `/vr3d/<id>` continuent de fonctionner puisque l'identifiant ne
change pas.

À LANCER DÉTACHÉ : un assemblage prend 5 à 10 minutes par panorama sur cette machine, et
la console DigitalOcean coupe la session (donc tue le processus) bien avant.

    cd /root/cineflight_web
    nohup python3 reassembler_pano.py > /root/reassemblage.log 2>&1 &

Usage :
    reassembler_pano.py                 -> les 2 travaux à 25 photos les plus récents
    reassembler_pano.py <id> [<id>...]  -> ces travaux précisément
    reassembler_pano.py --lister        -> n'assemble RIEN, affiche l'inventaire

⚠ Le tri se fait sur la date du dossier `in`, PAS sur celle du travail : réécrire
`panorama.jpg` change la date du travail, et un second passage ne choisirait plus les
mêmes dossiers.
"""

import glob
import os
import sys
import time

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pano_jobs")


def inventaire():
    """Tous les travaux disposant de photos d'entrée, du plus ancien au plus récent."""
    res = []
    for j in glob.glob(os.path.join(BASE, "*")):
        din = os.path.join(j, "in")
        if not os.path.isdir(din):
            continue
        photos = glob.glob(os.path.join(din, "*.jpg")) + glob.glob(os.path.join(din, "*.JPG"))
        if not photos:
            continue
        res.append({
            "id": os.path.basename(j),
            "chemin": j,
            "photos": len(photos),
            "quand": os.path.getmtime(din),
        })
    res.sort(key=lambda e: e["quand"])
    return res


def afficher(inv, retenus=()):
    print("Travaux disponibles :")
    for e in inv:
        marque = "  <-- a refaire" if e["id"] in retenus else ""
        sortie = os.path.join(e["chemin"], "panorama.jpg")
        taille = ""
        if os.path.exists(sortie):
            try:
                from PIL import Image
                Image.MAX_IMAGE_PIXELS = None
                taille = " sortie=%dx%d" % Image.open(sortie).size
            except Exception:
                taille = " sortie=?"
        print("  %s  %s  %2d photos%s%s" % (
            time.strftime("%Y-%m-%d %H:%M", time.localtime(e["quand"])),
            e["id"], e["photos"], taille, marque))
    sys.stdout.flush()


def main(args):
    inv = inventaire()
    if not inv:
        print("Aucun travail avec des photos d'entree dans %s" % BASE)
        return 1

    if "--lister" in args:
        afficher(inv)
        return 0

    ids = [a for a in args if not a.startswith("-")]
    if ids:
        connus = {e["id"]: e for e in inv}
        manquants = [i for i in ids if i not in connus]
        if manquants:
            print("ECHEC : identifiants inconnus : %s" % ", ".join(manquants))
            afficher(inv)
            return 1
        choisis = [connus[i] for i in ids]
    else:
        # Defaut : les 2 derniers a 25 photos = les deux yeux d'un relief stereo.
        candidats = [e for e in inv if e["photos"] == 25]
        if len(candidats) < 2:
            print("ECHEC : moins de 2 travaux a 25 photos — precise les identifiants.")
            afficher(inv)
            return 1
        choisis = candidats[-2:]

    afficher(inv, retenus={e["id"] for e in choisis})

    import cine_panorama_stitch as stitch
    echecs = 0
    for e in choisis:
        print("\n=== %s (%d photos) ===" % (e["id"], e["photos"]))
        sys.stdout.flush()
        t0 = time.time()
        try:
            r = stitch.assembler(os.path.join(e["chemin"], "in"),
                                 os.path.join(e["chemin"], "panorama.jpg"))
            print("RESULTAT %s : %s" % (e["id"], r))
        except Exception as ex:
            echecs += 1
            print("ECHEC %s : %s" % (e["id"], ex))
        print("(%.0f s)" % (time.time() - t0))
        sys.stdout.flush()

    # VERIFICATION DU PRODUIT, pas de la commande : c'est la taille du fichier qui
    # dit si le canevas complet a ete respecte. Une commande juste peut donner un
    # resultat faux — c'est exactement ce qui s'est passe avec enblend.
    print("\n--- verification ---")
    for e in choisis:
        p = os.path.join(e["chemin"], "panorama.jpg")
        try:
            from PIL import Image
            Image.MAX_IMAGE_PIXELS = None
            w, h = Image.open(p).size
            ok = abs(w - 2 * h) <= 2
            print("%s : %dx%d  %s" % (e["id"], w, h,
                  "OK (rapport 2:1)" if ok else "PROBLEME : ce n'est PAS du 2:1"))
        except Exception as ex:
            echecs += 1
            print("%s : illisible (%s)" % (e["id"], ex))
    return 1 if echecs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
