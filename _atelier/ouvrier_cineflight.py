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
import threading
import time

try:
    import requests
except ImportError:
    print("Il manque la bibliotheque `requests`.  ->  py -m pip install requests")
    sys.exit(1)

try:
    import traitement
except ImportError:
    traitement = None      # l'atelier fonctionne sans : le calcul reste manuel

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
    Rapatrie un travail, UNE PHOTO À LA FOIS. Rend (dossier, deja_la), ou None en cas
    d'échec.

    ⚠ « DÉJÀ LÀ » N'EST PAS UN ÉCHEC. La première version rendait None dans les deux cas :
    un travail déjà rapatrié n'entrait donc jamais dans la liste des dépôts surveillés, et
    le maillage n'était jamais envoyé tout seul. Il fallait effacer le dossier pour réarmer
    l'automatisme — piège silencieux, découvert le 2026-07-28.

    ⚠ POURQUOI PAS UNE ARCHIVE. La première version téléchargeait un zip construit en flux
    par le serveur : il se corrompait en route et le transfert mourait à 97 Mo sur 249,
    sans reprise possible. Fichier par fichier, chaque photo a sa taille annoncée, se
    retente seule, et une erreur ne coûte que celle-là. C'est le même principe que le
    rapatriement depuis le drone, qui a déjà fait ses preuves.
    """
    cible = os.path.join(DOSSIER, t["id"])
    dossier_photos = os.path.join(cible, "photos")
    if os.path.isdir(dossier_photos):
        # Deja rapatrie : on ne retelecharge pas, MAIS on rend le dossier pour que la
        # surveillance du maillage s'arme quand meme.
        n = len([x for x in os.listdir(dossier_photos)
                 if os.path.isfile(os.path.join(dossier_photos, x))])
        attendu = t.get("photos")
        if attendu and n != attendu:
            # Un dossier `photos` incomplet ne devrait pas exister (le rapatriement passe
            # par `_photos_partiel`), mais une version anterieure du script a pu en laisser.
            # On le DIT au lieu de laisser croire que tout est pret.
            print("  ⚠ deja telecharge mais %d fichier(s) sur %d attendus." % (n, attendu))
            print("    Efface %s pour relancer le rapatriement." % dossier_photos)
        else:
            print("  (deja telecharge : %d photos)" % n)
        return cible, True
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
    os.rename(tmp, dossier_photos)
    return cible, False


def deposer(j, mid, url_depot, glb):
    """Envoie le maillage au serveur. Rend True si le serveur l'a accepté."""
    mo = os.path.getsize(glb) / 1e6
    print("  depot de %s (%.0f Mo) ..." % (os.path.basename(glb), mo), end=" ")
    sys.stdout.flush()
    try:
        with open(glb, "rb") as f:
            r = requests.post(SERVEUR + url_depot,
                              files={"file": (os.path.basename(glb), f, "model/gltf-binary")},
                              timeout=3600)
    except requests.RequestException as e:
        print("echec reseau (%s)" % e.__class__.__name__); return False
    if r.status_code != 200:
        # Le serveur refuse ce qui n'est pas du glTF ou fait moins de 1 Kio : un mauvais
        # format donnerait une page noire chez le client, sans indice.
        print("REFUSE : HTTP %d — %s" % (r.status_code, r.text[:200])); return False
    print("accepte.")
    print("  ===============================================================")
    print("  MODELE EN LIGNE : %s/modele3d/%s" % (SERVEUR, mid))
    print("  ===============================================================\n")
    return True


def surveiller_depots(j, attentes):
    """
    Guette l'apparition d'un `.glb` dans les dossiers de travail, et le dépose seul.

    ⚠ ON ATTEND QUE LE FICHIER SOIT STABLE. RealityScan écrit le maillage progressivement :
    l'envoyer pendant l'écriture donnerait un fichier tronqué que le serveur accepterait
    peut-être — et le client verrait un modèle incomplet sans que rien ne le signale.
    Deux relevés de taille identiques à une minute d'intervalle valent preuve d'écriture
    terminée.

    @param attentes dict id -> {"dossier", "url_depot", "taille"} des travaux à surveiller.
    """
    for mid in list(attentes.keys()):
        info = attentes[mid]
        glbs = [os.path.join(info["dossier"], n) for n in os.listdir(info["dossier"])
                if n.lower().endswith(".glb")]
        if not glbs:
            continue
        glb = max(glbs, key=os.path.getmtime)      # le plus récent, s'il y en a plusieurs
        taille = os.path.getsize(glb)
        if taille < 1024:
            continue
        if info.get("taille") != taille:
            info["taille"] = taille                 # encore en cours d'écriture
            print("  %s : maillage en cours d'ecriture (%.0f Mo)"
                  % (mid, taille / 1e6))
            continue
        if deposer(j, mid, info["url_depot"], glb):
            del attentes[mid]                       # livré : on cesse de surveiller


def lancer_traitement(mid, dossier):
    """
    Démarre la reconstruction sur un fil de fond.

    ⚠ SUR UN FIL, PAS EN DIRECT. Un calcul dure des heures ; le faire dans la boucle
    principale suspendrait l'interrogation du serveur pendant tout ce temps — l'atelier
    paraîtrait mort, et un second travail arrivé entre-temps ne serait même pas rapatrié.
    Le verrou de `traitement` garantit qu'un seul calcul tourne malgré ce parallélisme.
    """
    def travail():
        def fini(ok, msg):
            marque = "OK" if ok else "ECHEC"
            print("\n%s  %s %s : %s\n" % (time.strftime("%H:%M:%S"), marque, mid, msg))
        traitement.traiter(dossier, sur_fin=fini)

    threading.Thread(target=travail, daemon=True).start()


def main():
    j = jeton()
    if not j:
        print("Aucun jeton. Definis CINE_OUVRIER_JETON, ou place-le dans jeton.txt.")
        return 1
    os.makedirs(DOSSIER, exist_ok=True)
    print("Atelier CineFlight")
    print("  serveur : %s" % SERVEUR)
    print("  dossier : %s" % DOSSIER)
    auto = False
    if traitement is not None:
        auto, detail = traitement.pret()
        # On DIT lequel des deux modes tourne. Sans cette ligne, un traitement qui ne se
        # declenche pas ressemble a un traitement en cours.
        if auto:
            print("  calcul  : AUTOMATIQUE — %s" % detail)
            print("            %s" % ("sans interface" if traitement.autonome()
                                      else "AVEC INTERFACE tant que les reglages d'export "
                                           "ne sont pas enregistres"))
        else:
            print("  calcul  : MANUEL — %s" % detail)
    print("  je regarde toutes les %d s. Ctrl+C pour arreter.\n" % PERIODE_S)
    connus = set()
    attentes = {}          # travaux téléchargés, dont on guette le maillage
    while True:
        try:
            # D'ABORD les dépôts : un maillage prêt ne doit pas attendre le tour suivant.
            if attentes:
                surveiller_depots(j, attentes)
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
                rendu = telecharger(j, t)
                if rendu is None:
                    connus.discard(t["id"])   # echec : a reproposer au tour suivant
                    continue
                dossier, deja = rendu
                attentes[t["id"]] = {"dossier": dossier, "url_depot": t["url_depot"],
                                     "taille": None}
                print("\n  ===============================================================")
                print("  PHOTOS PRETES : %s" % os.path.join(dossier, "photos"))
                if auto:
                    print("  Reconstruction lancee. Elle dure de 20 min a plusieurs heures.")
                    print("  Le depot suivra tout seul.")
                    lancer_traitement(t["id"], dossier)
                else:
                    print("  1. Ouvre RealityScan et traite ce dossier.")
                    print("  2. Exporte le maillage en .glb DANS :")
                    print("     %s" % dossier)
                    print("  Le depot se fera TOUT SEUL des que le fichier sera complet.")
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
