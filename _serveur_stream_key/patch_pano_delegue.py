# -*- coding: utf-8 -*-
"""
patch_pano_delegue.py — le serveur n'assemble plus, il délègue à l'atelier (2026-07-28).

LE PROBLÈME QUE ÇA ÉVITE. Une fois `patch_travaux_pano.py` en place, l'atelier PC réclame
les panoramas qui n'ont pas encore d'image. Si le serveur lance AUSSI son propre assemblage
en recevant les photos, les deux machines calculent le même travail : le droplet s'écroule
sous une charge qu'il ne peut pas tenir, et le dernier des deux à finir écrase le résultat
de l'autre. Une seule machine doit assembler un panorama donné.

COMMENT. Un fichier-témoin décide, sans toucher au code à chaque fois :

    /root/cineflight_web/assemblage_sur_pc.flag

présent  -> le serveur REÇOIT les photos, les range, et s'arrête là. L'atelier prend la
            suite. L'état passe à `attente_atelier`.
absent   -> comportement d'origine, le serveur assemble lui-même.

⚠ FERMÉ DANS LE BON SENS. Sans le fichier, rien ne change : un déploiement incomplet ne
peut pas laisser les panoramas sans personne pour les assembler. C'est l'INVERSE du jeton
de l'atelier, où l'absence de fichier ferme la porte — ici l'absence garde le comportement
qui fonctionne tout seul.

⚠ À FAIRE APRÈS `patch_pano_parenthese.py` : ce correctif s'applique sur la ligne CORRIGÉE.

IDEMPOTENT, sauvegarde `app.py.avant_delegue`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_delegue.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

AVANT = ("    _pano_threading.Thread(target=_pano_worker, "
         "args=(job_id, din, out_jpg, int(largeur_max), _pano_angles), daemon=True).start()")

APRES = '''    # ── ASSEMBLAGE DELEGUE A L'ATELIER PC (voir patch_pano_delegue.py) ──────────
    # `cpfind` sur 61 photos a occupe 1,26 Go et 15 min de processeur sur ce droplet
    # d'un seul coeur — et ce n'etait que la premiere phase. Quand le fichier-temoin
    # est present, on se contente de RECEVOIR : l'atelier reclame le travail par
    # /api/travaux_pano et depose l'image finie. Deux machines sur le meme panorama
    # s'ecraseraient l'une l'autre.
    _pano_temoin = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "assemblage_sur_pc.flag")
    if os.path.exists(_pano_temoin):
        try:
            _PANO_JOBS[job_id]["etat"] = "attente_atelier"
            _PANO_JOBS[job_id]["etape"] = "photos recues — assemblage sur l'atelier PC"
        except Exception:
            pass
    else:
        _pano_threading.Thread(target=_pano_worker, args=(job_id, din, out_jpg, int(largeur_max), _pano_angles), daemon=True).start()'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "assemblage_sur_pc.flag" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if "int(largeur_max, _pano_angles)" in src:
        print("ECHEC : applique d'abord patch_pano_parenthese.py.")
        print("        Ce correctif porte sur la ligne CORRIGEE. RIEN modifie.")
        return 1
    if AVANT not in src:
        print("ECHEC : la ligne de lancement attendue est introuvable.")
        print("        app.py differe de ce qui etait prevu — RIEN modifie.")
        return 1

    out = src.replace(AVANT, APRES, 1)
    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_delegue")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : l'assemblage est delegable.")
    print("Sauvegarde : app.py.avant_delegue")
    print()
    print("POUR DELEGUER A L'ATELIER :")
    print("  touch /root/cineflight_web/assemblage_sur_pc.flag")
    print("POUR REVENIR AU SERVEUR :")
    print("  rm /root/cineflight_web/assemblage_sur_pc.flag")
    print()
    print("Puis :  systemctl restart cineflight")
    print("⚠ Verifie par un vrai panorama, pas par la compilation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
