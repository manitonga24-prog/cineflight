# -*- coding: utf-8 -*-
"""
patch_modele3d_lots.py — envoi des photos PAR LOTS (2026-07-28).

LE MUR QU'ON VIENT DE HEURTER. Le domaine passe par Cloudflare, qui **refuse toute requête
de plus de 100 Mo** sur les plans gratuits. Un envoi de 249 Mo a été coupé AVANT d'atteindre
le serveur : aucune trace dans nginx, aucune dans le journal du service, et côté client une
erreur SSL sans explication.

⚠ PORTÉE RÉELLE : ce n'est pas un défaut de l'outil d'essai. Une capture 3D de 96 photos
pleine résolution approche le gigaoctet — elle aurait échoué **en vol**, après la batterie
et le déplacement, sans que rien ne l'annonce. C'est un mur structurel.

CE QUE ÇA AJOUTE :

    POST /api/modele3d/{id}/ajouter   -> verse un lot de photos dans un travail existant
    POST /api/modele3d/{id}/finir     -> déclare le jeu complet, décide de la suite

L'appelant crée le travail avec un premier lot (l'endpoint existant), puis verse les
suivants, puis clôt. Chaque requête reste sous la limite. Le travail n'est proposé à
l'atelier QU'APRÈS `finir` : sans ça, le PC téléchargerait un jeu incomplet et
reconstruirait un modèle troué sans que personne comprenne pourquoi.

IDEMPOTENT, sauvegarde `modele3d.py.avant_lots`, `compile()` avant écriture.
Usage :  cd /root/cineflight_web && python3 patch_modele3d_lots.py
"""

import os
import shutil
import sys

CHEMIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modele3d.py")

ANCRE = '@router.get("/api/modele3d/{mid}")'

BLOC = '''@router.post("/api/modele3d/{mid}/ajouter")
async def ajouter_photos(mid: str, files: list[UploadFile] = File(...)):
    """
    Verse un lot de photos dans un travail existant.

    Cloudflare refuse les requetes de plus de 100 Mo sur les plans gratuits : un jeu
    photogrammetrique complet DOIT donc arriver en plusieurs fois. Voir
    patch_modele3d_lots.py.
    """
    tout = _lire()
    if mid not in tout:
        raise HTTPException(404, "modele inconnu")
    images = os.path.join(_BASE, mid, "images")
    os.makedirs(images, exist_ok=True)
    ajoutees = 0
    for f in files:
        chemin = os.path.join(images, os.path.basename(f.filename or "img.jpg"))
        with open(chemin, "wb") as sortie:
            while True:
                bloc = await f.read(1 << 20)
                if not bloc:
                    break
                sortie.write(bloc)
        ajoutees += 1
    total = len(os.listdir(images))
    # ETAT INTERMEDIAIRE : tant que `finir` n'a pas ete appele, ce travail ne doit PAS
    # etre propose a l'atelier. Un jeu incomplet donne un modele troue, et personne ne
    # saurait dire pourquoi.
    _maj(mid, photos=total, etat="reception", etape="reception des photos (%d)" % total)
    return JSONResponse({"modele_id": mid, "ajoutees": ajoutees, "total": total})


@router.post("/api/modele3d/{mid}/finir")
async def finir_envoi(mid: str):
    """Declare le jeu complet. C'est SEULEMENT ici que le travail devient disponible."""
    tout = _lire()
    if mid not in tout:
        raise HTTPException(404, "modele inconnu")
    images = os.path.join(_BASE, mid, "images")
    total = len(os.listdir(images)) if os.path.isdir(images) else 0
    if total < PHOTOS_MIN:
        raise HTTPException(422, "seulement %d photos : au moins %d sont necessaires"
                            % (total, PHOTOS_MIN))
    res = ressources()
    out = outils_presents()
    capable = res["ram_go"] >= RAM_MIN_GO and out["colmap"]
    _maj(mid, photos=total, ressources=res, outils=out,
         etat=("en_cours" if capable else "en_attente_ressources"),
         etape=("preparation" if capable else "machine insuffisante"), pct=0)
    if capable:
        threading.Thread(target=_reconstruire, args=(mid, os.path.join(_BASE, mid)),
                         daemon=True).start()
    return JSONResponse({"modele_id": mid, "photos": total,
                         "reconstruction_lancee": capable,
                         "url": "/modele3d/%s" % mid})


'''


def main():
    if not os.path.exists(CHEMIN):
        print("ECHEC : modele3d.py introuvable"); return 1
    src = open(CHEMIN, encoding="utf-8").read()

    if "/ajouter" in src:
        print("RIEN A FAIRE : le correctif est deja applique."); return 0
    if ANCRE not in src:
        print("ECHEC : ancre introuvable dans modele3d.py — RIEN modifie."); return 1
    for besoin in ("_maj", "PHOTOS_MIN", "ressources()", "outils_presents()"):
        if besoin.rstrip("()") not in src:
            print("ECHEC : %s absent — fichier inattendu, RIEN modifie." % besoin); return 1

    out = src.replace(ANCRE, BLOC + ANCRE, 1)
    try:
        compile(out, "modele3d.py", "exec")
    except SyntaxError as e:
        print("ECHEC : le resultat ne compile pas (%s) — fichier laisse INTACT." % e)
        return 1

    shutil.copyfile(CHEMIN, CHEMIN + ".avant_lots")
    open(CHEMIN, "w", encoding="utf-8").write(out)
    print("OK : envoi par lots disponible (/ajouter et /finir).")
    print("Sauvegarde : modele3d.py.avant_lots")
    print("Redemarre :  systemctl restart cineflight")
    return 0


if __name__ == "__main__":
    sys.exit(main())
