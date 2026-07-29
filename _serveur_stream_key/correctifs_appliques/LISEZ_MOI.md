# Correctifs déjà appliqués — archive

Ces vingt-huit scripts ont tous été appliqués sur le serveur de production et vérifiés
par `etat_serveur.py` le 2026-07-28. **Ils ne sont plus le moyen de déployer.**

## Pourquoi ils sont ici et plus à côté des modules

Ils étaient empilés dans le dossier principal, et chaque nouveau correctif devait deviner
ce que les précédents avaient laissé dans le fichier cible. Cette accumulation a coûté
cher en une seule journée :

- une **parenthèse déplacée d'un caractère** (`int(largeur_max, _pano_angles)`) a empêché
  tout assemblage de démarrer pendant des heures — et `py_compile` la validait, puisque
  `int(a, b)` est une signature légitime ;
- un **nom non importé** (`File`) a mis le site entier en 502 pendant une demi-heure ;
- des **options ajoutées après les fichiers d'entrée** (`list(cmd) + ["--no-optimize"]`)
  étaient silencieusement inertes, ce qui a fait abandonner trois fois une géométrie juste.

## Ce qui les remplace

**`cine_panorama_stitch.py`** vit désormais dans le dépôt GitHub, en version canonique.
Il ne contient aucun secret et le serveur le sert déjà à l'atelier PC
(`GET /api/atelier/assembleur`). On l'édite dans le dépôt, on le déploie **entier** :

    curl -fsSL -O https://raw.githubusercontent.com/manitonga24-prog/cineflight/\
    feature/voice-phase-2-sdk-observation/_serveur_stream_key/cine_panorama_stitch.py
    sha256sum cine_panorama_stitch.py     # comparer aux deux bouts

Aucun redémarrage : l'atelier retélécharge le script à chaque travail.

**`app.py`** ne peut pas aller sur le dépôt — il est public et le fichier porte des secrets.
Les correctifs restent donc le véhicule pour lui (la console DigitalOcean corrompt les gros
collages), mais un **git local** existe maintenant sur le serveur : chaque application se
vérifie par `git diff` et s'annule par `git checkout`.

## Avant toute intervention

    cd /root/cineflight_web && python3 etat_serveur.py

Il dit, marqueur par marqueur, ce qui est réellement en place. On ne fige et on ne
diagnostique rien sans avoir lu cet inventaire.

## Si un serveur doit être reconstruit de zéro

Les scripts restent valides et **idempotents**. Ordre d'application :

1. `patch_pano_sphere.py` — canevas 2:1 imposé, plafond 8192 px
2. `patch_pano_enblend.py` — `enblend -f` (la sortie était recadrée au contenu)
3. `patch_api_angles.py` + `patch_pano_angles.py` — assemblage guidé par les angles du vol
4. `patch_pano_repli.py`, `patch_pano_ordre.py` — cascade des voies
5. `patch_pano_photometrie.py` — `autooptimiser -m` sur la voie angles
6. `patch_pano_parenthese.py` — ⚠ **avant** `patch_pano_delegue.py`
7. `patch_travaux_ouvrier.py` → `patch_travaux_fichiers.py` — file de travaux 3D
8. `patch_modele3d_lots.py` → `patch_modele3d_reception.py` — envoi par lots
9. `patch_travaux_pano.py` → `patch_travaux_pano_import.py` — file de travaux panorama
10. `patch_pano_delegue.py` — délégation à l'atelier PC
11. `patch_pano_wrap.py`, `patch_pano_reessai.py`, `patch_pano_enblend_secours.py`,
    `patch_pano_secours_ordre.py`, `patch_pano_ancree.py`, `patch_pano_ancree_rang.py`
    — ⚠ ces six-là portent sur `cine_panorama_stitch.py`, dont la version canonique est
    maintenant dans le dépôt : **prendre le fichier entier** plutôt que de les rejouer.

Puis vérifier avec `etat_serveur.py`, et valider par un **essai de bout en bout** — jamais
par la compilation.

## La règle qui résume la journée

Un déploiement se valide par son **résultat**, pas par sa forme. `py_compile` accepte un
fichier vide, RealityScan rend 0 sans avoir produit de maillage, `enblend` rend un fichier
de huit octets, et une commande juste peut produire une image fausse. Mesurer le produit.
