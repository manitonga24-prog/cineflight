# -*- coding: utf-8 -*-
"""
patch_pano_reessai.py — un plantage d'outil ne doit pas coûter la bonne géométrie (2026-07-28).

CE QUI S'EST PASSÉ, MESURÉ. Deux panoramas d'une même paire stéréo, 61 photos chacun,
mêmes angles, même lieu, assemblés à la suite :

    277a3c293f89  voie angles_seuls       Fill sky  9,0 %   -> horizon juste
    eca54f3db18e  voie angles_optimises   Fill sky 30,7 %   -> SPHÈRE BASCULÉE

La voie stricte du second a été abandonnée sur :

    !! tentative angles_seuls abandonnee : Echec enblend (code 3221225477)

`3221225477` = `0xC0000005` = violation d'accès mémoire. **`enblend` ne l'a pas refusée, il
s'est planté.** Le repli a donc pris le relais comme prévu — et `autooptimiser -n` a
retouché la géométrie jusqu'à faire basculer la sphère, exactement le défaut consigné le
2026-07-27 (« un horizon en tente »).

DEUX CORRECTIFS.

1. **RÉESSAYER UN PLANTAGE, PAS UN REFUS.** Un code de sortie non nul « ordinaire » signifie
   que l'outil a examiné le travail et l'a rejeté : le refaire ne changerait rien. Un
   plantage natif, lui, est souvent passager — mémoire, fragmentation, course interne. On ne
   réessaie donc QUE dans ce cas, reconnaissable à son code : négatif sous Unix (signal),
   au-delà de 0xC0000000 sous Windows. Distinguer les deux est tout l'intérêt : réessayer
   aveuglément doublerait l'attente sur chaque vraie erreur.

2. **DIRE QU'ON A CHANGÉ DE VOIE.** Le choix se faisait en silence, et les seuls contrôles
   en aval sont « le fichier existe » et « il fait 2:1 » — une sphère pivotée passe les deux
   sans difficulté. Sur une paire stéréo, deux yeux assemblés par des voies différentes sont
   inutilisables : il faut que ça se voie dans le journal, pas dans le casque.

⚠ CE QUE CE CORRECTIF NE FAIT PAS. Il ne REJETTE pas un résultat trop rempli. Le taux de
ciel comblé est le bon indicateur — 9 % contre 30,7 % l'a montré — mais il n'est connu
qu'APRÈS le rendu complet, et rejeter imposerait de tout refaire dans une autre voie, vingt
minutes de plus. C'est une décision de conception, pas une correction de défaut ; elle
mérite d'être prise à froid.

IDEMPOTENT, sauvegarde `cine_panorama_stitch.py.avant_reessai`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_reessai.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "cine_panorama_stitch.py")

RUN_AVANT = '''    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("Echec %s (code %d):\\n%s\\n%s" % (
            cmd[0], r.returncode, r.stdout[-400:], r.stderr[-1500:]))
    return r.stdout'''

RUN_APRES = '''    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0 and _est_plantage(r.returncode):
        # PLANTAGE, PAS REFUS : on retente UNE fois. Mesure du 2026-07-28 — `enblend` est
        # mort en 0xC0000005 sur un jeu de 61 couches, puis a fonctionne sur le meme jeu
        # par une autre voie. Renoncer a la bonne geometrie a la premiere secousse a
        # produit une sphere basculee, livree sans que rien ne le signale.
        log("!! %s a PLANTE (code %d) — seconde tentative" % (cmd[0], r.returncode))
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0:
            log("   la seconde tentative a abouti")
    if r.returncode != 0:
        raise RuntimeError("Echec %s (code %d):\\n%s\\n%s" % (
            cmd[0], r.returncode, r.stdout[-400:], r.stderr[-1500:]))
    return r.stdout'''

# Inséré juste avant `def _run`.
PLANTAGE = '''def _est_plantage(code):
    """
    Un code de sortie trahit-il un PLANTAGE plutot qu'un refus ?

    Un outil qui examine le travail et le rejette rend un petit code non nul : le refaire
    ne changerait rien. Un processus tue par un signal (Unix, code negatif) ou victime
    d'une exception native (Windows, >= 0xC0000005) n'a pas juge quoi que ce soit — il est
    mort. Ces cas-la valent une seconde tentative, les autres non.
    """
    return code < 0 or code >= 0xC0000000


'''

VOIE_AVANT = '''            log("!! voie %s : echec, on essaie la suivante" % etiquette)
        if voie is None:
            raise RuntimeError("toutes les voies d'assemblage ont echoue")
        log("VOIE D'ASSEMBLAGE : %s" % voie)'''

VOIE_APRES = '''            log("!! voie %s : echec, on essaie la suivante" % etiquette)
        if voie is None:
            raise RuntimeError("toutes les voies d'assemblage ont echoue")
        # ⚠ UN REPLI SE DIT. Les controles en aval — le fichier existe, il fait 2:1 — ne
        # voient PAS une sphere pivotee. Sur une paire stereo, deux yeux assembles par des
        # voies differentes ne fusionnent pas ; il faut que ca apparaisse dans le journal
        # et non dans le casque. Mesure du 2026-07-28 : angles_seuls 9 % de ciel comble,
        # angles_optimises 30,7 % sur le meme lieu.
        if tentatives and voie != tentatives[0][0]:
            log("!! REPLI D'ASSEMBLAGE : la voie stricte (%s) a echoue, resultat produit "
                "par %s. La geometrie a pu etre retouchee — sur une paire stereo, verifie "
                "que les DEUX yeux portent la meme voie." % (tentatives[0][0], voie))
        log("VOIE D'ASSEMBLAGE : %s" % voie)'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "_est_plantage" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    manque = []
    if RUN_AVANT not in src:
        manque.append("le corps de _run")
    if VOIE_AVANT not in src:
        manque.append("la boucle de choix de voie")
    if "def _run(cmd, log):" not in src:
        manque.append("la definition de _run")
    if manque:
        print("ECHEC : introuvable — %s." % ", ".join(manque))
        print("        Le fichier differe de ce qui etait prevu, RIEN modifie.")
        return 1

    out = src.replace("def _run(cmd, log):", PLANTAGE + "def _run(cmd, log):", 1)
    out = out.replace(RUN_AVANT, RUN_APRES, 1)
    out = out.replace(VOIE_AVANT, VOIE_APRES, 1)
    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_reessai")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : plantage reessaye une fois, repli signale au journal.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_reessai")
    print()
    print("⚠ La compilation ne prouve rien. L'atelier telecharge ce script a chaque")
    print("  travail : il suffit de relancer un panorama pour l'eprouver.")
    print("  Aucun redemarrage du service n'est necessaire.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
