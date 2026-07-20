# Rapport - Test au sol de la perception d'obstacles

**Projet :** CineFlight Solo (Android / Kotlin, DJI Mobile SDK v5)
**Objet :** consigner les preuves obtenues lors du test au sol du logger de perception passif, et distinguer sans ambiguité ce qui est prouvé de ce qui reste à prouver, avant toute décision de réparation de l'évitement.
**Date du rapport :** 2026-07-18
**Contrainte permanente rappelée :** aucun changement au mode d'évitement, et aucune annonce vocale « protection active » / « obstacle évité », tant que la source réelle des données, la fraîcheur, le comportement sans données et le dernier point de modification des vitesses avant envoi au SDK ne sont pas prouvés à l'exécution en conditions de vol.

---

## 1. Contexte reproductible

| Élément | Valeur |
|---|---|
| Commit testé (HEAD) | `00f91f84f00e92935df7dc0d4ccfad18102b0306` |
| Message du commit | « Diag perception : bouton + tick periodique dans Premier vol (test sol/simulateur) » |
| Commit du logger passif | `e72adcd` (« Logger perception lecture seule ... passif, off par defaut ») |
| Arbre de travail | propre côté code source (seuls des fichiers `_to_delete/` et scripts `.ps1` non suivis) |
| Outil de test | bouton « Diag perception (sol/sim) » de `PremierVolActivity` |
| Process observé dans Logcat | `8749`, tag `PerceptionDiag` |
| Cycles observés | `VS-1` à `VS-279` |

Pour reproduire la lecture du commit :
```
git rev-parse HEAD          # -> 00f91f84...
git status --short          # -> uniquement _to_delete/ et *.ps1 non suivis
git log --oneline -3        # -> 00f91f8, e72adcd, 03f0768
```

---

## 2. Conditions réelles du test

Le test a été réalisé **au sol**, drone posé, en agitant la main devant les capteurs horizontaux.

| Champ journalisé | Valeur observée | Conséquence |
|---|---|---|
| `vs_active` | **false** tout du long | Virtual Stick INACTIF : on n'a PAS testé le comportement en vol autonome |
| `model` | **?** | Le fournisseur de modèle n'était pas branché sur ce chemin (`PremierVolActivity` a son propre `LecteurPerception`) |
| `unit_assumption` | `millimeters` | Hypothèse du code, désormais confirmée par la doc (section 4) |
| `logs_perdus` | **0** | Le logger non-bloquant n'a jamais débordé ni retardé quoi que ce soit |

**Ce que ces conditions permettent d'affirmer :** on a mesuré le flux capteur brut et son unité. **Ce qu'elles interdisent d'affirmer :** tout ce qui concerne le vol (réception pendant Virtual Stick actif, exécution réelle de `appliquerEvitement`, atteinte du SDK).

---

## 3. Valeurs observées (log process 8749)

Séquence résumée :

- `VS-1` à `VS-3` (et début `VS-4`) : `raw_distance=null`, `raw_list=[]` — aucune donnée encore (listener tout juste enregistré).
- À partir de `VS-4` (~09:44:02) : la liste devient `[60000, 60000, ...]` sur l'ensemble des secteurs — « rien de proche ».
- À partir de `VS-6` : de VRAIES distances apparaissent et **varient de façon cohérente** avec le mouvement de la main : `297 -> 283 -> 266 -> 250 -> 238 ...` puis remontée vers ~440-500, avec des `60000` intercalés sur les secteurs non couverts par la main.

Interprétation des ordres de grandeur (unité = mm, cf. section 4) :

```
valeur brute 238   = 238 mm   = 0,24 m   (main très proche, plausible)
valeur brute 297   = 297 mm   = 0,30 m
valeur brute 500   = 500 mm   = 0,50 m
valeur brute 60000 = 60000 mm = 60 m     (hors portée -> "aucune détection proche")
```

---

## 4. Unité des distances — TRANCHÉE par la doc officielle DJI

La documentation officielle DJI MSDK v5 indique que `getHorizontalObstacleDistance()` (ainsi que les distances vers le haut et vers le bas) est exprimée en **millimètres**.

Source : developer.dji.com — API Reference v5 Android, `IPerceptionManager.ObstacleData`.

**Conséquence directe sur le code (prouvée par lecture du code, commit 00f91f8) :**

Dans `MainActivity.appliquerEvitement` (lignes 2335-2385), la décision compare la distance brute `d` (issue de `distanceHorizontale()`, donc en mm) directement aux constantes :

```
EVIT_DANGER_MM   = 4000   // 4 m
EVIT_CRITIQUE_MM = 2000   // 2 m
```

Les seuils sont nommés `_MM` et valent effectivement des millimètres. La comparaison est donc **mm comparé à mm : cohérente**. C'est le « cas correct » : aucune confusion d'unité dans ce chemin précis. (La valeur `nearestM = d/1000.0` n'est calculée QUE pour l'affichage du log, pas pour la décision.)

**Attention documentée pour la suite :** les paramètres NATIFS DJI de distance d'avertissement/freinage (`horizontalObstacleAvoidanceWarningDistance`, `...BrakingDistance`) sont, eux, exprimés en **mètres**. Il existe donc un risque réel de mélange d'unités si un futur code compare une distance capteur (mm) à un paramètre natif (m). À surveiller.

---

## 5. Valeur sentinelle 60000 — preuve EXPÉRIMENTALE, non certifiée par DJI

Dans le test, `60000` correspond systématiquement à « aucun obstacle proche dans ce secteur » (valeur observée quand rien n'est devant le capteur).

**Statut de preuve : EXPÉRIMENTAL.** La documentation officielle consultée ne définit pas explicitement `60000` comme une valeur sentinelle « pas de détection ». On conserve donc cette conclusion comme observation reproductible, PAS comme règle DJI documentée.

**Effet sur le code actuel (par lecture) :** `distanceHorizontale()` fait `minOrNull()`. Comme `60000` est une grande valeur, `minOrNull()` l'ignore dès qu'une distance plus courte existe ; et quand TOUS les secteurs valent `60000`, `minOrNull()` renvoie `60000 > EVIT_DANGER_MM (4000)` -> aucune action. Le comportement est donc correct **par construction**, indépendamment de la certification de la sentinelle.

---

## 6. État de la chaîne d'évitement (par lecture du code, commit 00f91f8)

| Maillon | État | Preuve |
|---|---|---|
| 1. Le drone émet des données obstacle | **PROUVÉ À L'EXÉCUTION** | log 8749 : distances variables cohérentes avec la main |
| 2. La perception est démarrée en vol autonome normal | **ROMPU** | `lecteurPerception.demarrer()` n'est appelé QU'EN L1239 (mode Sentinelle) et L4242 (validation clicker) — jamais dans le vol autonome normal |
| 3. `appliquerEvitement` reçoit une distance | Conditionnel au maillon 2 | appelé en L324 (montée) et L2613 (`calculerSuivi`), mais `distanceHorizontale()` renvoie null si perception non démarrée |
| 4. Unité de comparaison | **CORRECTE (mm vs mm)** | seuils `_MM` comparés à `d` brut ; section 4 |
| 5. Sortie modifiée avant le SDK | **NON PROUVÉ À L'EXÉCUTION** | point d'émission unique `PiloteDrone.emettreSdk` (L334) trace la commande finale, mais rien n'a été capturé en vol (vs_active=false) |
| 6. Évitement NATIF DJI configuré (BRAKE/BYPASS/CLOSE) | **ABSENT** | aucun `setObstacleAvoidanceType` / setter APAS dans tout `app/src/main/java/` — seul un GETTER `info.obstacleAvoidanceType` est lu dans un log |

**Conséquence maillon 2 :** en mode AUTO (`getModeEvitement()==2`), `evitementDoitAgir()` appelle `perceptionDisponible()`, qui est `false` tant qu'aucun listener n'est enregistré (`derniereMajMs=0`). Donc `appliquerEvitement` retourne les vitesses **inchangées**. En vol autonome normal, l'évitement CineFlight n'agit pas, faute de perception démarrée.

**Conséquence maillon 6 :** CineFlight ne configure jamais l'évitement natif DJI. Celui-ci reste à son état par défaut (non déterminé par l'app). Un getter indique l'état ; seul un setter prouverait une configuration — il n'y en a pas.

---

## 7. Conclusion actuelle

Le DJI Mini 4 Pro **transmet effectivement des données d'obstacle** (prouvé au sol), et l'**unité est le millimètre** (doc officielle DJI). Le code d'évitement `appliquerEvitement` utilise la **bonne unité** (mm vs mm). En revanche :

- la **réception pendant Virtual Stick actif** n'est **pas** prouvée (test au sol, `vs_active=false`) ;
- l'**action réelle sur les commandes de vol** n'est **pas** prouvée ;
- la perception **n'est pas démarrée** dans le chemin de vol autonome normal (maillon 2 rompu) ;
- l'évitement **natif DJI n'est jamais configuré** par l'app (maillon 6 absent) ;
- la sentinelle `60000` est une **observation**, pas une règle DJI certifiée.

**Décision maintenue :** aucun changement au mode d'évitement, aucune voix « protection active ». La seule formulation vocale sûre reste : « Évitement d'obstacles non confirmé en contrôle automatique. »

---

## 8. Séquence recommandée pour la suite (aucune action ici)

1. **[FAIT] Rapport écrit** (ce document).
2. **Vérification mm -> mètres** dans le chemin réel de décision (confirmer une ligne de preuve : `raw=... mm / converted=... m / seuil`).
3. **Relecture des trames en Virtual Stick simulé** : obstacle devant à ~0,8 m -> commande avant / commande après / conversion des axes / commande finale SDK. Note : il n'est pas prouvé que le simulateur DJI reproduise fidèlement les trames de perception du Mini 4 Pro ; ne PAS conclure « capteurs KO » à partir d'une absence de trames en simulateur.
4. **Vérification des axes et de la commande finale** (quel axe est modifié, altitude/yaw touchés ou non).
5. **Maillon 2 en mode OBSERVATION SEULEMENT** : démarrer le listener dans le chemin normal derrière un flag distinct, en shadow mode —
   ```
   PERCEPTION_NORMAL_PATH_OBSERVATION = true    // données reçues + loguées
   AVOIDANCE_COMMAND_MODIFICATION     = false   // commande réelle INCHANGÉE
   ```
   La commande « évitée théorique » est calculée et journalisée seulement ; la commande réelle reste celle du système actuel.
6. **Vérification séparée de l'évitement natif DJI** : chercher/observer `getObstacleAvoidanceType()`, et n'introduire un `setObstacleAvoidanceType(...)` que si décidé explicitement — comportement réel en Virtual Stick à classer « non prouvé » tant que non observé.
7. **Décision séparée** sur l'activation de l'évitement CineFlight.
## 9. FusionPerception — incohérence d'unité mm/mètres (mise à jour 2026-07-18)

Audit lecture seule confirmé au commit `00f91f8`, avec vérification concurrente au commit `00f91f8` puis re-vérifié inchangé.

**Fait prouvé.** Dans `FusionPerception.kt`, `distH = lecteur.distanceHorizontale()` (ligne 66) fournit une distance en **millimètres** (unité DJI officielle), mais est comparée à `DIST_DANGER = 3` (ligne 72) et `DIST_DEGAGE = 10` (ligne 79), tous deux déclarés « m » (lignes 39-40). La structure réelle est deux `if` indépendants avec `return` : `if (distH < 3) danger; if (distH >= 10) dégagé; sinon intermédiaire`. Interprétés comme des nombres nus face à des millimètres, cela revient à des seuils de **3 mm** et **10 mm**.

**Effet si exécuté.** Toute distance réelle > 10 mm (20 cm, 80 cm, 2,5 m…) passe la branche `>= 10` → classée **« dégagé »**, avec plancher de confiance à 60 et bonus +25 si le visuel est bas. Ce n'est donc pas seulement une détection inopérante : c'est une **fausse classification « voie libre »** qui pourrait *remonter* artificiellement le score comme si le champ était dégagé, alors qu'un obstacle est proche. La branche danger (`< 3`) ne se déclencherait qu'à moins de 3 mm — jamais en pratique.

**Exposition — DORMANT mais ACTIVABLE.** Preuve par recherche exhaustive (`grep -RIn`, hors `.git`/`build`/`_to_delete`) au commit `00f91f8` :

- Instance unique créée : `MainActivity.kt:163` — `FusionPerception(lecteurPerception, active = false)`.
- Branchée au scoring vivant : `MainActivity.kt:1192` — `fusionPerception.envelopper { … }` passé comme `scoreFn` à la Sentinelle ; `envelopper` (ligne 53) appelle `fusionner` (ligne 61) à chaque tick.
- **Aucune écriture** `fusionPerception.active = true` nulle part. Tous les `active = true` du projet concernent `voix.settings.active` (TTS). La seule mention « active = true » dans `FusionPerception.kt` est un **commentaire** (ligne 18).
- Donc `fusionner()` s'exécute à chaque tick mais **court-circuite ligne 63** (`if (!active || !perceptionDisponible()) return score visuel pur`). Le bloc fautif (lignes 72/79) **n'est jamais atteint** tant que `active` reste `false`.

**Classification retenue : bug d'unité mm/mètres PROUVÉ, actuellement DORMANT (par la seule valeur du drapeau `active`, pas par déconnexion du chemin), ACTIVABLE au premier `active = true` introduit.** Ne pas activer `FusionPerception` tant que l'incohérence d'unité n'est pas corrigée ET revalidée. Correction future à faire dans un commit séparé (soit seuils `DIST_*_MM` en millimètres, soit une conversion `/1000.0` unique — jamais les deux mélangées).

**Incohérence secondaire (même cause).** `ExecuteurMissionWpml.kt:198` compare `distanceHorizontale()` (mm) à `SEUIL_ALERTE_M = 5` (ligne 51, « < 5 m »). Conditionnel à `surveillancePerceptionActive` (false par défaut, jamais mis à true) ; de plus l'effet se limite à une **alerte log/callback**, pas à une commande de vol. Dormant et non-critique, mais à corriger avec la même règle d'unité.

**Cause racine documentée.** Les commentaires « (m) » et logs « …m » de `LecteurPerception.kt` (lignes 115-117, 166, 203, 206) décrivent en *mètres* des valeurs en *millimètres* — c'est ce libellé trompeur qui a induit les deux seuils erronés.

---

## 10. Maillon 2 — observation passive câblée (commit 7383828)

Le point de rupture du maillon 2 (perception jamais démarrée en vol autonome normal — cf. section 6) est désormais **câblé**, en observation passive, sans aucun changement de comportement par défaut.

| Élément | Valeur |
|---|---|
| Commit | `7383828` (branche `feature/voice-phase-2-sdk-observation`) |
| Fichiers | `MainActivity.kt`, `PerceptionDiagLogger.kt` (2 fichiers, 82 insertions) |
| Drapeau | `PERCEPTION_NORMAL_PATH_OBSERVATION_ENABLED = false` (OFF par défaut) |
| Build | Debug `assembleDebug` : SUCCESSFUL |
| Commandes de vol | **inchangées** (aucun `vx/vy/vz/yaw`, `appliquerEvitement`, `emettreSdk` touché) |
| FusionPerception | **inchangée** (`active` reste `false`) |

**Ce que fait le lot quand le flag passera à `true` (test volontaire uniquement).** Sur connexion drone (`onConnexionProduit`→`connecte==true`, et cas déjà-connecté `produitConnecte`), il appelle `lecteurPerception.demarrer()` (idempotent) et lance un moniteur `lifecycleScope` qui sonde toutes les 500 ms et journalise UNIQUEMENT les transitions d'état : `LISTENER_START_REQUESTED`, `LISTENER_STARTED`, `FIRST_FRAME`, `STALE`, `RESTORED`, `LISTENER_STOPPED`. Sur déconnexion et `onDestroy` : annulation du job + `arreter()`. Option A retenue : `LecteurPerception` **intact** (transitions calculées côté MainActivity).

**Garanties de conception (prouvées par le diff).** Tous les appels à `demarrer()`/`arreter()` sont sur le thread principal (callbacks `runOnUiThread` + coroutine `lifecycleScope` sans dispatcher IO) → la garde `if (actif) return` du lecteur suffit, aucune synchronisation ajoutée. Garde anti double-moniteur : `if (perceptionObsJob?.isActive == true) return`. Aucun `onPause`/`onStop` ajouté (le lecteur n'est pas coupé sur perte de premier plan pendant un vol). `LISTENER_STARTED` signifie « `demarrer()` appelé », **pas** « trame reçue » ; seul `FIRST_FRAME` prouve une trame réelle.

**Limite documentée.** `FIRST_FRAME` est détecté par sondage périodique (précision ~500 ms) ; ce n'est pas l'horodatage exact du callback DJI. Sans effet sur la conclusion recherchée.

**Question ouverte que le futur test au sol / Virtual Stick doit trancher.** En vol normal avec Virtual Stick actif, le Mini 4 Pro continue-t-il à transmettre des trames d'obstacle fraîches à CineFlight ? Critères de succès : `LISTENER_STARTED → FIRST_FRAME`, `model=Mini 4 Pro`, `vs_active=false` puis `true` avec perception toujours `FRESH`, aucun listener en double, `logs_perdus=0`, `fusionPerception.active=false`, aucune commande modifiée. **Aucune correction appliquée au drone tant que cette continuité n'est pas prouvée — et même alors, décision séparée.**
## 11. Test au sol du chemin normal — évitement lu à CLOSE (2026-07-18)

Test réalisé au sol, drone Mini 4 Pro connecté via la RC, hélices retirées, moteurs arrêtés, en promenant le drone devant des obstacles et un visage de près. Observation passive (maillon 2), flag `PERCEPTION_NORMAL_PATH_OBSERVATION_ENABLED = true` temporairement, code non commité.

**Fait observé — lecture par le getter direct.** Interrogation de `getObstacleAvoidanceType()` (API distincte du listener d'info). Précision sur l'issue DJI Mobile-SDK-Android-V5 #618 : elle documente un défaut du `PerceptionInformationListener` (lors d'un changement de mode, il peut ne pas ré-émettre de callback, et l'objet `PerceptionInfo` déjà fourni peut être muté silencieusement). Elle **ne démontre pas** que le getter direct est plus fiable, ni qu'il est instable. On lit donc les deux et on les journalise côte à côte pour comparaison, sans préjuger de la source correcte :

```
11:53:19.683  PERCEPTION_TRANSITION evt=OA_TYPE_QUERY_REQUESTED
11:53:19.683  PERCEPTION_TRANSITION evt=OA_TYPE_RESULT type=CLOSE
```

**PROUVÉ À L'EXÉCUTION : l'évitement d'obstacle du drone est réglé sur `CLOSE`** (freinage et contournement automatiques désactivés).

Autres faits observés (process 27002 et précédents, même session) :

- Le chemin de connexion normal démarre bien `PerceptionManager` : `LISTENER_STARTED`, `model=DJI_MINI_4_PRO` reconnu, `listener_active=true`. **Maillon 2 opérationnel en observation.**
- Callbacks `ObstacleData` reçus, mais trames valides **très rares** (une toutes les 1-3 min) et espacées de listes vides (`raw_list=[]`).
- Toutes les trames valides observées valent `60000` sur les ~360 secteurs (= « aucune détection à portée »). **Aucun obstacle proche n'a produit de distance utile**, même en promenant le drone devant des obstacles.
- Délai d'initialisation important : ~40-45 s après connexion avant la première trame valide.
- **Aucune commande de vol modifiée** ; `FusionPerception` et `appliquerEvitement` restés inactifs ; aucun setter appelé (lecture seule stricte : listeners + getter uniquement).

**Ce qui est prouvé / ce qui ne l'est pas.**

| Élément | Statut |
|---|---|
| Listener démarré dans le chemin normal | Prouvé |
| Callbacks `ObstacleData` reçus | Prouvé |
| Modèle Mini 4 Pro identifié | Prouvé |
| Type d'évitement = `CLOSE` (getter direct) | **Prouvé à l'exécution** |
| `CLOSE` est la *cause* de l'absence de mesures | Fortement corrélé, **pas encore prouvé** (test A/B requis) |
| Distances utiles devant obstacles | Non observées en `CLOSE` |
| Évitement natif DJI actif pendant Virtual Stick | Non prouvé |
| Évitement CineFlight appliqué aux commandes | Non — volontairement inactif |

**`60000`** reste une valeur expérimentale correspondant apparemment à « aucune mesure proche » ; DJI documente l'unité en millimètres mais ne certifie pas `60000` comme sentinelle officielle.

**Prochaine étape décidée (Voie 1, manuelle) — sans setter dans CineFlight.** Réactiver l'évitement à `BRAKE` via l'interface DJI habituelle, puis refaire le test au sol à l'identique (hélices retirées, mêmes obstacles/distances, getter vérifiant `type=BRAKE`) et comparer :

```
CLOSE → listes vides / 60000
BRAKE → distances proches réelles  OU  comportement inchangé
```

Si des distances réelles apparaissent en `BRAKE` : preuve expérimentale forte que le mode d'évitement conditionne la disponibilité des mesures sur ce Mini 4 Pro. Si les données restent vides/60000 : hypothèse réfutée, chercher une autre cause (limitation SDK, état au sol, firmware). Ce test ne prouvera PAS le freinage natif pendant Virtual Stick — validation séparée.

**Première fonction utile envisagée dans l'app (pas encore codée) :** lire et afficher le mode d'évitement courant, et avertir le pilote si `CLOSE` (« Évitement DJI désactivé. Données d'obstacle possiblement indisponibles. »). Aucun setter automatique pour l'instant.

---

## 12. Procédure A/B instrumentée — CODÉE, non encore validée (2026-07-18)

> **Statut de cette section — à lire avant tout.** L'instrumentation décrite ci-dessous est **codée** dans l'arbre de travail, mais elle est :
> - **non encore validée par `assembleDebug`** (voir « Vérification build/tests » plus bas — le build n'a pas pu être exécuté dans l'environnement de rédaction) ;
> - **non encore exécutée sur le drone** (aucune donnée matérielle collectée avec cette version) ;
> - **sans effet** sur `appliquerEvitement`, `FusionPerception` ni sur les commandes Virtual Stick (aucune de ces trois n'est touchée).
>
> Cette section **documente le protocole** qui permettra de vérifier l'hypothèse « BRAKE conditionne la disponibilité des mesures ». Elle **ne conclut pas** que BRAKE améliore les données. La conclusion viendra uniquement après exécution matérielle.

### 12.1 Correction de méthode par rapport au test précédent

Le test de la section 11 a montré que le setter est accepté et que la mesure ne réapparaît pas immédiatement, mais il ne permet **pas** de conclure sur le lien entre `BRAKE` et les distances. Deux corrections de méthode sont intégrées :

1. **Phases explicites plutôt que « restauration d'un état initial »** : l'état initial est ambigu, on ne le rejoue donc jamais en silence.
2. **Comptage par callback DJI réel, pas par tick de moniteur** : le comptage des trames est déplacé du moniteur périodique (500 ms, qui recomptait la dernière trame mémorisée) vers un hook `onTrameComptee` invoqué exactement une fois par callback `ObstacleData`.

### 12.2 Déroulé instrumenté (au sol, hélices retirées)

- **Deux boutons de phase** : `Phase CLOSE` et `Phase BRAKE`, chacun commandant **explicitement** `setObstacleAvoidanceType(...)` (journal `OA_SET_REQUESTED` → `OA_SET_OK` / `OA_SET_ERROR`).
- **Durée identique** : constante unique `OA_TEST_PHASE_DURATION_MS = 180_000L` (180 s), partagée par CLOSE et BRAKE.
- **Trois lectures du getter par phase** : `OA_READBACK_1/2/3` à 2 s, 4 s, 6 s après le set confirmé.
- **Même scénario d'obstacle** attendu du pilote pour les deux phases (présenter/déplacer un objet devant les capteurs pendant la fenêtre).
- **Clôture manuelle explicite** : un bouton dédié `Terminer le test → CLOSE` envoie `CLOSE`, journalise `OA_TEST_CLOSED`, et marque la session close. Le pilote choisit ensuite consciemment le mode à conserver (il peut vouloir garder `BRAKE` pour un usage normal).
- **Aucune restauration silencieuse dans `onDestroy`** : à la fermeture, on ne remet **pas** le drone en `CLOSE` (cela désactiverait son freinage natif sans que le pilote en soit conscient). Si le test a modifié l'évitement sans clôture explicite, `onDestroy` se contente de **journaliser** `OA_TEST_QUIT_SANS_CLOTURE` avec l'état lu.

### 12.3 Nouveaux champs de fraîcheur et d'intervalle

Par trame (`PERCEPTION_FRAME`), mesures **réelles** (plus de `age_ms=0` artificiel) :

```
callback_interval_ms        intervalle réel depuis le callback DJI précédent (-1 si premier)
last_valid_frame_age_ms     âge de la dernière trame à liste non vide (-1 si aucune vue)
last_valid_frame_timestamp  horodatage de cette dernière trame exploitable (-1 si aucune)
```

Par phase (`TEST_BILAN`), compteurs incrémentés **une fois par callback DJI** :

```
max_callback_gap_ms      plus grand intervalle EXACT entre deux callbacks (mesuré dans le hook)
max_valid_frame_age_ms   plus grand âge de la dernière trame exploitable — ÉCHANTILLONNÉ à 500 ms (borne, pas exact)
valid_frame_count        trames à liste non vide  (= only_60000_count + distance_frame_count)
empty_frame_count        trames à liste vide/null
only_60000_count         trames non vides dont tous les secteurs valent 60000
distance_frame_count     trames non vides avec au moins un secteur < 60000
minimum_distance_mm      plus petite distance observée sur la phase
```

Invariants vérifiés par lecture du code (à re-confirmer à l'exécution) : comptage une seule fois par callback DJI ; le moniteur 500 ms ne recompte plus la trame mémorisée ; `valid_frame_count = only_60000_count + distance_frame_count` ; `callback_interval_ms` calculé entre callbacks réels ; `max_callback_gap_ms` exact / `max_valid_frame_age_ms` échantillonné 500 ms ; compteurs remis à zéro à la **confirmation** du set (séparation stricte CLOSE/BRAKE, les trames reçues pendant la latence du setter ne sont pas comptées).

### 12.4 Comment conclure (après exécution matérielle uniquement)

- **Si BRAKE produit régulièrement des `distance_frame_count` > 0 avec des distances proches, et pas CLOSE** : preuve **expérimentale locale** (ce Mini 4 Pro, ce firmware, cette version MSDK) que l'activation explicite de `BRAKE` est associée à la réception de mesures utiles au sol. Pas une règle universelle DJI.
- **Si CLOSE et BRAKE donnent les mêmes résultats** : l'hypothèse « BRAKE active le flux » n'est pas confirmée ; envisager limitation du Mini 4 Pro au sol, capteurs actifs seulement en certaines conditions de vol, limite du MSDK, secteurs mal interprétés, cadence de callback irrégulière.
- **Si le getter continue d'osciller** (ex. CLOSE puis BRAKE d'une lecture à l'autre) : classer la lecture du mode comme **instable dans cette configuration**, sans attribuer ce comportement à l'issue #618 (qui concerne le listener, pas le getter).

### 12.5 Vérification build / tests (obligatoire avant toute exécution)

Les commandes suivantes **doivent** être lancées sur la machine de développement (Windows, SDK Android + wrapper Gradle disponibles) avant d'exécuter le test matériel :

```
git diff --check                         # espaces / marqueurs de conflit
git --no-pager diff --stat -- <3 fichiers>   # revue ciblée du diff
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

État au moment de la rédaction de cette section :

| Vérification | Résultat |
|---|---|
| `git diff --check` (fichiers de code) | OK — aucune erreur d'espaces (seuls des avertissements CRLF, sans rapport) |
| Diff ciblé des 3 fichiers | Généré et relu (MainActivity, LecteurPerception, PerceptionDiagLogger) |
| `assembleDebug` (1re tentative, machine de dev) | **ÉCHEC** — `compileDebugKotlin` : `MainActivity.kt:2624` et `:2632` « Unresolved reference 'ui' » (Handler inexistant sous ce nom). Avertissements CMake `[CXX5304]` (version XML SDK) non bloquants. |
| Correction appliquée | Handler dédié `handlerTestEvitement = Handler(Looper.getMainLooper())`, deux `ui.postDelayed` remplacés, `removeCallbacksAndMessages(null)` ajouté dans `onDestroy`. |
| `assembleDebug` (après correction) | **SUCCESSFUL** sur la machine de dev. |
| `testDebugUnitTest` | Non détaillé ici — à confirmer si des tests unitaires couvrent ce chemin. |

**État Git au moment de cette validation (rien n'est committé).**

| Élément | Valeur |
|---|---|
| HEAD | `afae8a09b096250a537a80efbccdde19d8e9bc37` (`afae8a0`) |
| Message HEAD | « Rapport evitement (sections 9-10) + garde d'activation FusionPerception » |
| Branche | `feature/voice-phase-2-sdk-observation` |
| Fichiers modifiés **non commités** | `MainActivity.kt`, `LecteurPerception.kt`, `PerceptionDiagLogger.kt`, `docs/RAPPORT_EVITEMENT_TEST_SOL_20260718.md` |

**Statut : instrumentation compilée (`assembleDebug` SUCCESSFUL), test A/B matériel restant à exécuter.** Les modifications ne sont **pas** commitées : elles attendent la revue finale du diff. Ce rapport ne conclut rien sur `BRAKE` — aucune donnée drone n'a encore été collectée avec cette version.

---

## 13. Premier run A/B matériel — INVALIDE, conservé (défaut d'instrumentation révélé, 2026-07-18)

Un premier run a été exécuté au sol (process 5356, Mini 4 Pro connecté via RC). Il est **conservé comme test invalidé** parce qu'il a révélé un défaut réel de l'instrumentation, qui a été corrigé. Trois constats distincts sont à inscrire.

### 13.1 Constat 1 — Résultat A/B INVALIDE (compteurs partagés écrasés)

La phase `BRAKE` a été lancée **avant** la publication du bilan de la phase `CLOSE`. Comme les compteurs sont un jeu **unique et partagé**, le démarrage de `BRAKE` (dans `onFait`) les a remis à zéro. Le bilan `CLOSE`, différé de 180 s, a donc été publié **après** ce reset :

```
13:06:36  OA_SET_OK type=CLOSE     (phase CLOSE armée)
13:08:04  OA_SET_OK type=BRAKE     (reset des compteurs par BRAKE, AVANT le bilan CLOSE)
13:09:36  TEST_BILAN phase=CLOSE  duree_ms=180000 ... valid_frame_count=0 ... total=0
```

**`total=0` sur le bilan CLOSE ne permet AUCUNE conclusion sur CLOSE.** Le chiffre ne reflète pas ce que CLOSE a produit ; il reflète l'état remis à zéro par BRAKE. Le run A/B est donc **invalide** — pas « négatif ». À rejeter, pas à interpréter.

### 13.2 Constat 2 — Callbacks effectivement très espacés (observation valable, non généralisable)

Les logs bruts montrent des callbacks `ObstacleData` réellement rares après l'initialisation :

```
VS-1  callback_interval_ms=-1     raw_list=[]   model=Drone connecte
VS-2  callback_interval_ms=255    raw_list=[]
VS-3  callback_interval_ms=82605  raw_list=[]   model=UNRECOGNIZED
```

Le trou d'environ **82 secondes** entre VS-2 et VS-3 est une **observation valable** issue des logs bruts (pas une estimation échantillonnée). Aucune trame à liste non vide n'a été reçue. **Mais** ceci ne prouve pas encore que le drone est *systématiquement* « muet au sol » : un seul run, phases invalidées, `model` passant à `UNRECOGNIZED` (état de liaison instable possible). À reconfirmer sur un run propre avant toute généralisation.

### 13.3 Constat 3 — Correctif appliqué, PAS encore validé

Corrections apportées à `MainActivity.kt` après ce run (compilées, **non encore testées** à l'exécution) :

- **Verrou `phaseEnCours`** : une seule phase à la fois. Armé au lancement (avant la confirmation du set), tout second appui pendant une phase active est **refusé** → log `OA_PHASE_REFUSEE` + Toast, sans toucher aux compteurs.
- **Annulation explicite à la clôture** : `Terminer le test → CLOSE` fait `removeCallbacksAndMessages(null)` (supprime readbacks/bilan en attente), remet `phaseTestCourante=null` et libère le verrou.
- **Diagnostic `AUCUN_CALLBACK`** : si `total=0` au bilan, on journalise `NOTE=AUCUN_CALLBACK_RECU_PENDANT_LA_PHASE` et le Toast indique « test non concluant pour cette phase » — pour ne jamais confondre « aucune trame reçue » avec « ce mode ne donne rien ».

**Justification de conception (verrou plutôt que capture par phase).** Le verrou ne « capture » pas les compteurs par phase ; il rend le **jeu partagé acceptable** en garantissant qu'une seule phase tourne à la fois. C'est suffisant **à condition** que : (a) aucun ancien `postDelayed` ne survive à une annulation ; (b) les compteurs ne soient remis à zéro qu'au **début** de la nouvelle phase ; (c) `phaseEnCours` soit libéré après bilan, erreur **ou** clôture ; (d) un double-appui rapide ne puisse pas lancer deux setters. Ces quatre conditions sont vérifiées par lecture du code ; elles restent **à confirmer à l'exécution** (test rapide du verrou : lancer CLOSE puis appuyer immédiatement sur BRAKE → attendu `OA_PHASE_REFUSEE`, compteurs intacts).

### 13.4 Procédure du prochain run (valide)

1. `git diff --check`, `.\gradlew.bat assembleDebug` (**SUCCESSFUL**), `.\gradlew.bat testDebugUnitTest`.

   **Note tests unitaires (2026-07-18).** `testDebugUnitTest` échoue à la compilation, mais **sur des tests préexistants sans rapport avec la perception** : `CapacitesDroneTest` (valeurs d'enum `Evitement`/`ModeVision` disparues) et la famille `PiloteDrone*Test` (constructeur `PiloteDrone(pont: PontDji, …)` modifié le 2026-07-18 par `e72adcd`, alors que les tests datent du 2026-07-14 et passent encore un `FauxPont` au lieu d'un `Context`). Ces tests dérivent du code de prod `control/` indépendamment de ce travail. Aucun fichier de test n'a été modifié ici ; le seul test lié à la perception (`FusionPerceptionUniteGardeTest`) n'est pas touché. `assembleDebug` (code de prod, instrumentation incluse) reste **vert**. **La resynchronisation des tests `control/` est un chantier séparé, à traiter hors de ce lot.**
2. **Test rapide du verrou** (sans attendre 3 min) : CLOSE puis appui immédiat sur BRAKE → le 2ᵉ doit être refusé (`OA_PHASE_REFUSEE`), aucun reset de compteurs.
3. **Test A/B séquentiel strict** : `CLOSE → attendre obligatoirement TEST_BILAN phase=CLOSE → BRAKE → attendre obligatoirement TEST_BILAN phase=BRAKE → Terminer le test → CLOSE`. Même approche d'obstacle, comparable, pendant les deux phases ; même durée ; compteurs séparés dans le temps.
4. Aucun vol réel à ce stade.

**Ce run reste dans le rapport comme jalon négatif utile : il a prouvé que l'instrumentation, et non le drone, était en cause dans le `total=0` de CLOSE.**

---

*Rapport généré à partir d'une lecture directe du code (commits 00f91f8 et 7383828) et du log de test process 8749. Sections 9-10 ajoutées le 2026-07-18. Section 11 (test CLOSE) et section 12 (procédure A/B instrumentée, non encore validée par build ni exécutée sur le drone) ajoutées le 2026-07-18. Les affirmations « prouvé à l'exécution » reposent sur des données observées ; les affirmations « par lecture » reposent sur le code source cité avec ses numéros de ligne. L'instrumentation de la section 12 n'a encore produit aucune donnée matérielle.*
