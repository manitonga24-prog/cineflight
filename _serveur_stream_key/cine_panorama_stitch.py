# -*- coding: utf-8 -*-
# Assemblage panorama 360 HAUTE QUALITE via Hugin.
# Chaine : pto_gen -> cpfind(--multirow,--celeste) -> cpclean -> autooptimiser
#          -> pano_modify(equirect) -> nona -> enblend(multibande) -> JPEG.
import os, sys, subprocess, glob, tempfile, shutil, time

def _est_plantage(code):
    """
    Un code de sortie trahit-il un PLANTAGE plutot qu'un refus ?

    Un outil qui examine le travail et le rejette rend un petit code non nul : le refaire
    ne changerait rien. Un processus tue par un signal (Unix, code negatif) ou victime
    d'une exception native (Windows, >= 0xC0000005) n'a pas juge quoi que ce soit — il est
    mort. Ces cas-la valent une seconde tentative, les autres non.
    """
    return code < 0 or code >= 0xC0000000


def _run(cmd, log):
    log("  $ " + " ".join(str(x) for x in cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0 and _est_plantage(r.returncode):
        # PLANTAGE, PAS REFUS : on retente UNE fois. Mesure du 2026-07-28 — `enblend` est
        # mort en 0xC0000005 sur un jeu de 61 couches, puis a fonctionne sur le meme jeu
        # par une autre voie. Renoncer a la bonne geometrie a la premiere secousse a
        # produit une sphere basculee, livree sans que rien ne le signale.
        # MESURE DU 2026-07-28 : rejouer la MEME commande a replante a l'identique.
        # Le plantage est reproductible, pas passager. On garde donc la geometrie — les
        # positions du drone sont justes — et on simplifie la COUTURE par paliers.
        # Une jointure moins bien placee ne se voit presque pas ; une sphere basculee est
        # inutilisable, et sur une paire stereo elle ruine les deux yeux.
        # ⚠ LES OPTIONS S'INSERENT APRES L'EXECUTABLE, JAMAIS A LA FIN.
        # `cmd` se termine par les noms de fichiers d'entree ; ajouter une option apres
        # eux revient a ne pas l'appliquer — la generation des masques est deja
        # configuree. C'etait le defaut du 2026-07-28 : les deux paliers rejouaient la
        # commande d'origine, echouaient forcement, et faisaient abandonner une geometrie
        # juste au profit d'une sphere basculee.
        # MESURE sur les couches de production : `--no-optimize` reellement transmis
        # produit les 61 couches fusionnees (69 Mo), la ou la commande d'origine plante.
        if "enblend" in os.path.basename(str(cmd[0])).lower():
            secours = [
                (["--no-optimize"], [cmd[0], "--no-optimize"] + list(cmd[1:])),
                (["--no-optimize", "--primary-seam-generator=nearest-feature-transform"],
                 [cmd[0], "--no-optimize",
                  "--primary-seam-generator=nearest-feature-transform"] + list(cmd[1:])),
            ]
        else:
            secours = [([], list(cmd))]
        for options, variante in secours:
            ajout = " ".join(options) or "meme commande"
            log("!! %s a PLANTE (code %d) — nouvelle tentative : %s"
                % (cmd[0], r.returncode, ajout))
            r = subprocess.run(variante, capture_output=True, text=True)
            if r.returncode == 0:
                log("   abouti avec : %s" % ajout)
                break
            if not _est_plantage(r.returncode):
                # L'outil JUGE et refuse : les paliers suivants n'y changeraient rien.
                break
    if r.returncode != 0:
        raise RuntimeError("Echec %s (code %d):\n%s\n%s" % (
            cmd[0], r.returncode, r.stdout[-400:], r.stderr[-1500:]))
    return r.stdout

def _lire_canvas(pto):
    with open(pto) as f:
        for ligne in f:
            if ligne.startswith("p "):
                w = h = 0
                for tok in ligne.split():
                    if tok.startswith("w"):
                        try: w = int(tok[1:])
                        except: pass
                    elif tok.startswith("h"):
                        try: h = int(tok[1:])
                        except: pass
                return w, h
    return 0, 0

def _remplir_ciel(im, log=print):
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
def testfill(log=print):
    import numpy as np
    from PIL import Image
    H, W = 600, 1200
    arr = np.zeros((H, W, 4), np.uint8)
    arr[200:400, :, :3] = [120, 170, 230]; arr[200:400, :, 3] = 255
    arr[400:, :, :3] = [80, 140, 80]; arr[400:, :, 3] = 255
    out = _remplir_ciel(Image.fromarray(arr, "RGBA"), log=log)
    out.save("/tmp/testfill.jpg", "JPEG", quality=90)
    a = np.asarray(out)
    return int((a[:150].sum(axis=2) < 20).sum())

# Sens de rotation du lacet dans Hugin par rapport au cap boussole du drone.
# -1.0 ou +1.0 : A DETERMINER PAR LA MESURE (essai_angles.py), pas par intuition.
SENS_YAW = 1.0


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
    open(pto, "w", encoding="utf-8").write("\n".join(lignes))
    log("Positions ecrites pour %d images (ancre = image 0, sens_yaw=%.0f)"
        % (len(idx), SENS_YAW))
    return True


def _ancrer_et_optimiser(pto, log):
    """
    Optimise les positions en LAISSANT L'IMAGE 0 IMMOBILE. True si l'optimisation a tourne.

    Le format .pto porte des lignes `v` qui enumerent les parametres a optimiser. Une image
    dont le cap, l'inclinaison et le roulis n'y figurent PAS est laissee telle quelle par
    l'optimiseur : c'est l'ancrage. On ecrit donc `v yN pN rN` pour toutes les images sauf
    la premiere, puis on lance `autooptimiser -n`, qui n'optimise que ce qui est declare.

    ⚠ `-n` ET RIEN D'AUTRE. `-a` reoptimise tout, `-l` remet l'horizon a plat et `-s`
    recalcule le cadrage : chacun peut faire pivoter la sphere, ce qu'on cherche justement
    a empecher. C'est la combinaison `-a -m -l -s` qui avait produit l'horizon en tente.
    """
    try:
        with open(pto, encoding="utf-8", errors="replace") as f:
            lignes = f.read().splitlines()
    except Exception as ex:
        log("!! voie ancree : lecture du projet impossible (%s)" % ex)
        return False

    # On repart de zero cote variables d'optimisation : celles qui traineraient
    # decideraient a notre place de ce qui bouge.
    gardees = [l for l in lignes if not (l == "v" or l.startswith("v "))]
    indices = [i for i, l in enumerate(gardees) if l.startswith("i ")]
    if len(indices) < 3:
        log("!! voie ancree : %d images seulement, optimisation sans objet" % len(indices))
        return False

    # Une ligne par image, sauf la premiere. Hugin ecrit lui-meme sous cette forme.
    variables = ["v y%d p%d r%d" % (n, n, n) for n in range(1, len(indices))]
    apres = indices[-1] + 1
    nouvelles = gardees[:apres] + variables + gardees[apres:]
    try:
        with open(pto, "w", encoding="utf-8") as f:
            f.write("\n".join(nouvelles) + "\n")
    except Exception as ex:
        log("!! voie ancree : ecriture du projet impossible (%s)" % ex)
        return False

    log("voie ancree : %d images libres, image 0 VERROUILLEE (sphere non pivotable)"
        % (len(indices) - 1))
    try:
        _run(["autooptimiser", "-n", "-o", pto, pto], log)
        return True
    except Exception as ex:
        log("!! voie ancree : autooptimiser a echoue (%s) — on garde le placement brut"
            % str(ex).split(chr(10))[0])
        return False


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
        _run(["enblend", "--wrap=horizontal", "--compression=LZW", "-f", "%dx%d" % (larg, haut),
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
                # POSITIONS DU DRONE D'ABORD (correctif 2026-07-27, voir
                # patch_pano_ordre.py). Mesure : sur deux panoramas de la meme paire,
                # `autooptimiser` a fait echouer l'un (photos empilees) et bascule l'autre
                # (sol en pointe de tente). Le ciel n'a pas de texture, donc les points de
                # correspondance des rangees hautes sont faux ; l'optimiseur deplace de
                # bonnes positions pour coller a du bruit. Boussole + nacelle sont des
                # mesures d'instruments : on leur fait confiance.
                # PHOTOMETRIE (correctif 2026-07-27, patch_pano_photometrie.py).
                # `-m` egalise luminosite, couleur et vignettage entre images en
                # s'appuyant sur les recouvrements. Il ne touche PAS aux positions :
                # celles qu'on vient d'ecrire depuis la boussole et la nacelle restent
                # intactes. Sans lui, plus rien ne corrigeait la lumiere depuis le
                # passage a la voie « angles seuls ».
                # Un echec ici n'arrete rien : la photometrie est un confort, la
                # geometrie est le produit.
                try:
                    _run(["autooptimiser", "-m", "-o", sans_opt, sans_opt], log)
                    log("photometrie egalisee (autooptimiser -m)")
                except Exception as ex:
                    log("!! photometrie non appliquee (%s) : on continue" % ex)
                # ── VOIE ANCREE (2026-07-28), essayee EN PREMIER ──────────────
                # Elle part de `sans_opt`, qui porte deja les positions du drone ET la
                # photometrie egalisee, et n'y ajoute qu'un affinage des positions
                # relatives — image 0 verrouillee, donc sphere non pivotable.
                # Elle vise le defaut mesure sur `eca54f3db18e` : sentiers dedoubles aux
                # jointures, faute d'ajustement fin entre images voisines.
                # ⚠ ORDRE ETABLI PAR LA MESURE, PAS PAR LE RAISONNEMENT (2026-07-28).
                # Sur le meme jeu de 61 photos : angles_seuls -> 9,0 % de ciel comble ;
                # angles_affines -> 27,8 %, avec le tiers superieur en aplat gris.
                # L'ancrage empeche bien la sphere de pivoter, mais laisse l'optimiseur
                # disperser les images. Le placement brut passe donc en premier.
                tentatives.append(("angles_seuls", sans_opt))
                # La voie ancree reste en SECOND : si le placement brut echoue un jour,
                # elle vaut mieux que `angles_optimises`, qui fait pivoter la sphere.
                affines = os.path.join(work, "angles_affines.pto")
                shutil.copyfile(sans_opt, affines)
                if _ancrer_et_optimiser(affines, log):
                    tentatives.append(("angles_affines", affines))
                # L'optimisation reste disponible, mais en SECOURS seulement.
                try:
                    _run(["autooptimiser", "-n", "-o", avec_opt, avec_opt], log)
                    tentatives.append(("angles_optimises", avec_opt))
                except Exception as ex:
                    log("!! autooptimiser a echoue (%s) : sans consequence, il n'est"
                        " plus qu'un secours" % ex)
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
        # ⚠ UN REPLI SE DIT. Les controles en aval — le fichier existe, il fait 2:1 — ne
        # voient PAS une sphere pivotee. Sur une paire stereo, deux yeux assembles par des
        # voies differentes ne fusionnent pas ; il faut que ca apparaisse dans le journal
        # et non dans le casque. Mesure du 2026-07-28 : angles_seuls 9 % de ciel comble,
        # angles_optimises 30,7 % sur le meme lieu.
        if tentatives and voie != tentatives[0][0]:
            log("!! REPLI D'ASSEMBLAGE : la voie stricte (%s) a echoue, resultat produit "
                "par %s. La geometrie a pu etre retouchee — sur une paire stereo, verifie "
                "que les DEUX yeux portent la meme voie." % (tentatives[0][0], voie))
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
def selftest(log=print):
    from PIL import Image, ImageDraw
    import numpy as np
    W, H = 1600, 600
    arr = np.random.default_rng(1).integers(0, 256, (H, W, 3), dtype=np.uint8)
    base = Image.fromarray(arr, "RGB")
    d = ImageDraw.Draw(base)
    rng = np.random.default_rng(2)
    for _ in range(60):
        x = int(rng.integers(0, W)); y = int(rng.integers(0, H))
        d.ellipse([x, y, x + 50, y + 50], outline=(0, 0, 0), width=3)
    work = tempfile.mkdtemp(prefix="panotest_")
    tw, step, i = 800, 400, 0
    for x0 in range(0, W - tw + 1, step):
        base.crop((x0, 0, x0 + tw, H)).save(os.path.join(work, "tuile%02d.jpg" % i), "JPEG", quality=95)
        i += 1
    log("Auto-test : %d tuiles avec recouvrement" % i)
    out = os.path.join(work, "pano_test.jpg")
    res = assembler(work, out, largeur_max=4000, log=log)
    log("AUTO-TEST REUSSI : %s" % res)
    return res

if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "--testfill":
        print("pixels noirs en haut:", testfill()); raise SystemExit
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        try:
            selftest(); print("\n>>> CHAINE HUGIN FONCTIONNELLE <<<")
        except Exception as e:
            print("AUTO-TEST ECHOUE:", e); sys.exit(1)
    elif len(sys.argv) >= 3:
        try:
            print("RESULTAT:", assembler(sys.argv[1], sys.argv[2],
                  largeur_max=int(sys.argv[3]) if len(sys.argv) > 3 else 12000))
        except Exception as e:
            print("ERREUR:", e); sys.exit(1)
    else:
        print("usage: python3 cine_panorama_stitch.py --selftest")
        print("   ou: python3 cine_panorama_stitch.py <dossier> <sortie.jpg> [largeur_max]")
