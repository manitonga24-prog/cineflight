# -*- coding: utf-8 -*-
"""
essai_angles.py — détermine le SENS DU LACET par la mesure (2026-07-27).

La convention de signe du lacet dans Hugin par rapport au cap boussole du drone n'est pas
documentée de façon utilisable ici, et se tromper produit un panorama **inversé
horizontalement** — une erreur parfaitement plausible à l'œil sur un paysage symétrique,
donc exactement le genre qu'on ne remarque qu'une fois chez le client.

Ce script assemble le MÊME jeu de photos trois fois — sans angles, avec `SENS_YAW=+1`,
avec `SENS_YAW=-1` — puis MESURE laquelle des trois versions ressemble le plus à la
référence automatique. On tranche donc sur un chiffre, pas sur une impression.

    cd /root/cineflight_web
    nohup python3 essai_angles.py <job_id> > /root/essai_angles.log 2>&1 &

⚠ Compter ~6 minutes par version, soit une vingtaine de minutes au total. Les fichiers
d'essai sont écrits à côté (`panorama_essai_*.jpg`) : le panorama officiel du travail
n'est PAS touché.

Le jeu de photos doit porter ses angles : le fichier `angles.json` déposé par l'app dans
le dossier du travail. Sans lui, l'essai n'a pas lieu d'être.
"""

import glob
import json
import os
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))


def horizon_median(chemin):
    """Hauteur médiane de la limite ciel/sol, en degrés (0 = horizon parfait)."""
    import numpy as np
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None
    w, h = 2048, 1024
    a = np.asarray(Image.open(chemin).convert("L").resize((w, h)), np.float32)
    b0, b1 = int(h * 0.35), int(h * 0.60)
    ys = []
    for x in range(0, w, 4):
        col = a[b0:b1, x]
        grad = col[:-1] - col[1:]
        i = int(np.argmax(grad))
        if grad[i] < 15:
            continue
        ys.append(b0 + i)
    if not ys:
        return None
    return float(np.median(np.array(ys, np.float32) - h / 2) * 180.0 / h)


def ressemblance(a_path, b_path):
    """Écart moyen absolu entre deux panoramas réduits. Plus c'est bas, plus ils
    représentent la même scène dans le même repère."""
    import numpy as np
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None
    w, h = 1024, 512
    a = np.asarray(Image.open(a_path).convert("L").resize((w, h)), np.float32)
    b = np.asarray(Image.open(b_path).convert("L").resize((w, h)), np.float32)
    y0, y1 = int(h * 0.40), int(h * 0.85)      # bande utile : ni zénith comblé ni nadir
    return float(np.abs(a[y0:y1] - b[y0:y1]).mean())


def main(args):
    if not args:
        print("Usage : essai_angles.py <job_id>")
        return 1
    jid = args[0]
    job = os.path.join(BASE, "_pano_jobs", jid)
    din = os.path.join(job, "in")
    if not os.path.isdir(din):
        print("ECHEC : %s introuvable" % din); return 1
    fa = os.path.join(job, "angles.json")
    if not os.path.exists(fa):
        print("ECHEC : %s absent — ce jeu de photos n'a pas ete envoye avec ses angles."
              % fa)
        print("        (il faut une capture faite avec la version de l'app qui les joint)")
        return 1
    angles = json.load(open(fa))
    print("Angles fournis : %d" % len(angles)); sys.stdout.flush()

    import cine_panorama_stitch as stitch

    essais = [("auto", None), ("yaw_plus", +1.0), ("yaw_moins", -1.0)]
    produits = {}
    for nom, sens in essais:
        out = os.path.join(job, "panorama_essai_%s.jpg" % nom)
        print("\n=== essai %s ===" % nom); sys.stdout.flush()
        t0 = time.time()
        try:
            if sens is not None:
                stitch.SENS_YAW = sens
            r = stitch.assembler(din, out, angles=(None if sens is None else angles))
            produits[nom] = out
            print("  %s  (%.0f s)" % (r, time.time() - t0))
        except Exception as ex:
            print("  ECHEC : %s" % ex)
        sys.stdout.flush()

    print("\n--- MESURES ---")
    ref = produits.get("auto")
    for nom in ("auto", "yaw_plus", "yaw_moins"):
        p = produits.get(nom)
        if not p or not os.path.exists(p):
            print("%-10s : absent" % nom); continue
        hm = horizon_median(p)
        ec = ressemblance(ref, p) if (ref and nom != "auto") else 0.0
        print("%-10s : horizon=%s   ecart_vs_auto=%.1f" % (
            nom, ("%+.2f deg" % hm) if hm is not None else "?", ec))

    print("\nLECTURE : le bon sens est celui dont l'ecart a la reference automatique est")
    print("le PLUS FAIBLE — il represente la meme scene, dans le meme repere. Un sens")
    print("inverse donne une image miroir : l'ecart explose.")
    print("Reporter ensuite ce sens dans SENS_YAW de cine_panorama_stitch.py.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
