# -*- coding: utf-8 -*-
"""
patch_pano_sphere.py — corrige DEUX défauts de l'assembleur de panoramas (2026-07-27).

Constatés sur le vol de relief stéréo du 2026-07-27, en comparant les deux yeux :

DÉFAUT 1 — GÉOMÉTRIE DE SORTIE VARIABLE.
    `pano_modify --fov=AUTO --canvas=AUTO --crop=AUTO` dimensionne et recadre la sortie
    SELON LE CONTENU. Deux panoramas du même lieu, pris à 90 secondes d'intervalle, sont
    sortis en 12000×6000 et 12000×3282. Une visionneuse plaque l'image sur une sphère en
    supposant 360°×180° : la seconde était donc ÉTIRÉE de 98° à 180°, plaçant tout le
    paysage à la mauvaise hauteur. Pour une paire stéréo c'est rédhibitoire — les deux
    yeux ne voient plus le même monde et ne peuvent pas fusionner.
    → On impose `--fov=360x180` et un canevas 2:1 FIXE, indépendant du contenu.
    → Plafond ramené à 8192 px : c'est la taille de texture maximale d'un GPU mobile.
      Au-delà, le navigateur d'un casque autonome réduit l'image ou refuse de l'afficher —
      les 12000 px n'étaient donc jamais vus, ils coûtaient juste de la mémoire.

DÉFAUT 2 — LES TROUS ENCLAVÉS N'ÉTAIENT JAMAIS COMBLÉS.
    `_remplir_ciel` ne remplissait qu'AU-DESSUS du premier pixel couvert et AU-DESSOUS du
    dernier. Un trou situé ENTRE deux zones couvertes — le cas au zénith, où la rangée
    haute de photos laisse des lobes non couverts — restait NOIR. C'est ce qu'on voit sur
    l'œil gauche du 2026-07-27.
    → Interpolation verticale des trous intérieurs, en plus des fondus d'extrémité.
    Et la détection de couverture ne dépend plus du seul canal alpha : sans alpha (selon
    ce que PIL lit du TIFF d'enblend), la fonction sortait AUSSITÔT sans rien combler, en
    silence. Le noir pur sert désormais de repli.

IDEMPOTENT (relancé, il ne fait rien), sauvegarde `.avant_sphere`, et `compile()` du
résultat AVANT écriture : un module cassé stopperait toute la chaîne panorama.

Usage :  cd /root/cineflight_web && python3 patch_pano_sphere.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")

ANCIEN_MODIFY = '''        _run(["pano_modify", "--projection=2", "--fov=AUTO",
              "--canvas=AUTO", "--crop=AUTO", "-o", pto, pto], log)'''

NOUVEAU_MODIFY = '''        # SPHERE COMPLETE (correctif 2026-07-27) — voir patch_pano_sphere.py.
        # Avant : --fov=AUTO --canvas=AUTO --crop=AUTO, donc une geometrie de sortie
        # qui DEPENDAIT du contenu (12000x6000 pour un oeil, 12000x3282 pour l'autre).
        # Une visionneuse plaque l'image sur une sphere en supposant 360x180 : la
        # seconde etait etiree, et la paire stereo infusionnable. On impose donc la
        # geometrie, quel que soit le contenu.
        # 8192 px = taille de texture maximale d'un GPU mobile (casque autonome) :
        # au-dela, l'image est reduite ou refusee par le navigateur.
        _larg = min(int(largeur_max), 8192)
        _haut = _larg // 2
        _run(["pano_modify", "--projection=2", "--fov=360x180",
              "--canvas=%dx%d" % (_larg, _haut),
              "--crop=0,%d,0,%d" % (_larg, _haut), "-o", pto, pto], log)'''

NOUVEAU_FILL = '''def _remplir_ciel(im, log=print):
    # Bouche TOUTES les zones non couvertes d'un panorama equirectangulaire.
    #
    # CORRECTIF 2026-07-27 (voir patch_pano_sphere.py) :
    #  - les trous ENCLAVES entre deux zones couvertes restaient noirs : seules les
    #    extremites haute et basse de chaque colonne etaient traitees ;
    #  - sans canal alpha, la fonction sortait aussitot SANS RIEN COMBLER, en silence.
    #    Le noir pur sert desormais de repli pour detecter la couverture.
    #  - travail en uint8 : la version en float32 demandait plus de 1 Go sur une image
    #    12000x6000, soit toute la memoire de cette machine.
    import numpy as np
    from PIL import Image, ImageFilter
    if im.mode == "RGBA":
        _a = np.asarray(im)
        rgb = _a[:, :, :3].astype(np.uint8, copy=True)
        covered = _a[:, :, 3] > 8
        del _a
    else:
        rgb = np.asarray(im.convert("RGB")).astype(np.uint8, copy=True)
        covered = rgb.max(axis=2) > 8      # enblend laisse le non-couvert en noir pur
    H, W, _ = rgb.shape
    nb = int((~covered).sum())
    if nb == 0 or not covered.any():
        return Image.fromarray(rgb, "RGB")
    cols = np.arange(W)
    any_col = covered.any(axis=0)
    top_idx = np.argmax(covered, axis=0)
    bot_idx = H - 1 - np.argmax(covered[::-1, :], axis=0)
    top_col = rgb[top_idx, cols].astype(np.float32)
    bot_col = rgb[bot_idx, cols].astype(np.float32)
    zc = top_col[any_col].mean(axis=0)
    nc = bot_col[any_col].mean(axis=0)
    lignes = np.arange(H)
    for x in range(W):
        if not any_col[x]:
            g = (lignes / max(1.0, H - 1.0))[:, None]
            rgb[:, x, :] = np.clip(zc[None, :] * (1 - g) + nc[None, :] * g, 0, 255).astype(np.uint8)
            continue
        colc = covered[:, x]
        t = int(top_idx[x]); b = int(bot_idx[x])
        # 1) TROUS INTERIEURS : interpolation verticale entre pixels couverts.
        interieur = ~colc
        interieur[:t] = False
        interieur[b + 1:] = False
        if interieur.any():
            src = np.where(colc)[0]
            dst = np.where(interieur)[0]
            colonne = rgb[:, x, :].astype(np.float32)
            for c in range(3):
                colonne[dst, c] = np.interp(dst, src, colonne[src, c])
            rgb[dst, x, :] = np.clip(colonne[dst], 0, 255).astype(np.uint8)
        # 2) EXTREMITES : fondu vers la couleur moyenne du zenith / du nadir.
        if t > 0:
            r = np.arange(t); f = (r / float(t))[:, None]
            rgb[r, x, :] = np.clip(zc[None, :] * (1 - f) + top_col[x][None, :] * f, 0, 255).astype(np.uint8)
        if b < H - 1:
            r = np.arange(b + 1, H); f = ((r - b) / float(H - 1 - b))[:, None]
            rgb[r, x, :] = np.clip(bot_col[x][None, :] * (1 - f) + nc[None, :] * f, 0, 255).astype(np.uint8)
    base = Image.fromarray(rgb, "RGB")
    # Flou LIMITE aux zones comblees : le paysage reel doit rester net.
    rayon = max(2, W // 1200)
    flou = np.asarray(base.filter(ImageFilter.GaussianBlur(radius=rayon)))
    rgb[~covered] = flou[~covered]
    log("Fill sky: %d px combles (%.1f pct)" % (nb, 100.0 * nb / (H * W)))
    return Image.fromarray(rgb, "RGB")
'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "SPHERE COMPLETE (correctif 2026-07-27)" in src:
        print("RIEN A FAIRE : le correctif est deja applique.")
        return 0

    if ANCIEN_MODIFY not in src:
        print("ECHEC : la ligne pano_modify attendue est introuvable — RIEN modifie.")
        print("        (le fichier a peut-etre deja ete edite a la main)")
        return 1
    out = src.replace(ANCIEN_MODIFY, NOUVEAU_MODIFY, 1)

    # Remplacement de la fonction entiere : de sa definition jusqu'au prochain 'def ' en
    # colonne 0. Decoupe TEXTUELLE volontaire — pas d'AST : on veut un echec net et
    # visible si le fichier ne ressemble pas a ce qu'on attend.
    d = out.find("def _remplir_ciel(")
    if d < 0:
        print("ECHEC : _remplir_ciel introuvable — RIEN modifie."); return 1
    f = out.find("\ndef ", d + 1)
    if f < 0:
        print("ECHEC : fin de _remplir_ciel introuvable — RIEN modifie."); return 1
    out = out[:d] + NOUVEAU_FILL + out[f + 1:]

    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_sphere")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : sphere complete 360x180 imposee (max 8192 px) + trous interieurs combles.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_sphere")
    print("Redemarre :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
