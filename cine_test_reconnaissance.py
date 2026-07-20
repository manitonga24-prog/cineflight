#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cine_test_reconnaissance.py — Banc de test LOCAL de toute la chaîne (QUESTSERVER).

Prend un dossier de photos SD du Mini 3, fait tourner la chaîne complète
EN LOCAL (perception GPU + sélection + triangulation), affiche T. Aucun réseau,
aucun serveur DO : tout sur QUESTSERVER qui a le 3090 ET fait le CPU.

C'est le test DÉCISIF : « est-ce que N vraies photos donnent un bon T ? »

USAGE :
    cd C:\\cineflight_android\\CineFlightSolo
    python cine_test_reconnaissance.py <dossier_photos> <poi_lat> <poi_lon> [poi_alt]

    ex : python cine_test_reconnaissance.py C:\\photos_phare 48.5 -68.5 50

Le dossier doit contenir les photos SD pleine résolution (DJI_xxxx.JPG) avec
leur XMP (GPS + gimbal). Le script :
  1. liste les .jpg du dossier
  2. perception GPU sur chacune (YOLO-World + SAM2)  -> centroïde + qualité
  3. lit le XMP de chacune                            -> télémétrie
  4. sélection (qualité + répartition angulaire)
  5. triangulation                                    -> T (lat, lon, alt)
  6. affiche T + diagnostics

Réutilise EXACTEMENT la même logique que l'endpoint serveur
(cine_endpoint_reconnaissance.traiter_reconnaissance), mais avec la perception
RÉELLE injectée localement au lieu d'un appel réseau.
"""

from __future__ import annotations
import sys
import os
import glob
import json
import time


def perception_locale(chemin: str) -> dict:
    """Perception GPU réelle, en local (réutilise le worker)."""
    from worker_perception import perception_sur_fichier
    return perception_sur_fichier(chemin)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        print("\nERREUR : arguments manquants.")
        print("Usage : python cine_test_reconnaissance.py <dossier> <poi_lat> <poi_lon> [poi_alt]")
        sys.exit(1)

    dossier = sys.argv[1]
    poi_lat = float(sys.argv[2])
    poi_lon = float(sys.argv[3])
    poi_alt = float(sys.argv[4]) if len(sys.argv) > 4 else 50.0

    if not os.path.isdir(dossier):
        print(f"ERREUR : dossier introuvable : {dossier}")
        sys.exit(1)

    # lister les photos (jpg/jpeg, insensible à la casse)
    motifs = ["*.jpg", "*.JPG", "*.jpeg", "*.JPEG"]
    chemins = []
    for m in motifs:
        chemins.extend(glob.glob(os.path.join(dossier, m)))
    chemins = sorted(set(chemins))

    if not chemins:
        print(f"ERREUR : aucune photo .jpg dans {dossier}")
        sys.exit(1)

    print("=" * 70)
    print(f"BANC DE TEST RECONNAISSANCE — {len(chemins)} photos")
    print(f"POI approximatif : lat={poi_lat} lon={poi_lon} alt={poi_alt} m")
    print("=" * 70)

    # configurer la perception réelle dans l'endpoint (réutilise sa logique)
    from cine_endpoint_reconnaissance import traiter_reconnaissance, configurer_perception
    print("\nChargement des modèles GPU (lent la 1re fois)...")
    configurer_perception(perception_locale)

    print(f"Traitement des {len(chemins)} photos (perception + sélection + triangulation)...\n")
    t0 = time.time()
    resultat = traiter_reconnaissance(chemins, poi_lat, poi_lon, poi_alt)
    dt = time.time() - t0

    print("=" * 70)
    print("RÉSULTAT")
    print("=" * 70)
    print(json.dumps(resultat, indent=2, ensure_ascii=False))
    print(f"\nTemps total : {dt:.1f} s")

    if resultat.get("succes"):
        t = resultat["T"]
        print("\n" + "=" * 70)
        print(f"✓ SUJET LOCALISÉ : {t['lat']}, {t['lon']}, alt {t['alt_m']} m")
        print(f"  erreur horizontale estimée : ±{resultat['erreur_horizontale_m']} m")
        print(f"  observations utilisées : {resultat['nb_retenues']}/{resultat['nb_photos']}")
        print(f"  base angulaire : {resultat['base_angulaire_deg']}°")
        print("=" * 70)
        # comparer T au POI approximatif (écart = ce que la triangulation a corrigé)
        import math
        m_lat = 111320.0
        m_lon = 111320.0 * math.cos(math.radians(poi_lat))
        d = math.hypot((t["lat"] - poi_lat) * m_lat, (t["lon"] - poi_lon) * m_lon)
        print(f"\nÉcart T vs POI approximatif : {d:.1f} m")
        print("(c'est la correction apportée par la triangulation sur l'estimation initiale)")
    else:
        print("\n" + "=" * 70)
        print(f"✗ ÉCHEC : {resultat.get('raison', 'inconnu')}")
        print("=" * 70)
        if resultat.get("avertissements"):
            print("\nAvertissements :")
            for a in resultat["avertissements"]:
                print(f"  • {a}")
        if resultat.get("rejets"):
            print(f"\nRejets ({len(resultat['rejets'])}) :")
            for r in resultat["rejets"][:10]:
                print(f"  • {r['photo']} : {r['raison']}")


if __name__ == "__main__":
    main()
