# -*- coding: utf-8 -*-
"""
envoyer_jeu.py — envoie un dossier de photos au serveur comme le ferait l'app (2026-07-28).

À QUOI ÇA SERT. Éprouver la chaîne complète — serveur, file de travaux, atelier PC,
RealityScan, dépôt, visionneuse — SANS attendre un vol de drone. On injecte un jeu de
photos pris au téléphone, et tout le reste se déroule exactement comme après une capture.

C'est le seul moyen de distinguer, le jour du premier vol, un problème de CAPTURE d'un
problème de CHAÎNE : si tout fonctionne ici, ce qui échouera là-bas viendra du vol.

Usage :
    python envoyer_jeu.py "C:\\chemin\\vers\\les\\photos"  ["Titre du modele"]

Il faut au moins 12 photos (le serveur refuse en deçà : rien à reconstruire).
"""

import os
import sys

try:
    import requests
except ImportError:
    print("Il manque `requests`.  ->  python -m pip install requests")
    sys.exit(1)

SERVEUR = os.environ.get("CINE_SERVEUR", "https://cineflight.ca")
EXTENSIONS = (".jpg", ".jpeg", ".png")

# ⚠ CLOUDFLARE REFUSE TOUTE REQUÊTE DE PLUS DE 100 Mo sur les plans gratuits. Un envoi de
# 249 Mo a été coupé AVANT d'atteindre le serveur le 2026-07-28 : rien dans les journaux
# nginx, rien dans ceux du service, et côté client une erreur SSL sans explication.
# On vise 70 Mo par lot — la marge couvre l'enrobage multipart et une photo plus lourde
# que prévu.
LOT_MAX_OCTETS = int(os.environ.get("CINE_LOT_MAX", str(70 * 1000 * 1000)))


def decouper(photos):
    """Regroupe les photos en lots dont le poids reste sous la limite."""
    lots, courant, poids = [], [], 0
    for p in photos:
        t = os.path.getsize(p)
        # Une photo seule plus lourde que la limite partira quand même seule : mieux vaut
        # un refus explicite du serveur qu'un lot qu'on sait condamné.
        if courant and poids + t > LOT_MAX_OCTETS:
            lots.append(courant); courant, poids = [], 0
        courant.append(p); poids += t
    if courant:
        lots.append(courant)
    return lots


def main(args):
    if not args:
        print(__doc__)
        return 1
    dossier = args[0]
    titre = args[1] if len(args) > 1 else "Essai de chaine"
    if not os.path.isdir(dossier):
        print("Dossier introuvable : %s" % dossier)
        return 1

    photos = sorted(
        os.path.join(dossier, n) for n in os.listdir(dossier)
        if n.lower().endswith(EXTENSIONS)
    )
    if len(photos) < 12:
        print("Seulement %d photo(s) : il en faut au moins 12." % len(photos))
        return 1

    total = sum(os.path.getsize(p) for p in photos)
    print("%d photos, %.0f Mo" % (len(photos), total / 1e6))
    print("envoi vers %s en lots de %.0f Mo ..." % (SERVEUR, LOT_MAX_OCTETS / 1e6))

    lots = decouper(photos)
    print("%d lot(s)" % len(lots))
    mid = None
    for i, lot in enumerate(lots, 1):
        poids = sum(os.path.getsize(p) for p in lot) / 1e6
        print("  lot %d/%d : %d photos, %.0f Mo" % (i, len(lots), len(lot), poids), end=" ")
        sys.stdout.flush()
        ouverts = []
        try:
            champs = []
            if mid is None:
                champs.append(("titre", (None, titre)))
            for p in lot:
                f = open(p, "rb"); ouverts.append(f)
                champs.append(("files", (os.path.basename(p), f, "image/jpeg")))
            url = (SERVEUR + "/api/modele3d") if mid is None \
                else (SERVEUR + "/api/modele3d/%s/ajouter" % mid)
            r = requests.post(url, files=champs, timeout=3600)
        finally:
            for f in ouverts:
                try: f.close()
                except Exception: pass
        if r.status_code != 200:
            print("\nECHEC au lot %d : HTTP %d — %s" % (i, r.status_code, r.text[:300]))
            if mid:
                print("Le travail %s reste sur le serveur, incomplet et NON propose a"
                      " l'atelier." % mid)
            return 1
        d = r.json()
        mid = mid or d.get("modele_id")
        print("OK (total %s)" % d.get("total", d.get("photos", "?")))

    # C'est SEULEMENT ici que le jeu est declare complet et devient visible pour l'atelier.
    r = requests.post(SERVEUR + "/api/modele3d/%s/finir" % mid, timeout=120)
    if r.status_code != 200:
        print("ECHEC a la cloture : HTTP %d — %s" % (r.status_code, r.text[:300]))
        return 1
    d = r.json()
    print()
    print("  ENVOYE.  identifiant : %s  (%s photos)" % (mid, d.get("photos")))
    print("  page     : %s/modele3d/%s" % (SERVEUR, mid))
    print("  calcul lance cote serveur : %s" % d.get("reconstruction_lancee"))
    print()
    if not d.get("reconstruction_lancee"):
        print("  Le serveur ne calcule pas — c'est ATTENDU.")
        print("  L'ouvrier sur ce PC va le voir dans la minute et telecharger les photos.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
