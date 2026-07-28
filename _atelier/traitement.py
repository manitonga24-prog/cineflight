# -*- coding: utf-8 -*-
"""
traitement.py — reconstruction automatique par RealityScan en ligne de commande (2026-07-28).

CE QUE ÇA REMPLACE. Jusqu'ici l'atelier téléchargeait les photos et s'arrêtait là : il
fallait ouvrir RealityScan, aligner, mailler, texturer, exporter à la main. Ce module
enchaîne les mêmes étapes par la CLI documentée d'Epic, sans interface.

⚠ CE QUI RESTE MANUEL, UNE SEULE FOIS : le fichier de paramètres d'export. RealityScan
n'accepte pour `-exportModel` qu'un XML produit par sa propre boîte de dialogue d'export.
On ne peut pas l'écrire à la main de façon fiable — les clés changent d'une version à
l'autre, et un fichier approximatif donne soit un refus, soit un modèle sans texture.
    Dans RealityScan : EXPORT > Model > choisir .glb, régler « Export textures = oui » et
    « Embedded textures = oui », puis « Save settings » (ou copier le bloc <ModelExport>
    du .rsinfo) vers :  _atelier\\export_params.xml

⚠ COMPTE EPIC. RealityScan exige une session ouverte. Le mode `-headless` revient à
l'interface si une fenêtre de connexion s'affiche : il faut donc s'être connecté au moins
une fois dans l'application, sur cette machine, avant d'automatiser.

⚠ UNE RECONSTRUCTION À LA FOIS. Deux instances se disputeraient la même carte graphique et
la même mémoire : le verrou est global au processus.

Rien n'est déposé ici. Le fichier .glb est écrit dans le dossier du travail, et c'est la
surveillance déjà en place dans ouvrier_cineflight.py qui l'envoie — elle attend que la
taille se stabilise, donc un export interrompu ne part jamais.
"""

import os
import subprocess
import sys
import threading
import time

ICI = os.path.dirname(os.path.abspath(__file__))

# Emplacements connus de l'exécutable, du plus probable au moins probable. La variable
# CINE_REALITYSCAN prime : une installation hors des chemins Epic reste possible.
#
# ⚠ LE NUMÉRO DE VERSION EST DANS LE NOM DU DOSSIER — et il changera à chaque mise à jour
# d'Epic. Une liste figée casserait l'atelier en silence le jour d'une 2.3. On BALAIE donc
# `Epic Games\RealityScan*` au lieu d'énumérer, et la liste ci-dessous ne sert que de repli.
#
RACINES_EPIC = [
    r"C:\Program Files\Epic Games",
    r"C:\Program Files (x86)\Epic Games",
]
CANDIDATS_EXE = [
    r"C:\Program Files\Epic Games\RealityScan\RealityScan.exe",
    r"C:\Program Files\Epic Games\RealityScan_2.2\RealityScan.exe",
    r"C:\Program Files\Capturing Reality\RealityCapture\RealityCapture.exe",
]

PARAMS = os.environ.get("CINE_EXPORT_PARAMS", os.path.join(ICI, "export_params.xml"))

# ⚠ NOMBRE DE TRIANGLES. Un maillage brut sort à plusieurs dizaines de millions de
# triangles : illisible dans un navigateur, et mortel pour un casque autonome, dont le
# budget tient plutôt autour du million pour TOUTE la scène. 500 000 laisse de la marge
# pour l'interface et le rendu stéréo (deux yeux = deux rendus).
TRIANGLES = int(os.environ.get("CINE_TRIANGLES", "500000"))

# ⚠ NIVEAU DE DÉTAIL — « preview » | « normal » | « high ».
# Le niveau gouverne la résolution des cartes de profondeur : `high` les calcule à pleine
# résolution d'image, d'où un temps et une mémoire nettement supérieurs.
# NORMAL PAR DÉFAUT, et ce n'est pas de la timidité : le maillage est de toute façon ramené
# à TRIANGLES pour tenir dans un casque, donc l'essentiel du détail gagné en `high` est
# jeté juste après. Ce qui s'améliore vraiment en `high`, c'est la JUSTESSE de la forme
# (arêtes moins arrondies, petits reliefs préservés) — à juger sur un vol réel, pas ici.
_MODELES = {"preview": "-calculatePreviewModel",
            "normal": "-calculateNormalModel",
            "high": "-calculateHighModel"}
QUALITE = os.environ.get("CINE_QUALITE", "normal").strip().lower()
if QUALITE not in _MODELES:
    QUALITE = "normal"

# 6 h : au-delà, quelque chose est bloqué (fenêtre de connexion, erreur avalée). Mieux vaut
# rendre la main et le DIRE que laisser un processus muet occuper la machine.
DELAI_MAX_S = int(os.environ.get("CINE_DELAI_TRAITEMENT", str(6 * 3600)))

_verrou = threading.Lock()


def trouver_exe():
    """Chemin de l'exécutable, ou None. La version la plus récente l'emporte."""
    perso = os.environ.get("CINE_REALITYSCAN", "").strip('"')
    if perso:
        return perso if os.path.isfile(perso) else None
    trouves = []
    for racine in RACINES_EPIC:
        if not os.path.isdir(racine):
            continue
        try:
            for nom in os.listdir(racine):
                if not nom.lower().startswith("realityscan"):
                    continue
                exe = os.path.join(racine, nom, "RealityScan.exe")
                if os.path.isfile(exe):
                    trouves.append(exe)
        except OSError:
            pass
    if trouves:
        # « RealityScan_2.2 » passe apres « RealityScan_2.1 » en ordre alphabetique : c'est
        # suffisant tant que les numeros restent a un chiffre, et ca evite d'inventer un
        # analyseur de version pour un cas ou l'utilisateur peut toujours forcer le chemin.
        return sorted(trouves)[-1]
    for c in CANDIDATS_EXE:
        if os.path.isfile(c):
            return c
    return None


def autonome():
    """
    Le fichier de paramètres d'export est-il là ?

    ⚠ C'EST LUI QUI DÉCIDE DU MODE, et c'est un problème d'œuf et de poule assumé : la
    boîte de dialogue d'export ne s'ouvre que s'il existe déjà un modèle, donc ce fichier
    ne peut pas exister avant la première reconstruction réussie.
    SANS lui  : on tourne INTERFACE VISIBLE. Si RealityScan réclame quelque chose — une
                connexion Epic, un réglage d'export — on le voit et on répond. Et c'est
                précisément cette boîte de dialogue qui permet d'enregistrer le fichier
                manquant : le premier passage fabrique de quoi automatiser les suivants.
    AVEC lui  : `-headless`, plus personne devant l'écran.
    """
    return os.path.isfile(PARAMS)


def pret():
    """Rend (True, exe) si on peut lancer quelque chose, sinon (False, raison)."""
    exe = trouver_exe()
    if not exe:
        return False, ("RealityScan introuvable. Definis CINE_REALITYSCAN avec le chemin "
                       "complet de RealityScan.exe.")
    return True, exe


def commande(exe, dossier_photos, sortie_glb, projet, journal):
    """
    Séquence CLI. L'ordre suit l'exemple officiel d'Epic — il n'est pas arbitraire :

      align                     -> retrouve la position de chaque prise de vue
      selectMaximalComponent    -> ne garde que le plus grand groupe d'images alignées ;
                                   les photos isolees formeraient des morceaux flottants
      setReconstructionRegionAuto -> borne le calcul au sujet, pas a l'horizon
      calculateNormalModel      -> maillage (qualite normale : « high » quadruple le temps
                                   pour un gain invisible sur un modele destine au web)
      selectMarginalTriangles / removeSelectedTriangles
                                -> retire les bavures de bord, celles qui font ces
                                   « toiles d'araignee » autour des modeles
      simplify                  -> ramene au budget d'affichage
      calculateTexture          -> texture le maillage SIMPLIFIE (l'inverse gaspille)
      exportModel               -> .glb, selon le XML regle depuis l'interface
    """
    cmd = [exe]
    if autonome():
        # Interface cachee UNIQUEMENT quand on sait exporter sans rien demander. Sinon on
        # laisse la fenetre : un dialogue invisible est un blocage sans message.
        cmd += ["-headless"]
    cmd += [
        "-set", "appQuitOnError=true",      # une erreur ne doit pas figer la machine
        "-setInstanceName", "CineFlightAtelier",
        "-newScene",
        "-addFolder", dossier_photos,
        "-align",
        "-selectMaximalComponent",
        "-setReconstructionRegionAuto",
        _MODELES[QUALITE],
        "-selectMarginalTriangles",
        "-removeSelectedTriangles",
        "-simplify", str(TRIANGLES),
        "-calculateTexture",
        "-renameSelectedModel", "CineFlight",
        "-save", projet,
    ]
    # Le XML est FACULTATIF : l'exemple officiel d'Epic appelle `-exportModel "Nom"` seul.
    # Sans lui, RealityScan emploie les derniers reglages d'export — d'ou l'interface
    # visible, pour pouvoir repondre s'il en demande.
    cmd += ["-exportModel", "CineFlight", sortie_glb] + ([PARAMS] if autonome() else [])
    cmd += ["-quit"]
    return cmd


def traiter(dossier_travail, sur_fin=None):
    """
    Reconstruit un travail. Bloquant : appeler depuis un fil de fond.

    @param dossier_travail dossier contenant `photos`.
    @param sur_fin         rappel optionnel (ok: bool, message: str).
    @return True si un .glb exploitable a été écrit.
    """
    ok, exe_ou_raison = pret()
    if not ok:
        if sur_fin:
            sur_fin(False, exe_ou_raison)
        return False
    exe = exe_ou_raison

    photos = os.path.join(dossier_travail, "photos")
    sortie = os.path.join(dossier_travail, "CineFlight.glb")
    projet = os.path.join(dossier_travail, "projet.rsproj")
    journal = os.path.join(dossier_travail, "realityscan.log")

    if os.path.isfile(sortie) and os.path.getsize(sortie) > 1024:
        if sur_fin:
            sur_fin(True, "maillage deja present")
        return True

    # ⚠ UN SEUL CALCUL À LA FOIS : la 3090 ne se partage pas, et deux instances
    # s'écrouleraient en mémoire au lieu d'aller deux fois plus vite.
    with _verrou:
        debut = time.time()
        cmd = commande(exe, photos, sortie, projet, journal)
        # ⚠ ON NE COMPTE QUE LES IMAGES. RealityScan depose ses propres fichiers dans le
        # dossier d'entree (cache, listes) : compter « tous les fichiers » faisait grimper
        # le total d'un passage a l'autre et laissait croire a des photos apparues seules.
        n_photos = len([n for n in os.listdir(photos)
                        if n.lower().endswith((".jpg", ".jpeg", ".png", ".tif", ".tiff"))])
        print("  RealityScan %s : demarrage (%d photos, qualite %s, cible %d triangles)"
              % ("sans interface" if autonome() else "AVEC INTERFACE (1er passage)",
                 n_photos, QUALITE.upper(), TRIANGLES))
        if not autonome():
            print("    ⚠ regarde l'ecran : s'il ouvre une boite d'export, enregistre les")
            print("      reglages vers %s — les passages suivants seront autonomes." % PARAMS)
        print("    journal : %s" % journal)
        try:
            with open(journal, "w", encoding="utf-8", errors="replace") as jf:
                jf.write("commande :\n%s\n\n" % " ".join('"%s"' % a for a in cmd))
                jf.flush()
                r = subprocess.run(cmd, stdout=jf, stderr=subprocess.STDOUT,
                                   timeout=DELAI_MAX_S)
            code = r.returncode
        except subprocess.TimeoutExpired:
            msg = ("ABANDON apres %d h sans resultat — RealityScan attend probablement une "
                   "interaction (connexion Epic ?). Ouvre-le une fois a la main."
                   % (DELAI_MAX_S // 3600))
            print("  " + msg)
            if sur_fin:
                sur_fin(False, msg)
            return False
        except Exception as e:
            msg = "lancement impossible : %s" % e
            print("  " + msg)
            if sur_fin:
                sur_fin(False, msg)
            return False

        duree = time.time() - debut
        # ⚠ LE CODE DE RETOUR NE SUFFIT PAS. RealityScan rend 0 dans des cas où rien n'a
        # été produit (alignement sans solution, par exemple). Le SEUL fait qui compte est
        # l'existence d'un fichier exploitable — même leçon que le py_compile satisfait
        # d'un fichier vide.
        if os.path.isfile(sortie) and os.path.getsize(sortie) > 1024:
            # ⚠ DURÉE EN SECONDES quand c'est court. « 0 min » masquait la seule chose qui
            # comptait : un calcul anormalement rapide n'a pas calculé grand-chose.
            duree_txt = ("%d s" % duree) if duree < 600 else ("%d min" % (duree / 60))
            msg = ("maillage produit en %s (%.1f Mo)"
                   % (duree_txt, os.path.getsize(sortie) / 1e6))
            print("  " + msg)
            if duree < 300:
                print("  ⚠ TRES RAPIDE pour une reconstruction — ouvre le maillage avant de")
                print("    le croire, et lis %s" % journal)
            if sur_fin:
                sur_fin(True, msg)
            return True

        msg = ("AUCUN maillage apres %d min (code %s). Cause la plus frequente : les photos "
               "ne s'alignent pas — il faut des prises de vue AUTOUR du sujet, avec "
               "recouvrement. Un panorama pris d'un point fixe ne peut rien donner. "
               "Detail : %s" % (duree / 60, code, journal))
        print("  " + msg)
        if sur_fin:
            sur_fin(False, msg)
        return False


if __name__ == "__main__":
    # Usage direct :  python traitement.py <dossier_du_travail>
    if len(sys.argv) < 2:
        ok, r = pret()
        print("RealityScan : %s" % (r if not ok else "trouve — %s" % r))
        print("Export      : %s" % ("autonome (%s)" % PARAMS if autonome()
                                    else "reglages non enregistres — 1er passage avec "
                                         "interface visible"))
        print("Usage : python traitement.py <dossier contenant `photos`>")
        sys.exit(0 if ok else 1)
    sys.exit(0 if traiter(sys.argv[1]) else 1)
