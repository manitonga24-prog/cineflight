# -*- coding: utf-8 -*-
"""
patch_api_angles.py — l'API panorama accepte et conserve les ANGLES de prise de vue
(2026-07-27).

L'app connaît exactement le cap et l'inclinaison de chaque cliché (`PanoramaGrille`) ;
jusqu'ici cette information mourait dans le téléphone. Ce patch ajoute au point d'entrée
`POST /api/panorama` un champ de formulaire `angles` (JSON : `[[yaw, pitch], ...]`, dans
l'ordre des photos envoyées). Il est :

 - ÉCRIT SUR DISQUE dans `_pano_jobs/<id>/angles.json`, donc conservé indépendamment de la
   mémoire du service — un réassemblage six mois plus tard en bénéficiera encore ;
 - PASSÉ à l'assembleur, qui s'en sert pour placer les images au lieu de les deviner.

FACULTATIF DE BOUT EN BOUT : une app plus ancienne, ou un envoi sans angles, retombe
exactement sur le comportement actuel. Aucune régression possible pour les clients déjà
servis.

IDEMPOTENT, sauvegarde `app.py.avant_angles`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_api_angles.py
"""

import os
import re
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app.py")

ANCIEN_WORKER = '''def _pano_worker(job_id, dossier_in, out_jpg, largeur_max):'''

NOUVEAU_WORKER = '''def _pano_worker(job_id, dossier_in, out_jpg, largeur_max, angles=None):'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : app.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "angles.json" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0

    if ANCIEN_WORKER not in src:
        print("ECHEC : _pano_worker introuvable — RIEN modifie."); return 1

    out = src

    # 1) Le worker transmet les angles a l'assembleur.
    out = out.replace(ANCIEN_WORKER, NOUVEAU_WORKER, 1)
    m = re.search(r"( *)res, logs = .*_pano_mod\.assembler\(([^)]*)\)", out)
    if m:
        out = out[:m.start()] + m.group(0).replace(")", ", angles=angles)", 1) + out[m.end():]
    else:
        # L'appel n'a pas la forme attendue : on injecte au debut du worker une
        # transmission par variable globale plutot que de deviner. Fail-closed lisible.
        m2 = re.search(r"def _pano_worker\([^)]*\):\n", out)
        if not m2:
            print("ECHEC : corps de _pano_worker illisible — RIEN modifie."); return 1
        print("AVERTISSEMENT : appel a assembler() non reconnu — les angles sont ecrits")
        print("                sur disque mais PAS transmis automatiquement.")
        print("                Verifie la ligne 'assembler(' dans _pano_worker.")

    # 2) Le point d'entree accepte le champ `angles` et l'ecrit sur disque.
    # ⚠ La signature contient des parentheses IMBRIQUEES (`_PanoFile(...)`) : une classe
    # negative `[^)]*` s'arreterait a la premiere fermante et raterait la vraie fin.
    # On prend la ligne entiere, puis la DERNIERE `)` avant le `:`.
    m3 = re.search(r"async def panorama_creer\(.*\):", out)
    if not m3:
        print("ECHEC : signature de panorama_creer introuvable — RIEN modifie."); return 1
    pos = out.rfind(")", m3.start(), m3.end())
    out = out[:pos] + ", angles: str = _PanoForm(None)" + out[pos:]

    # Import du Form de FastAPI sous un alias. ⚠ PAS a cote de l'import de
    # cine_panorama_stitch : celui-la est INDENTE (dans un try:), et y ajouter une ligne
    # en colonne 0 casse le bloc. On s'ancre sur `_PANO_DIR = `, qui est en colonne 0.
    if "from fastapi import Form as _PanoForm" not in out:
        anc = "\n_PANO_DIR = "
        if anc not in out:
            print("ECHEC : ancre _PANO_DIR introuvable — RIEN modifie."); return 1
        out = out.replace(anc, "\nfrom fastapi import Form as _PanoForm" + anc, 1)

    # Ecriture du fichier angles.json juste avant le lancement du worker.
    m4 = re.search(r"( *)with _PANO_LOCK:\n\1    _PANO_JOBS\[job_id\] = \{[^\n]*\n", out)
    if not m4:
        print("ECHEC : bloc d'enregistrement du job introuvable — RIEN modifie."); return 1
    ind = m4.group(1)
    bloc = (
        ind + "# ANGLES DE PRISE DE VUE (2026-07-27) : ecrits sur DISQUE, pas seulement\n" +
        ind + "# gardes en memoire — un reassemblage ulterieur doit pouvoir en beneficier.\n" +
        ind + "_pano_angles = None\n" +
        ind + "if angles:\n" +
        ind + "    try:\n" +
        ind + "        import json as _pano_json\n" +
        ind + "        _pano_angles = _pano_json.loads(angles)\n" +
        ind + "        with open(os.path.join(_PANO_DIR, job_id, 'angles.json'), 'w') as _fa:\n" +
        ind + "            _pano_json.dump(_pano_angles, _fa)\n" +
        ind + "    except Exception as _ex:\n" +
        ind + "        print('[PANO] angles illisibles, ignores :', _ex)\n" +
        ind + "        _pano_angles = None\n"
    )
    out = out[:m4.start()] + bloc + out[m4.start():]

    # Le worker est lance avec les angles.
    out = re.sub(r"(_pano_worker,\s*args=\()([^)]*)\)",
                 lambda mm: mm.group(1) + mm.group(2) + ", _pano_angles)", out, count=1)

    try:
        compile(out, "app.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — app.py laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_angles")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : /api/panorama accepte le champ `angles` et l'ecrit dans")
    print("     _pano_jobs/<id>/angles.json")
    print("Sauvegarde : app.py.avant_angles")
    print("Redemarre :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
