# -*- coding: utf-8 -*-
"""
ouvrier_cineflight.py — l'atelier sur le PC (2026-07-27).

CE QU'IL FAIT. Interroge le serveur toutes les minutes, télécharge les jeux de photos en
attente de calcul, les range dans un dossier, et prévient. Le calcul lui-même reste manuel
pour l'instant : tu ouvres RealityScan, tu traites, tu déposes le `.glb` — ce script te
donne la commande toute faite.

POURQUOI DANS CE SENS. C'est le PC qui appelle le serveur, jamais l'inverse : aucun port à
ouvrir, aucune adresse fixe, rien d'exposé sur Internet. Le pare-feu du fournisseur n'a
rien à savoir. C'est ce qui rend l'atelier possible depuis une connexion résidentielle.

INSTALLATION (une fois)
    py -m pip install requests
    set CINE_OUVRIER_JETON=<le jeton affiché par le serveur>
    py ouvrier_cineflight.py

Le jeton peut aussi être placé dans un fichier `jeton.txt` à côté de ce script.

⚠ CE SCRIPT NE SUPPRIME RIEN, ni sur le serveur ni chez toi. Un travail déjà téléchargé est
simplement ignoré au tour suivant : on reconnaît son dossier. Il reste sur le serveur tant
que le maillage n'a pas été déposé — c'est voulu, un travail non livré ne doit pas
disparaître parce qu'un script a tourné.
"""

import os
import sys
import time
import zipfile

try:
    import requests
except ImportError:
    print("Il manque la bibliotheque `requests`.  ->  py -m pip install requests")
    sys.exit(1)

SERVEUR = os.environ.get("CINE_SERVEUR", "https://cineflight.ca")
DOSSIER = os.environ.get("CINE_ATELIER", os.path.join(os.path.expanduser("~"), "CineFlight_Atelier"))
PERIODE_S = int(os.environ.get("CINE_PERIODE", "60"))


def jeton():
    j = os.environ.get("CINE_OUVRIER_JETON", "").strip()
    if j:
        return j
    ici = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jeton.txt")
    try:
        with open(ici, "r") as f:
            return f.read().strip()
    except Exception:
        return ""


def entetes(j):
    return {"X-Cine-Ouvrier": j}


def travaux(j):
    r = requests.get(SERVEUR + "/api/travaux", headers=entetes(j), timeout=30)
    if r.status_code == 503:
        print("  le serveur n'a pas de jeton installe (voir patch_travaux_ouvrier.py)")
        return []
    if r.status_code == 403:
        print("  jeton REFUSE par le serveur — verifie CINE_OUVRIER_JETON")
        return []
    r.raise_for_status()
    return r.json().get("travaux", [])


def telecharger(j, t):
    """Rapatrie et decompresse un travail. Rend le dossier local, ou None."""
    cible = os.path.join(DOSSIER, t["id"])
    if os.path.isdir(os.path.join(cible, "photos")):
        return None                      # deja fait : on ne retelecharge pas
    os.makedirs(cible, exist_ok=True)
    zipf = os.path.join(cible, "photos.zip")
    mo = t.get("octets", 0) / 1e6
    print("  telechargement de %s — %d photos, %.0f Mo" % (t["id"], t.get("photos", 0), mo))
    with requests.get(SERVEUR + t["url_photos"], headers=entetes(j),
                      stream=True, timeout=1800) as r:
        r.raise_for_status()
        recu = 0
        with open(zipf, "wb") as f:
            for bloc in r.iter_content(chunk_size=1 << 20):
                if not bloc:
                    continue
                f.write(bloc); recu += len(bloc)
                if mo > 0:
                    pct = min(100, int(recu / 1e6 / mo * 100))
                    print("\r    %3d %%  (%.0f / %.0f Mo)" % (pct, recu / 1e6, mo), end="")
        print()
    # DECOMPRESSION dans un dossier temporaire puis renommage : un dossier `photos`
    # existant signifie « travail complet ». Si l'extraction echoue a mi-chemin, on ne
    # doit pas le laisser croire.
    tmp = os.path.join(cible, "_photos_partiel")
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(zipf) as z:
        z.extractall(tmp)
    os.rename(tmp, os.path.join(cible, "photos"))
    os.remove(zipf)
    return cible


def main():
    j = jeton()
    if not j:
        print("Aucun jeton. Definis CINE_OUVRIER_JETON, ou place-le dans jeton.txt.")
        return 1
    os.makedirs(DOSSIER, exist_ok=True)
    print("Atelier CineFlight")
    print("  serveur : %s" % SERVEUR)
    print("  dossier : %s" % DOSSIER)
    print("  je regarde toutes les %d s. Ctrl+C pour arreter.\n" % PERIODE_S)
    connus = set()
    while True:
        try:
            liste = travaux(j)
            nouveaux = [t for t in liste if t["id"] not in connus]
            if not liste:
                print("%s  rien en attente" % time.strftime("%H:%M:%S"))
            for t in nouveaux:
                connus.add(t["id"])
                print("\n%s  NOUVEAU TRAVAIL : %s" % (time.strftime("%H:%M:%S"), t["titre"]))
                dossier = telecharger(j, t)
                if dossier is None:
                    print("  (deja telecharge)")
                    continue
                print("\n  ===============================================================")
                print("  PHOTOS PRETES : %s" % os.path.join(dossier, "photos"))
                print("  1. Ouvre RealityScan et traite ce dossier.")
                print("  2. Exporte le maillage en .glb")
                print("  3. Depose-le :")
                print('     curl.exe -F "file=@modele.glb" %s%s' % (SERVEUR, t["url_depot"]))
                print("  ===============================================================\n")
                # Un signal sonore : le PC tourne souvent sans qu'on le regarde.
                try:
                    print("\a", end="", flush=True)
                except Exception:
                    pass
        except requests.RequestException as e:
            print("%s  serveur injoignable (%s) — nouvel essai dans %d s"
                  % (time.strftime("%H:%M:%S"), e.__class__.__name__, PERIODE_S))
        except KeyboardInterrupt:
            print("\narret demande."); return 0
        except Exception as e:
            print("%s  erreur inattendue : %s" % (time.strftime("%H:%M:%S"), e))
        try:
            time.sleep(PERIODE_S)
        except KeyboardInterrupt:
            print("\narret demande."); return 0


if __name__ == "__main__":
    sys.exit(main())
