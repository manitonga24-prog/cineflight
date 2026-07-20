# CineFlight Solo — App Android (drone assistant-réalisateur)

App Android (Kotlin, DJI Mobile SDK v5) qui transforme un **DJI Mini 3** en
**assistant-réalisateur autonome** : le drone repère un sujet, le suit, le cadre
et le filme tout seul, pendant que l'utilisateur garde les mains libres. Il se
commande à l'écran, à la voix, ou en montrant des cartes imprimées (tags ArUco).

**Honnêteté importante :** ce code est écrit avec soin et conforme au MSDK v5,
mais il n'est **pas compilé ni testé dans cet environnement** (pas d'Android
Studio ni de drone ici). C'est TOI qui le compiles, y mets ta clé DJI, et le
déboggues sur ton téléphone + Mini 3. Attends-toi à quelques ajustements
(versions de SDK, permissions, Gradle).

---

## 1. Fonctionnalités principales

L'app est un outil de tournage complet. Les grandes familles :

- **Suivi automatique d'un sujet** par IA visuelle **YOLO embarquée** (NCNN) :
  personnes par défaut, et au choix animaux et véhicules. On désigne le sujet
  d'un toucher à l'écran, par la voix, ou avec le tag de présentation.
- **Trois façons de commander :** écran tactile, commande vocale (mot d'éveil
  « CineFlight »), et **tags ArUco** (cartes imprimées lues par la caméra) — la
  méthode la plus fiable à distance.
- **Cadrages** (gros plan → plan d'ensemble) et **mouvements cinématographiques**
  (statique, orbite, travelling, révélation, approche, spotlight, recul, suivi),
  combinables et réglables (curseurs bornés, bouton « Valeurs sûres »).
- **Macros programmables** : des séquences de plans rejouables d'un seul tag
  (tags 11–30).
- **Rail Cable-Cam + Hyperlapse** : travelling rectiligne parfait entre deux
  points, avec suivi du sujet et timelapse en mouvement.
- **Capture Auto Intelligente** : photos prises automatiquement aux bons moments.
- **Montage automatique par IA** : l'app assemble un clip à partir des meilleurs
  passages.
- **Reconnaissance / triangulation** d'un sujet fixe (bâtiment, pont, berge,
  barrage) et **CineFlight Explorer** : mouvements de caméra planifiés sur carte
  et vérifiés sur le terrain réel (LiDAR) avant validation.
- **Tournage automatique** : un « réalisateur » compose et enchaîne les meilleurs
  plans selon l'intention et le temps disponible.
- **⭐ Suivre un véhicule (récent)** : suivi **GPS** d'une voiture équipée d'un
  boîtier GPS de précision (position relayée par le serveur CineFlight). Détails
  ci-dessous.

### ⭐ « Suivre un véhicule » (écran Phase3)

Ouvert depuis le menu « Que voulez-vous filmer ? », en mode paysage. Le drone
suit une voiture qui roule d'après la position que celle-ci **émet** (et non en
la « voyant ») — fiable même si la voiture va vite, s'éloigne, ou est masquée un
instant. Ce que l'écran offre :

- **Affichage complet** : vidéo en direct + barre de télémétrie (batterie,
  altitude, signal GPS, cap, position, modèle), mise à jour en direct.
- **Angles changeables en direct** : derrière, devant, gauche, droite, plongée
  (vue du dessus).
- **Mouvements changeables en direct** : orbite, révélation (« reveal »),
  rapprochement.
- **Transitions sécuritaires** : pour changer de côté, le drone **contourne par
  l'arrière**, jamais au-dessus ni devant la voiture.
- **Mode manuel** : un bouton rend le pilotage à la télécommande DJI (le pilote
  reprend la main), puis le rend à l'app. Après un arrêt d'urgence, le drone
  passe automatiquement en mode manuel.
- **Couche de confirmation visuelle YOLO** : YOLO confirme la voiture à l'écran
  et **affine le cadrage** (pointage caméra : yaw + nacelle). Cette couche
  **ne déplace jamais le drone** : la translation reste pilotée à 100 % par le
  GNSS/RTK. Corrections de cadrage bornées, actives seulement si une seule
  voiture est vue avec assez de confiance ; sinon on retombe sur le cadrage GNSS.

---

## 2. Guide utilisateur intégré

L'app embarque un **guide pédagogique complet** (21 sections), lisible dans
l'app via le bouton **Tags → Guide**, et imprimable / exportable en PDF. Sa
source est `app/src/main/assets/guide.html` — c'est le document de référence pour
l'utilisateur final. Il est écrit volontairement **sans accents** (choix
d'encodage pour l'affichage WebView) et **sans jargon** (aucun terme « RTK / FIX
/ FLOAT » visible par l'utilisateur : on parle de « boîtier GPS » et de signal
« précis / moyen / faible »).

---

## 3. Structure du projet

```
CineFlightSolo/
├── build.gradle · settings.gradle · gradle/           (build Gradle)
└── app/
    ├── build.gradle                                    (dépendances DJI MSDK v5)
    └── src/main/
        ├── AndroidManifest.xml                         (clé DJI, permissions)
        ├── assets/guide.html                           (guide utilisateur, 21 sections)
        ├── java/ca/cineflight/stage/
        │   ├── MainActivity.kt                         (poste de pilotage / cockpit)
        │   ├── Phase3Activity.kt                       (⭐ « Suivre un véhicule » : suivi GPS + YOLO)
        │   ├── TagsActivity.kt · GuideActivity.kt      (menu tags, guide)
        │   ├── TelemetrieActivity.kt                   (télémétrie)
        │   ├── EnregistrementSdk.kt                    (enregistrement SDK v5)
        │   ├── cine/                                   (menus « recettes », traces serveur…)
        │   └── control/                                (le cœur : SDK + moteurs)
        │       ├── PontDji.kt (PontDjiReel)            (SEUL fichier qui touche le SDK DJI)
        │       ├── FluxCamera.kt                       (affichage vidéo live)
        │       ├── YoloSuivi.kt · OverlayYolo.kt       (détection YOLO embarquée)
        │       ├── TraductionAxes.kt                   (✅ cœur validé : scène → drone)
        │       ├── GenerateurMouvement.kt              (mouvements cinématographiques)
        │       ├── DiagnosticPredictionRtk.kt          (prédiction / anticipation position)
        │       ├── CableCam.kt · Hyperlapse.kt         (rail + timelapse)
        │       ├── Macros.kt · MonteurVideo.kt         (macros, montage auto)
        │       └── … (autres moteurs : reconnaissance, sécurité, télémétrie, voix…)
        └── res/                                        (layouts, strings, filtres USB)
```

Ouvre simplement le dossier `CineFlightSolo/` dans Android Studio.

> Le fichier `control/PontDji.kt` (`PontDjiReel`) est le **seul** à toucher le
> SDK DJI ; tout le reste en est indépendant, ce qui permet de tester la logique
> sans matériel.

---

## 4. Mise en route

### a) Clé d'API DJI (indispensable)
1. Crée un compte sur https://developer.dji.com
2. Crée une app « Mobile SDK », **package name = `ca.cineflight.stage`** (doit
   correspondre EXACTEMENT à `applicationId` dans `app/build.gradle`).
3. Copie l'App Key et colle-la dans `AndroidManifest.xml` à la place de
   `REMPLACER_PAR_TA_CLE_DJI`.

### b) Dépendances DJI
Dans `app/build.gradle`, vérifie la **dernière version** du SDK v5 sur le portail
DJI. Si les artefacts ne sont pas trouvés, le dépôt Maven DJI est déjà déclaré
dans `settings.gradle` — confirme l'URL courante dans la doc DJI.

### c) YOLO embarqué
La détection visuelle utilise **YOLO11 NCNN** (`com.tencent.yolo11ncnn.YOLO11Ncnn`)
avec les modèles dans `assets/`. Le modèle se charge via `yolo.loadModel(...)`
(léger CPU ou nano GPU Vulkan selon le réglage Qualité). Sans modèle chargé, les
fonctions visuelles restent simplement inactives — le reste (GPS, tags) marche.

---

## 5. Tester SANS drone d'abord (fortement conseillé)

`MainActivity.MODE_SIMULE = true` fait tourner la chaîne sans SDK ni drone, en
affichant l'état à l'écran. C'est le moyen de valider l'interface et la logique
avant tout vol. Quand c'est bon, passe `MODE_SIMULE = false` et branche le vrai
matériel via `PontDjiReel`.

---

## 6. ⚠ Vérification des axes (À FAIRE avant tout vol normal)

C'est l'étape de sécurité la plus importante. La traduction d'axes a été validée
en maths (`TraductionAxes.versDji`), mais le **sens réel** des axes DJI doit être
confirmé sur TON drone : une inversion enverrait le drone dans le mauvais sens.

**Drone attaché ou grand espace dégagé, ~1 m d'altitude :**
1. Décolle, laisse en hover.
2. **vy = +0.2 m/s** seule → le drone doit MONTER doucement (sinon inverser
   `verticalThrottle`).
3. **vz = +0.2** seule, cap 0 → doit AVANCER (nez).
4. **vx = +0.2** seule → doit aller à DROITE.
5. **yaw_rate = +10** seule → doit tourner sur place (sens à confirmer).
6. Refais 3–4 avec le drone tourné de 90° pour valider la rotation selon le cap.

Corrige les signes UNIQUEMENT dans `TraductionAxes.versDji` (fonction unique,
isolée exprès). Ne touche à rien d'autre.

---

## 7. Premiers vols réels — progressifs et prudents

1. Espace **dégagé, sans personne**, plafond bas, vitesses faibles.
2. Garde **toujours la télécommande en main** : l'ARRÊT D'URGENCE met en hover et
   te rend la main ; tu peux reprendre au manche à tout instant.
3. Augmente l'amplitude seulement quand chaque étape est stable.
4. **Pour « Suivre un véhicule » :** fais ton premier essai **voiture à l'arrêt**
   pour valider que le drone se cale bien derrière elle et tient sa position,
   AVANT de faire rouler la voiture (doucement d'abord).
5. La présence de personnes ne vient qu'en tout dernier, une fois le comportement
   parfaitement prévisible.

---

## 8. Sécurité — les règles d'or

- **Jamais au-dessus des personnes** (ni du sujet, ni des spectateurs) : le drone
  passe à côté, jamais en survol. Règle fondamentale et obligation légale.
- **Drone à vue en permanence** (obligation légale).
- **Arrêt d'urgence toujours accessible** (bouton STOP, tag Stop, « CineFlight
  stop »), et **télécommande en main**.
- **Marge de batterie** : ne jamais finir un vol au ras de la batterie.
- Respecte la **réglementation locale** (Transports Canada : zones, altitude max,
  distances).

---

## 9. Note sur le Gradle Wrapper (première ouverture)

Le projet inclut la configuration du Gradle Wrapper
(`gradle/wrapper/gradle-wrapper.properties`) mais **pas** le binaire
`gradle-wrapper.jar`. À la première ouverture, Android Studio propose de le
générer/télécharger — accepte. Sinon, dans le terminal :

```
gradle wrapper --gradle-version 8.7
```

Le fichier `local.properties` (chemin du SDK Android) est aussi généré
automatiquement par Android Studio ; un exemple est fourni
(`local.properties.exemple`) au besoin.
