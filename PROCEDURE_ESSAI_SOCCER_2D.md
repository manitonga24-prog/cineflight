# Procédure d'essai — Émission réelle 2D (CineFlight Soccer)

> **Règle d'or : rien ne bouge tant que le signe du throttle n'est pas prouvé au sol.**
> L'état par défaut du code est **totalement inerte**. Chaque activation ci-dessous est
> volontaire, temporaire, et se fait dans l'ordre. Doigt sur la reprise manuelle à tout moment.

---

## 0. État par défaut (aucune action requise)

Dans `Phase3Activity.kt`, les verrous sont fermés :

| Flag | Défaut | Effet |
|---|---|---|
| `SOCCER_2D_REAL_ENABLED` | `false` | aucune commande 2D émise |
| `SOCCER_2D_MAX_VSPEED_MPS` | `0f` | même flag ouvert, vitesse verticale = 0 |

Tant que ces deux valeurs ne sont pas changées **ensemble**, le drone ne reçoit **aucune**
commande d'altitude du réalisateur. Le panneau affiche `émission 2D: INERTE (flag off)`.

La commande n'est émise que si **TOUTES** ces conditions sont vraies simultanément :

```
mode 2D actif
+ opérateur armé (soccerArme)
+ SOCCER_2D_REAL_ENABLED = true
+ SOCCER_2D_MAX_VSPEED_MPS > 0
+ SafetySnapshot valide (batterie, RTK FIX/FLOAT, en vol, Virtual Stick, etc.)
+ arbitre retient la source SoccerRail
```

Un seul élément manquant → throttle 0. (Prouvé par `Emission2DGuardTest` sur les 512 cas.)

---

## 1. TEST STATIQUE DU SIGNE THROTTLE (au sol, obligatoire)

**But :** vérifier que « altitude cible plus haute » produit bien une commande de **montée**
(throttle positif), et jamais une descente. Ne JAMAIS supposer le signe.

**Conditions :**
- Drone posé, **hélices retirées** (ou banc d'essai totalement sécurisé).
- Personne à proximité. Télécommande allumée, reprise manuelle prête.

**Étapes :**
1. Brancher le téléphone à la télécommande, drone sous tension, Virtual Stick actif.
2. Activer temporairement dans `Phase3Activity.kt` :
   - `SOCCER_2D_REAL_ENABLED = true`
   - `SOCCER_2D_MAX_VSPEED_MPS = 0.2f` (valeur minuscule)
3. Entrer en mode soccer 2D, armer, forcer une **altitude optimisée > altitude actuelle**
   (p. ex. joueurs volontairement « trop grands » dans l'image → le director veut monter).
4. Lire le **log d'émission** (`PontDji` → `envoyerVitesses`, champ `verticalThrottle`) :
   - throttle **positif** attendu = montée. ✅ Le signe est bon.
   - throttle **négatif** = convention inverse. → passer `SENS_THROTTLE_MONTEE = -1f`
     dans `SoccerAltitudeThrottle.kt` (**un seul endroit**), rebuilder, refaire le test.

**Tant que ce test n'est pas ✅, ne pas passer à l'étape 2.**

---

## 2. ESSAI 1 — Altitude seule, vitesse très basse (extérieur dégagé)

**Conditions :** extérieur dégagé, RTK FIX/FLOAT, batterie > 40 %, VLOS, doigt sur la reprise.

**Réglage :**
- `SOCCER_2D_REAL_ENABLED = true`
- `SOCCER_2D_MAX_VSPEED_MPS = 0.2f` à `0.3f` (ne PAS augmenter avant l'Essai 2)
- roll / pitch / yaw restent à 0 (aucun mouvement horizontal en Essai 1).

**Ce qu'on vérifie :**
- le drone monte/descend **doucement** dans le bon sens ;
- il **s'arrête** dès que l'altitude cible est atteinte (zone morte ±0.30 m) ;
- la reprise manuelle coupe **immédiatement** (priorité pilote absolue) ;
- désarmer ou sortir du mode 2D → throttle 0 immédiat ;
- perte YOLO / RTK dégradé → throttle 0.

---

## 3. Après l'Essai 1 réussi

- Augmenter `SOCCER_2D_MAX_VSPEED_MPS` par petits pas seulement si le comportement est net.
- **Essai 2** (plus tard) : activer l'axe **latéral** (roll) — même doctrine, vitesse basse.
- Le **zoom** reste inactif sur Mini 4 Pro (zoom numérique seulement, `CapaciteZoom.MINI_4_PRO`).

---

## Coupures de sécurité (toujours actives)

| Déclencheur | Effet |
|---|---|
| Reprise manuelle (`modeManuel`) | commande PILOTE, priorité absolue |
| Arrêt d'urgence (`soccerArretUrgence`) | commande neutre (0) |
| Batterie < 40 % | bloqué |
| RTK ≠ FIX/FLOAT | bloqué (position non fiable) |
| Virtual Stick indisponible / pas en vol | bloqué |
| Opérateur trop loin (> 200 m) | bloqué |
| Entrée NaN / erreur | throttle 0 (fail-safe) |

---

## Après chaque session de test

- **Remettre les deux flags à leur défaut inerte** (`false` / `0f`) avant de ranger.
- Commiter en local si des réglages ont changé.

*Généré comme aide-mémoire terrain. Le code de sécurité (arbitre + garde 2D) est prouvé
sur les 512 combinaisons de conditions ; cette procédure ne remplace pas la vigilance.*
