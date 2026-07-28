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


class Refuse(Exception):
    """L'appel a échoué. ⚠ DISTINCT d'une liste vide : afficher « rien en attente »
    après un refus ferait croire que tout va bien alors que rien ne fonctionne —
    c'est ce qui a masqué des heures de 403 le 2026-07-28."""


def travaux(j):
    r = requests.get(SERVEUR + "/api/travaux", headers=entetes(j), timeout=30)
    if r.status_code == 503:
        raise Refuse("le serveur n'a pas de jeton installe (voir patch_travaux_ouvrier.py)")
    if r.status_code == 403:
        raise Refuse("jeton REFUSE — le jeton de ce PC ne correspond pas a celui du serveur")
    if r.status_code == 404:
        raise Refuse("/api/travaux INTROUVABLE — patch non applique, ou service non redemarre")
    if r.status_code != 200:
        raise Refuse("reponse inattendue : HTTP %d" % r.status_code)
    return r.json().get("travaux", [])


def telecharger(j, t):
    """
    Rapatrie un travail, UNE PHOTO À LA FOIS. Rend le dossier local, ou None.

    ⚠ POURQUOI PAS UNE ARCHIVE. La première version téléchargeait un zip construit en flux
    par le serveur : il se corrompait en route et le transfert mourait à 97 Mo sur 249,
    sans reprise possible. Fichier par fichier, chaque photo a sa taille annoncée, se
    retente seule, et une erreur ne coûte que celle-là. C'est le même principe que le
    rapatriement depuis le drone, qui a déjà fait ses preuves.
    """
    cible = os.path.join(DOSSIER, t["id"])
    if os.path.isdir(os.path.join(cible, "photos")):
        return None                      # deja fait : on ne retelecharge pas
    os.makedirs(cible, exist_ok=True)
    # Dossier PARTIEL le temps du transfert : un dossier `photos` complet signifie
    # « travail entier ». Un transfert interrompu ne doit jamais le laisser croire.
    tmp = os.path.join(cible, "_photos_partiel")
    os.makedirs(tmp, exist_ok=True)

    r = requests.get(SERVEUR + "/api/travaux/%s/liste" % t["id"],
                     headers=entetes(j), timeout=60)
    r.raise_for_status()
    liste = r.json().get("photos", [])
    total_mo = sum(p["octets"] for p in liste) / 1e6
    print("  telechargement de %s — %d photos, %.0f Mo" % (t["id"], len(liste), total_mo))

    recu = 0
    for i, p in enumerate(liste, 1):
        dest = os.path.join(tmp, p["nom"])
        if os.path.exists(dest) and os.path.getsize(dest) == p["octets"]:
            recu += p["octets"]          # deja la, taille juste : on saute
            continue
        ok = False
        for essai in (1, 2):             # une seconde chance par photo
            try:
                with requests.get(
                        SERVEUR + "/api/travaux/%s/photo/%s" % (t["id"], p["nom"]),
                        headers=entetes(j), stream=True, timeout=300) as rp:
                    rp.raise_for_status()
                    with open(dest, "wb") as f:
                        for bloc in rp.iter_content(chunk_size=1 << 20):
                            if bloc:
                                f.write(bloc)
                if os.path.getsize(dest) == p["octets"]:
                    ok = True; break
                print("\n    %s : taille inattendue, nouvel essai" % p["nom"])
            except requests.RequestException as e:
                print("\n    %s : %s, nouvel essai" % (p["nom"], e.__class__.__name__))
        if not ok:
            print("\n  ECHEC sur %s — travail laisse INCOMPLET, il restera propose." % p["nom"])
            return None
        recu += p["octets"]
        pct = min(100, int(recu / 1e6 / max(total_mo, 0.001) * 100))
        print("\r    %3d %%  (%d/%d fichiers, %.0f / %.0f Mo)"
              % (pct, i, len(liste), recu / 1e6, total_mo), end="")
    print()
    os.rename(tmp, os.path.join(cible, "photos"))
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
                # Une ligne par tour, avec l'heure : on doit pouvoir vérifier d'un coup
                # d'œil que l'atelier veille encore, sans noyer l'écran.
                print("%s  rien en attente (%d travaux connus)"
                      % (time.strftime("%H:%M:%S"), len(connus)))
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
        except Refuse as e:
            print("%s  ECHEC : %s" % (time.strftime("%H:%M:%S"), e))
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
