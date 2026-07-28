# -*- coding: utf-8 -*-
"""
patch_modele3d_reception.py — un jeu incomplet ne part PAS en reconstruction (2026-07-28).

CE QUI ÉTAIT CASSÉ, ET COMMENT ON L'A VU. L'envoi par lots (imposé par la limite de 100 Mo
de Cloudflare) crée le travail avec le PREMIER lot, puis verse les suivants. Or
`creer_modele` déclarait aussitôt l'état `en_attente_ressources` — précisément l'un des
états que `/api/travaux` propose à l'atelier.

Résultat mesuré : l'expéditeur annonce `total 30`, et l'ouvrier, qui interroge toutes les
minutes, télécharge **11 photos** — celles arrivées quand il a regardé. Il reconstruit
dessus et dépose un modèle. Aucune ligne, nulle part, ne signale qu'il manque les deux
tiers du jeu. Le seul indice était un décompte dans une trace de progression.

⚠ PORTÉE. Ce n'est pas un défaut d'outil d'essai : après un vol, la course entre l'envoi
et l'interrogation se produit à chaque capture. Le client aurait reçu un modèle troué,
calculé sur un tiers de ses photos, sans avertissement.

CE QUE ÇA CHANGE. `creer_modele` pose l'état `reception` et ne lance rien. La décision de
reconstruire appartient à `/finir`, seul endroit qui sait le jeu complet — ce qui était
déjà écrit dans la documentation, mais pas dans le code.

IDEMPOTENT, sauvegarde `modele3d.py.avant_reception`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_modele3d_reception.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modele3d.py")

AVANT = '''    res = ressources()
    out = outils_presents()
    capable = res["ram_go"] >= RAM_MIN_GO and out["colmap"]
    _maj(mid, titre=titre, photos=total, cree=int(time.time()),
         ressources=res, outils=out,
         etat=("en_cours" if capable else "en_attente_ressources"),
         etape=("préparation" if capable else "machine insuffisante"),
         pct=0)
    if capable:
        threading.Thread(target=_reconstruire, args=(mid, dossier), daemon=True).start()
    return JSONResponse({"modele_id": mid, "url": "/modele3d/%s" % mid,
                         "reconstruction_lancee": capable})'''

APRES = '''    # ⚠ ETAT « reception », ET RIEN D'AUTRE. Ce point d'entree ne recoit que le PREMIER
    # lot. Declarer ici un etat que /api/travaux accepte rend le travail visible a
    # l'atelier AVANT l'arrivee des lots suivants — l'ouvrier telecharge alors ce qui se
    # trouve la et reconstruit sur un jeu tronque, en silence.
    # CONSTATE le 2026-07-28 : 11 photos sur 30, modele deposé, aucune alerte.
    # La decision de reconstruire appartient a /finir, seul endroit qui sait le jeu complet.
    _maj(mid, titre=titre, photos=total, cree=int(time.time()),
         etat="reception", etape="reception des photos (%d)" % total, pct=0)
    return JSONResponse({"modele_id": mid, "url": "/modele3d/%s" % mid,
                         "reconstruction_lancee": False, "etat": "reception"})'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : modele3d.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if 'etat="reception", etape="reception des photos (%d)" % total, pct=0' in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if AVANT not in src:
        print("ECHEC : le bloc attendu est introuvable dans creer_modele —")
        print("        fichier different de celui prevu, RIEN modifie.")
        return 1
    if "/finir" not in src:
        print("ECHEC : patch_modele3d_lots.py n'a pas ete applique. Sans /finir, poser")
        print("        l'etat `reception` rendrait tout jeu INVISIBLE pour toujours.")
        return 1

    out = src.replace(AVANT, APRES, 1)
    try:
        compile(out, "modele3d.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_reception")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : un jeu n'est propose a l'atelier qu'apres /finir.")
    print("Sauvegarde : modele3d.py.avant_reception")
    print("Redemarre :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
