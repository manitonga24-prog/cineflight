# -*- coding: utf-8 -*-
"""
patch_pano_angles.py — assemblage GUIDÉ PAR LES ANGLES CONNUS (2026-07-27).

LE PROBLÈME QU'IL RÉSOUT. Jusqu'ici l'app jetait une information qu'elle possédait
exactement — le cap et l'inclinaison de CHAQUE cliché, calculés par `PanoramaGrille` — et
le serveur demandait à Hugin de les redécouvrir à partir des pixels. Deux conséquences,
toutes deux constatées au vol du 2026-07-27 :

 1. **Les deux yeux d'une paire stéréo n'atterrissaient pas dans le même repère.** Hugin
    optimise chaque panorama INDÉPENDAMMENT et choisit lui-même l'origine des azimuts et la
    hauteur d'horizon. Forcer le cap à la CAPTURE ne force pas l'orientation à l'ASSEMBLAGE.
    Mesuré : horizon à +3,9° d'un côté, +1,2° de l'autre — environ 2,6° d'écart vertical,
    soit cinq fois ce qu'un œil humain fusionne confortablement.
 2. **Les rangées hautes se plaçaient mal.** Le ciel n'a presque aucune texture : `cpfind`
    n'y trouve pas de points communs, donc ces photos partent n'importe où. D'où les taches
    noires et rouges dans le ciel de l'œil gauche.

CE QUE FAIT LE CORRECTIF. Quand l'app fournit les angles, on écrit directement les
positions (yaw/pitch/roll) dans le projet Hugin, on ne demande à l'optimiseur que d'AFFINER
à partir de là (`autooptimiser -n`), et on ne le laisse plus ni recentrer ni « redresser »
la scène : le repère est celui du drone, donc **le même pour les deux yeux**.

FAIL-SAFE. Toute étape qui échoue fait retomber sur l'ancienne chaîne automatique, et le
journal DIT quel chemin a été pris (`voie=angles` ou `voie=auto`). Sans angles fournis,
le comportement est strictement inchangé.

⚠ CONVENTION DE SIGNE. Le sens de rotation du lacet dans Hugin par rapport au cap boussole
n'est pas vérifié ici — il se détermine par la MESURE, avec `essai_angles.py`. D'où la
constante `SENS_YAW`, à régler une fois pour toutes sur la foi d'un essai, pas d'une
intuition.

IDEMPOTENT, sauvegarde `.avant_angles`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_pano_angles.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cine_panorama_stitch.py")

NOUVELLE_FONCTION = '''
# Sens de rotation du lacet dans Hugin par rapport au cap boussole du drone.
# -1.0 ou +1.0 : A DETERMINER PAR LA MESURE (essai_angles.py), pas par intuition.
SENS_YAW = -1.0


def _ecrire_positions(pto, angles, log=print):
    """
    Ecrit les positions CONNUES dans le projet Hugin, et demande a l'optimiseur de
    n'AFFINER que les images suivantes (l'image 0 reste l'ancre : c'est elle qui fixe
    le repere, donc le meme pour les deux yeux d'une paire stereo).

    Un fichier .pto contient une ligne 'i ...' par image, avec les champs y= p= r=.
    On les reecrit ; puis la ligne 'v ...' declare les variables a optimiser.
    Manipulation TEXTUELLE volontaire : pas de dependance a la syntaxe de pto_var, dont
    le comportement varie selon les versions de Hugin.
    """
    import re
    lignes = open(pto, encoding="utf-8", errors="ignore").read().splitlines()
    idx = [k for k, l in enumerate(lignes) if l.startswith("i ")]
    if len(idx) != len(angles):
        log("!! positions NON ecrites : %d images dans le projet, %d angles fournis"
            % (len(idx), len(angles)))
        return False
    for n, k in enumerate(idx):
        yaw = SENS_YAW * float(angles[n][0])
        pitch = float(angles[n][1])
        l = lignes[k]
        for champ, val in (("y", yaw), ("p", pitch), ("r", 0.0)):
            motif = r"(?<![A-Za-z])%s-?[0-9.]+" % champ
            remplacement = "%s%.4f" % (champ, val)
            l, nb = re.subn(motif, remplacement, l, count=1)
            if nb == 0:
                l = l + " " + remplacement
        lignes[k] = l
    # Variables a optimiser : tout SAUF l'image 0 (l'ancre du repere).
    vars_opt = " ".join("y%d p%d r%d" % (n, n, n) for n in range(1, len(idx)))
    lignes = [l for l in lignes if not l.startswith("v ")]
    lignes.append("v " + vars_opt)
    lignes.append("")
    open(pto, "w", encoding="utf-8").write("\\n".join(lignes))
    log("Positions ecrites pour %d images (ancre = image 0, sens_yaw=%.0f)"
        % (len(idx), SENS_YAW))
    return True


def assembler(dossier, out_jpg, largeur_max=12000, qualite=92, remplir_ciel=True,
              angles=None, log=print):
    """
    @param angles liste [(yaw_deg, pitch_deg), ...] ALIGNEE sur l'ordre des photos triees.
        Fournie par l'app (grille de panorama). None -> ancienne chaine automatique.
    """
    t0 = time.time()
    photos = sorted(p for p in glob.glob(os.path.join(dossier, "*"))
                    if p.lower().endswith((".jpg", ".jpeg", ".png", ".tif", ".tiff"))
                    and os.path.abspath(p) != os.path.abspath(out_jpg))
    if len(photos) < 2:
        raise RuntimeError("Moins de 2 photos dans %s" % dossier)
    log("Photos: %d" % len(photos))
    if angles is not None and len(angles) != len(photos):
        log("!! %d angles pour %d photos : angles IGNORES (voie=auto)"
            % (len(angles), len(photos)))
        angles = None
    work = tempfile.mkdtemp(prefix="pano_")
    try:
        pto = os.path.join(work, "projet.pto")
        _run(["pto_gen", "-o", pto] + photos, log)
        _run(["cpfind", "--multirow", "--celeste", "-o", pto, pto], log)
        _run(["cpclean", "-o", pto, pto], log)

        voie = "auto"
        if angles is not None:
            try:
                if _ecrire_positions(pto, angles, log):
                    # -n : optimiser LES VARIABLES DECLAREES, en partant de nos positions.
                    # Surtout PAS -a (alignement automatique) ni -l (redressement) ni -s :
                    # ils choisiraient une orientation de sortie propre a ce panorama, et
                    # les deux yeux d'une paire ne tomberaient plus dans le meme repere.
                    _run(["autooptimiser", "-n", "-o", pto, pto], log)
                    voie = "angles"
            except Exception as ex:
                log("!! voie angles impossible (%s) : repli sur l'automatique" % ex)
        if voie == "auto":
            _run(["autooptimiser", "-a", "-m", "-l", "-s", "-o", pto, pto], log)
        log("VOIE D'ASSEMBLAGE : %s" % voie)

        # SPHERE COMPLETE (correctif 2026-07-27) : geometrie de sortie IMPOSEE, jamais
        # deduite du contenu — sinon deux panoramas du meme lieu sortent a des tailles
        # differentes et la paire stereo est infusionnable.
        # 8192 px = taille de texture maximale d'un GPU mobile.
        _larg = min(int(largeur_max), 8192)
        _haut = _larg // 2
        _run(["pano_modify", "--projection=2", "--fov=360x180",
              "--canvas=%dx%d" % (_larg, _haut),
              "--crop=0,%d,0,%d" % (_larg, _haut), "-o", pto, pto], log)

        remap = os.path.join(work, "remap")
        _run(["nona", "-m", "TIFF_m", "-o", remap, pto], log)
        couches = sorted(glob.glob(remap + "*.tif"))
        if not couches:
            raise RuntimeError("nona n'a produit aucune couche")
        pano_tif = os.path.join(work, "pano.tif")
        # -f : enblend produirait sinon une image de la taille de l'UNION des couches,
        # donc recadree au contenu (mesure : 8192x2321 malgre un canevas correct).
        _run(["enblend", "--compression=LZW", "-f", "%dx%d" % (_larg, _haut),
              "-o", pano_tif] + couches, log)
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        im = Image.open(pano_tif)
        if remplir_ciel:
            im = _remplir_ciel(im, log=log)
        elif im.mode not in ("RGB", "L"):
            im = im.convert("RGB")
        im.save(out_jpg, "JPEG", quality=qualite)
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

    if "VOIE D'ASSEMBLAGE" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0

    # Les deux passes precedentes doivent etre en place : ce patch reecrit `assembler`
    # en INTEGRANT leurs corrections. S'il s'appliquait sur une version anterieure, il
    # les reintroduirait quand meme — mais on verifie, pour ne pas masquer un desordre.
    if "_remplir_ciel" not in src:
        print("ECHEC : _remplir_ciel absent — fichier inattendu, RIEN modifie."); return 1

    d = src.find("def assembler(")
    if d < 0:
        print("ECHEC : fonction assembler introuvable — RIEN modifie."); return 1
    f = src.find("\ndef ", d + 1)
    if f < 0:
        f = len(src)          # assembler est la derniere fonction du fichier
    out = src[:d] + NOUVELLE_FONCTION.lstrip("\n") + src[f + 1:]

    try:
        compile(out, "cine_panorama_stitch.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_angles")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : assemblage guide par les angles disponible (parametre `angles`).")
    print("     Sans angles fournis, le comportement est INCHANGE.")
    print("Sauvegarde : cine_panorama_stitch.py.avant_angles")
    print("\nETAPE SUIVANTE OBLIGATOIRE : determiner le sens du lacet par la mesure")
    print("     python3 essai_angles.py <job_id>")
    return 0


if __name__ == "__main__":
    sys.exit(main())
