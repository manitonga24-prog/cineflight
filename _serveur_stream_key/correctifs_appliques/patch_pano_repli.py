# -*- coding: utf-8 -*-
"""
patch_pano_repli.py — repli en cascade + sortie atomique (2026-07-27).

DEUX DÉFAUTS CONSTATÉS AU RÉASSEMBLAGE DES DEUX YEUX.

1. **`autooptimiser` ruine de bonnes positions.** Les deux panoramas d'une paire ont des
   angles IDENTIQUES ; l'un est passé, l'autre a échoué sur
   `enblend: excessive image overlap detected`. Partant des positions écrites — qui sont
   justes — l'optimiseur a empilé deux photos en suivant de mauvais points de
   correspondance, ceux du ciel qui n'a pas de texture. C'est précisément ce qu'on voulait
   éviter en fournissant les angles, et le laisser optimiser librement le réintroduit.
   → Cascade de tentatives : angles + optimisation → angles SEULS (les positions du drone
   sont fiables : boussole et nacelle) → automatique complet. On s'arrête à la première
   qui aboutit, et le journal dit laquelle.

2. **Un échec détruisait le panorama existant.** La sortie était écrite directement sur
   `panorama.jpg` ; un réassemblage raté laissait le travail SANS image, alors qu'il en
   avait une avant. Les liens `/vr/` et `/vr3d/` déjà envoyés à un client cessaient de
   fonctionner à cause d'une tentative d'amélioration.
   → Écriture dans un fichier temporaire, puis `os.replace` : l'ancien résultat n'est
   remplacé qu'une fois le nouveau complet.

IDEMPOTENT, sauvegarde `.avant_repli`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_repli.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")

NOUVELLE = '''
def _tenter_montage(pto_src, work, out_tif, larg, haut, etiquette, log):
    """Une tentative complete pano_modify -> nona -> enblend. True si elle aboutit."""
    pto = os.path.join(work, "essai_%s.pto" % etiquette)
    shutil.copyfile(pto_src, pto)
    try:
        _run(["pano_modify", "--projection=2", "--fov=360x180",
              "--canvas=%dx%d" % (larg, haut),
              "--crop=0,%d,0,%d" % (larg, haut), "-o", pto, pto], log)
        remap = os.path.join(work, "remap_%s" % etiquette)
        _run(["nona", "-m", "TIFF_m", "-o", remap, pto], log)
        couches = sorted(glob.glob(remap + "*.tif"))
        if not couches:
            log("!! %s : nona n'a produit aucune couche" % etiquette)
            return False
        _run(["enblend", "--compression=LZW", "-f", "%dx%d" % (larg, haut),
              "-o", out_tif] + couches, log)
        return os.path.exists(out_tif)
    except Exception as ex:
        # `excessive image overlap` arrive ici : deux photos empilees par l'optimiseur.
        log("!! tentative %s abandonnee : %s" % (etiquette, str(ex).split(chr(10))[0]))
        return False


def assembler(dossier, out_jpg, largeur_max=12000, qualite=92, remplir_ciel=True,
              angles=None, log=print):
    """
    @param angles liste [(yaw_deg, pitch_deg), ...] ALIGNEE sur l'ordre des photos triees.
        None -> chaine automatique. Voir patch_pano_angles.py et patch_pano_repli.py.
    """
    t0 = time.time()
    photos = sorted(p for p in glob.glob(os.path.join(dossier, "*"))
                    if p.lower().endswith((".jpg", ".jpeg", ".png", ".tif", ".tiff"))
                    and os.path.abspath(p) != os.path.abspath(out_jpg))
    if len(photos) < 2:
        raise RuntimeError("Moins de 2 photos dans %s" % dossier)
    log("Photos: %d" % len(photos))
    if angles is not None and len(angles) != len(photos):
        log("!! %d angles pour %d photos : angles IGNORES" % (len(angles), len(photos)))
        angles = None
    work = tempfile.mkdtemp(prefix="pano_")
    try:
        base = os.path.join(work, "base.pto")
        _run(["pto_gen", "-o", base] + photos, log)
        _run(["cpfind", "--multirow", "--celeste", "-o", base, base], log)
        _run(["cpclean", "-o", base, base], log)

        larg = min(int(largeur_max), 8192)
        haut = larg // 2
        out_tif = os.path.join(work, "pano.tif")

        # CASCADE. Chaque tentative repart du projet apres cpfind/cpclean : une tentative
        # ratee ne contamine pas la suivante.
        tentatives = []
        if angles is not None:
            avec_opt = os.path.join(work, "angles_opt.pto")
            shutil.copyfile(base, avec_opt)
            if _ecrire_positions(avec_opt, angles, log):
                sans_opt = os.path.join(work, "angles_seuls.pto")
                shutil.copyfile(avec_opt, sans_opt)
                try:
                    _run(["autooptimiser", "-n", "-o", avec_opt, avec_opt], log)
                    tentatives.append(("angles_optimises", avec_opt))
                except Exception as ex:
                    log("!! autooptimiser a echoue (%s) : on garde les positions brutes" % ex)
                # Positions du drone SEULES : boussole + nacelle, sans optimisation.
                # C'est le repli qui sauve les cas ou l'optimiseur empile des photos.
                tentatives.append(("angles_seuls", sans_opt))
        if not tentatives or True:
            auto = os.path.join(work, "auto.pto")
            shutil.copyfile(base, auto)
            _run(["autooptimiser", "-a", "-m", "-l", "-s", "-o", auto, auto], log)
            tentatives.append(("auto", auto))

        voie = None
        for etiquette, pto in tentatives:
            if os.path.exists(out_tif):
                os.remove(out_tif)
            if _tenter_montage(pto, work, out_tif, larg, haut, etiquette, log):
                voie = etiquette
                break
            log("!! voie %s : echec, on essaie la suivante" % etiquette)
        if voie is None:
            raise RuntimeError("toutes les voies d'assemblage ont echoue")
        log("VOIE D'ASSEMBLAGE : %s" % voie)

        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        im = Image.open(out_tif)
        if remplir_ciel:
            im = _remplir_ciel(im, log=log)
        elif im.mode not in ("RGB", "L"):
            im = im.convert("RGB")
        # SORTIE ATOMIQUE : on n'ecrase le resultat existant qu'une fois le nouveau
        # complet. Un echec ne doit jamais laisser un travail SANS image alors qu'il en
        # avait une — les liens deja envoyes a un client cesseraient de fonctionner.
        tmp_jpg = out_jpg + ".nouveau"
        im.save(tmp_jpg, "JPEG", quality=qualite)
        os.replace(tmp_jpg, out_jpg)
        wpx, hpx = im.size
        dt = time.time() - t0
        log("OK -> %s (%dx%d) en %.1fs [voie=%s]" % (out_jpg, wpx, hpx, dt, voie))
        return {"ok": True, "largeur": wpx, "hauteur": hpx, "voie": voie,
                "duree_s": round(dt, 1), "nb_photos": len(photos)}
    finally:
        shutil.rmtree(work, ignore_errors=True)
'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : cine_panorama_stitch.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "_tenter_montage" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if "_ecrire_positions" not in src:
        print("ECHEC : patch_pano_angles.py n'a pas ete applique — RIEN modifie."); return 1
    # On verifie l'USAGE, pas la forme de l'import : le module importe sur une ligne
    # groupee (`import glob, os, tempfile, time, shutil`), qu'un `"import shutil"`
    # litteral ne trouve pas. Chercher la forme plutot que le fait est une erreur
    # classique de patch — elle fait echouer un correctif parfaitement applicable.
    if "shutil." not in src:
        print("ECHEC : shutil n'est pas disponible dans ce module — RIEN modifie."); return 1

    d = src.find("def assembler(")
    if d < 0:
        print("ECHEC : fonction assembler introuvable — RIEN modifie."); return 1
    f = src.find("\ndef ", d + 1)
    if f < 0:
        f = len(src) - 1
    out = src[:d] + NOUVELLE.lstrip("\n") + src[f + 1:]

    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_repli")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : cascade angles+optim -> angles seuls -> auto, et sortie atomique.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_repli")
    return 0


if __name__ == "__main__":
    sys.exit(main())
