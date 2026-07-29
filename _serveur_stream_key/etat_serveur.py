# -*- coding: utf-8 -*-
"""
etat_serveur.py — quels correctifs sont RÉELLEMENT appliqués ? (2026-07-28)

POURQUOI. Une vingtaine de correctifs se sont empilés sur ce serveur en trois jours.
Chacun est idempotent et fail-closed, mais leur ÉTAT GLOBAL n'existe nulle part : pour
savoir si `app.py` porte tel changement, il fallait se souvenir de l'avoir appliqué. C'est
ce qui a produit la parenthèse fausse (validée par une compilation qui ne pouvait pas la
voir) et le `File` manquant (502 sur tout le site).

CE QUE FAIT CE SCRIPT. Il cherche dans chaque fichier le MARQUEUR que chaque correctif y a
laissé, et rend un tableau appliqué/manquant. Il ne modifie RIEN. C'est l'inventaire à
faire avant de figer l'état dans git — on ne photographie pas un état qu'on n'a pas lu.

Usage :  cd /root/cineflight_web && python3 etat_serveur.py
Code de sortie : 0 si tout est applique, 1 sinon.
"""

import os
import sys

ICI = os.path.dirname(os.path.abspath(__file__))

# (fichier, correctif, marqueur laissé par le correctif)
VERIFS = [
    ("app.py", "file de travaux 3D (travaux_ouvrier)", '@app.get("/api/travaux")'),
    ("app.py", "transfert fichier par fichier (travaux_fichiers)", "/api/travaux/{mid}/liste"),
    ("app.py", "parenthese de l'assemblage (pano_parenthese)", "int(largeur_max), _pano_angles"),
    ("app.py", "delegation a l'atelier (pano_delegue)", "assemblage_sur_pc.flag"),
    ("app.py", "file de travaux PANORAMA (travaux_pano)", '"/api/travaux_pano"'),
    ("app.py", "import File/UploadFile (travaux_pano_import)", "_OuvrierUpload"),
    ("modele3d.py", "envoi par lots (/ajouter, /finir)", "/api/modele3d/{mid}/ajouter"),
    ("modele3d.py", "etat reception avant /finir", 'etat="reception"'),
    ("pano_vr.py", "page d'attente (etat en_cours)", "RECHARGE_S"),
    ("cine_panorama_stitch.py", "sphere complete forcee (fov 360x180)", "--fov=360x180"),
    ("cine_panorama_stitch.py", "raccord 0/360 (wrap)", "--wrap=horizontal"),
    ("cine_panorama_stitch.py", "assemblage guide par les angles", "_ecrire_positions"),
    ("cine_panorama_stitch.py", "photometrie sur la voie angles", "photometrie egalisee"),
    ("cine_panorama_stitch.py", "reessai d'un plantage", "_est_plantage"),
    ("cine_panorama_stitch.py", "paliers de couture simplifiee", "primary-seam-generator"),
    ("cine_panorama_stitch.py", "options AVANT les fichiers (secours_ordre)", "for options, variante in secours:"),
    ("cine_panorama_stitch.py", "voie ancree (image 0 verrouillee)", "_ancrer_et_optimiser"),
    ("cine_panorama_stitch.py", "voie ancree RETROGRADEE (rang)", "ORDRE ETABLI PAR LA MESURE"),
]

# Présences dont l'ABSENCE est un défaut connu (forme cassée, jamais les deux à la fois).
CONTRE_VERIFS = [
    ("app.py", "⚠ parenthese CASSEE encore presente", "int(largeur_max, _pano_angles)"),
]

# Etats de configuration : ni bons ni mauvais en soi, mais a CONNAITRE.
INFOS = [
    ("assemblage_sur_pc.flag", "delegation ACTIVE : l'atelier PC assemble les panoramas"),
    ("ouvrier_token.txt", "jeton de l'atelier installe"),
    (".venv/bin/python", "environnement virtuel (le service tourne AVEC, pas le python systeme)"),
    (".git", "suivi git LOCAL du serveur"),
]


def lire(nom):
    chemin = os.path.join(ICI, nom)
    if not os.path.exists(chemin):
        return None
    try:
        return open(chemin, encoding="utf-8", errors="replace").read()
    except Exception:
        return None


def main():
    contenus = {}
    manquants = 0

    print("=" * 72)
    print("ETAT DES CORRECTIFS — %s" % ICI)
    print("=" * 72)

    fichier_courant = None
    for fichier, nom, marqueur in VERIFS:
        if fichier not in contenus:
            contenus[fichier] = lire(fichier)
        if fichier != fichier_courant:
            print("\n--- %s %s ---" % (fichier,
                  "(INTROUVABLE)" if contenus[fichier] is None else ""))
            fichier_courant = fichier
        src = contenus[fichier]
        ok = src is not None and marqueur in src
        if not ok:
            manquants += 1
        print("  %-52s %s" % (nom, "applique" if ok else "** MANQUE **"))

    for fichier, nom, marqueur in CONTRE_VERIFS:
        src = contenus.get(fichier) or lire(fichier)
        if src is not None and marqueur in src:
            manquants += 1
            print("\n  %s dans %s" % (nom, fichier))

    print("\n--- configuration ---")
    for nom, description in INFOS:
        present = os.path.exists(os.path.join(ICI, nom))
        print("  %-52s %s" % (description, "OUI" if present else "non"))

    print("\n" + "=" * 72)
    if manquants:
        print("RESULTAT : %d correctif(s) MANQUANT(S). Ne fige pas cet etat dans git :" % manquants)
        print("applique d'abord ce qui manque, puis relance ce script.")
        return 1
    print("RESULTAT : tous les correctifs attendus sont en place.")
    print("L'etat peut etre fige dans git.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
