# -*- coding: utf-8 -*-
"""
verifier_sens.py — tranche la convention de signe du lacet, correctement (2026-07-27).

POURQUOI CE SECOND ESSAI. Le premier (`essai_angles.py`) comparait les images pixel à
pixel et concluait « le plus faible écart gagne ». C'était FAUX : l'assemblage guidé place
le nord à un endroit défini, l'automatique le place où il veut, et une simple rotation
globale en azimut — parfaitement légitime — suffit à faire exploser l'écart. Le premier
essai mesurait donc la ROTATION, pas ce qu'on cherchait. Résultat : 33,1 contre 40,3, deux
valeurs grandes et trop proches pour décider quoi que ce soit.

CE QUE MESURE CELUI-CI. Un signe de lacet inversé produit une image **miroir**. Le miroir,
lui, ne se rattrape par AUCUNE rotation. On cherche donc, pour chaque candidat :
  - le meilleur décalage circulaire contre la référence TELLE QUELLE ;
  - le meilleur décalage circulaire contre la référence INVERSÉE horizontalement.
Le candidat est du bon sens si le premier résidu est nettement plus bas que le second.

C'est un test qui peut RÉPONDRE « indécis » — et c'est voulu. Deux résidus voisins veulent
dire que les images ne se ressemblent pas assez pour conclure, et il vaut mieux le dire que
de choisir à pile ou face une convention qui donnerait des panoramas en miroir aux clients.

Usage :  cd /root/cineflight_web && python3 verifier_sens.py <job_id>
"""

import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))


def charger(chemin, w=1024, h=512):
    import numpy as np
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None
    im = Image.open(chemin).convert("L").resize((w, h))
    return np.asarray(im, dtype="float32")


def meilleur_residu(a, b, pas=4):
    """Plus petit écart moyen entre `a` et `b` sur tous les décalages circulaires."""
    import numpy as np
    h = a.shape[0]
    y0, y1 = int(h * 0.40), int(h * 0.85)   # bande utile : ni zénith comblé ni nadir
    ref = a[y0:y1]
    best, bshift = None, 0
    for dx in range(0, a.shape[1], pas):
        v = float(np.abs(ref - np.roll(b, dx, axis=1)[y0:y1]).mean())
        if best is None or v < best:
            best, bshift = v, dx
    return best, bshift


def main(args):
    if not args:
        print("Usage : verifier_sens.py <job_id>"); return 1
    job = os.path.join(BASE, "_pano_jobs", args[0])
    import numpy as np
    ref_p = os.path.join(job, "panorama_essai_auto.jpg")
    if not os.path.exists(ref_p):
        print("ECHEC : %s absent — lance d'abord essai_angles.py" % ref_p); return 1
    ref = charger(ref_p)
    ref_miroir = ref[:, ::-1].copy()

    print("Reference : panorama_essai_auto.jpg")
    print("%-12s %12s %12s   %s" % ("candidat", "residu_direct", "residu_miroir", "verdict"))
    resultats = {}
    for nom in ("yaw_plus", "yaw_moins"):
        p = os.path.join(job, "panorama_essai_%s.jpg" % nom)
        if not os.path.exists(p):
            print("%-12s absent" % nom); continue
        c = charger(p)
        rd, sd = meilleur_residu(ref, c)
        rm, sm = meilleur_residu(ref_miroir, c)
        # 10 % d'ecart minimum pour conclure : en deca, les deux hypotheses expliquent
        # aussi bien l'image et le test doit se declarer indecis.
        if rd < rm * 0.90:
            v = "MEME SENS que l'auto (decalage %d/1024)" % sd
        elif rm < rd * 0.90:
            v = "MIROIR de l'auto -> signe INVERSE"
        else:
            v = "INDECIS (%.1f vs %.1f)" % (rd, rm)
        resultats[nom] = (rd, rm, v)
        print("%-12s %12.2f %12.2f   %s" % (nom, rd, rm, v))

    print()
    bons = [n for n, (rd, rm, _) in resultats.items() if rd < rm * 0.90]
    if len(bons) == 1:
        print("=> SENS_YAW a retenir : %s" % ("+1.0" if bons[0] == "yaw_plus" else "-1.0"))
        print("   Reporter dans SENS_YAW de cine_panorama_stitch.py.")
    elif len(bons) == 2:
        # Les deux ne peuvent pas etre du bon sens : c'est que la mesure ne discrimine pas.
        print("=> INDECIS : les deux candidats ressemblent a la reference directe.")
        print("   Le lacet n'a probablement pas d'effet visible ici (scene trop uniforme).")
    else:
        print("=> INDECIS : aucun candidat ne tranche.")
        print("   Verifier a l'oeil : ouvrir les deux images et chercher un detail")
        print("   ASYMETRIQUE (batiment, route) a gauche/droite d'un repere connu.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
