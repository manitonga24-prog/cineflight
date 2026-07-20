# Plan de design — ObstacleSafetyGate (Mini 4 Pro)

**Projet :** CineFlight Solo (Android / Kotlin, DJI Mobile SDK v5)
**Date :** 2026-07-18
**Statut :** DESIGN — aucun code écrit. À valider avant implémentation.
**Règle centrale :** *pas de données d'obstacle fraîches = pas de mouvement automatique.*

> Ce document est un plan. Il ne modifie aucun fichier. Il décrit une couche de
> protection CineFlight propre au Mini 4 Pro : **ralentir, arrêter, avertir, exiger une
> reprise volontaire** — sans jamais tenter un contournement automatique de l'obstacle,
> et sans dépendre de l'évitement natif DJI comme garantie.

---

## 0. Ce que les audits ont prouvé (base factuelle du design)

Quatre audits en lecture seule + doc officielle DJI ont établi :

1. **Point de convergence unique.** Le seul point que TOUS les chemins de mouvement réels
   traversent est `PontDjiReel.envoyerVitesses` (`PontDji.kt:288`) → `sendVirtualStickAdvancedParam`
   (`PontDji.kt:312`). Trois chemins **automatiques** contournent `PiloteDrone` avec leur
   propre instance `PontDjiReel()` : `Phase3Activity` suivi RTK (`:809`), `Phase3Activity`
   vision (`:947`), `PremierVolActivity` démo (`:306`). Un gate placé dans `PiloteDrone`
   serait aveugle à ces trois-là. **Conclusion : le gate doit vivre dans `envoyerVitesses`.**

2. **Sémantique des axes au point de convergence.** `pitch/roll/throttle` en **m/s**,
   `yaw` en **deg/s**, repère `FlightCoordinateSystem.BODY` (corps du drone). Signes
   déclarés : `pitch+` = avance (nez), `roll+` = droite, `throttle+` = monte, `yaw+` =
   horaire. `throttle` (vertical) est le seul axe dont le repère est commun monde/corps.

3. **Clamps et assainissement : rien d'universel.** Au point `envoyerVitesses`, **aucun**
   clamp de magnitude et **aucune** garde NaN/∞. `finiOuZero()` (NaN/±∞→0) et
   `coerceIn(±2.0 m/s / ±60 deg/s)` n'existent que sur le chemin `PiloteDrone`
   (`PiloteDrone.kt:301-304`, `:320-325`, `:347`). `Phase3Activity` a ses propres bornes
   **différentes** (±4.0 m/s horiz, ±35 deg/s) ; `PremierVolActivity` n'a que des constantes.

4. **Orientation des secteurs horizontaux : NON PROUVÉE.** Ni le code (`LecteurPerception.kt`
   dit 3 fois « orientation du 0 à découvrir en vol ») ni la **doc officielle DJI**
   (`ObstacleData` : liste en mm, intervalle angulaire 360°, mais **aucune** définition de
   l'index 0 ni du sens de rotation) ne permettent d'associer sûrement une direction
   horizontale à un secteur. → **Le gate horizontal directionnel est bloqué** tant que
   cette calibration n'est pas faite en vol.

5. **Vertical exploitable, lui.** La doc DJI définit `getUpwardObstacleDistance()` et
   `getDownwardObstacleDistance()` comme des **scalaires** haut/bas non ambigus (mm). Le
   `throttle` a un repère fiable. → **Le vertical directionnel est sûr dès l'étape 1.**

**Contraintes permanentes :** `FusionPerception` reste **désactivée** (son bug d'unité
mm/mètres n'est PAS utilisé). Le mode DJI `BRAKE` peut rester activé comme filet
supplémentaire, mais CineFlight **ne le considère pas comme une garantie**.

---

## 1. Découpage en 4 étapes (ordre imposé)

| Étape | Contenu | Dépend de |
|---|---|---|
| **0** | Origine de commande (§2bis) + dans `envoyerVitesses` : assainissement NaN/∞ + clamp universel explicite | rien |
| **1** | Gate vertical directionnel (upward/downward) + staleness par canal + état SUSPENDED verrouillé | étape 0 |
| **2** | Gate horizontal conservateur (frein omnidirectionnel, sans direction, sans exception d'éloignement) | étape 1 |
| **3** | Gate horizontal directionnel | **calibration des secteurs en vol** |

Régime de chaque étape (correction v4-1, lève l'ambiguïté sur le miroir) :
- **Étape 0 : APPLIQUÉE RÉELLEMENT dès le départ.** L'assainissement NaN/∞→0 et le clamp de
  bornes ne sont **jamais** en miroir — ce sont des durcissements permanents du point
  d'émission (envoyer un NaN au SDK serait dangereux).
- **Étapes 1 à 3 : en MODE MIROIR initialement** (calcul + log, commande réelle inchangée),
  derrière un flag OFF, jusqu'à validation — puis activation réelle décidée séparément.

En résumé : « chaque étape passe en miroir » ne concerne QUE le **gate obstacle** (étapes
1-3), pas le durcissement de l'étape 0.

---

## 2. Étape 0 — Assainissement + clamp universels dans `envoyerVitesses`

**Problème résolu :** aujourd'hui un NaN/∞ ou une vitesse hors borne passant par un chemin
de contournement atteint le SDK sans filtre. On centralise le durcissement au seul point
commun, AVANT toute logique de gate.

Ordre cible à l'intérieur de `envoyerVitesses`, pour tous les chemins :

```
entrée (pitch,roll,throttle,yaw en repère BODY, + ORIGINE de commande — cf. §2bis)
  → assainir : NaN/±Infinity → 0            (nouveau, universel)
  → clamp magnitude : bornes de sécurité    (nouveau, universel)
  → [GATE : étapes 1-3, quand activé — agit si ORIGINE == AUTOMATIC || UNKNOWN]
  → construction VirtualStickFlightControlParam
  → sendVirtualStickAdvancedParam
```

**Bornes universelles — fixées explicitement (correction : le clamp PEUT modifier le
comportement).** Neutraliser NaN/∞ est sûr et sans effet fonctionnel. Un clamp de vitesse,
lui, **bride** un chemin si sa borne est inférieure à une commande légitime existante.
« Aucun changement fonctionnel » n'est donc vrai QUE si chaque borne universelle est
**≥ au maximum légitime réellement envoyé par tous les chemins**. Maxima relevés par
l'audit (valeur réellement envoyable de chaque chemin) :

| Chemin | Horizontal max | Vertical max | Yaw max |
|---|---|---|---|
| PiloteDrone | ±2.0 m/s | ±2.0 m/s | ±60 deg/s |
| Phase3 (RTK/vision) | ±4.0 m/s | ±2.0 m/s | ±35 deg/s |
| PremierVol (démo) | 0 | ±0.4 m/s | ±15 deg/s |

Bornes universelles proposées = **enveloppe supérieure** de ces maxima, pour ne brider
aucun chemin légitime :
```
CLAMP_HORIZ_MAX = 4.0 m/s   (pitch, roll)   ≥ tous
CLAMP_VERT_MAX  = 2.0 m/s   (throttle)      ≥ tous
CLAMP_YAW_MAX   = 60 deg/s  (yaw)           ≥ tous
```
Ces valeurs sont **explicites dans le code**, pas dérivées implicitement. **Avant
activation**, un test doit prouver que, pour chaque chemin existant, aucune commande
légitime n'atteint ces bornes (sinon on les relève). Ce clamp reste un **filet de dernier
recours** contre l'aberrant, pas le réglage fin de chaque mode.

**Champs du param à verrouiller (robustesse) :** le code ne fixe explicitement que
`rollPitchCoordinateSystem = BODY`. Le repère du vertical/yaw dépend du défaut SDK (non
lisible : classe DJI externe R8-minifiée). Comme le gate agira surtout en **forçant des
axes à 0** (sûr quel que soit le repère), ce n'est pas bloquant, mais fixer explicitement
tout coordinate system disponible pour vertical/yaw est recommandé pour lever l'incertitude.

**Invariant étape 0 :** avec les bornes fixées à l'enveloppe supérieure ci-dessus, aucun
changement de comportement fonctionnel pour les commandes saines — seuls les NaN/∞ et les
valeurs aberrantes (au-delà de l'enveloppe) sont neutralisés.

**Correction 3 — l'assainissement N'EST PAS en miroir : il est APPLIQUÉ pour de vrai.**
Distinction essentielle entre deux briques de l'étape 0, qui n'ont pas le même régime :

- **Assainissement universel (NaN/∞→0) + clamp de bornes : TOUJOURS APPLIQUÉS avant le
  SDK**, même en phase de test. Envoyer volontairement un `NaN`, un `∞` ou une vitesse
  aberrante au SDK « pour observer le miroir » serait dangereux. Ces deux traitements ne
  sont donc **jamais** en miroir — ils font partie du durcissement permanent du point
  d'émission.
- **Gate obstacle (étapes 1-3) : lui SEUL passe par le mode miroir** (calculé, journalisé,
  non appliqué) tant qu'il n'est pas validé.

Autrement dit, ce qui part au SDK en phase de test = **commande assainie + clampée**
(jamais la brute si elle contient NaN/∞/aberrant), mais **sans** la modulation du gate
obstacle. Les trois valeurs sont journalisées côte à côte (cf. §6) : entrée brute,
commande assainie (réellement envoyée), commande proposée par le gate (non envoyée).

---

## 2bis. Origine de commande — prérequis du gate (correction 1)

**Problème :** aujourd'hui `envoyerVitesses(pitch, roll, throttle, yaw)` ne reçoit que les
4 axes. Le gate ne peut pas deviner si une commande est automatique ou manuelle. Un booléen
`automaticMode` serait fragile (facile à oublier sur un chemin). Il faut une **origine
explicite et typée**, propagée jusqu'au point commun.

```kotlin
enum class CommandOrigin {
    AUTOMATIC,  // suivi RTK, vision, orbite, mission, montée auto, sentinelle → GATE ACTIF
    MANUAL,     // joystick, télécommande, bridge piloté par un humain        → GATE PASS
    TEST,       // bancs d'axes (Phase2b), démos scriptées de test            → GATE PASS
    UNKNOWN     // origine non déclarée — état d'ERREUR, jamais toléré en prod
}
```

**Changement de signature nécessaire :**
```
envoyerVitesses(pitch, roll, throttle, yaw, origin: CommandOrigin)
```
C'est une modification d'interface qui touche **tous** les appelants (les 3 chemins
convergents via `PiloteDrone` + les contournements `Phase3`/`PremierVol`/`Phase2b`). Chaque
appelant déclare son origine :
- `PiloteDrone.emettreSdk` : `AUTOMATIC` pour le suivi/mission/sentinelle, `MANUAL` pour le
  joystick/bridge humain (à distinguer selon la source de la `CommandeBridge`).
- `Phase3Activity` (suivi RTK, vision, orbite) : `AUTOMATIC`.
- `PremierVolActivity` (démo scriptée) : `AUTOMATIC` (mouvement non piloté par un humain).
- `Phase2bActivity` (test d'axes), `Phase2Activity` (zéros) : `TEST`.

**Règle du gate :** il agit si `origin == AUTOMATIC || origin == UNKNOWN` (UNKNOWN traité
prudemment comme automatique, cf. plus bas). Pour `MANUAL`/`TEST`, il renvoie
`DISABLED`/`PASS` — on ne bride jamais le pilote ni un banc de test.

**Traitement de `UNKNOWN` (correction 1 — pas de défaut `MANUAL` silencieux).** Classer par
défaut une origine non déclarée comme `MANUAL` serait un **échec non sécuritaire** : un
chemin automatique oublié contournerait le gate sans bruit. On refuse ce compromis. À la
place :
- **aucune valeur par défaut permissive** : la signature n'a **pas** de défaut `MANUAL`.
  Si un défaut transitoire est nécessaire pendant la migration, ce sera `UNKNOWN`, jamais
  `MANUAL` ni `AUTOMATIC` ;
- `origin == UNKNOWN` est un **état d'erreur** : journalisé comme tel (`GATE_ORIGIN_UNKNOWN`)
  et **ne permet jamais silencieusement un mouvement automatique**. Concrètement, une commande
  `UNKNOWN` non nulle est traitée au minimum aussi prudemment que `AUTOMATIC` (donc soumise
  au gate/staleness) — jamais laissée passer comme du manuel ;
- **interdit après migration** : une fois tous les appelants câblés, `UNKNOWN` ne doit plus
  jamais apparaître. Un test le vérifie : lister tous les appelants de `envoyerVitesses` et
  prouver qu'aucun n'envoie `UNKNOWN`, et que chaque chemin automatique passe `AUTOMATIC`.

---

## 3. Interface du gate (contrat, indépendant de l'implémentation)

```kotlin
// Commande en repère CORPS du drone, au point envoyerVitesses.
data class VelocityCommand(
    val pitch: Float,     // m/s, + = avance (nez)
    val roll: Float,      // m/s, + = droite
    val throttle: Float,  // m/s, + = monte
    val yaw: Float        // deg/s, + = horaire
)

// Instantané de perception, tel que lu par LecteurPerception (mm).
// Correction 4 : un âge PAR CANAL — les 3 données arrivent/expirent indépendamment.
data class ObstacleSnapshot(
    val horizontalDistancesMm: List<Int>?, // secteurs — ORIENTATION NON CALIBRÉE (étape 3)
    val horizontalAgeMs: Long,             // âge de la dernière trame horizontale exploitable
    val upwardMm: Int?,                    // scalaire haut (doc DJI) — fiable
    val upwardAgeMs: Long,                 // âge de la dernière mesure haut
    val downwardMm: Int?,                  // scalaire bas  (doc DJI) — fiable
    val downwardAgeMs: Long                // âge de la dernière mesure bas
)

data class SafetyGateInput(
    val command: VelocityCommand,
    val perception: ObstacleSnapshot?,
    val origin: CommandOrigin,             // correction 1 : AUTOMATIC/MANUAL/TEST
    val coordinateFrame: CoordinateFrame   // attendu : BODY
)

enum class GateAction {
    PASS,               // commande inchangée
    SLOW,               // vitesse réduite dans la direction dangereuse
    BLOCK_DIRECTION,    // composante vers l'obstacle mise à 0 (autres axes libres)
    STOP_TRANSLATION,   // toutes translations à 0 (yaw éventuellement conservé)
    PERCEPTION_STALE,   // données absentes/périmées → mouvement auto stoppé
    SUSPENDED,          // suspension VERROUILLÉE — reste tant que le pilote n'a pas repris
    DISABLED,           // gate OFF (flag) ou origine MANUAL/TEST → PASS intégral
    ORIGIN_UNKNOWN      // origine non déclarée → traité comme AUTOMATIC prudent + erreur loggée
}

data class SafetyGateResult(
    val command: VelocityCommand,  // commande éventuellement modifiée
    val action: GateAction,
    val reason: String             // journalisé (mode miroir + réel)
)
```

**Horloge et concurrence (correction v4-3).** Deux exigences non négociables pour que les
âges soient justes et que le gate ne lise pas un instantané incohérent :

- **Horloge monotone** : tous les âges (`horizontalAgeMs`, `upwardAgeMs`, `downwardAgeMs`)
  se calculent avec `SystemClock.elapsedRealtime()` — jamais `System.currentTimeMillis()`,
  qui peut sauter (NTP, changement d'heure) et fausser une fraîcheur. Chaque canal mémorise
  le `elapsedRealtime()` de sa dernière mesure valide ; l'âge = `now − timestampCanal`.
- **Instantané atomique thread-safe** : le callback `ObstacleDataListener` (thread DJI) et
  la boucle Virtual Stick (`envoyerVitesses`) s'exécutent sur des threads différents. Le
  gate doit lire un `ObstacleSnapshot` **cohérent** — pas un mélange où `upwardMm` vient
  d'une trame et `upwardAgeMs` d'une autre. Le callback publie donc un snapshot **immuable
  d'un coup** (référence `@Volatile` ou `AtomicReference<ObstacleSnapshot>`) ; le gate lit
  la référence une seule fois par tick. Aucune structure mutable partagée n'est lue
  champ par champ.

---

**Correction 2 — le gate n'est PAS entièrement pur, et la suspension est PAR CANAL.** Une
fonction pure peut calculer un `STOP` instantané, mais elle ne peut pas MÉMORISER qu'une
suspension a eu lieu. Or le plan exige « aucune reprise automatique ». Cela impose un **état
persistant**. Et comme la staleness est par canal (correction 4), le verrou doit l'être
aussi : un `upward` périmé ne doit bloquer que la montée, pas la descente ni l'horizontal.
Un booléen global `suspended` contredirait cette granularité. On utilise donc un **état à
trois canaux** :

```kotlin
data class ObstacleSafetyState(
    // Suspensions verrouillées par canal (correction v2-2)
    val verticalUpSuspended: Boolean,    // montée auto verrouillée
    val verticalDownSuspended: Boolean,  // descente auto verrouillée
    val horizontalSuspended: Boolean,    // translations horizontales auto verrouillées
    // Mémoire d'hystérésis par canal (correction v4-2) : « le SLOW était-il déjà engagé ? »
    // Sans cette mémoire, l'hystérésis décrite en §4.0 ne peut pas fonctionner (il faut
    // savoir si on était en zone SLOW pour appliquer le seuil de SORTIE, pas d'entrée).
    val verticalUpSlowing: Boolean,
    val verticalDownSlowing: Boolean,
    val horizontalSlowing: Boolean
)
```

L'évaluateur reçoit cet état, lit les drapeaux `...Slowing` pour choisir le bon seuil
(entrée `D_DEGAGE` vs sortie `D_DEGAGE + H_MM`), et la machine à états met à jour ces
drapeaux après chaque tick. Les drapeaux `...Slowing` sont de la **mémoire d'hystérésis**,
pas des verrous : ils n'empêchent aucun mouvement, ils stabilisent seulement le facteur SLOW.

On sépare deux briques :

- **Évaluateur pur** `evaluate(input, state: ObstacleSafetyState): SafetyGateResult` —
  fonction pure, testable sans drone/SDK/Android. Lit l'état ; ne le modifie pas.
- **Machine à états** (impure, minimale) qui met à jour `ObstacleSafetyState` :
  - un canal passe à *suspendu* dès qu'un des événements critiques suivants le concerne en
    `AUTOMATIC`/`UNKNOWN` (correction v5-2) :
    - `PERCEPTION_STALE` (donnée absente/périmée/INDISPONIBLE) ;
    - `BLOCK_DIRECTION` critique (obstacle atteint `D_STOP`, vitesse vers l'obstacle → 0) ;
    - `STOP_TRANSLATION` critique (horizontal sous `D_STOP_H`).
    Ex. montée + `upward` périmé **ou** `upwardMm ≤ D_STOP_V` → `verticalUpSuspended = true`.
    **Sans ce verrou sur `BLOCK_DIRECTION`, la montée/descente reprendrait automatiquement
    dès que la distance remonte de quelques mm — ce que « aucune reprise automatique »
    interdit.** ;
  - **reste** suspendu même si sa donnée redevient fraîche (pas de reprise auto) ;
  - ne sort de suspension que sur **reprise explicite du pilote** (bouton « Reprendre le
    contrôle automatique » ou reprise manuelle des commandes), journalisée (`GATE_RESUME`).
    La reprise peut être **par canal** ou globale selon l'UI retenue (à trancher à
    l'implémentation ; par défaut, une reprise lève tous les canaux).

Tant qu'un canal est suspendu, l'évaluateur force à 0 **la composante de ce canal
seulement** : `verticalUpSuspended` bloque `throttle>0` mais laisse `throttle<0` et
l'horizontal ; `horizontalSuspended` bloque pitch/roll mais laisse le vertical et le yaw.

---

## 4. Règles du gate

### 4.0 Définition du ralentissement `SLOW` (correction v3-3)
Dans la zone « approche » (`D_STOP < d < D_DEGAGE`), la vitesse vers l'obstacle est
multipliée par un **facteur** linéaire, borné et hystérétique :

```
facteur = clamp01( (d - D_STOP) / (D_DEGAGE - D_STOP) )
  d >= D_DEGAGE            → facteur = 1   (PASS, vitesse pleine)
  d <= D_STOP              → facteur = 0   (BLOCK_DIRECTION / STOP_TRANSLATION)
  entre les deux           → facteur ∈ ]0,1[  (SLOW proportionnel)
```

- `clamp01` borne le résultat entre 0 et 1 (jamais négatif, jamais > 1).
- **Hystérésis** anti-oscillation, avec **mémoire** (correction v4-2) : deux seuils décalés
  d'une marge `H_MM`. On **entre** en zone SLOW quand `d` passe sous `D_DEGAGE` ; on n'en
  **sort** (retour PASS plein) que lorsque `d` remonte au-dessus de `D_DEGAGE + H_MM`. Ce
  choix dépend de l'état précédent (« étais-je déjà en SLOW ? »), d'où le drapeau
  `...Slowing` par canal dans `ObstacleSafetyState`. Sans cette mémoire, l'hystérésis ne
  peut pas exister. `H_MM` est une constante à calibrer (départ ~200 mm).
- Le facteur ne s'applique **qu'à la composante allant vers l'obstacle** ; les autres axes
  et l'éloignement (là où il est identifiable, cf. 4.3bis) ne sont pas ralentis.

### 4.0bis Règle des mesures invalides — INDISPONIBLE ≠ DÉGAGÉ (correction v3-4)
Règle de sûreté fondamentale : **une donnée qu'on ne comprend pas n'est jamais interprétée
comme « espace dégagé ».**

- liste horizontale **vide après filtrage**, valeur **nulle/inconnue**, ou **sentinelle non
  prouvée** → statut = **INDISPONIBLE**, jamais « loin/dégagé » ;
- une donnée INDISPONIBLE pour le canal engagé déclenche la staleness de ce canal (§4.2),
  donc suspension du mouvement auto concerné — pas un `PASS`.
- La valeur `60000` reste **expérimentale** (non certifiée DJI). Tant que sa signification
  n'est pas validée en vol, on ne peut pas affirmer que « tous secteurs = 60000 » signifie
  « rien à proximité ». **Conséquence : l'étape 2 (horizontal conservateur) NE DOIT PAS être
  activée avant validation de la sémantique de `60000`.** L'étape 1 (vertical) n'en dépend
  pas (scalaires upward/downward prouvés par la doc).

### 4.1 Périmètre : origine AUTOMATIC ou UNKNOWN (corrections 1 + v2-1)
Le gate agit si `origin == AUTOMATIC` **ou** `origin == UNKNOWN` — `UNKNOWN` est traité
prudemment **comme** de l'automatique (jamais laissé passer comme du manuel), et journalise
`GATE_ORIGIN_UNKNOWN`. Pour `MANUAL` et `TEST`, il renvoie `DISABLED` : on ne bride jamais
le pilote ni un banc de test. L'origine est fournie par l'appelant (cf. §2bis), pas devinée.

### 4.2 Staleness — PAR CANAL (correction 4) + verrouillage (correction 2)
Chaque canal a son propre âge (`horizontalAgeMs`, `upwardAgeMs`, `downwardAgeMs`). Un canal
est *périmé* si sa donnée est absente OU son âge > `TIMEOUT_PERCEPTION_MS`.

- Si le canal **pertinent pour la commande en cours** est périmé (ex. le drone monte et
  `upward` est périmé), on **arrête le mouvement automatique concerné** et on **verrouille**
  l'état à `SUSPENDED` (cf. §3, correction 2) ;
- **aucune reprise automatique** : rester `SUSPENDED` même si la donnée revient ;
- reprise **uniquement** par action explicite du pilote ;
- annonce vocale : « Perception obstacle perdue. Contrôle automatique suspendu. » ;
- action = `PERCEPTION_STALE` (transition) puis `SUSPENDED` (état verrouillé).

Granularité : on ne suspend que ce qui dépend du canal périmé. Un `upward` périmé ne
suspend pas une descente si `downward` est frais (cf. 4.4). La suspension ne survient que
si le canal réellement engagé par la commande auto est périmé.

### 4.3 Toujours autorisé (jamais bloqué par le gate)
- un **STOP** (commande nulle) — toujours ;
- la **reprise manuelle** (le pilote reprend les commandes) — toujours ;
- un **RTH natif DJI** (sauf décision spécifique ultérieure) — toujours ;
- une commande qui **éloigne** le drone de l'obstacle — **UNIQUEMENT** là où la direction
  sûre est connue (correction 3, cf. 4.3bis).

### 4.3bis Où « l'éloignement autorisé » s'applique vraiment (correction 3)
Le principe « une commande d'éloignement est toujours permise » n'a de sens **que si l'on
sait de quel côté est l'obstacle**. Ce n'est vrai que pour :
- le **vertical** (étape 1) : `upward`/`downward` sont des scalaires directionnels prouvés,
  donc « s'éloigner » = sens opposé, sûr et identifiable ;
- l'**horizontal directionnel** (étape 3), **après calibration** des secteurs seulement.

Pour l'**horizontal conservateur** (étape 2), l'orientation est inconnue : on **ne peut pas**
distinguer une translation qui s'éloigne d'une qui s'approche. La règle « éloignement
autorisé » **ne s'applique donc PAS** à l'horizontal avant calibration — c'est la
résolution de la contradiction relevée. À l'étape 2, toutes les translations horizontales
sont traitées identiquement (cf. 4.5). Le STOP, lui, reste toujours possible.

### 4.4 Vertical directionnel (étape 1) — sûr et prouvé
Utilise les scalaires DJI `upwardMm` / `downwardMm` (chacun avec son âge) :
- `throttle > 0` (monte) → évaluer `upwardMm` (canal `upward`) ;
- `throttle < 0` (descend) → évaluer `downwardMm` (canal `downward`) ;
- **canal concerné absent/périmé** → bloquer **seulement** le mouvement vertical automatique
  dans ce sens ; l'autre sens et l'horizontal restent libres ;
- **STOP et mouvement opposé toujours autorisés** (monter reste permis même si `downwardMm`
  manque/périmé, et inversement) — ici l'éloignement EST identifiable (4.3bis).

**Validité de la mesure verticale (correction v4-4) — INDISPONIBLE ≠ dégagé.** La *direction*
haut/bas est prouvée (doc DJI), mais la *valeur* ne l'est pas encore. Toute valeur
`upwardMm`/`downwardMm` qui n'est pas une distance positive plausible est traitée comme
**INDISPONIBLE** (→ staleness du canal, §4.2), **jamais** comme « dégagé » :
- `null`, `0`, valeur **négative**, ou toute **valeur spéciale/sentinelle non documentée** ;
- valeur hors plage physique plausible (à borner une fois observée en vol).
Une valeur INDISPONIBLE ne doit jamais produire un `PASS` (« espace libre ») — c'est le
même principe qu'en horizontal (§4.0bis). **Conséquence : l'étape 1 peut être CODÉE en mode
miroir, mais son activation RÉELLE est interdite tant que les valeurs `upward`/`downward`
n'ont pas été observées en vol** (plage réelle, valeur « rien détecté », comportement au
sol vs en l'air). Le miroir sert précisément à collecter ces valeurs sans risque.

Barème de distance (mm, à calibrer — valeurs de départ ; `SLOW` défini en §4.0) :
```
loin      (d >= D_DEGAGE_V)   → PASS                (facteur = 1)
approche  (D_STOP_V < d < D_DEGAGE_V) → SLOW        (facteur = (d-D_STOP_V)/(D_DEGAGE_V-D_STOP_V))
proche    (d <= D_STOP_V)     → BLOCK_DIRECTION     (facteur = 0, vitesse vers l'obstacle = 0)
```

### 4.5 Horizontal conservateur (étape 2) — sans direction
Tant que les secteurs ne sont pas calibrés, on **n'attribue aucune direction** à
l'obstacle horizontal (canal `horizontal`, âge `horizontalAgeMs`). On utilise la
**distance minimale toutes directions** :
```
min_h = min(horizontalDistancesMm sans les valeurs sentinelles ~60000)
si la liste filtrée est vide OU min_h indéterminé → INDISPONIBLE (jamais « dégagé », cf. 4.0bis)
loin      (min_h >= D_DEGAGE_H)  → PASS   (facteur = 1)
approche  (D_STOP_H < min_h < D_DEGAGE_H) → SLOW (facteur §4.0 sur la NORME horizontale)
proche    (min_h <= D_STOP_H)    → STOP_TRANSLATION (facteur = 0 ; pitch=roll=0 ; yaw conservé)
```
Rappel (correction v3-4) : cette étape 2 **reste désactivée** tant que la sémantique de la
sentinelle `60000` n'est pas validée en vol.
Conservateur assumé (correction 3) : on ralentit/arrête **toutes** les translations
horizontales, **y compris celles qui s'éloigneraient**, parce qu'on ignore de quel côté est
l'obstacle. La règle « éloignement autorisé » ne s'applique donc PAS ici (cf. 4.3bis). C'est
le prix de l'incertitude d'orientation — levé à l'étape 3 après calibration. Le canal
`horizontal` périmé verrouille `SUSPENDED` si une translation horizontale auto est en cours.

### 4.6 Horizontal directionnel (étape 3) — BLOQUÉ jusqu'à calibration
Ne sera conçu/activé qu'après avoir prouvé empiriquement, en vol, obstacle placé dans une
direction connue : (a) quel index de secteur pointe vers le nez, (b) le sens de progression
des index, (c) le référentiel (corps confirmé). Procédure = `journaliserTrame()` en vol.
Tant que ces 3 points ne sont pas prouvés, l'étape 3 reste au tableau, non codée.

---

## 5. Seuils, unités, timeout (tous en millimètres)

| Constante | Rôle | Valeur de départ (à calibrer) |
|---|---|---|
| `TIMEOUT_PERCEPTION_MS` | au-delà, perception périmée → stop | 500 ms (à ajuster vu la cadence réelle) |
| `D_DEGAGE_V` | vertical dégagé | 3000 mm |
| `D_STOP_V` | vertical critique | 1000 mm |
| `D_DEGAGE_H` | horizontal dégagé | 4000 mm |
| `D_STOP_H` | horizontal critique | 2000 mm |
| `SENTINELLE_MM` | valeur « rien détecté » à ignorer | ~60000 (expérimental, non certifié DJI) |
| `CLAMP_HORIZ_MAX` | filet universel étape 0 (pitch, roll) | 4.0 m/s (enveloppe des chemins) |
| `CLAMP_VERT_MAX` | filet universel étape 0 (throttle) | 2.0 m/s |
| `CLAMP_YAW_MAX` | filet universel étape 0 (yaw) | 60 deg/s |
| `H_MM` | hystérésis anti-oscillation du SLOW (§4.0) | ~200 mm |

Le timeout est **par canal** : `horizontalAgeMs`, `upwardAgeMs`, `downwardAgeMs` sont
comparés séparément à `TIMEOUT_PERCEPTION_MS`.

Toutes les distances capteurs sont en **mm** (doc DJI officielle). Aucune comparaison à des
seuils en mètres (c'est précisément le bug de `FusionPerception`, qu'on n'utilise pas).

---

## 6. Flag + mode miroir (déploiement sans risque)

```
OBSTACLE_SAFETY_GATE_ENABLED = false   // OFF par défaut
OBSTACLE_SAFETY_GATE_MIRROR  = false   // true = calcule + log, mais N'APPLIQUE PAS
```

Séquence de mise en service :
1. gate codé + tests unitaires verts ;
2. **assainissement + clamp actifs** (permanents, cf. correction 3) ;
3. **gate obstacle en mode miroir** : `evaluate()` est appelé et journalisé, mais sa
   modulation n'est PAS appliquée. Ce qui part au SDK = **commande assainie/clampée**
   (jamais une brute NaN/∞/aberrante) ;
4. comparaison dans les logs sur plusieurs vols ;
5. activation réelle du gate obstacle **seulement** après validation.

Log du mode miroir (par tick) — **trois** commandes distinctes (correction 3) :
```
GATE_MIRROR
  origin=<AUTOMATIC|MANUAL|TEST|UNKNOWN>
  cmd_brute=(pitch,roll,throttle,yaw)         # entrée telle que reçue (peut contenir NaN/∞)
  cmd_assainie=(pitch,roll,throttle,yaw)      # APRÈS NaN/∞→0 + clamp — RÉELLEMENT envoyée au SDK
  cmd_gate=(pitch,roll,throttle,yaw)          # proposée par le gate obstacle — NON envoyée (miroir)
  action=<GateAction>
  state=(up=<bool>,down=<bool>,horiz=<bool>)  # ObstacleSafetyState par canal
  distance_obstacle_mm=<min pertinent du canal engagé>
  age_horizontal_ms=<horizontalAgeMs>
  age_upward_ms=<upwardAgeMs>
  age_downward_ms=<downwardAgeMs>
  direction=<VERTICAL_UP|VERTICAL_DOWN|HORIZONTAL_OMNI|NONE>
  raison=<reason>
```
Invariant miroir : `cmd_assainie` part toujours au SDK ; `cmd_gate` n'est jamais appliquée
en miroir. Le gate obstacle n'est activé qu'après avoir observé, compris et jugé correct
`cmd_gate` ≠ `cmd_assainie` sur des cas réels.

**État miroir séparé de l'état réel (correction v2b-2).** Le mode miroir fait tourner la
machine à états, donc elle accumule des suspensions. Ces suspensions **ne doivent pas**
contaminer le fonctionnement réel ni laisser un verrou actif au moment où l'on activera le
gate pour de vrai. On maintient donc **deux instances distinctes** de `ObstacleSafetyState` :

```kotlin
liveSafetyState    // état RÉEL — piloté uniquement quand le gate est ACTIVÉ
mirrorSafetyState  // état d'OBSERVATION — piloté par le mode miroir, sans effet
```

Règles :
- en mode miroir, seul `mirrorSafetyState` est mis à jour ; `liveSafetyState` reste neutre
  (aucun canal suspendu) ;
- le miroir **ne suspend jamais** l'état réel et **ne bloque jamais** une commande ;
- à l'activation réelle du gate, `liveSafetyState` **démarre propre** (aucun verrou hérité
  du miroir) ; `mirrorSafetyState` peut être conservé pour comparaison ou remis à zéro ;
- une reprise pilote (`GATE_RESUME`) agit sur l'état actif du moment (miroir en observation,
  live en activation), jamais l'un sur l'autre.

---

## 7. Tests (avant toute activation)

L'**évaluateur** étant pur, tests unitaires sans drone (l'état `ObstacleSafetyState` est
passé en argument et vérifié séparément) :

- **Staleness par canal** : `upwardAgeMs > timeout` pendant une montée → `PERCEPTION_STALE`
  ; mais une descente au même instant avec `downwardAgeMs` frais reste évaluée normalement.
- **Verrouillage SUSPENDED par canal** : après un `PERCEPTION_STALE`, un `BLOCK_DIRECTION`
  critique OU un `STOP_TRANSLATION` critique sur la
  montée, `verticalUpSuspended=true` ; **la perception qui redevient fraîche ne rétablit
  PAS** la montée. Vérifier en même temps que `verticalDownSuspended` et
  `horizontalSuspended` restent `false` (la descente et l'horizontal ne sont pas bloqués).
- **Indépendance des canaux** : `horizontalSuspended=true` bloque pitch/roll mais laisse
  passer throttle et yaw ; et réciproquement.
- **Reprise explicite** : depuis un canal suspendu, seule l'action pilote dédiée lève le
  verrou (log `GATE_RESUME`) ; toute autre entrée laisse le canal suspendu.
- **Vertical up** : monter vers un `upwardMm` décroissant → PASS → SLOW → BLOCK_DIRECTION ;
  descendre au même instant reste autorisé (éloignement identifiable, 4.3bis).
- **Vertical down** : symétrique avec `downwardMm`.
- **Canal vertical absent/périmé** : `upwardMm=null` ou `upwardAgeMs>timeout` → monter
  bloqué, descendre libre.
- **Horizontal conservateur** : `min_h` décroissant → PASS → SLOW → STOP_TRANSLATION ;
  yaw conservé ; une translation horizontale « d'éloignement » est **aussi** freinée
  (correction 3 : pas d'exception d'éloignement avant calibration).
- **STOP / reprise manuelle / RTH** : toujours autorisés quel que soit l'état perception.
- **Origine MANUAL/TEST** : → `DISABLED`/PASS intégral, même perception périmée ;
  `origin=AUTOMATIC` → gate actif.
- **Origine UNKNOWN (correction v2-1)** : une commande auto non nulle en `UNKNOWN` est
  traitée **au moins aussi prudemment que `AUTOMATIC`** (jamais laissée passer comme du
  manuel) et journalise `GATE_ORIGIN_UNKNOWN`. Test de migration : aucun appelant n'envoie
  `UNKNOWN` en prod.
- **Clamp universel (étape 0)** : une commande légitime à la borne exacte (4.0 / 2.0 / 60)
  ressort **inchangée** ; au-delà, ramenée à la borne. Test dédié : pour chaque chemin
  existant, la commande max réelle ≤ borne (sinon relever).
- **Assainissement TOUJOURS appliqué (correction v2-3)** : une entrée avec `NaN`/`∞` produit
  une `cmd_assainie` à 0 sur l'axe fautif **et c'est bien elle qui part au SDK**, même en
  mode miroir. Vérifier qu'aucune `cmd_brute` contenant NaN/∞ ne peut atteindre le SDK.
- **Miroir du gate obstacle seulement** : en mode miroir, `cmd_gate` n'est **jamais**
  appliquée ; la commande envoyée est `cmd_assainie` (pas la brute, pas la gate).
- **SLOW proportionnel (v3-3)** : à `d = (D_STOP+D_DEGAGE)/2` → facteur ≈ 0.5 ; à
  `d = D_DEGAGE` → 1 ; à `d = D_STOP` → 0. Bornage : `d < D_STOP` ne donne jamais un facteur
  négatif ; `d > D_DEGAGE` jamais > 1.
- **Hystérésis (v3-3)** : un `d` oscillant autour du seuil dans la marge `H_MM` ne fait pas
  basculer l'action à chaque tick (entrée/sortie de zone décalées).
- **Invalide ≠ dégagé (v3-4)** : liste horizontale vide après filtrage, ou valeur
  inconnue/sentinelle non prouvée → statut INDISPONIBLE → staleness du canal (jamais PASS).
- **État miroir séparé (v2b-2)** : faire suspendre `mirrorSafetyState` en miroir ne modifie
  jamais `liveSafetyState` ; à l'activation, `liveSafetyState` démarre sans verrou hérité.
- **Mémoire d'hystérésis (v4-2)** : partant de `...Slowing=true`, un `d` entre `D_DEGAGE` et
  `D_DEGAGE+H_MM` **reste** en SLOW (ne repasse pas PASS) ; il ne repasse PASS qu'au-dessus
  de `D_DEGAGE+H_MM`. Partant de `...Slowing=false`, on n'entre en SLOW qu'en dessous de
  `D_DEGAGE`.
- **Horloge monotone (v4-3)** : l'âge se calcule sur `elapsedRealtime()` ; un recul de
  `currentTimeMillis()` (simulé) ne doit pas affecter les âges. Snapshot lu en une fois
  (pas de mélange trame/âge de deux callbacks).
- **Validité verticale (v4-4)** : `upwardMm` ∈ {null, 0, négatif, sentinelle non prouvée}
  → INDISPONIBLE → montée bloquée (jamais PASS) ; seule une distance positive plausible
  autorise PASS/SLOW.

Cible : couvrir chaque `GateAction` (dont `SUSPENDED` par canal et `ORIGIN_UNKNOWN`), chaque
« toujours autorisé », chaque origine, l'indépendance des trois canaux, et la séparation
assainissement (appliqué) / gate (miroir), par au moins un test.

---

## 8. Ce que le plan NE fait PAS (limites explicites)

- Ne code rien tant qu'il n'est pas validé.
- N'active aucun gate par défaut (flag OFF).
- Ne prétend pas connaître la direction horizontale de l'obstacle (étape 3 bloquée).
- N'utilise pas `FusionPerception` ni son barème mm/mètres.
- Ne considère pas `BRAKE` DJI comme une garantie.
- Ne tente jamais de **contourner** un obstacle : il ralentit, bloque la direction
  dangereuse, arrête, avertit — et exige une reprise volontaire.

---

## 9. Prochaine décision (après validation de ce plan)

Implémenter **étape 0 + étape 1** en mode miroir, avec tests unitaires, sans activation
réelle. La calibration des secteurs horizontaux (prérequis de l'étape 3) est un chantier
distinct, à mener en vol via `journaliserTrame()` avec un obstacle en direction connue.
