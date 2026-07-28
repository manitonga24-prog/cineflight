# -*- coding: utf-8 -*-
"""
assemblage_pano.py — les panoramas s'assemblent sur le PC (2026-07-28).

POURQUOI. Mesuré le 2026-07-28 : `cpfind` sur 61 photos a occupé 1,26 Go et quinze minutes
de processeur sur le droplet, qui n'a qu'un cœur — et ce n'était que la première phase.
Pendant ce temps le serveur ne sert plus personne. Le calcul appartient à la machine qui a
la puissance ; le serveur reçoit, sert et montre.

⚠ L'ASSEMBLEUR EST TÉLÉCHARGÉ, PAS RECOPIÉ. `cine_panorama_stitch.py` a reçu cinq
correctifs successifs (sphère complète, `enblend -f`, guidage par angles, ordre des voies,
photométrie). En garder une copie ici créerait une seconde source de vérité, qui divergerait
au premier correctif suivant. On prend la version courante du serveur à chaque travail.

PRÉREQUIS
    Hugin pour Windows — https://hugin.sourceforge.io
    py -m pip install requests pillow

⚠ CE SCRIPT NE SUPPRIME RIEN. Les photos restent sur le serveur ; un travail reste proposé
tant qu'aucun panorama n'a été déposé. Un atelier éteint ne fait perdre aucun vol.
"""

import os
import shutil
import sys
import time

try:
    import requests
except ImportError:
    print("Il manque `requests`.  ->  python -m pip install requests")
    sys.exit(1)

SERVEUR = os.environ.get("CINE_SERVEUR", "https://cineflight.ca")
DOSSIER = os.environ.get("CINE_ATELIER",
                         os.path.join(os.path.expanduser("~"), "CineFlight_Atelier"))

# Emplacements connus de Hugin. On BALAIE plutôt que d'énumérer : le dossier peut porter
# un numéro de version, comme RealityScan.
RACINES_HUGIN = [
    r"C:\Program Files\Hugin\bin",
    r"C:\Program Files (x86)\Hugin\bin",
]
OUTILS = ("pto_gen", "cpfind", "cpclean", "autooptimiser", "pano_modify", "nona", "enblend")


def trouver_hugin():
    """Dossier des exécutables Hugin, ou None. La variable CINE_HUGIN prime."""
    perso = os.environ.get("CINE_HUGIN", "").strip('"')
    if perso:
        return perso if os.path.isfile(os.path.join(perso, "cpfind.exe")) else None
    for r in RACINES_HUGIN:
        if os.path.isfile(os.path.join(r, "cpfind.exe")):
            return r
    for base in (r"C:\Program Files", r"C:\Program Files (x86)"):
        if not os.path.isdir(base):
            continue
        try:
            for nom in os.listdir(base):
                if not nom.lower().startswith("hugin"):
                    continue
                b = os.path.join(base, nom, "bin")
                if os.path.isfile(os.path.join(b, "cpfind.exe")):
                    return b
        except OSError:
            pass
    return None


def pret():
    """(True, dossier) si l'atelier peut assembler, sinon (False, raison)."""
    b = trouver_hugin()
    if not b:
        return False, ("Hugin introuvable. Installe-le depuis hugin.sourceforge.io, "
                       "ou definis CINE_HUGIN avec le chemin de son dossier bin.")
    manquants = [o for o in OUTILS if not os.path.isfile(os.path.join(b, o + ".exe"))]
    if manquants:
        return False, "Hugin incomplet dans %s : %s" % (b, ", ".join(manquants))
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        return False, "Il manque Pillow. -> python -m pip install pillow"
    return True, b


def entetes(j):
    return {"X-Cine-Ouvrier": j}


def travaux(j):
    r = requests.get(SERVEUR + "/api/travaux_pano", headers=entetes(j), timeout=30)
    if r.status_code == 404:
        raise RuntimeError("/api/travaux_pano INTROUVABLE — patch_travaux_pano.py non applique")
    if r.status_code != 200:
        raise RuntimeError("reponse inattendue : HTTP %d" % r.status_code)
    return r.json().get("travaux", [])


def telecharger(j, t):
    """Rapatrie photos et angles. Rend le dossier local, ou None en cas d'echec."""
    cible = os.path.join(DOSSIER, "pano_" + t["id"])
    dossier_in = os.path.join(cible, "in")
    if os.path.isdir(dossier_in):
        n = len(os.listdir(dossier_in))
        if n == t["photos"]:
            print("  (deja rapatrie : %d photos)" % n)
            return cible
        # Incomplet : on recommence plutot que d'assembler un jeu troue.
        print("  ⚠ %d photos sur %d — on reprend le rapatriement" % (n, t["photos"]))
        shutil.rmtree(dossier_in, ignore_errors=True)
    os.makedirs(cible, exist_ok=True)
    tmp = os.path.join(cible, "_in_partiel")
    os.makedirs(tmp, exist_ok=True)

    r = requests.get(SERVEUR + "/api/travaux_pano/%s/liste" % t["id"],
                     headers=entetes(j), timeout=60)
    r.raise_for_status()
    liste = r.json().get("photos", [])
    total_mo = sum(p["octets"] for p in liste) / 1e6
    print("  telechargement — %d photos, %.0f Mo" % (len(liste), total_mo))
    recu = 0
    for i, p in enumerate(liste, 1):
        dest = os.path.join(tmp, p["nom"])
        if os.path.exists(dest) and os.path.getsize(dest) == p["octets"]:
            recu += p["octets"]; continue
        ok = False
        for _ in (1, 2):                       # une seconde chance par photo
            try:
                with requests.get(
                        SERVEUR + "/api/travaux_pano/%s/photo/%s" % (t["id"], p["nom"]),
                        headers=entetes(j), stream=True, timeout=300) as rp:
                    rp.raise_for_status()
                    with open(dest, "wb") as f:
                        for bloc in rp.iter_content(chunk_size=1 << 20):
                            if bloc:
                                f.write(bloc)
                if os.path.getsize(dest) == p["octets"]:
                    ok = True; break
            except requests.RequestException as e:
                print("\n    %s : %s, nouvel essai" % (p["nom"], e.__class__.__name__))
        if not ok:
            print("\n  ECHEC sur %s — travail laisse INCOMPLET, il restera propose." % p["nom"])
            return None
        recu += p["octets"]
        print("\r    %3d %%  (%d/%d, %.0f / %.0f Mo)"
              % (min(100, int(recu / 1e6 / max(total_mo, 0.001) * 100)),
                 i, len(liste), recu / 1e6, total_mo), end="")
    print()

    # ⚠ RECOMPTE APRÈS COUP, comme pour la 3D : si la liste a grandi pendant le transfert,
    # le jeu n'était pas complet. On ne reconstruit pas sur un jeu tronqué.
    try:
        r2 = requests.get(SERVEUR + "/api/travaux_pano/%s/liste" % t["id"],
                          headers=entetes(j), timeout=60)
        r2.raise_for_status()
        if len(r2.json().get("photos", [])) != len(liste):
            print("  ⚠ JEU INCOMPLET : la liste a change pendant le transfert. Rien d'assemble.")
            return None
    except requests.RequestException:
        pass                                   # panne reseau : on ne condamne pas

    os.rename(tmp, dossier_in)
    # Angles : facultatifs, mais ce sont eux qui font atterrir les deux yeux d'une paire
    # stereo dans le meme repere.
    try:
        ra = requests.get(SERVEUR + "/api/travaux_pano/%s/angles" % t["id"],
                          headers=entetes(j), timeout=60)
        if ra.status_code == 200:
            open(os.path.join(cible, "angles.json"), "wb").write(ra.content)
            print("  angles recus")
        else:
            print("  pas d'angles : assemblage automatique")
    except requests.RequestException:
        print("  angles injoignables : assemblage automatique")
    return cible


def obtenir_assembleur(j, dossier):
    """Telecharge la version COURANTE du script d'assemblage. Rend son chemin, ou None."""
    try:
        r = requests.get(SERVEUR + "/api/atelier/assembleur", headers=entetes(j), timeout=60)
        r.raise_for_status()
    except requests.RequestException as e:
        print("  assembleur injoignable (%s)" % e.__class__.__name__); return None
    f = os.path.join(dossier, "cine_panorama_stitch.py")
    open(f, "wb").write(r.content)
    # ⚠ On VERIFIE le fichier, pas la requete : un fichier vide compile parfaitement.
    if os.path.getsize(f) < 2000 or b"def assembler" not in r.content:
        print("  assembleur invalide (%d octets)" % os.path.getsize(f)); return None
    return f


def assembler(dossier, chemin_assembleur, bin_hugin):
    """Lance l'assemblage. Rend le chemin du panorama produit, ou None."""
    # Hugin appelle ses outils par leur nom nu : ils doivent etre dans le PATH.
    os.environ["PATH"] = bin_hugin + os.pathsep + os.environ.get("PATH", "")
    sys.path.insert(0, os.path.dirname(chemin_assembleur))
    import importlib
    stitch = importlib.import_module("cine_panorama_stitch")
    importlib.reload(stitch)          # toujours la version qu'on vient de telecharger

    angles = None
    fa = os.path.join(dossier, "angles.json")
    if os.path.exists(fa):
        try:
            import json
            angles = json.load(open(fa))
            print("  angles : %d entrees" % len(angles))
        except Exception as e:
            print("  angles illisibles (%s) : assemblage automatique" % e)

    sortie = os.path.join(dossier, "panorama.jpg")
    t0 = time.time()
    print("  assemblage en cours — compte plusieurs minutes...")
    sys.stdout.flush()
    try:
        stitch.assembler(os.path.join(dossier, "in"), sortie, angles=angles)
    except Exception as e:
        print("  ECHEC de l'assemblage : %s" % e); return None
    duree = time.time() - t0
    if not os.path.isfile(sortie) or os.path.getsize(sortie) < 100_000:
        print("  AUCUNE image produite apres %d min." % (duree / 60)); return None

    # VERIFICATION DU PRODUIT, PAS DE LA COMMANDE. Une commande juste peut donner un
    # resultat faux — c'est exactement ce qui s'est passe avec enblend, dont la sortie
    # faisait 8192x2321 au lieu de 8192x4096.
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        w, h = Image.open(sortie).size
    except Exception as e:
        print("  image illisible : %s" % e); return None
    if abs(w - 2 * h) > 2:
        print("  PROBLEME : %dx%d, ce n'est PAS du 2:1 — le serveur refusera." % (w, h))
        return None
    print("  panorama %dx%d en %d min %02d s (%.0f Mo)"
          % (w, h, duree / 60, duree % 60, os.path.getsize(sortie) / 1e6))
    return sortie


def deposer(j, t, chemin):
    print("  depot ...", end=" "); sys.stdout.flush()
    try:
        with open(chemin, "rb") as f:
            r = requests.post(SERVEUR + t["url_depot"],
                              files={"file": ("panorama.jpg", f, "image/jpeg")},
                              timeout=3600)
    except requests.RequestException as e:
        print("echec reseau (%s)" % e.__class__.__name__); return False
    if r.status_code != 200:
        print("REFUSE : HTTP %d — %s" % (r.status_code, r.text[:200])); return False
    print("accepte.")
    print("  ===============================================================")
    print("  PANORAMA EN LIGNE : %s/vr/%s" % (SERVEUR, t["id"]))
    print("  ===============================================================\n")
    return True


def traiter(j, t, bin_hugin):
    """Un travail complet. Rend True si le panorama a ete depose."""
    dossier = telecharger(j, t)
    if dossier is None:
        return False
    a = obtenir_assembleur(j, dossier)
    if a is None:
        return False
    p = assembler(dossier, a, bin_hugin)
    if p is None:
        return False
    return deposer(j, t, p)
