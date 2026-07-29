# CLAUDE.md — Règles de travail (Christian / CineFlight)

## RÈGLES ABSOLUES DE COMMUNICATION

1. **JAMAIS de carte de questions à choix multiples (AskUserQuestion).** JAMAIS. Sous aucun prétexte.
   Si une clarification est nécessaire, poser la question directement en texte, brièvement.
2. Répondre en **français**.
3. Être **concis et direct**. Enlever les mots inutiles. Pas de verbiage.
4. Toujours regarder le fichier de programmation au complet pour éviter les duplications.

## Projet

- App Android Kotlin « CineFlight » — contrôle de drone DJI (package `ca.cineflight.stage`).
- Branche : `feature/voice-phase-2-sdk-observation`.
- minSdk 24, targetSdk 33, compileSdk 34, JVM 17, Kotlin 2.1.0.
- Build/test/adb : sur le PC Windows de Christian (`.\gradlew.bat assembleDebug`, `testDebugUnitTest`).
  Le sandbox NE PEUT PAS compiler (pas de SDK Android / deps DJI).

## Live streaming (état)

- Chaîne : bouton LIVE → `LiveStreamActivity` → `YouTubeLiveService` → `DjiLiveStreamEngine`
  → SDK DJI (`liveStreamManager`) → YouTube/Régie.
- **IMPORTANT terrain : utiliser `rtmp://` (port 1935), PAS `rtmps://`.** Le drone DJI échoue
  au handshake TLS avec rtmps (fps/débit restent à 0).
- Clé de diffusion chiffrée via Android Keystore AES-256-GCM (`YouTubeSecretCrypto`).
- Sync auto de la clé depuis le compte web `cineflight.ca` (`CineAuth.recupererCleStream`).
- Sécurité : ne JAMAIS journaliser la clé, l'URL RTMP complète, le ciphertext, le presse-papiers.
  Log acceptable : serveur seul + `cle presente=true`.
- Gate obstacle : `OBSTACLE_GATE_MIROIR_ACTIF` doit rester `false` en commit ; `true` seulement
  le temps d'un test, puis revert.

## Live YouTube automatique — OAuth (FONCTIONNE, testé bout-en-bout 2026-07-21)

Chaîne complète qui marche : drone DJI → RTMP → serveur cineflight.ca crée le broadcast
YouTube via OAuth → clé + « go-live » automatiques → page publique `/live/{user}`.
Plus aucun copier-coller de clé, plus aucun clic dans YouTube Studio.

### Côté serveur (Ubuntu DigitalOcean, `root@`, service `cineflight.service` port 8095)
- Dossier prod : `/root/cineflight_web/` — lancé par `uvicorn app:app` (Python SYSTÈME, pas de venv).
  Debian 12 → `pip install --break-system-packages ...` pour installer des libs.
- `stream_key.py` : router `/api/stream_key` (GET/POST) + page publique `/live/{utilisateur}`
  (isolée par compte via `username`). Stockage `stream_keys.json`.
- `youtube_oauth.py` (NOUVEAU, PAS dans git — copie de secours à faire) : router OAuth.
  - `GET  /api/youtube/oauth/start`  → renvoie `auth_url` Google (JWT requis)
  - `GET  /api/youtube/oauth/callback` → échange code→tokens, stocke dans `youtube_tokens.json`
  - `GET  /api/youtube/status` / `POST /api/youtube/disconnect`
  - `POST /api/youtube/go_live` → crée broadcast+stream, les lie, écrit stream_key+watch_url
    dans stream_keys.json (via `_sk._ecrire`). `POST /api/youtube/stop_live`.
- Config : `/root/cineflight_web/youtube_oauth_config.json` (chmod 600) = client_id,
  client_secret, redirect_uri. Tokens par uid dans `youtube_tokens.json` (chmod 600).
  Les deux sont dans `.gitignore`.
- Branché dans `app.py` juste après `app.include_router(stream_key_router)`.
- Libs : google-auth, google-auth-oauthlib, google-api-python-client, requests.

### 3 corrections critiques qui l'ont fait marcher (NE PAS OUBLIER)
1. **PKCE désactivé** : `Flow.from_client_config(..., autogenerate_code_verifier=False)` aux
   2 créations de Flow. Sinon `InvalidGrantError` à l'échange (verifier non retrouvé au callback).
2. **`enableAutoStart: True`** dans le broadcast → YouTube passe le direct en public tout seul
   dès que le drone envoie (plus besoin de « Go Live » manuel).
3. **`enableAutoStop: False`** → sinon YouTube coupe le broadcast sur un simple hoquet réseau
   (le direct s'arrêtait après ~1:20).

### Côté Google Cloud (projet `subtle-lambda-442102` / `-m9`)
- API YouTube Data v3 : ACTIVÉE.
- Client OAuth Web : ID `1051543423619-8ekdvhubnla8448ge43v79lnts6fgf3o...`,
  redirect `https://cineflight.ca/api/youtube/oauth/callback`.
- Scope demandé dans le code : `https://www.googleapis.com/auth/youtube` (sensible).

### LIMITE actuelle : app OAuth en mode TEST
- Seuls les comptes ajoutés dans Audience → Utilisateurs test peuvent connecter YouTube
  (sinon `access_denied 403`). Max 100 testeurs. Tokens expirent après 7 jours.
- Pour ouvrir à TOUS les clients en libre-service → passer en PRODUCTION + validation Google
  (scope youtube = sensible : page confidentialité, vidéo démo, parfois audit). Plusieurs semaines.

### Token web côté navigateur
- JWT stocké dans `localStorage['cineflight_token']` (à envoyer en `Authorization: Bearer`).

### Pages légales (EN LIGNE — prérequis validation Google, publiées 2026-07-21)
- Servies par `pages_legales.py` (router FastAPI, HTML intégré, branché dans app.py).
- `https://cineflight.ca/confidentialite.html` — Politique de confidentialité.
- `https://cineflight.ca/conditions.html` — Conditions d'utilisation.
- Contact indiqué : `contact@cineflight.ca` (à changer si besoin).

### Étapes RESTANTES pour passer OAuth en Production (validation Google)
1. Pages confidentialité + CGU en ligne. → FAIT.
2. Vérifier le domaine `cineflight.ca` dans Google Search Console.
3. Écran de consentement OAuth : nom d'app, logo, les 2 URL légales, domaines autorisés, scope youtube.
4. Vidéo de démo du parcours OAuth (YouTube).
5. Soumettre à Google → validation (scope youtube = sensible) : plusieurs semaines.
En attendant : servir les clients pilotes comme utilisateurs test (Audience, max 100).

## ESSAI E-03 — instrumentation COMPLÈTE (2026-07-22, compile + 48 tests verts)

Essai de banc PRÉALABLE (hélices retirées) exigé AVANT tout vol automatisé.
Réf. dossier v59 : §378 (critères), §381 (config figée), tableau 36 (16 scénarios).

### Constat de départ
`e03Declencher` ne remplissait que T0 et forçait `derniereVitesse=0f` → tous les scénarios
auraient rendu `verdict=FAIL` avec une persistance de 0 sans signification. L'essai
n'aurait produit AUCUNE donnée recevable.

### Ce qui a été ajouté
- `EssaiE03Capteur.kt` (PUR, testable JVM) — capture T0..T6 + persistance, thread-safe
  (AtomicLong ; les points de mesure vivent sur des fils différents).
- `EssaiE03Config.kt` (PUR) — configuration figée §381 en en-tête de journal +
  contrôle de conformité d'environnement (colonne « Env. » du tableau 36).
- `PontDjiReel` : hook `obsVitesseVerticale` via `KeyAircraftVelocity` (NED : vV = -v.z,
  même pattern que PontCockpitImpl) + `obsVsDesactivationConfirmee` (T5 = acquittement SDK
  de la SORTIE Virtual Stick, qui n'existait pas).
- `Phase3Activity` : `TEST_E03_SIMULATEUR` SÉPARÉ de `TEST_E03_CESSATION_VS` ; marquage
  T1..T4 dans `mettreEnSecuriteDepuisWatchdogIndep` ET `arretUrgence` ; T0 + persistance
  de commande au point d'émission unique ; fenêtre d'observation 3 s avant synthèse.
- `docs/FICHE_ESSAI_E03.md` — procédure complète (sécurité, 16 scénarios, lecture du
  journal, critères, relevé, limites).

### DEUX DÉFAUTS TROUVÉS ET CORRIGÉS (ne pas réintroduire)
1. **Faux PASS** — télémétrie constamment à 0 alors qu'un throttle est commandé →
   `persist_ms=0` → PASS trompeur. `incoherenceMesure()` rend `MESURE_SUSPECTE`
   (aéronef sans réaction OU mesure défaillante) → répétition NON comptabilisable.
2. **FAIL artificiel** — la boucle pilote rafraîchit T0 à 10 Hz ; après le stimulus elle
   continuait → T0 plus récent que la dernière commande → persistance NÉGATIVE (−14 ms
   mesurés) → FAIL sur tout. D'où `demarrerObservation()` qui FIGE T0 au stimulus et
   CONSERVE les vitesses d'avant (donnée dont on mesure justement la persistance).
   `reinitialiser()` déverrouille pour la répétition suivante.

### E03-03 rendu réellement testant
Le code se contentait de journaliser : les battements continuaient, le watchdog n'aurait
JAMAIS déclenché. Ajout de `e03GelBattements` — la boucle cesse d'alimenter le watchdog
indépendant (les 2 points de battement), levé en fin de fenêtre d'observation.

### Pièges d'environnement évités
- `Build.getSerial()` RETIRÉ (API 26 vs minSdk 24 → lint bloquant ; et refusé aux apps
  non privilégiées). SN téléphone = saisie manuelle, signalé manquant dans l'en-tête.
- Version MSDK lue par RÉFLEXION (symbole incertain), repli sur `5.18.0 (build.gradle)`.

### Preuves (exécutées sur le PC de Christian, 2026-07-22)
`EssaiE03CapteurTest` 24 cas · `EssaiE03ConfigTest` 10 cas · `EssaiE03LogTest` 14 cas
→ **48 cas, 0 échec, 0 erreur**. `assembleDebug` OK.
Correctif au passage : `DjiLiveStreamEngineTest.FauxManager` n'implémentait pas
`diffuseDeja()` (ajouté à l'interface) — bloquait TOUTE compilation des tests.

### Config figée : 5 champs sur 7 AUTOMATISÉS (2026-07-22, compile OK)
- `SondeIdentiteDji.kt` (NOUVEAU, fichier isolé façon « sonde passe 2A ») — lit au SDK :
  `FlightControllerKey.KeySerialNumber` / `KeyFirmwareVersion` et
  `RemoteControllerKey.KeySerialNumber` / `KeyFirmwareVersion`.
  **Les 4 clés existent et compilent en MSDK 5.18.0** (vérifié par assembleDebug).
- `apkHash` : SHA-256 du fichier APK (`sourceDir`), calculé une fois, mis en cache,
  **hors fil UI** (lecture disque ~1 s → risque d'ANR sinon).
- RESTENT MANUELS (aucun capteur possible) : `cableType`, `portUtilise`, et
  `telNumeroSerie` (Build.getSerial refusé aux apps non privilégiées).
  → le journal porte donc toujours `complete=NON` : c'est VOULU, rien n'est masqué.
- ATTENTE ACTIVE : `getValue()` lit le CACHE du SDK ; au démarrage les SN ne sont pas
  encore poussés. Boucle de 8×1 s sur `SondeIdentiteDji.Identite.complete()` —
  surtout PAS sur `EssaiE03Config.complete()` (inclut les champs manuels toujours vides
  → attendrait le délai max à chaque démarrage). Ligne `E03 CONFIG_LECTURE` = diagnostic.
- CONDITION TERRAIN : ouvrir Phase 3 APRÈS avoir connecté drone + RC + câble.

### Journal autoportant (correctif 2026-07-22)
`WDG_INDEP` (preuve de détection E03-03, avec l'âge) et la trace d'arrêt d'urgence
partaient UNIQUEMENT dans `test_secu_e0x.log`, conditionné par `TEST_E06_09_SECU` —
un drapeau SANS rapport avec E-03. Preuve principale du scénario 03 perdue.
→ les deux lignes sont maintenant DUPLIQUÉES dans `essai_e03.log`.
Chemin : `/sdcard/Android/data/ca.cineflight.solo/files/essai_e03.log`
(applicationId = `ca.cineflight.solo`, différent du package `ca.cineflight.stage`).

### Pourquoi aucune mesure ne sortait — CAUSE RÉELLE (2026-07-22)

`activerVirtualStick(true)` n'est appelé QUE dans `decoller()` (bouton DÉCOLLER de l'app).
Christian a décollé **aux manches de la RC** → `decoller()` jamais exécuté → `enVol=false`
→ VS jamais demandé (aucun `enableVirtualStick DEMANDE...` au logcat). Les
`REQUEST_HANDLER_NOT_FOUND` sur `VirtualStickEnabled` étaient des échecs de **dés**activation
d'un VS jamais activé : symptôme, pas cause. (Le Mini 4 Pro supporte bien le VS — piste
Mini 4 Pro écartée à tort par Claude, corrigée.)

### MODE BANC `TEST_E03_BANC` (2026-07-22) — dérogation assumée

L'arbitre exige `inFlightCompatible=true` ; au banc hélices retirées on n'est jamais en vol
→ blocage total (`raison="soccer bloque : virtual_stick_indisponible"`, bit VS/IF à 0).
Le mode banc, **armé manuellement par un bouton rouge** dans le panneau E-03 :
- active le simulateur (réessais) PUIS demande le VS — ordre imposé : le SDK n'accorde le
  VS que simulateur actif (`accorde=OUI` avec, `accorde=NON` sans) ;
- force **3 bits** (audit 2026-07-22 — 1 seul ne suffisait pas) :
  **IF** (au sol par définition de l'essai), **PF** `dronePositionFresh` (= RTK de la
  VOITURE lu sur le réseau ; au banc `autoRtk="—"` donc 0 pour toujours), **ON**
  `operatorNearRail` (fail-closed sans profil/GPS). Ce sont des préconditions de SITE,
  couvertes ailleurs, sans rapport avec la cessation VS.
  Restent juges : **PO, EM, VS, AC, BO, RV, CC, OG**.
  La ligne DEBUT consigne `derogation_bits=IF,PF,ON` + la valeur RÉELLE de chacun.
- verrous : refusé si `enVol`, désarmement AUTO si l'aéronef décolle (boucle pilote),
  jamais armé à l'ouverture de l'écran, inopérant si `TEST_E03_BANC=false` ;
- traçabilité : `banc=OUI` + `en_vol_reel=` sur DEBUT et FIN, ligne `E03 BANC arme=OUI`,
  et surtout `E03 VS_ACTIVATION accorde=OUI|NON` = réponse RÉELLE du SDK (si NON, les
  commandes partent mais l'aéronef les ignore — la ligne le dit).
- **PORTÉE HONNÊTE** : moteurs à l'arrêt → T6 et la persistance PHYSIQUE ne sont PAS
  mesurables au banc. Mesurables : T0→T5 + persistance DE COMMANDE. D'où
  `incoherenceMesure(banc=true)` qui rend `MESURE_BANC` (limite, répétition RECEVABLE)
  au lieu de `MESURE_SUSPECTE` (défaut, à rejouer). Le banc ne masque PAS
  `MESURE_INVALIDE` (t0 absent) ni `MESURE_SANS_OBJET`.
- Scénario refusé si banc non armé (`cause=banc_non_arme`), sauf E03-02 (zéro maintenu).
- ⚠ 4e drapeau à REMETTRE À `false` après la campagne.

### Perte de RC — hypothèse RÉFUTÉE par la mesure (2026-07-22)

`RC_CONNEXION connecte=false ... estConnecte=true` m'avait fait conclure à un faux négatif
de `RemoteControllerKey.KeyConnection`, d'où une corroboration différée de 400 ms.
**RÉFUTÉ au relevé suivant** : `RC_PERTE_VERIFICATION ... fc_aussi_perdu=true` — la liaison
du contrôleur de vol tombe bien, 400 ms APRÈS. La clé RC ne ment pas, elle DEVANCE
(premier maillon à lâcher). La corroboration retardait donc une VRAIE détection de 400 ms
sur un budget de 500 ms (§378) : mauvais échange pour une sécurité.
→ RETOUR à l'action immédiate. La vérification différée est conservée en OBSERVATION
PASSIVE (`E03 RC_PERTE_OBSERVATION`, note=observation_seule) : elle accumule la preuve
sans peser sur la décision. Comportement E-09 INCHANGÉ par rapport à la v58.
LEÇON : ne pas conclure d'un seul relevé sur une fonction de sécurité.

### ⚠ RÉFUTÉ (2026-07-22) — « le VS exige le simulateur actif » est FAUX

Relevé `apk_hash=fed2d326…`, `simulateur=INACTIF env=CHAINE_REELLE` :
`E03 VS_ACTIVATION accorde=OUI par=SDK_DJI`. **Le SDK accorde le Virtual Stick au sol
SANS simulateur**, drone allumé et RC connectée.

L'ancienne conclusion (« accorde=OUI avec simulateur, accorde=NON sans ») venait de deux
relevés isolés ; le `accorde=NON` observé s'expliquait par une liaison RC dégradée au même
instant (`estConnecte=false` répété), pas par l'absence de simulateur.

CONSÉQUENCE MAJEURE : les 12 scénarios « RÉEL » (simulateur OFF) **sont mesurables au banc**,
persistance de commande comprise. Ils n'exigent pas un vol, contrairement à ce que j'avais
annoncé. Restent réellement tributaires du vol : T6 / effet physique et le volet FS.
LEÇON (encore) : ne pas figer une règle à partir de deux observations corrélées.

### Simulateur : ne PAS réactiver s'il tourne déjà (2026-07-22)
`enableSimulator` ÉCHOUE quand le simulateur est déjà actif → 6 `simulateur=ECHEC` alors
qu'il tournait depuis l'ouverture de l'écran. Drapeau `e03SimulateurActif` ; le banc
journalise `simulateur=DEJA_ACTIF reactivation=INUTILE`.
Confirmé au passage : le SDK accorde le VS au sol quand le simulateur tourne (`accorde=OUI`).

### Campagne session 1 (2026-07-22) — 15 répétitions au banc, simulateur ACTIF
`E03-01 ×5 PASS` (T5 = 83..162 ms) · `E03-04 ×5 PASS` (T5 = 56..138 ms) — très en deçà
du seuil de 500 ms. `E03-02 ×5 FAIL` → DEUX défauts de l'essai, pas de la sécurité :
1. **Critère inapplicable** — le verdict exigeait T2 (désarmement) et T4 (sortie VS) alors
   que « zéro maintenu » ne désarme rien et ne sort pas du VS. FAIL inconditionnel, quoi
   que fasse l'aéronef → ne testait rien. `verdict()` est maintenant scénario-conscient :
   E03-02 exige T1 + T3 + persistance. La persistance (critère CENTRAL) reste jugée à
   l'identique, et le critère général est INCHANGÉ pour tous les autres scénarios.
2. **Le stimulus était contredit par l'app** — la boucle pilote réécrivait +0.2 à 10 Hz
   juste après les 10 zéros (`persist_commande_ms≈2998` = toute la fenêtre). Drapeau
   `e03ForcerZero` : zéro imposé pendant la fenêtre, levé au REARME.

### SÉRIE AUTO ×5 (bouton vert) — évite 80 confirmations manuelles
Bouton « Répétitions : 1 ↔ ×5 AUTO ». Enchaîne réarmement soccer → 3 s → stimulus →
fenêtre d'observation. S'interrompt si banc désarmé ou aéronef en vol.
UNIQUEMENT pour les scénarios à stimulus LOGICIEL : `E03_SCENARIOS_AUTOMATISABLES` =
01, 02, 03, 04, 11. Les autres exigent un geste physique → tir unitaire (les automatiser
journaliserait un stimulus qui n'a pas eu lieu). La confirmation humaine d'armement est
contournée pendant la série : tracé par `E03 SERIE_REARMEMENT` à chaque cycle.
Le VS est rétabli automatiquement au REARME (`vs_retabli=OUI`) — sinon la répétition
suivante partirait avec VS=0.

### Session 2 (2026-07-22) — 4 répétitions PERDUES, défaut d'ergonomie corrigé
Tir en ×1 : E03-01 désarme le soccer (c'est son objet), et rien ne le réarmait en mode
unitaire → `soccer_arme=NON`, `v_commandee=0.0`, `MESURE_SANS_OBJET` sur les 4 suivantes.
Piège sournois : le verdict affichait **PASS** alors que RIEN n'était exercé.
→ 2 correctifs : (1) les scénarios automatisables passent TOUJOURS par `e03LancerSerie`,
même pour n=1 (réarmement des préconditions avant chaque stimulus) ;
(2) `e03Declencher` REFUSE si `!soccerArme` (`cause=soccer_non_arme`), avant le stimulus.

### ⚠ DÉFAUT MAJEUR DE L'INSTRUMENTATION (2026-07-22) — critère central VIDE au banc

`sourcePersistance()` retenait la TÉLÉMÉTRIE dès qu'elle « répondait ». Or au banc les
moteurs sont à l'arrêt : la vitesse réelle vaut structurellement 0 → `persistanceMs()`
rendait 0 → persistance TOUJOURS acceptée → **PASS gratuit sur le critère CENTRAL de
E-03**, dans toutes les répétitions.
Révélé par E03-03 : `persist_ms=0 verdict=PASS` alors que `persist_commande_ms=521` et
`525` ms, soit AU-DELÀ du seuil de 500 ms (§378).
→ Correctif : la télémétrie n'est retenue que si elle a observé AU MOINS UN échantillon
non nul (`telemetrieVue && finVReelle>=0`). Sinon → source COMMANDE, quitte à rendre FAIL.
Une télémétrie constamment nulle ne prouve pas l'absence de persistance : elle ne mesure rien.
⚠ CONSÉQUENCE : les répétitions E03-03 des sessions 1-3 sont à REJOUER, et 2/5 seraient
FAIL avec le critère corrigé. Ne PAS reporter les anciens PASS de E03-03 au dossier.

### Campagne session 3 (2026-07-22) — après correctifs
`E03-01 ×5 PASS` (T5 43..151 ms) · `E03-04 ×5 PASS` (T5 55..145 ms) — valides.
`E03-03 ×5` : watchdog indépendant détecte à `age_ms=504..577`, T1=507..578 ms —
comportement CONFORME à sa conception (timeout 500 ms), mais verdict à recalculer
avec le critère corrigé (persist_commande 414..525 ms).
`E03-02 0/5` : le watchdog s'est déclenché pendant le réarmement (`age_ms=-3`, âge
NÉGATIF = mesure faite avant le premier battement) → soccer désarmé → série interrompue.
Correctif : `battement()` immédiat après `armer()` dans `e03RearmerSoccerAuto`.
`E03-11 refusé ×5` (ENV_NON_CONFORME, exige simulateur OFF) : le garde-fou a fonctionné,
mais le compteur affichait « 5/5 faites ». `e03Declencher` rend maintenant un Boolean et
la série s'INTERROMPT au premier refus.

### Campagne session 4 (2026-07-22) — PREMIÈRE campagne entièrement valide
`source_retenue=commande` partout (correctif appliqué). 20 répétitions recevables :

| Scénario | Verdict | T5 | Persistance commande |
|---|---|---|---|
| E03-01 ×5 | **5 PASS** | 69..132 ms | 0 ms |
| E03-02 ×5 | **5 PASS** | — (ni T2 ni T4, normal) | 0..121 ms |
| E03-03 ×5 | **1 PASS / 4 FAIL** | 594..632 ms | 458, 518, 528, 530, 536 ms |
| E03-04 ×5 | **5 PASS** | 85..137 ms | 0 ms |

### ⚠⚠ E03-03 : INCOHÉRENCE DE CONCEPTION, pas un défaut d'essai

`SafetyLimits.WATCHDOG_TIMEOUT_MS = 500` et `WATCHDOG_INDEP_PERIODE_MS = 100`.
Le thread B ne déclare la panne que si l'âge du battement DÉPASSE 500 ms, et il ne vérifie
que toutes les 100 ms → détection entre **500 et 600 ms** (mesuré : `age_ms=514..585`).
Or §378 exige une persistance ≤ **500 ms**.
→ **Le critère est mathématiquement inatteignable** dans le scénario de gel du fil :
la détection ne PEUT PAS survenir avant l'échéance du budget. 4 FAIL sur 5 le confirment
(518..536 ms), le seul PASS (458 ms) étant un cas où le stimulus est tombé juste après
une vérification.
### DÉCISION PRISE (Christian, 2026-07-22) — voie A, budget watchdog resserré

`WATCHDOG_TIMEOUT_MS : 500 → 350` · `WATCHDOG_INDEP_PERIODE_MS : 100 → 50`.
Détection au pire 400 ms + réaction 10..40 ms = 410..440 ms → marge ≈ 60 ms sous 500 ms.
350 ms tolère ~3,5 cycles de la boucle pilote (10 Hz) : plus de gigue admissible qu'avec
300 ms, jugé trop agressif.
Voie B (relever le seuil du dossier) ÉCARTÉE : adapter l'exigence à l'implémentation ne se
défend pas sans analyse de risque démontrant qu'une persistance > 500 ms est sûre.

⚠ PORTÉE : `WATCHDOG_TIMEOUT_MS` gouverne AUSSI `CommandeWatchdog` (watchdog de cycle),
pas seulement le thread B. Les deux se resserrent — voulu (un budget de détection unique
se défend mieux qu'deux valeurs divergentes), mais la campagne anti-faux-positif doit
couvrir les DEUX.

CRITÈRES D'ACCEPTATION de la nouvelle configuration (plus stricts qu'un simple PASS) :
1. E03-03 : persistance MAXIMALE < **450 ms** (pas « < 500 »), sur N≥5.
2. AUCUN faux positif sous charge : CPU chargé, pauses GC, changement d'activité Android,
   retards volontaires de la boucle pilote.
Instrumentation : `E03 WDG_INDEP ... contexte=FENETRE_E03_DECLENCHEMENT_ATTENDU` ou
`HORS_FENETRE_E03_DECLENCHEMENT_INATTENDU` (libellé NEUTRE : constate sans préjuger d'un
faux positif — un déclenchement hors fenêtre peut révéler une vraie perte de cycle),
+ `timeout_ms`/`periode_ms` sur chaque ligne.

CONSTANTES DE CONTRAT ajoutées à `SafetyLimits` (et INCLUSES dans `CONFIG_ID`) :
`PERSISTANCE_MAX_MS=500` (source UNIQUE du seuil §378 ; `EssaiE03Log` s'y réfère au lieu
de le dupliquer), `REACTION_BUDGET_MAX_MS=80` (contrat, PAS un relevé : 2× la pire réaction
mesurée, pour qu'un téléphone plus lent ne casse pas l'argument), `WATCHDOG_CYCLES_BOUCLE_MIN=3`.
Invariant : `350 + 50 + 80 = 480 < 500`.
Tests JVM : `detection_au_pire_laisse_une_marge_de_reaction_sous_le_seuil`,
`le_budget_de_reaction_double_au_moins_la_pire_reaction_mesuree`,
`le_timeout_tolere_le_nombre_minimal_de_cycles_de_boucle_pilote` (calculé depuis
`BOUCLE_PILOTE_HZ`, pas de durée en dur).

### StressBanc — charge reproductible (drapeau `TEST_E03_STRESS`, 5e à remettre à false)
`diag/StressBanc.kt` (PUR, testé JVM) + 3 boutons bruns dans le panneau E-03 :
CPU 30 s · Mémoire/GC 30 s · CPU+GC 30 s.
Pression mémoire **BORNÉE** : anneau plafonné à min(15 % du tas, 64 blocs de 1 Mio), blocs
relâchés au fur et à mesure. PAS d'allocation illimitée — un OOM tuerait le processus et
mesurerait la mort de l'app au lieu de la gigue d'ordonnancement.
La charge CPU garde des micro-pauses : elle RALENTIT la boucle sans la geler (un gel
provoquerait une VRAIE détection, qui ne prouverait rien sur les faux positifs).
Journal encadré par `E03 STRESS DEBUT` / `E03 STRESS FIN` — la preuve est l'ABSENCE de
`WDG_INDEP` entre les deux bornes.
⚠ Ne remplace PAS les essais réels : rotation d'écran, arrière-plan/premier plan, écran
verrouillé/déverrouillé, application lourde en parallèle, changement de réseau, session
prolongée 30-60 min. Le bouton donne la répétabilité, la manipulation donne la représentativité.

⚠ NOUVEAU `CONFIG_ID` → la campagne de qualification est à REJOUER. Les 20 répétitions de
la session 4 NE sont PAS perdues : elles restent la preuve valide, sous l'ancien CONFIG_ID,
que cette configuration ne satisfaisait PAS E03-03. À conserver au dossier comme telle.

### Campagne session 5 (2026-07-22) — nouveau CONFIG_ID `6e4e22d2612568ec` (350/50)

**E03-03 : 5 PASS / 5.** Détection `age_ms=358..381` (fenêtre 350-400 prédite),
persistance de commande **314, 317, 318, 318, 334 ms** → maximum **334 ms**, sous le
critère d'acceptation de 450 ms (et non pas seulement sous 500). Le dimensionnement se
comporte comme calculé.
E03-01, E03-02, E03-04 : 5 PASS chacun, persistance 0 ms. 20 répétitions valides.

### ⚠ CAMPAGNE ANTI-FAUX-POSITIF DE LA SESSION 5 : NON RECEVABLE

Les 3 charges (CPU, MEMOIRE, CPU+GC, 30 s chacune) ont tourné avec `soccer_arme=NON` —
E03-04 venait de désarmer. Le watchdog indépendant ne surveille QUE s'il est armé :
l'absence de `WDG_INDEP` ne prouvait donc rien (détecteur débranché).
→ `e03LancerStress` REFUSE maintenant si `!soccerArme || !surveillanceArmee()`
(`E03 STRESS REFUS ... cause=detecteur_hors_service`), et la ligne FIN porte
`detecteur_en_fin=TOUJOURS_EN_SERVICE|DESARME_EN_COURS_preuve_partielle`.
Le stress est donc à REJOUER, mode SOCCER armé, AVANT tout scénario qui désarme.

### ⚠ DÉFAUT DANGEREUX CORRIGÉ (2026-07-22) — âge négatif = fausse panne

`WatchdogIndependant.verifier()` prenait `nowNanos` en argument PAR DÉFAUT : l'horloge était
donc lue AVANT le dernier battement. Si la boucle pilote battait entre les deux lectures,
l'horodatage du battement était POSTÉRIEUR à l'instant de référence → `ageMs` NÉGATIF →
traité comme `perime` → **arrêt d'urgence complet déclenché par un battement sain**.
Constaté au banc : `WDG_INDEP declenche age_ms=-3` → soccer désarmé → série E03-02 à 0/5.
Avec la période à 50 ms, l'occasion se présente 20 fois/seconde → en vol, mise en sécurité
sans aucune cause.
→ Correctif (proposé par Christian, meilleur que ma 1re idée de seuil) :
1. **ordre de lecture inversé** — `dernier` d'abord, `now` ensuite → `now >= dernier` par
   construction ; signature `verifier(nowNanos: Long? = null)` (injectable pour les tests) ;
2. `ageMs = (delta / 1_000_000).coerceAtLeast(0)` — un âge négatif résiduel vaut 0 ;
3. `perime = ageMs > timeoutMs` — **plus aucun déclenchement sur âge négatif** ;
4. écart négatif AMPLE (> 1 s, `ANOMALIE_HORLOGE_SEUIL_NS`) → COMPTABILISÉ
   (`anomaliesHorloge()`, `pireDeltaNegatifMs()`, journalisé sur la ligne WDG_INDEP) mais
   SANS peser sur la décision — pas de seuil arbitraire dans une décision de sécurité.
Contrat de test revu : `un_battement_plus_recent_que_l_instant_de_reference_ne_declenche_pas`
(+ vérifie que la surveillance reste opérationnelle ensuite) et
`un_ecart_negatif_ample_est_comptabilise_sans_declencher`.
NOTE : `CommandeWatchdog` a la même règle « négatif → non frais », mais ses deux points
d'appel sont sur le MÊME fil (boucle pilote) → course impossible en pratique, et l'effet
serait bénin (un cycle à zéro, auto-corrigé). Laissé tel quel, à revoir si le contexte change.

### Test de concurrence corrigé (CommandeWatchdogTest)
`lectures_concurrentes_...` comparait deux lectures SÉPARÉES (`ageMs` puis `cycleFrais`) : un
battement entre les deux rendait les deux réponses justes mais différentes → échec sur un
entrelacement légitime. Le test relit l'âge après et ne juge que si l'état est STABLE —
conforme à ce que son propre commentaire annonçait déjà.

### Campagne session 6 (2026-07-22) — QUALIFICATION VALIDE, watchdog corrigé
`config_id=6e4e22d2612568ec`, après le correctif d'ordre de lecture. 20 répétitions :
`E03-01 ×5 PASS` (persist 0) · `E03-02 ×5 PASS` (persist 0) ·
**`E03-03 ×5 PASS` — persistance 207, 220, 311, 312, 338 ms → max 338 ms** (critère 450) ·
`E03-04 ×5 PASS` (persist 0). `anomalies_horloge=0` sur toutes les lignes WDG_INDEP :
confirme que les âges négatifs venaient de l'entrelacement, pas d'une horloge défaillante.
→ C'est LA campagne de qualification à porter au dossier pour les 4 scénarios « Simu+réel ».

### Stress : réarmement automatique du détecteur (2026-07-22)
8 refus consécutifs `STRESS REFUS ... detecteur_hors_service` : chaque scénario désarme le
soccer, et le stress exigeait un réarmement MANUEL que rien ne signalait clairement.
→ `e03LancerStress` réarme maintenant le soccer lui-même (comme la série), attend 1 s que
la boucle publie des battements, VÉRIFIE que la surveillance est en service, puis démarre.
Le BANC, lui, reste à armer à la main : c'est la décision humaine, elle ne s'automatise pas.
Traces : `E03 STRESS PREPARATION detecteur=hors_service action=rearmement_automatique`.

### Campagne anti-faux-positif VALIDE (2026-07-22) — `config_id=6e4e22d2612568ec`
3 charges de 30 s (CPU 8 fils · MEMOIRE 38 blocs de 1 Mio · CPU+GC), chacune avec
`soccer_arme=OUI surveillance_watchdog=ARMEE detecteur=EN_SERVICE` au DEBUT et
`detecteur_en_fin=TOUJOURS_EN_SERVICE` à la FIN.
**AUCUNE ligne `WDG_INDEP` entre les bornes** → aucun faux positif sous charge, avec le
budget resserré à 350/50 ms. Critère 2 satisfait.
Réserve mineure à consigner : une ligne isolée `T5 acquittement_sortie_vs ok=true` apparaît
dans la fenêtre CPU+GC (≈28,7 s), sans ARRET_URGENCE ni WDG_INDEP associés. Origine non
élucidée ; sans effet sur le verdict (le détecteur est resté en service), mais à ne pas
présenter comme « rien ne s'est produit ».

### ÉTAT E-03 au 2026-07-22 — 4 scénarios sur 16 QUALIFIÉS
Sous `config_id=6e4e22d2612568ec`, 20 répétitions valides + campagne de charge :
E03-01, E03-02, E03-04 → 5 PASS chacun (persistance 0 ms) ;
E03-03 → 5 PASS, persistance max **338 ms** (critère 450, seuil dossier 500).

### Campagne session 7 (2026-07-22) — QUALIFICATION SUR LE LOGICIEL DÉPOSÉ

Rejouée en UNE session sur l'APK final : un seul en-tête `E03 CONFIG`,
`apk_hash=8fc44108e1e2f2cb5a326077eb54b079a4d022d2c53192f9effa5867d7a6e740`,
`config_id=6e4e22d2612568ec`. Charges de stress d'abord, puis 21 répétitions.
Matériel : Mini 4 Pro SN `1581F6Z9C24AU0034SRD` fw 07.00.00.07 · RC-N2 SN `6ZDZM9U007032G`
fw 01.01.0300 · SM-A546W Android 16 · MSDK 5.18.0 · app 4.2.0.

| Scénario | N | Verdict | T5 | Persistance commande |
|---|---|---|---|---|
| E03-01 | 6 | **6 PASS** | 68..168 ms | 0 ms |
| E03-02 | 5 | **5 PASS** | — (ni T2 ni T4) | 0 ms |
| E03-03 | 5 | **5 PASS** | 409..462 ms | 309, 315, 318, 319, **343 ms** |
| E03-04 | 5 | **5 PASS** | 81..141 ms | 0 ms |

`WDG_INDEP` : `age_ms=358, 365, 380, 385, 397` — dans la fenêtre 350-400 prédite.
`anomalies_horloge=0` partout. `source_retenue=commande` sur les 21 répétitions.
Anti-faux-positif : 3 charges de 30 s (CPU 8 fils · MEMOIRE 38 blocs · CPU+GC), toutes
`interrompu=NON`, `detecteur=EN_SERVICE` au début et `TOUJOURS_EN_SERVICE` à la fin,
**aucune ligne `WDG_INDEP` entre les bornes**. La ligne `T5` isolée de la campagne
précédente NE s'est PAS reproduite (consigné : non reproduit, cause non établie).

Confirmations incidentes :
- **Instance unique** `P3-eab59bc` (tâche 2863) sur toute la session ; aucune ligne
  `AUTORITE_REFUSEE` → la barrière de dernier recours n'a jamais eu à s'interposer.
- E03-01 rep 6 : transition `PAUSE→STOP→BOUCLE_ARRETEE raison=onStop` à +2,32 s dans la
  fenêtre de 3 s. Les jalons T0..T5 étaient acquis à +168 ms → mesure NON affectée,
  répétition conservée avec réserve consignée. Preuve terrain que la boucle s'arrête bien
  à onStop et que l'autorité est reprise proprement au START suivant.
- E03-04 : `ARRET_URGENCE` **exécuté** 5/5 (jamais IGNORE), chacun précédé d'un réarmement
  → la garde d'idempotence n'assèche pas la détection, N=5.
- Fin de session : débranchement RC sans réarmement → `occurrence=3` et `4` tous deux
  `ARRET_URGENCE_IGNORE` (verrou encore posé). Double livraison SDK toujours présente,
  toujours absorbée.

→ **C'EST CETTE CAMPAGNE qui est portée au dossier** (§2.3 et §2.5 de l'addendum). Les
sessions 4 à 6 restent valides mais portent sur des binaires antérieurs.

### ⚠⚠ DÉFAUT DE SÛRETÉ — PRODUCTEURS DE COMMANDES CONCURRENTS (2026-07-22)

**VOL INTERDIT** tant que l'unicité de l'autorité de commande n'est pas confirmée.

Constat : un seul événement de perte RC a produit **6 arrêts d'urgence**, avec des compteurs
distincts (`occurrence=2` ×3 puis `occurrence=3` ×3) → **plusieurs producteurs actifs
simultanément**. ⚠ Ce relevé ne dit PAS lequel des mécanismes est en cause (instances
d'écran survivantes, ponts multiples, écouteurs réenregistrés, boucle non arrêtée) —
ne pas conclure trop vite, c'est l'erreur que Claude a faite en annonçant « 3 Activités ».

CAUSE IDENTIFIÉE DANS LE CODE : `jobPilote` vivait dans `lifecycleScope`, annulé seulement
à `onDestroy()`. Android ne garantit pas que `onDestroy()` arrive vite → un écran en
arrière-plan continuait d'émettre à 10 Hz. Les écouteurs DJI n'étaient jamais retirés.

CORRECTIFS :
1. `AutoriteCommandeDrone` (PUR, testé JVM) — **verrou global : une seule autorité de
   commande pilote par aéronef**. Acquis à `onStart`, libéré à `onStop`/`onDestroy`.
   Idempotent pour le même détenteur ; un tiers ne peut pas libérer l'autorité d'un autre.
   Vérifié AU POINT D'ÉMISSION : sans autorité, throttle = 0 (`AUTORITE_REFUSEE` au journal).
   ⚠ Ce verrou BORNE la conséquence, il ne corrige pas la cause — les deux sont nécessaires.
2. Boucles extraites dans `demarrerBoucles()` (idempotent) + `arreterBouclePilote(raison)`
   (idempotent, `AtomicBoolean`). Arrêt à **onStop**, pas onDestroy. Relance à onStart.
3. `PontDjiReel.libererEcouteurs()` — `cancelListen` à onStop, réenregistrement à onStart.
4. `instanceId` + journal `CYCLE_VIE instance=… task=… etape=CREATE|START|RESUME|PAUSE|STOP|
   DESTROY` (aussi dans essai_e03.log) — c'est cette trace qui permettra de compter les
   instances réellement vivantes.

À VÉRIFIER SUR APPAREIL avant de lever l'interdiction :
```
adb shell dumpsys activity activities | Select-String "Phase3Activity"
adb shell dumpsys activity activities | Select-String "mResumedActivity|topResumedActivity"
adb logcat -d -s Phase3Lifecycle
```
Attendu : une seule instance vivante, `CYCLE_VIE ... etape=STOP` suivi de
`BOUCLE_ARRETEE`, et aucune ligne `AUTORITE_REFUSEE` en fonctionnement normal.

### Épinglage d'écran pendant l'armement soccer (2026-07-22)
`startLockTask()` + `FLAG_KEEP_SCREEN_ON` à l'armement, retirés au désarmement.
**PROTECTION D'ERGONOMIE, PAS DE SÉCURITÉ** — Android peut toujours arrêter l'écran
(appel, mémoire basse, extinction) et l'utilisateur sort par Retour + Aperçu. La vraie
protection reste : autorité unique, arrêt de boucle à onStop, désinscription des écouteurs.
Échec d'épinglage journalisé (`EPINGLAGE_ECHEC`), jamais propagé — il ne doit pas empêcher
d'armer ni surtout de désarmer.
ORDRE IMPOSÉ au désarmement : commandes coupées → commande neutre → PUIS déverrouillage.
Désépinglage aussi dans `arretUrgence()` et `onStop()` (sinon écran verrouillé et inerte).
**Désarmement = appui MAINTENU 3 s** sur le bouton SOCCER (l'écran étant épinglé, la sortie
volontaire doit être là où le pilote regarde, pas dans une combinaison Android sous stress).
Appui bref → message, aucun effet. `runnableDesarmementLong` annulé si le doigt se lève.
ÉCARTS ASSUMÉS vs proposition de Christian : le désarmement soccer n'arrête PAS la boucle
pilote ni les écouteurs DJI (ils servent au vol manuel et au rail, qui doivent rester
opérants), et ne libère PAS l'autorité (elle est liée à l'écran vivant — la libérer
bloquerait le stationnaire et la reprise manuelle). L'autorité est en revanche VÉRIFIÉE
avant d'armer (`ARMEMENT_REFUSE cause=autorite_detenue_par=…`).
Kiosque complet (device owner) ÉCARTÉ : inadapté tant que l'appareil n'est pas dédié et
administré ; compliquerait essais, dépannage et accès à l'app DJI.

### Preuve terrain de l'unicité — 6 → 2 → (à confirmer 1)
Après les correctifs de cycle de vie, un débranchement RC ne produit PLUS 6 arrêts
d'urgence mais **2**, et le journal montre **une seule instance vivante**
(`P3-4b3633d`, compteur 3→4 puis 6→7 = même observateur appelé deux fois).
→ Cause résiduelle : `initialiserListeners()` appelé depuis DEUX chemins (rappel
d'enregistrement SDK **et** `onStart`), le `cancelListen(this)` en tête ne suffisant pas
si les appels se chevauchent → DEUX abonnements sur la même clé.
→ Correctif : garde `ecouteursActifs` (AtomicBoolean) dans `PontDjiReel`.
`initialiserListeners()` est IDEMPOTENT — un second appel est ignoré tant que
`libererEcouteurs()` n'a pas été appelé. Trace : « Ecouteurs DJI DEJA enregistres ».
→ RÉSULTAT : garde CONFIRMÉE active au logcat (« Ecouteurs DJI DEJA enregistres »), mais
le débranchement produit TOUJOURS 2 réactions. **Le double abonnement est donc ÉCARTÉ :
c'est le SDK DJI qui livre l'événement deux fois** (17 ms d'écart, même observateur,
même instance, un seul enregistrement).

### `arretUrgence()` rendu IDEMPOTENT (2026-07-22) — correctif final
Le 2e appel arrivait avec `soccerArme_avant=false` : système DÉJÀ désarmé, latch posé,
VS coupé, neutre envoyé. La seconde exécution n'ajoutait aucune sécurité — seulement des
appels SDK inutiles et une trace DUPLIQUÉE qui empêche de distinguer « deux événements »
de « un événement livré deux fois ».
→ `if (soccerArretUrgence) { log ARRET_URGENCE_IGNORE ; return }`.
Le latch n'étant levé que par un réarmement humain explicite, la garde ne peut PAS masquer
un second événement survenu après retour à l'état armé.
→ **CONFIRMÉ AU BANC (2026-07-22)** : un débranchement RC produit désormais
`occurrence=2 → ARRET_URGENCE` puis `occurrence=3 → ARRET_URGENCE_IGNORE`.
**6 réactions → 1.** Le second débranchement, sans réarmement intercalé, donne deux
`IGNORE` — correct, le latch était encore posé.
→ CONTRE-ESSAI PASSÉ (2026-07-22) : après retour à l'état armé, un nouveau débranchement
produit un `ARRET_URGENCE` **exécuté** (`occurrence=2 soccerArme_avant=true`, ts …404241)
suivi d'un `IGNORE`. La garde n'assèche donc PAS la détection.
→ **CONTRE-ESSAI STRICT PASSÉ (2026-07-22)** — même instance, compteur CONTINU :
`occ=2 soccerArme_avant=true → ARRET_URGENCE` · `occ=3 → IGNORE` ·
`SERIE_REARMEMENT soccer_arme=OUI urgence_latch=NON` ·
`occ=6 soccerArme_avant=true → ARRET_URGENCE` · `occ=7 → IGNORE`.
Un réarmement lève le latch ; un second événement RÉEL déclenche un NOUVEL arrêt exécuté.
La garde absorbe les doublons du SDK sans masquer une vraie perte.
⚠ PORTÉE EXACTE : le réarmement provient de `SERIE_REARMEMENT` (chemin AUTOMATIQUE du
banc), pas du bouton SOCCER manuel. Les deux remettent `soccerArretUrgence=false`, mais
seul le chemin automatique est ÉPROUVÉ. À écrire ainsi au dossier — ne pas revendiquer
« démontré par appui manuel ». Trace `E03 SOCCER_ARME latch_urgence_avant=POSE` ajoutée
pour couvrir le chemin manuel lors d'un prochain relevé.

### DÉFAUT DES PRODUCTEURS CONCURRENTS — CLOS (2026-07-22)
6 réactions → 1. Trois causes distinctes traitées : (a) boucle pilote survivant en
arrière-plan → arrêt à onStop ; (b) écouteurs DJI enregistrés deux fois → garde
`ecouteursActifs` ; (c) double livraison du SDK DJI (NON corrigeable côté app) → absorbée
par `arretUrgence()` idempotent. Instance unique prouvée au journal `CYCLE_VIE`.
Verrou `AutoriteCommandeDrone` en barrière de dernier recours.
→ L'INTERDICTION DE VOL liée à ce défaut peut être levée. Les autres réserves (12 scénarios
physiques, essais Android réels, couplage RTK-voiture, T6 non mesurable) restent OUVERTES.

### ⚠⚠ DÉFAUT DE SÛRETÉ — WATCHDOG MORT EN ARRIÈRE-PLAN (2026-07-22)

Trouvé par LECTURE du code avant de lancer les essais Android réels — et c'est heureux :
ces essais l'auraient MASQUÉ au lieu de le révéler.

`onStop` appelait `soccerWatchdogIndep.arreter()` (tue le thread B) mais laissait
`soccerArme=true`, et `onStart` ne relançait JAMAIS ce thread. `arreter()` ne touchait pas
`surveillanceArmee` → après un aller-retour en arrière-plan (appel entrant, verrouillage
d'écran, notification plein écran) :
- la boucle pilote REDÉMARRE et recommande l'aéronef ;
- la protection contre le gel du fil d'émission (REQ-WDG-001) est HORS SERVICE ;
- le bouton, le journal et le garde-fou de la campagne de charge annoncent tous
  `detecteur=EN_SERVICE` — ils lisaient une variable d'INTENTION, pas un état de santé.

⚠ LE PIÈGE : l'essai « arrière-plan/premier plan » n'aurait relevé AUCUN `WDG_INDEP`, et
cette absence serait partie au dossier comme preuve d'absence de faux positif. Même
mécanisme que la campagne de stress invalide de la session 5, mais invisible au journal.

CORRECTIFS (décision Christian) :
1. `WatchdogIndependant.arreter()` désarme la surveillance EN PREMIER (avant de tuer le
   thread) → aucun lecteur concurrent ne peut observer « armée sans porteur ».
   INVARIANT : `surveillanceArmee == true` ⟹ thread vivant.
2. `estEnService() = surveillanceArmee && doitTourner && thread.isAlive` — état de SANTÉ
   constaté. Le stress, la ligne `STRESS DEBUT/FIN`, la ligne `E03 DEBUT` et
   `SERIE_REARMEMENT` l'utilisent désormais au lieu de `surveillanceArmee()`.
3. `onStop` → `desarmerSoccerCycleVie("ecran_hors_premier_plan")` : commandes coupées et
   neutre envoyé D'ABORD, puis désépinglage, arrêt de boucle, retrait des écouteurs,
   `arreter()` du watchdog, autorité libérée, `soccerArme=false`, bouton remis à jour,
   ligne `SOCCER_DESARME_CYCLE_VIE`. Idempotent.
4. `onStart` : AUCUN réarmement automatique. Message explicite au pilote
   (« Mode SOCCER désarmé / Raison : application passée en arrière-plan / Réarmement
   manuel requis ») + ligne `REPRISE_SANS_REARMEMENT`.

Champs de journal qui étaient ÉCRITS EN DUR et sont maintenant RELEVÉS :
`STRESS DEBUT ... soccer_arme/surveillance_watchdog/detecteur` et
`SERIE_REARMEMENT ... soccer_arme/mode_manuel/urgence_latch`. Une preuve d'essai ne se
déclare pas, elle se relève.

Tests JVM ajoutés (5) : `arreter_desarme_la_surveillance`,
`arreter_met_le_detecteur_hors_service`, `apres_un_cycle_arret_l_etat_ne_ment_pas`,
`surveillance_armee_sans_porteur_demarre_n_est_pas_en_service`,
`un_rearmement_explicite_remet_le_detecteur_en_service`.

⚠ CONSÉQUENCE DE CAMPAGNE : la session 7 reste VALIDE (aucun passage en arrière-plan
pendant les charges ; l'unique transition de cycle de vie, E03-01 rep 6, est survenue
APRÈS l'acquisition de T0..T5 et hors de toute fenêtre de stress). Mais le binaire change
→ nouvel `apk_hash`, campagne à rejouer avant dépôt final.

### ⚠⚠ AUDIT 2026-07-22 — LE BOUTON « MODE SOCCER » N'EXISTAIT PAS

Session de 20 min perdue : `etait_arme=NON` sur 10 désarmements de cycle de vie, aucune
ligne `SOCCER_ARME` du journal. Audit demandé par Christian — ce n'était PAS une erreur de
manipulation. DEUX défauts empilés, chacun masquant l'autre :

1. **Bouton jamais créé.** `if (SOCCER_RAIL_REAL_ENABLED)` gardait sa construction. Ce
   drapeau vaut `false` (mode RAIL). Or E-03 se déroule en mode **2D**
   (`SOCCER_2D_REAL_ENABLED = TEST_E01_SIGNE_THROTTLE = true`). `btnSoccer` restait null,
   `majBoutonSoccer()` sortait sur `?: return` → **aucun moyen d'armer à la main**.
   → Condition corrigée : `SOCCER_RAIL_REAL_ENABLED || SOCCER_2D_REAL_ENABLED`.
2. **`soccerArme` pré-armé par un drapeau de compilation** (`= TEST_E01_SIGNE_THROTTLE`).
   Trois effets : le bouton (quand il existait) affichait « désarmé » alors que l'état
   interne disait « armé » — `majBoutonSoccer()` n'était pas appelé à la construction ;
   `basculerArmementSoccer()` prenait la branche `if (soccerArme)` et n'armait donc JAMAIS,
   sans dialogue ni trace ; et surtout `soccerArme=true` **sans thread de watchdog démarré,
   sans épinglage, sans maintien d'écran** — le même état menteur « armé sans détecteur »
   corrigé ailleurs le même jour, réintroduit par une valeur initiale.
   → `soccerArme = false` TOUJOURS au démarrage. Un mode automatique s'arme par une décision
   humaine, jamais par un drapeau de compilation.

POURQUOI PERSONNE NE L'AVAIT VU : les deux béquilles se compensaient. Le pré-armement
rendait le bouton inutile, et `e03RearmerSoccerAuto` réarme de toute façon avant chaque
répétition — toutes les campagnes 1 à 7 passent par là. Le chemin MANUEL n'a donc jamais
été exercé, ce qui explique la réserve « démontré par le chemin automatique seulement ».

AJOUTS : `majBoutonSoccer()` appelé à la création du bouton (l'affichage se DÉRIVE de
l'état, il ne se recopie pas) ; ligne `E03 SOCCER_APPUI_SANS_EFFET etat_interne=ARME
detecteur=…` — un appui qui ne produit rien doit laisser une marque, sinon on cherche
20 minutes sans indice.

⚠ EFFET DE BORD À SURVEILLER : `soccerArme` ne démarrant plus à `true`, la charge de stress
et les séries dépendent entièrement de leur réarmement automatique (déjà en place). Le mode
2D n'émet plus rien tant que l'opérateur n'a pas armé — c'est le comportement voulu.

### Session 8 (2026-07-22) — APK `f0c0168e…d5c313` : 2 acquis, 1 défaut de plus

**ACQUIS 1 — armement MANUEL prouvé, première fois du projet.**
`E03 SOCCER_ARME instance=P3-1feae3 latch_urgence_avant=LIBRE … vs=OUI banc=OUI` +
`EPINGLAGE actif=OUI`. Les deux défauts de l'audit (bouton absent, pré-armement) sont
corrigés et vérifiés sur appareil. La réserve « chemin automatique seulement » peut tomber.

**ACQUIS 2 — essai de durée PASSÉ : 15 min 5 s armé en continu, AUCUN `WDG_INDEP`.**
Armé à `…521330`, arrêt d'urgence par perte RC à `…426860` (905 s). Entre les deux : aucun
`STOP`, aucun `SOCCER_DESARME_CYCLE_VIE` → l'épinglage + `KEEP_SCREEN_ON` ont tenu l'écran
allumé, la boucle n'a jamais quitté le premier plan. Le budget 350/50 tient sur 15 minutes
de fonctionnement réel (critère 2 du dossier, volet « durée »).
⚠ À REJOUER : l'APK change avec les correctifs ci-dessous.

**20 répétitions NULLES** (`mode2D=NON joueurs_vus=0`) : aucune commande émise, donc
`MESURE_INVALIDE t0_absent`. Non comptabilisées — l'instrumentation a bien tenu.

### ⚠⚠ DÉFAUT — `verdict=PASS` sur une mesure INVALIDE (2026-07-22)

Les 20 répétitions nulles portaient SIMULTANÉMENT
`MESURE_INVALIDE t0_absent … repetition_a_rejouer` **et** `verdict=PASS`.
Deux lignes contradictoires sur la même répétition — c'est le PASS qu'un lecteur retient.

MÉCANISME : sans commande émise, `derniereVitesse=0` → `persistanceMs()` rendait `0 ms`
(« persistance parfaite ») alors qu'elle convertissait une ABSENCE DE DONNÉE en meilleur
résultat possible ; et T1..T4 étaient bien marqués par le stimulus. Toutes les cases du
critère cochées par du vide.

CORRECTIFS :
1. `persistanceMs()` rend `null` si `t0Nanos < 0` — un zéro qui vient de rien ne vaut pas
   un zéro mesuré. `persistanceAcceptee()` reste fail-closed → false.
2. `mesureExploitable(m) = m.t0Nanos >= 0` et `verdict()` rend **`NUL`** dans ce cas.
   PAS `FAIL` : ce serait affirmer que la sécurité n'a pas tenu alors qu'elle n'a pas été
   sollicitée. Un essai qui n'a rien mesuré ne réussit ni n'échoue.
3. La dérogation E03-02 et le volet FS passent aussi par ce garde-fou (testé).
4. `E03 SOCCER_ARME` porte désormais `detecteur=EN_SERVICE|HORS_SERVICE`, et la ligne est
   écrite APRÈS `demarrer()`. Sans ce champ, 15 minutes sans alarme ne distinguaient pas
   « rien ne s'est produit » de « personne ne surveillait ».

Tests JVM ajoutés (4) : `verdict_nul_si_t0_absent_meme_avec_tous_les_jalons`,
`verdict_nul_sans_t0_y_compris_pour_e03_02_et_le_volet_fs`,
`persistance_non_mesurable_sans_t0_meme_si_la_vitesse_est_nulle`,
`ligne_d_une_repetition_sans_t0_porte_verdict_nul_et_persistance_absente`.

⚠ RAPPEL DE PROCÉDURE (cause des 20 répétitions perdues) : avant toute série, vérifier sur
la ligne `E03 DEBUT` que `mode2D=OUI` ET `joueurs_vus>=1`. Sans les deux, rien n'est émis.

### ⚠⚠ DÉFAUT — les 12 scénarios PHYSIQUES n'étaient pas mesurables (2026-07-22)

Révélé au premier essai réel (E03-07, débranchement USB, simulateur OFF).

**Défaut 1 — T0 figé trop tôt.** `e03Declencher` appelait `demarrerObservation()` pour TOUS
les scénarios, figeant T0 à l'appui sur le bouton. Correct quand l'appui EST le stimulus
(01..04, 11). FAUX pour un stimulus physique : l'appui ne fait qu'annoncer l'intention,
l'événement arrive 1 à 4 s plus tard, le temps que l'opérateur agisse. Le délai de réaction
HUMAIN était donc compté comme de la persistance LOGICIELLE.
Relevé : `E03-07 rep=1 persist_ms=1561 verdict=FAIL` — sans le moindre défaut de sécurité.
De plus `marquerT1()` était appelé au bouton : T1 datait l'intention, pas la détection.
→ `EssaiE03Capteur.armerObservationDifferee()` : T0 CONTINUE de suivre le heartbeat ;
c'est le premier `marquerT1()` (détection RÉELLE par la chaîne) qui fige T0, via
`AtomicBoolean.compareAndSet` — un doublon SDK ne re-fige pas. T1 n'est plus marqué au bouton.

**Défaut 2 — fenêtre d'observation inatteignable.** 3 s pour saisir un câble et l'arracher :
trois tentatives d'affilée ont vu l'événement tomber APRÈS la fermeture (900 ms, 3,2 s et
3,9 s de retard). L'essai n'était pas sévère, il était inexécutable.
→ `E03_FENETRE_PHYSIQUE_MS = 15 s` pour les scénarios physiques ; 3 s inchangé pour les
logiciels. **N'assouplit aucun critère** : la persistance se mesure de T0 à la dernière
commande non nulle, jamais sur la durée de la fenêtre.

Journal enrichi : `FIN ... stimulus=PHYSIQUE|LOGICIEL t0_fige=… detection_survenue=…` —
distingue « l'événement n'a pas eu lieu » de « il a eu lieu et rien n'a été détecté ».

Tests JVM ajoutés (4) : `en_observation_differee_T0_suit_le_heartbeat_jusqu_a_la_detection`,
`le_delai_de_reaction_humain_n_entre_pas_dans_la_persistance`,
`une_seconde_detection_ne_refige_pas_T0`,
`le_stimulus_logiciel_fige_T0_des_l_appui_comme_avant` (non-régression 01..04).

**Défaut 3 — persistance NÉGATIVE (`persist_ms=-2413`).** Découvert dès le premier essai
réussi de l'observation différée. L'opérateur sort du champ de la caméra pour aller
débrancher → `joueurs_vus=0` → le throttle retombe à 0 → plus aucune commande non nulle.
Mais T0 suit le heartbeat, qui continue : au moment de la détection, T0 est 2,4 s PLUS RÉCENT
que la dernière commande non nulle. Durée négative, sans aucun sens physique.
→ `persistanceMs()` rend `null` si `fin < t0` (la commande avait déjà cessé avant
l'événement) ; `mesureExploitable()` rend false dans ce cas → `verdict=NUL`, à rejouer.
Ni PASS ni FAIL : le critère n'a pas été exercé, il n'y avait rien à faire cesser.
Test `une_commande_anterieure_a_l_evenement_rend_la_persistance_non_mesurable` (l'ancien
test actait la valeur négative comme attendue — contrat corrigé).
⚠ RÈGLE TERRAIN : pour un scénario physique, **rester dans le champ de la caméra** pendant
toute la manipulation, sinon la commande cesse pour une raison étrangère au scénario.

**Défaut 4 — `verdict=FAIL` sur `MESURE_SANS_OBJET`.** Relevé E03-11 : `verdict=FAIL` écrit
à côté de `MESURE_SANS_OBJET aucune_commande_non_nulle_emise`. Aucune commande non nulle
n'avait été émise : il n'y avait rien à faire cesser, donc rien d'éprouvé.
→ `mesureExploitable()` exige désormais les TROIS conditions : T0 présent, une commande non
nulle réellement émise, et cette commande postérieure ou simultanée à T0. Sinon `NUL`.
Tests d'appui réécrits pour porter une commande non nulle réaliste (0,2 m/s) au lieu du
raccourci `v = 0f`, qui masquait le cas.

**⚠ E03-07 n'éprouve PAS le critère de persistance, par construction.** Débrancher le câble
tue la vidéo AVANT que le SDK signale la perte de liaison : la porte de perception se ferme
(bit AC=0, fail-closed), le throttle retombe à zéro de lui-même, et il ne reste rien à faire
cesser au moment de la détection. C'est le comportement voulu, mais le scénario ne mesure
donc que la chaîne de DÉTECTION et de RÉACTION, pas la persistance. À écrire ainsi au dossier.

**PREMIER RELEVÉ COMPLET SUR SCÉNARIO PHYSIQUE** (E03-07, `apk_hash=fed2d326…`,
simulateur INACTIF, chaîne réelle) : `T1=27 T2=34 T3=36 T4=41 T5=139 ms`, très en deçà du
seuil de 500 ms. Persistance non exploitable (défaut 3) — à rejouer, mais la chaîne de
détection et de réaction est mesurée de bout en bout pour la première fois hors simulateur.

### E03-07 QUALIFIÉ (2026-07-22) — `apk_hash=e6ea9cf3…add20f76`, simulateur INACTIF

5 répétitions valides, chaîne RÉELLE, banc armé, hélices retirées.

| Rép. | T1 | T2 | T3 | T4 | T5 |
|---|---|---|---|---|---|
| 1 | 26 | 31 | 34 | 45 | 116 ms |
| 2 | 52 | 58 | 60 | 77 | 153 ms |
| 3 | 65 | 70 | 71 | 76 | 128 ms |
| 4 | 103 | 108 | 110 | 123 | 194 ms |
| 5 | 55 | 58 | 59 | 64 | 116 ms |

Détection 26..103 ms, acquittement SDK 116..194 ms — très en deçà des 500 ms du §378.
`verdict=NUL` sur les 5 : **normal et attendu**, la persistance n'est pas exerçable par ce
scénario (voir ci-dessus, la vidéo meurt avant que le SDK signale la perte). Ce que E03-07
démontre, c'est la chaîne de DÉTECTION et de RÉACTION, mesurée de bout en bout hors
simulateur.

⚠ PIÈGES DE PROCÉDURE identifiés (3 répétitions perdues chacun) :
1. **Un seul tir par ouverture d'écran.** L'arrêt d'urgence bascule l'app en MODE MANUEL
   et le réarmement du soccer ne le remet pas à zéro → `mode_manuel=OUI v_commandee=0.0`
   sur toutes les répétitions suivantes du même écran.
2. **Ne pas toucher au bouton MANUEL** : sur un écran neuf il est déjà à NON, et l'appuyer
   l'active ET fait sortir du mode 2D (`mode2D=NON joueurs_vus=0`).
3. **Ne pas quitter l'écran avant la fin des 15 s**, sinon la ligne de résultat n'est jamais
   écrite.
→ Vérifier sur `E03 DEBUT` : `mode2D=OUI joueurs_vus=1 mode_manuel=NON` AVANT chaque tir.

### E03-09 QUALIFIÉ (2026-07-22) — `apk_hash=e6ea9cf3…add20f76`, simulateur INACTIF

Perte de radiocommande (extinction physique de la RC-N2). 5 répétitions valides, un tir par
ouverture d'écran, `mode2D=OUI joueurs_vus=1 mode_manuel=NON` sur les cinq.

| Rép. | T1 | T2 | T3 | T4 | T5 |
|---|---|---|---|---|---|
| 1 | 41 | 43 | 44 | 47 | 89 ms |
| 2 | 25 | 30 | 31 | 41 | 93 ms |
| 3 | 29 | 31 | 32 | 37 | 66 ms |
| 4 | 39 | 41 | 41 | 44 | 77 ms |
| 5 | 44 | 46 | 47 | 50 | 80 ms |

Détection 25..44 ms, acquittement 66..93 ms. Dispersion NETTEMENT plus faible que E03-07
(l'extinction de la RC est un geste plus net que l'arrachage d'un câble).
`verdict=NUL` sur les 5 et `telemetrie=absente` : la RC éteinte coupe aussi la télémétrie —
même limite structurelle que E03-07, la persistance n'est pas exerçable par un scénario de
perte de liaison au banc. Ce qui est démontré : DÉTECTION + RÉACTION, hors simulateur.

### ⚠ DÉFAUT — la perte de l'AÉRONEF ne déclenchait rien (2026-07-22)

Révélé par E03-08 : 4 tentatives, `detection_survenue=false`, aucun jalon marqué. Drone
éteint, radiocommande toujours allumée → **rien ne se passait**.

CAUSE : `PontDjiReel` écoute bien `FlightControllerKey.KeyConnection` et expose
`obsConnexionDrone`, mais **aucun abonné n'était branché dans Phase3Activity**. Seule la
perte de la RADIOCOMMANDE (`obsConnexionRc`) déclenchait l'arrêt d'urgence. Le voyant
passait au rouge, et c'était tout : le mode soccer restait ARMÉ et la boucle continuait
d'émettre vers un aéronef absent.

PORTÉE HONNÊTE : ce n'est pas dangereux en soi — sans aéronef, personne n'exécute la
commande. C'est un état qui MENT, même famille que « armé sans détecteur ». Et la liaison
RC n'est pas un substitut : elle peut rester établie alors que le drone a disparu.

CORRECTIF : `obsConnexionDrone` branché sur `arretUrgence()`, en symétrie exacte avec la
perte RC (action immédiate, arrêt idempotent, compteur d'occurrences séparé
`e03CptConnexionDrone`, ligne `E03 DRONE_CONNEXION`). `marquerT1()` appelé AVANT
l'arrêt d'urgence pour que l'ordre des jalons reflète la chaîne réelle.

⚠ TRANSITION, PAS ÉTAT : on ne réagit qu'au passage **présent → absent**
(`droneConnecteDernier == true && !connecte`). À l'ouverture de l'écran la clé DJI délivre
l'état courant — `false` si le drone n'est pas encore sous tension. Ce n'est pas une perte,
c'est une absence initiale ; y réagir poserait un arrêt d'urgence à chaque ouverture de
Phase 3 sans aéronef et verrouillerait le latch avant toute action de l'opérateur.

### E03-08 QUALIFIÉ (2026-07-22) — `apk_hash=79043af1…36f0a056`, simulateur INACTIF

Perte de l'aéronef (extinction du drone, RC maintenue). 5 répétitions valides, un tir par
ouverture d'écran, `mode2D=OUI joueurs_vus=1 mode_manuel=NON detecteur=EN_SERVICE`.

| Rép. | T1 | T2 | T3 | T4 | T5 |
|---|---|---|---|---|---|
| 1 | 61 | 63 | 64 | 68 | 91 ms |
| 2 | 49 | 82 | 83 | 90 | 110 ms |
| 3 | 90 | 96 | 97 | 102 | 120 ms |
| 4 | 101 | 103 | 104 | 108 | 137 ms |
| 5 | 0 | 2 | 3 | 6 | 27 ms |

Détection 0..101 ms, acquittement 27..137 ms. La répétition 5 à `T1=0` correspond à une
extinction survenue dans le même cycle de boucle que le battement — meilleur cas, pas une
anomalie.

**Le garde-fou de transition validé 5/5** : le SDK livre la perte QUATRE fois par tir
(`occurrence=2..5`) et une seule porte `transition=PRESENT_VERS_ABSENT`. Aucun arrêt
d'urgence parasite à l'ouverture des 5 écrans (`occurrence=1 connecte=true`).
Confirme aussi le correctif « télémétrie écartée au banc » : `v_reelle=0.1..0.2` apparaît
pendant l'extinction (bruit) sans jamais devenir la source retenue.

### E03-11 QUALIFIÉ (2026-07-22) — `apk_hash=714bd195…b69bdd2a6`, simulateur INACTIF

Sortie Virtual Stick explicite. Stimulus LOGICIEL → série ×5 automatique, une seule session.

| Rép. | T1 | T4 | T5 | Persistance |
|---|---|---|---|---|
| 1 | 22 | 28 | 58 ms | 0 ms |
| 2 | 96 | 100 | 150 ms | 0 ms |
| 3 | 84 | 88 | 125 ms | 0 ms |
| 4 | 55 | 64 | 91 ms | 0 ms |
| 5 | 78 | 88 | 129 ms | 0 ms |

**5 PASS / 5.** Sortie demandée en 28..100 ms, acquittée par le SDK en 58..150 ms.

⚠ CRITÈRE PROPRE AU SCÉNARIO (même famille que E03-02). Premier passage : 5 FAIL sur 5,
T2 et T3 systématiquement absents. E03-11 demande au SDK de QUITTER le Virtual Stick,
rien d'autre : il ne désarme pas le mode (T2) et n'envoie pas de commande neutre (T3) —
ces jalons appartiennent à la chaîne d'ARRÊT D'URGENCE, qu'il n'emprunte pas. Les exiger
rendait FAIL inconditionnel.
→ `verdict()` : E03-11 exige **T1 + T4 + persistance acceptée**. Le critère CENTRAL reste
jugé à l'identique ; la dérogation est bornée au scénario (test de non-régression : le même
jeu de mesures reste FAIL pour E03-01).
Tests JVM ajoutés (3) : `verdict_e03_11_ne_reclame_ni_desarmement_ni_commande_neutre`,
`verdict_e03_11_echoue_si_la_commande_persiste`, `verdict_e03_11_exige_t1_et_t4`.

### E03-06 QUALIFIÉ (2026-07-22) — `apk_hash=2ab51a82…82431b9f`, simulateur INACTIF

Passage en arrière-plan (bouton POWER). 5 répétitions valides, un tir par ouverture d'écran.

| Rép. | T1 | T4 | T5 | T2 | T3 | Persistance |
|---|---|---|---|---|---|---|
| 1 | 108 | 184 | 401 | 613 | 613 | 139 ms |
| 2 | 42 | 253 | 384 | 560 | 562 | 119 ms |
| 3 | 55 | 138 | 201 | 560 | 562 | 105 ms |
| 4 | 30 | 70 | 124 | 159 | 160 | 0 ms |
| 5 | 15 | 30 | 235 | 507 | 508 | 0 ms |

**5 PASS.** Persistance max **139 ms** (seuil 500). Détection 15..108 ms.
Sur les 5 : `SOCCER_DESARME_CYCLE_VIE etait_arme=OUI` puis `REPRISE_SANS_REARMEMENT`.

INSTRUMENTATION AJOUTÉE pour ce scénario : le chemin du cycle de vie ne posait AUCUN jalon
(`detection_survenue=false`, et `T5_ms=-12303` au premier essai — la sortie VS acquittée
pendant la veille pendant que T0, faute de détection, continuait de suivre le heartbeat).
→ T1 marqué dans `onPause` (premier rappel où l'application apprend qu'elle quitte le
premier plan ; le marquer dans `onStop` daterait la détection APRÈS la sortie VS faite
juste avant, donc des jalons dans le désordre) et SEULEMENT si le mode était armé —
une mise en veille sans rien d'armé n'est pas une cessation.
T4 à la coupure du VS (onPause), T2 et T3 dans `desarmerSoccerCycleVie` (onStop).
→ `ligne()` n'affiche plus jamais un délai NÉGATIF : un jalon antérieur à T0 s'écrit « - ».
Test `ligne_n_affiche_jamais_un_delai_negatif`.

⚠ À CONSIGNER : T2 et T3 tombent entre 159 et 619 ms, donc parfois au-delà de 500 ms.
Ce n'est PAS un dépassement du critère — le critère porte sur la PERSISTANCE (0..139 ms).
C'est Android qui livre `onStop` avec un délai variable après `onPause`, hors du contrôle
de l'application. Les commandes, elles, ont déjà cessé ; le désarmement formel suit.

### E03-13 SURCHARGE THERMIQUE — surveillance AJOUTÉE + QUALIFIÉ 3 PASS (2026-07-23)

**Constat de départ : l'app n'avait AUCUNE détection thermique.** Seule la température MÉTÉO
était lue (dans `ConditionsVol.kt`), pour juger si le DRONE peut voler — jamais la surchauffe
du TÉLÉPHONE, qui est pourtant ce qui met la boucle de commande en danger. Appuyer sur 13 ne
produisait donc rien, comme E03-08 avant correction.

**Surveillance branchée** (`Phase3Activity`, API 29+, `PowerManager.addThermalStatusListener`) :
- Seuil `E03_SEUIL_THERMIQUE = 3` (`THERMAL_STATUS_SEVERE`). En dessous (LEGER/MODERE) le
  bridage ne gêne pas une boucle à 10 Hz — déclencher là poserait des mises en sécurité inutiles.
- **TRANSITION, pas état** (même garde que la perte d'aéronef) : on ne réagit qu'à l'ENTRÉE en
  zone sévère, et seulement si `soccerArme`. Sinon un appareil déjà chaud à l'ouverture
  déclencherait un arrêt d'urgence avant toute action.
- ⚠ **SEUIL NON CRITIQUE, et c'est assumé** : le watchdog indépendant détecte DÉJÀ un fil
  d'émission ralenti/gelé, quelle qu'en soit la cause. La surveillance thermique est une
  **défense en profondeur PRÉCOCE et spécifique** — pas la protection principale. Si le seuil
  était mal choisi, le watchdog rattrape la conséquence.

**Stimulus au banc : injection Android officielle** (`adb shell cmd thermalservice
override-status 3`). Ce n'est pas une simulation dans l'app : c'est le FRAMEWORK qui délivre le
niveau à `PowerManager`, donc au vrai écouteur par le vrai chemin. Tout est exercé sauf la
chaleur physique (impossible d'atteindre SEVERE au banc sans abîmer l'appareil — 3 charges de
stress ne montent qu'à LEGER). `adb` sans fil obligatoire (câble occupé par la RC) :
`adb tcpip 5555` → `adb connect <ip_wlan0>:5555`. **Reset AVANT chaque tir** (`cmd
thermalservice reset`), sinon l'override reste verrouillé à 3 et le tir suivant n'est pas une
transition.

**Résultat — 3 PASS** (`apk_hash=51916625…`, simulateur INACTIF, banc armé, mire vue) :

| Rép. | T1 | T2 | T3 | T4 | T5 | Persist. |
|---|---|---|---|---|---|---|
| 1 | 27 | 33 | 33 | 36 | 141 ms | 0 ms |
| 2 | 43 | 46 | 46 | 49 | 107 ms | 0 ms (T6=1103) |
| 3 | 0 | 3 | 4 | 8 | 44 ms | 0 ms |

Détection 0..43 ms, arrêt complet acquitté 44..141 ms — très en deçà des 500 ms du §378.
Contrairement à E03-07/08/09, E03-13 **mesure la persistance** (`derniere_v=0.2`, la surchauffe
ne tue pas la perception comme l'arrachage d'un câble) : c'est un vrai PASS, famille E03-06.

**Décision (Christian, 2026-07-23) : qualifié à 3 PASS.** Pousser à 5/5 exige un triple
alignement à la main (mire tenue en continu + transition thermique nette + injection dans la
fenêtre) ; pour une couche NON CRITIQUE, 3 PASS + détection <150 ms sur de nombreux tirs
suffisent. À rejouer sur le binaire final avec les autres.

**DEUX GARDE-FOUS AJOUTÉS pour rendre le tir fiable** (`E03_SCENARIOS_COMMANDE_ATTENDUE` =
{06, 13}) — un test qui rend NUL en silence est un mauvais test :
1. **Perception froide** → REFUS immédiat `cause=perception_non_fraiche` (même condition
   `yoloFrais + conf|joueurs` que le point d'émission) au lieu d'une fenêtre de 15 s perdue.
   Exclut les pertes de liaison 07/08/09, où l'absence de commande est NORMALE.
2. **Thermique déjà à SEVERE** → REFUS `cause=thermique_deja_au_seuil` (override adb laissé
   verrouillé = aucune transition possible). Message écran + reset indiqué.
Aucun ne truque quoi que ce soit : si la mire n'est pas là ou si le thermique est déjà chaud,
il n'y a réellement rien à mesurer. Le fail-closed reste intact.

### E03-05 CRASH PROCESSUS — instrumentation par handler de dernier recours + QUALIFIÉ (2026-07-23)

**Problème structurel : un process tué ne peut plus écrire son journal.** L'instrumentation
T0..T5 + fenêtre d'observation ne peut donc PAS se mesurer elle-même (la coroutine de synthèse
meurt avec le process). Mesuré autrement :

- `UncaughtExceptionHandler` installé à `onCreate` (build d'essai) qui, sur un crash NON
  intercepté, écrit une dernière ligne `E03 CRASH_PROCESS ... derniere_emission_non_nulle_il_y_a_ms=…`
  PUIS **chaîne au handler précédent** — le process meurt normalement, on n'avale rien.
- `e03DerniereEmissionNonNulleNanos` (AtomicLong) mis à jour au point d'émission unique quand
  un throttle NON NUL part réellement. Le marqueur dit depuis combien de temps l'aéronef était
  commandé au moment du crash.
- Stimulus (à la DIFFÉRENCE de E03-04 qui intercepte) : `Thread { throw RuntimeException(...) }`
  sur un fil dédié → atteint le handler → crash réel. Le pré-check `E03_SCENARIOS_COMMANDE_ATTENDUE`
  inclut désormais E03-05 : refus si aucune commande vivante (sinon on « prouverait » une
  cessation qui n'avait rien à couper).

**CE QUE E03-05 PROUVE, ET RIEN DE PLUS** : l'émetteur (boucle pilote + SDK DJI) vit DANS le
process → il meurt avec lui → **persistance après crash NULLE PAR CONSTRUCTION**, pas par une
mesure de délai. **CE QU'IL NE COUVRE PAS** : la protection de l'aéronef APRÈS la mort de l'app
= timeout Virtual Stick du DRONE (firmware DJI) → essai EN VOL, pas au banc.

**QUALIFIÉ** (`apk_hash` du binaire E03-05, simulateur INACTIF, banc armé) : 7 répétitions avec
commande active au crash, `derniere_emission_non_nulle_il_y_a_ms` = **5, 11, 27, 45, 49, 76, 81 ms**.
Handler déclenché sur les 12 crashs de la session (mécanisme robuste). 2 tirs à délai énorme
(31050, 27558 ms) = mire décrochée ~30 s avant → commande déjà cessée, NON comptés (honnête).

⚠ **BO (batterie) découvert au passage** : plusieurs tirs bloqués `raison="soccer bloque :
batterie_insuffisante"` (bit BO=0) → aucune commande émise, `derniere_emission=-1`. C'est le
**fail-closed qui fonctionne** (l'arbitre refuse d'émettre batterie basse), mais ça bloque le
test. Explique aussi les `derniere_v=0.0` de fin de session E03-13 : la batterie se vidait au
fil des tirs. **Batterie chargée = BO=1 = émission rétablie.** À retenir pour toute campagne :
surveiller le niveau batterie, une session longue le fait tomber.

### ÉTAT E-03 au 2026-07-23 (fin de session) — 11 scénarios sur 16

| Scénario | Env. | N | Résultat |
|---|---|---|---|
| E03-01 | Simu | 6 | PASS |
| E03-02 | Simu | 5 | PASS |
| E03-03 | Simu | 5 | PASS, persistance max 343 ms |
| E03-04 | Simu | 5 | PASS |
| E03-06 | Réel | 5 | PASS, persistance max 139 ms |
| E03-07 | Réel | 5 | Détection/réaction 26..194 ms |
| E03-08 | Réel | 5 | Détection/réaction 0..137 ms |
| E03-09 | Réel | 5 | Détection/réaction 25..93 ms |
| E03-11 | Réel | 5 | PASS, sortie VS 28..100 ms |
| E03-13 | Réel | 3 | PASS, détection 0..43 ms, persistance 0 ms (couche non critique) |
| E03-05 | Réel | 7 | Crash : commande active 5..81 ms avant la mort, persistance nulle par construction |

RESTANTS : 10, 12, FS1, FS2, FS3 — **tous exigent le VOL** (impossibles au banc). Les essais
au banc E-03 sont donc CLOS.

⚠ Les campagnes portent sur des `apk_hash` DIFFÉRENTS (le binaire a évolué en cours de
journée à chaque correctif). Avant dépôt, rejouer l'ensemble sur le binaire final — ou
consigner scénario par scénario l'empreinte correspondante. La seconde option est
acceptable si chaque tableau porte son `apk_hash`, mais la première est plus solide.

### Position 2D découplée du RTK voiture (2026-07-23) — PRÉREQUIS VOL

Le bit de sécurité `PF` (`dronePositionFresh`) était alimenté par le RTK de la VOITURE pour
les deux modes. En 2D (100% YOLO, suit les joueurs pas la voiture), sans voiture → PF=0 →
l'arbitre bloque TOUTE émission 2D. Masqué par la dérogation banc ; `TEST_E03_BANC=false`
l'aurait révélé au premier vol (`raison="soccer bloque : position_drone_perimee"`).
→ `capturerSnapshotSecurite(..., mode2D)` : **2D = position PROPRE du drone**
(`SafetyPosition2D.positionFraiche` : `gpsValide && satellites≥SAT_MIN_AUTO(14)`), RAIL
inchangé (RTK voiture). Garde-fou corrigé, pas retiré : seuil du vol auto. Pur + testé
(`SafetyPosition2DTest`, 5 cas). Lève la réserve §5 de l'addendum. En extérieur GPS≥14 → ne
bloque plus les essais en vol.

### À CONCEVOIR PLUS TARD — escalade de sécurité (mode AUTONOME, pas les essais)

Idée de Christian (2026-07-23), à faire APRÈS les essais en vol, PAS à bricoler avant.

CONSTAT : sur échec/arrêt d'urgence, l'app fait « commande neutre (stationnaire) + coupe VS
+ rend la main à la RC » (`arretUrgence`). C'est le comportement QUALIFIÉ au dossier, et il
est correct **quand un pilote tient les manches**. Mais pour le PRODUIT autonome (drone qui
filme seul, sans pilote), rester en stationnaire jusqu'à batterie vide n'est PAS sûr.

À CONCEVOIR : une **escalade** — échec → stationnaire → si personne ne reprend en N secondes
→ **RTH** (retour au point de départ), PAS un atterrissage en place.
- ⚠ NE PAS faire d'atterrissage « sous le drone » : descente aveugle sur terrain/gens/eau.
  Le RTH monte à l'altitude de retour et se pose à un point CONNU. C'est la seule variante sûre.
- Réglages DJI (altitude RTH, home point) sont un PRÉREQUIS, pas un substitut.
- À faire proprement : logique pure testable (compteur de temps sans reprise), tests JVM,
  et documentation dossier (c'est un NOUVEAU comportement de sécurité, à qualifier).
- Ne concerne PAS les essais en vol E-03 actuels : ceux-ci gardent stationnaire + reprise
  manuelle (pilote présent). L'escalade est une couche du mode autonome, séparée.

### VOL RÉEL 2D découplé du +0.2 forcé (2026-07-23) — PRÉREQUIS VOL, sécurité

CONSTAT (avant les essais en vol) : `SOCCER_2D_REAL_ENABLED = TEST_E01_SIGNE_THROTTLE`, et le
throttle 2D était **forcé à +0.2** (montée continue) quand ce drapeau valait true. Voler avec
= **montée en fugue** dès l'armement soccer 2D. Aucun état ne permettait un vol contrôlé :
soit +0.2 forcé (dangereux), soit émission inerte (vMax=0). L'app n'avait JAMAIS volé avec un
asservissement d'altitude réel.

CORRECTIF — nouveau drapeau `SOCCER_2D_VOL_REEL` (défaut false), découplé de
`TEST_E01_SIGNE_THROTTLE` :
- `SOCCER_2D_EMISSION_ACTIVE = TEST_E01_SIGNE_THROTTLE || SOCCER_2D_VOL_REEL` (profil personne,
  boucle 2D, miroir, émission — tout ce qui était gaté sur E-01 pointe maintenant ici).
- Throttle : `VOL_REEL -> throttle2Dcalcule` (asservissement, PRIORITAIRE) ; `E01 -> 0.2f` (sol
  uniquement) ; sinon calculé. **Jamais +0.2 en vol**, même si les deux drapeaux sont true.
- `SOCCER_2D_MAX_VSPEED_MPS` : VOL_REEL = **0.5 m/s** (prudent, premiers vols) ; E01 = 0.2 ; sinon 0.
- Le calculé est borné à ±vMax par `soccerAltThrottle` ET par `Emission2DGuard`.

CONFIG POUR LES ESSAIS EN VOL : `SOCCER_2D_VOL_REEL=true` (laisser `TEST_E01_SIGNE_THROTTLE`
à false de préférence), recompiler. Montée PROGRESSIVE obligatoire : stationnaire (throttle ~0)
→ micro-mouvements bornés → seulement ensuite les scénarios de cessation. ⚠ Remettre
`SOCCER_2D_VOL_REEL=false` après la campagne. C'est un NOUVEAU comportement de vol autonome, à
qualifier comme tel au dossier.

### RESTE À FAIRE (l'essai lui-même)
1. Consigner au CAHIER : type de câble, port, SN du téléphone (non automatisables).
2. Passer `TEST_E03_CESSATION_VS=true`, `TEST_E01_SIGNE_THROTTLE=true` (throttle non nul
   requis pour E03-10/FS3), `TEST_E03_SIMULATEUR=true` pour E03-01..04 puis `false`
   (recompilation) pour les 12 scénarios « RÉEL ».
3. Campagne au banc, N≥5 répétitions par scénario, hélices retirées.
4. REMETTRE LES 4 DRAPEAUX À `false` après la campagne (`TEST_E03_CESSATION_VS`,
   `TEST_E01_SIGNE_THROTTLE`, `TEST_E03_SIMULATEUR`, `TEST_E03_BANC`).
   ⚠ SÉCURITÉ : le vol à 1,5 m du 2026-07-22 n'a été sans conséquence QUE parce que le VS
   n'était jamais actif (l'app ne pouvait rien envoyer). Le mode banc supprime cette
   protection accidentelle → hélices retirées, sans exception, pour tout essai E-03.
5. FAIT — suite complète après `clean` (2026-07-22) : **77 classes · 786 cas · 0 échec**
   (v58 : 74 classes / 731 cas → +3 classes, +55 cas).
   Archive `Preuves_Tests_Logiciels_2026-07-22.zip`,
   SHA-256 `34411C6EA62A21BFBAE38CBFE589AC972C9D3148CB37386C4023C658DCC342E1`.
   Reportés dans `docs/ADDENDUM_DOSSIER_v60_E03.md` §1.4 et §1.5.
   ⚠ Toute modification de `SafetyLimits` invalide À LA FOIS le CONFIG_ID et ce condensat.

### Test périmé révélé (2026-07-22)
`DjiLiveStreamEngineTest.echec_demarrage_revient_a_idle` attendait `Idle` alors que le
moteur pose délibérément `Erreur(raison)` — pour EXPOSER la cause DJI au lieu de retomber
en silence. Le test ne tournait plus depuis un moment : le fichier ne compilait pas
(`diffuseDeja` manquant au faux), ce qui MASQUAIT l'écart. Test réaligné sur le contrat
réel + ajout d'un test de la sonde `diffuseDeja` (arrêt préalable si un flux DJI tourne
déjà — cas réel rencontré avec le RTSP, non couvert jusqu'ici).
LEÇON : lancer la suite COMPLÈTE, pas seulement les tests ciblés — une erreur de
compilation dans un fichier de test masque silencieusement tous ses cas.

## Écrans d'attente /live/{user} — 3 modèles (FONCTIONNE, 2026-07-21)

Page publique `/live/{user}` (dans `stream_key.py`) propose 3 écrans d'attente au choix.
Choix via menu déroulant dans Diffusion (formulaire `static/preview3d.html`).

- **Modèle Standard** (défaut) : codé DANS `stream_key.py` (bloc `else` de `page_live`) —
  drone SVG animé + compte à rebours + média (image/carrousel/vidéo). INCHANGÉ.
- **Modèles Télé + Sport** : dans `ecran_attente.py` (NOUVEAU module serveur, PAS dans git —
  copie de secours `ecran_attente.py.ok_*`). Fonction `contenu_variante(modele, titre, heure,
  desc, equipe_a, equipe_b, media_html)` → renvoie le HTML, ou `None` si standard.
  - Télé : bande rouge, compte à rebours, bandeau défilant.
  - Sport : écussons 2 équipes, VS, coup d'envoi. Équipes = champs formulaire OU extraites
    du titre (« A vs B »).
  - Les 2 réutilisent `bloc_img` (média) en FOND plein écran + voile sombre (pas de drone).

### Champs stream_keys.json ajoutés
`modele_attente` ("standard"|"tele"|"sport"), `equipe_a`, `equipe_b`.
Lus/écrits dans `Entree`, `post_stream_key`, `get_stream_key` (stream_key.py) + formulaire.

### Comportements clés
- Heure dépassée : les 3 modes affichent « Le direct va commencer » (plus de 00:00 figé,
  plus de reload en boucle) + rechargement lent 30 s pour capter le vrai direct.
- Bilingue FR/EN : tous les libellés fixes ont `data-fr`/`data-en` (bascule via bouton EN).
- Titre/desc/équipes NON traduits (contenu utilisateur).

### Partage — QR + réseaux sociaux (dans le bloc #cfDiffPartage du formulaire)
QR code du lien `/live/{user}` (lib qrcodejs via jsdelivr, généré navigateur) + bouton
« Télécharger le QR » + boutons Facebook / WhatsApp / X / Messenger. Pas de CSP sur le site.
Messenger utilise un app_id générique (à remplacer par le vrai app_id FB si besoin).

### Limites serveur (nginx + app)
- `client_max_body_size 50M` (nginx, `/etc/nginx/sites-available/cineflight`) ; `MAX_VID=50 Mo`
  dans stream_key.py. Vidéo > 50 Mo → 413. Message clair côté JS.
- Messages d'erreur clairs : vidéo trop lourde (413/400), session expirée (401).

### Intro + nettoyage formulaire Diffusion (2026-07-21)
- Intro FR (pas de bilingue sur page FR) sous « DIFFUSION EN DIRECT » : 1 phrase + 3 puces
  expliquant clé / régie / lien. Encadré jaune « À refaire avant chaque live » SUPPRIMÉ (faux
  depuis l'OAuth auto).
- Champ 3 « Lien de visionnage » CACHÉ (display:none, id `cfDiffWatch` gardé pour ne pas
  casser le JS) + puce n°3 retirée. Le lien vient de l'OAuth serveur, plus de saisie manuelle.

### Patchs Python idempotents (appliqués sur le VPS, gardés dans web_legal/)
`patch_ecran_attente.py`, `patch_formulaire.py`, `patch_media_fond.py`, `patch_msg_video.py`,
`patch_msg_401.py`, `patch_partage_social.py`, `patch_rebours_standard.py`,
`patch_intro_diffusion.py`, `patch_suppr_avert_jaune.py`, `patch_cacher_lien.py`.
Chacun fait une sauvegarde `.avant_*` avant modif et vérifie la syntaxe.

## AUDIT MISSIONS KMZ (plan souvenir) — RÉSOLU (2026-07-25)

« Les missions KMZ ne fonctionnent pas / le plan souvenir ne peut être lu et exécuté. »
AUDIT maillon par maillon, avec MESURES :
- Serveur /api/souvenir : SAIN (testé curl → plan_id + 35 waypoints propres).
- /api/kmz : SAIN (KMZ 5,1 Ko, wpmz/waylines.wpml + template.kml).
- Parseur `LecteurMissionKmz.parserWpml` : SAIN (rejoué en Python avec les MÊMES regex
  sur le KMZ réel → 34/34 waypoints, altitudes/vitesses/nacelle/types corrects).
- ⚠ CAUSE : `executerVol` passait par `ExecuteurMissionWpml` = missions wayline NATIVES
  (`WaypointMissionManager.pushKMZFileToAircraft`) — **réservées aux drones ENTERPRISE**
  par le MSDK v5. Sur Mini 4 Pro (grand public), l'upload échoue TOUJOURS.
→ CORRECTIF : `CapacitesDrone.supporteWaylinesNatives()` (fail-closed) + aiguillage dans
`executerVol` : drones grand public → `executerVolVirtualStick()` = le MÊME moteur que la
simulation (`LecteurMissionKmz`, éprouvé) branché sur le VRAI pilote : décollage auto si
au sol, parcours tracé sur la carte, dialogue de progression, bouton ARRÊT + RTH.
L'exécuteur natif est CONSERVÉ pour les drones enterprise. La simulation est inchangée.
⚠ À ÉPROUVER en SIMULATEUR DJI d'abord (bouton Simuler), puis vol réel court.
NOTE : le KMZ serveur déclare droneEnumValue=68 (M3E) — sans effet sur le chemin VS
(on parse nous-mêmes), à corriger côté serveur si un enterprise l'exécute un jour.

## PANORAMA EN VR + VISITE VIRTUELLE MULTI-POINTS (2026-07-26)

Un 360 aplati sur un écran perd tout son intérêt : le medium naturel est le casque.
DEUX pages web servies par le VPS (rien à installer côté client, UN lien pour tous les
supports : Quest en WebXR, téléphone au gyroscope, PC à la souris) :
- `_serveur_stream_key/pano_vr.py` → `GET /vr/{job_id}` : un panorama simple.
  L'app expose le lien après assemblage (`PanoramaAssemblage.dernierLienVr`) et propose
  « Voir en 360 / Partager le lien / Partager l'image ».
- `_serveur_stream_key/visite_vr.py` → `POST /api/visite`, `GET /visite/{id}` : VISITE
  VIRTUELLE — plusieurs panoramas reliés, téléportation entre points de vue (principe
  Matterport, en aérien). En casque : repères au sol que l'on FIXE du regard (curseur
  fuse) ; ailleurs : boutons. Les images ne sont PAS recopiées (chaque point pointe vers
  `/api/panorama/{job_id}/image`) → aucun stockage en double.
- `cine/VisiteMultiPoints.kt` (PUR, 8 tests) — planification et REFUS motivés :
  2..6 points, espacement ≥ 15 m (sinon panoramas identiques), parcours ≤ 900 m
  (retour batterie), altitude 10..90 m, ordre par plus proche voisin depuis le décollage
  (prévisible pour le pilote, contrairement à un optimum opaque). Durée estimée =
  montée + panoramas + transits + stabilisations, pour AVERTIR, pas pour promettre.
DÉPLOYÉ ET VÉRIFIÉ (2026-07-27) : `pano_vr.py` en place, branché dans `app.py`
(après `conditions_router`), testé sur un vrai panorama → visible à la souris et en casque.
⚠⚠ DÉFAUT SERVEUR TROUVÉ AU PASSAGE — `_PANO_JOBS` vit en MÉMOIRE : après un redémarrage
du service, `/api/panorama/{id}/image` rendait **404 alors que `panorama.jpg` existait sur
le disque**. Conséquence : tout lien VR envoyé à un client aurait cessé de fonctionner au
premier redémarrage. → CORRIGÉ : l'endpoint sert le fichier dès qu'il est présent sur
disque (repli avant la consultation mémoire). Sauvegarde `app.py.avant_repli`.
LEÇON : un identifiant partagé à un tiers ne doit JAMAIS dépendre d'un état en mémoire.
VISITE VR DÉPLOYÉE ET VÉRIFIÉE (2026-07-27) : `visite_vr.py` en place, branché après
`pano_vr_router`. Visite d'essai créée avec 3 panoramas existants → navigation et
téléportation confirmées au navigateur ET en casque (repères fixés du regard).
⚠ RESTE À FAIRE : brancher l'exécution multi-points dans MainActivity (enchaîner
transit → panorama à chaque point) pour que la visite devienne un BOUTON de l'app —
aujourd'hui la visite se crée par appel manuel à `POST /api/visite`.
⚠ ACCÈS : SSH depuis le PC de Christian expire (port 22 filtré par son réseau — ni
fail2ban, ni UFW). Déploiements par la console DigitalOcean ; les gros collages y sont
CORROMPUS → passer par des blocs courts (`cat > f << 'FIN'`) et vérifier par
`python3 -m py_compile`.

## RELIEF STÉRÉO + MODÈLE 3D (2026-07-27) — « je veux les deux »

### Relief stéréoscopique (hyperstéréo aérienne)
`lancerReliefStereo` : panorama en A → translation EST de `STEREO_BASE_M = 2 m` (lente,
`allerA(tolM=0.6, vMaxMps=0.8)`) → panorama en B. **Les deux panoramas partagent un cap de
départ FIGÉ** (`demarrerBouclePanorama(capDepartForce=)`) — sans ça les deux sphères sont
décalées en rotation et le cerveau ne fusionne pas.
- Ligne de base : règle d'usage aérienne ≈ distance/30 (6,5 cm d'écart oculaire ne donnent
  AUCUN relief à 50 m). 2 m = compromis prudent ; trop grand → « effet maquette ».
- L'écart RÉELLEMENT mesuré est journalisé sans être corrigé : c'est lui qui détermine
  l'intensité du relief, et il servira à régler la base au vol suivant sur une MESURE.
- ⚠ LIMITE À DIRE AU CLIENT : le relief n'existe que PERPENDICULAIREMENT à la ligne de base.
  Dans l'axe du décalage, parallaxe nulle → image plate. Un 360×180 stéréo complet exige un
  rig tournant au centimètre ; un drone dérive de mètres. Écarté sciemment.
- Serveur `_serveur_stream_key/stereo_vr.py` → `POST /api/stereo`, `GET /vr3d/{id}`.
  Deux sphères three.js sur les calques 1 et 2 ; en WebXR chaque œil n'active que le sien.
  Hors casque : œil gauche SEUL (superposer donnerait une image double, pas du relief).
  Refuse `job_gauche == job_droit` (aucune parallaxe → faux relief).

### Modèle 3D photogrammétrique
`cine/CaptureOrbite3D.kt` (PUR, 11 tests) — orbites concentriques, caméra vers le centre.
- **Le recouvrement ne dépend PAS du rayon**, seulement du pas angulaire (le R se simplifie) :
  s'éloigner n'améliore rien. 18 clichés/tour = 20° = 80 % de recouvrement (minimum 70).
- **L'altitude DÉCOULE de l'angle** : `R·tan(|pitch|)`, sinon le sujet sort du cadre sur
  tout l'anneau. 3 anneaux (−25/−45/−65°) — un seul anneau donne un modèle creux.
- Refus motivés : rayon 12..80 m, altitude 12..90 m, ≤120 clichés, recouvrement ≥70 %.
- `executerCapture3D` : photo À L'ARRÊT à chaque station (une orbite continue donne du flou
  de filé qui détruit la mise en correspondance). Passe par `declencherPhotoConfirmee`
  (chaîne durcie -472). Interruption si > 1/4 des prises refusées.
- Serveur `_serveur_stream_key/modele3d.py` → `POST /api/modele3d`, `GET /modele3d/{id}`.
  ⚠⚠ **Le droplet ne fera probablement PAS le calcul** : COLMAP+OpenMVS sur 54 photos 48 Mpx
  = 8-16 Go de RAM, plusieurs heures. Le module MESURE `/proc/meminfo` et, sous 7 Go, met
  l'état à `en_attente_ressources` en CONSERVANT les photos, au lieu de lancer un calcul que
  l'OOM killer tuera sans message. La page dit exactement ce qui manque.

### MÉNAGE DES CORRECTIFS (2026-07-28) — inventaire, git local, fichiers entiers

Une vingtaine de correctifs empilés sur `app.py` et `cine_panorama_stitch.py` : chaque
nouveau patch devait deviner ce que les précédents avaient laissé. C'est ce qui a produit
la parenthèse fausse et le `File` manquant (502 sur tout le site).

**1. INVENTAIRE AVANT TOUT** : `_serveur_stream_key/etat_serveur.py` — cherche le MARQUEUR
que chaque correctif laisse dans les fichiers, rend appliqué/MANQUE, détecte la forme
cassée de la parenthèse. À lancer avant de figer quoi que ce soit, et après tout doute.

**2. GIT LOCAL SUR LE SERVEUR** (`/root/cineflight_web/.git`, jamais poussé) : historique,
diff et retour arrière pour `app.py`, qui ne peut PAS aller sur le dépôt GitHub — le dépôt
est PUBLIC et app.py peut porter des secrets (JWT CineAuth notamment). `.gitignore` serveur :
tokens, stream_keys.json, `_pano_jobs/`, `modeles3d/`, `.venv/`, `*.avant_*`.
Les dizaines de `app.py.avant_*` sont remplacées par l'historique git (déplacées dans
`anciennes_sauvegardes/`, non suivies).

**3. `cine_panorama_stitch.py` CANONIQUE DANS LE DÉPÔT GitHub** (aucun secret, déjà servi
à l'atelier par `/api/atelier/assembleur`). Récupéré depuis le serveur par cet endpoint
même, vérifié par SHA-256 des deux côtés. Déploiement futur : éditer dans le dépôt → push →
`curl` du fichier ENTIER sur le serveur → `sha256sum` → aucun redémarrage (l'atelier le
retélécharge à chaque travail). **Plus de patch sur ce fichier.**

**4. `app.py` : les patchs restent le véhicule** (la console DigitalOcean corrompt les gros
collages), mais avec le git local ils deviennent vérifiables (`git diff` après application)
et réversibles (`git checkout`). Chaque application = un commit local.

RÈGLE DE VALIDATION inchangée : un déploiement se valide par un essai de bout en bout,
jamais par la compilation — et les erreurs d'import se lisent avec
`python -c "import app" > /dev/null` (jeter stdout, garder stderr).

### DÉPLOIEMENT SERVEUR — passer par GitHub, PAS par la console (2026-07-27)

DÉPLOYÉ ET VÉRIFIÉ : `/vr3d/x` et `/modele3d/x` rendent 404 (route présente, objet absent).

⚠ MÉTHODE DE DÉPLOIEMENT CHANGÉE. La console DigitalOcean **rejette les collages longs
EN ENTIER** (pas partiellement) : un bloc base64 de 2500 caractères n'a jamais atteint le
shell, `/tmp/*.b64` n'existait même pas, et `base64 -d` d'un fichier absent a produit un
fichier VIDE. Piège : `python3 -m py_compile` a répondu **OK sur ce fichier vide** (un
fichier vide est un module valide) — seule l'empreinte `e3b0c442…` (SHA-256 du vide) a
révélé la panne. **Ne jamais valider un transfert par la compilation seule ; toujours
`sha256sum`.**
→ Le dépôt `github.com/manitonga24-prog/cineflight` est PUBLIC, branche par défaut
`feature/voice-phase-2-sdk-observation`. Nouvelle procédure, 4 commandes courtes :
`git add <fichiers nommément>` + push depuis le PC, puis sur le VPS
`curl -fsSL -O <raw.githubusercontent…>` + `sha256sum` + `python3 patch_brancher_3d.py`.
`git add .` est PROSCRIT (dépôt public — ne jamais y pousser `youtube_oauth_config.json`,
`youtube_tokens.json`, `stream_keys.json`).
`patch_brancher_3d.py` : idempotent, fail-closed si l'ancre `visite_vr_router` manque,
`compile()` du résultat AVANT écriture, sauvegarde `app.py.avant_3d`.

⚠ SSH port 443 : IMPOSSIBLE, nginx l'occupe (site en HTTPS). Et sur Ubuntu récent la
directive `Port` de `sshd_config` est IGNORÉE tant que `ssh.socket` (activation par socket)
est active — d'où « sshd n'écoute que 22 » malgré la config. Piste abandonnée : GitHub
règle le besoin.

### ⚠ DROPLET 1 vCPU / 1 Go — le serveur SERT, il ne CALCULE PAS
Nom de machine : `ubuntu-s-1vcpu-1gb-35gb`. COLMAP demande 8-16 Go. `modele3d.py` MESURE
`/proc/meminfo` et met l'état à `en_attente_ressources` en CONSERVANT les photos.
Le relief stéréo, lui, n'exige aucun calcul lourd → OK ici.

### ATELIER PC — file de travaux automatique (2026-07-28, DÉPLOYÉ ET VÉRIFIÉ)
Le PC (RTX 3090) interroge le serveur ; le serveur ne l'appelle JAMAIS → aucun port à
ouvrir chez Christian (c'est ce qui bloquait SSH).
- Serveur : `patch_travaux_ouvrier.py` → `GET /api/travaux` (jeux de photos en attente) et
  `GET /api/travaux/{id}/photos.zip` (archive en flux, mémoire bornée). Jeton partagé
  `X-Cine-Ouvrier` lu dans `ouvrier_token.txt` (chmod 600, .gitignore) — **sans fichier,
  503 : fermé par défaut**, un oubli de config n'expose jamais les photos clients.
- PC : `_atelier/ouvrier_cineflight.py` — interroge chaque minute, télécharge dans
  `~\CineFlight_Atelier\<id>\photos`, bip, affiche la commande curl de dépôt du .glb.
  Ne supprime RIEN ni côté serveur ni côté PC ; un travail reste listé tant que le
  maillage n'est pas déposé. Jeton via `CINE_OUVRIER_JETON` ou `jeton.txt`.
  Windows : la commande est `python` (PAS `py`), Python 3.12 installé.
- ⚠ PIÈGE FastAPI corrigé : annoter `request: Request` échoue si `Request` n'est pas dans
  l'espace de noms d'app.py (FastAPI le prend pour un paramètre de requête → 422).
  → `from starlette.requests import Request as _OuvrierRequest` DANS le bloc injecté.
  Le test à blanc ne l'avait pas vu : le faux app.py importait Request, le vrai non.
  LEÇON : un faux d'essai doit reproduire aussi les IMPORTS du fichier cible.
- Vérifié : `{"travaux":[]}` avec jeton ; flux complet éprouvé contre un faux serveur
  (téléchargement, décompression atomique `_photos_partiel` → `photos`, idempotence,
  refus du mauvais jeton).

### PANORAMAS ASSEMBLÉS SUR LE PC (2026-07-28) — le serveur ne calcule plus rien

DÉCLENCHEUR MESURÉ : `cpfind` sur 61 photos a occupé **1,26 Go et 15 min de processeur**
sur le droplet (1 vCPU) sans finir — et ce n'était que la première des six étapes. Pendant
ce temps le site ne servait plus personne.
→ Même architecture que la 3D : `GET /api/travaux_pano`, téléchargement photo par photo,
jeton `X-Cine-Ouvrier`, dépôt par `POST /api/travaux_pano/{id}/resultat`.
- **L'assembleur est SERVI par le serveur** (`GET /api/atelier/assembleur`), pas recopié :
  `cine_panorama_stitch.py` a reçu 5 correctifs successifs, une copie sur le PC divergerait
  au premier suivant. Vérifié en sortie : `enblend -f 8192x4096` est bien présent.
- **Fichier-témoin** `assemblage_sur_pc.flag` : présent → le serveur reçoit et s'arrête ;
  absent → comportement d'origine. Sans le fichier RIEN ne change — un déploiement
  incomplet ne peut pas laisser les panoramas sans personne pour les assembler.
- **Dépôt refusé si l'image n'est pas en 2:1** (mesuré côté serveur, pas promis côté PC).
- ⚠ Hugin est ENTIÈREMENT PROCESSEUR : la 3090 n'y sert à rien. Le gain vient des cœurs et
  de la mémoire. La carte sert à l'autre file, RealityScan, où CUDA est obligatoire.
**RÉSULTAT VÉRIFIÉ** : 25 photos → 8192×4096 en **3 min 03 s**, déposé automatiquement,
enchaînement immédiat sur le travail suivant. Le droplet n'y était jamais arrivé.
61 photos avec angles → **21 min 39 s**, voie `angles_seuls` prise comme prévu.

### PREMIÈRE PREUVE DU PRÉRÉGLAGE « CIEL COMPLET » (2026-07-28)
Mesuré sur deux panoramas assemblés à la suite :
- ancien préréglage (25 photos, nacelle bridée à +30°) → **`Fill sky 59,1 %`** : plus de la
  moitié de la sphère était du remplissage inventé, pas de la photo.
- préréglage ciel complet (61 photos, nacelle à +60°) → **`Fill sky 9,0 %`**.
Le plafond de nacelle porté à +60° était donc bien le défaut, et le correctif tient. À
retenir pour les clients : c'est ce qui distingue un ciel réel d'un ciel comblé.

### ⚠⚠ UN PLANTAGE D'OUTIL A LIVRÉ UNE SPHÈRE BASCULÉE (2026-07-28)

Deux panoramas d'une même paire stéréo, 61 photos, mêmes angles, assemblés à la suite :

| travail | voie | ciel comblé | résultat |
|---|---|---|---|
| `277a3c293f89` | `angles_seuls` | **9,0 %** | horizon juste |
| `eca54f3db18e` | `angles_optimises` | **30,7 %** | **sphère basculée** |

CAUSE : `!! tentative angles_seuls abandonnee : Echec enblend (code 3221225477)`.
`0xC0000005` = violation d'accès mémoire — **enblend n'a pas refusé, il s'est planté**. Le
repli a pris le relais, et `autooptimiser -n` a retouché la géométrie jusqu'à faire pivoter
la sphère : exactement « l'horizon en tente » du 2026-07-27.

⚠ ET RIEN NE L'A VU. Les seuls contrôles en aval sont « le fichier existe » et « il fait
2:1 » — une sphère pivotée passe les deux. L'indicateur existait pourtant, imprimé à
l'écran : 9 % contre 30,7 % de ciel comblé, même lieu, même préréglage. Encore une fois la
FORME était vérifiée et pas le RÉSULTAT.

→ `patch_pano_reessai.py`, deux correctifs :
1. **Réessayer un PLANTAGE, jamais un REFUS.** Un petit code non nul = l'outil a jugé et
   rejeté, le refaire ne changerait rien. Un signal (code négatif, Unix) ou une exception
   native (≥ 0xC0000000, Windows) = il est mort sans juger — ça vaut une seconde tentative.
   Réessayer aveuglément doublerait l'attente sur chaque vraie erreur.
2. **Un repli de voie se DIT** (`!! REPLI D'ASSEMBLAGE`). Sur une paire stéréo, deux yeux
   assemblés par des voies différentes ne fusionnent pas ; ça doit apparaître au journal,
   pas dans le casque.

⚠ NON FAIT, à décider à froid : REJETER un résultat dont le ciel comblé est anormalement
haut. C'est le bon indicateur, mais il n'est connu qu'après le rendu complet — rejeter
imposerait 20 min de plus dans une autre voie. Décision de conception, pas correction.

### ⚠⚠ CORRECTION DU DIAGNOSTIC CI-DESSOUS (2026-07-28, plus tard) — c'était MON code

Ce qui suit accusait `--wrap` manquant. **C'était faux**, et la mesure sur les couches de
PRODUCTION l'a établi :

| commande | résultat |
|---|---|
| d'origine | plantage `0xC0000005` |
| `--wrap=horizontal` | plantage |
| **`--no-optimize`** | **OK, 69 Mo** |
| **`--wrap` + `--no-optimize`** | **OK, 69 Mo** |

`--no-optimize` suffit, et `--wrap` n'est ni cause ni obstacle. **La vraie cause était un
défaut de mon propre correctif** : `patch_pano_enblend_secours.py` construisait les paliers
en écrivant `list(cmd) + ["--no-optimize"]`. Or `cmd` finit par les 61 NOMS DE FICHIERS —
l'option arrivait donc APRÈS eux, et `enblend` ne l'appliquait pas. Les deux paliers
rejouaient la commande d'origine et échouaient forcément, faisant abandonner une géométrie
juste au profit d'une sphère basculée, trois fois de suite.
→ `patch_pano_secours_ordre.py` : les options s'insèrent après l'EXÉCUTABLE.
`--wrap` est CONSERVÉ — il reste le réglage juste pour un équirectangulaire.

**RÉSOLU ET VÉRIFIÉ (2026-07-28)** : `!! enblend a PLANTE — nouvelle tentative :
--no-optimize` puis `abouti avec : --no-optimize`, `VOIE D'ASSEMBLAGE : angles_seuls`,
**`Fill sky 9,0 %`** — au dixième près le chiffre de l'œil jumeau `277a3c293f89`. Même
lieu, même préréglage, même voie, même remplissage : les deux yeux de la paire stéréo sont
enfin dans le MÊME repère. 19 min 11 s pour 61 photos.

⚠⚠ **POURQUOI J'AI CRU LE CONTRAIRE**, et c'est la leçon la plus utile de la journée :
1. Mon essai manuel « réussi » portait sur des couches produites SANS `autooptimiser -m`
   (mode `--rapide` du diagnostic). **Ce n'étaient pas les données de la production.**
2. J'avais vérifié dans un bac à sable que les options étaient bien placées — pour la
   COMMANDE DE BASE, pas pour les variantes que mon correctif construisait.
Vérifier le cas qu'on a en tête et conclure pour tous les autres. Troisième occurrence de
la même erreur en une journée, après la non-régression de l'anneau de façade contrôlée à
un seul rayon et le test d'import qui ne pouvait pas voir le traceback.

### DIAGNOSTIC INITIAL (partiellement erroné, conservé pour la méthode)

**TROIS HYPOTHÈSES SUCCESSIVEMENT RÉFUTÉES PAR LA MESURE**, chacune coûtant ~25 min :
1. « plantage passager » → rejouer la même commande replante à l'identique.
2. « c'est l'optimisation de couture » → `--no-optimize` seul échoue aussi.
3. « c'est une couche dégénérée » → la plus petite fait 1,16 Mo, rien d'anormal.

**LA BISSECTION A TRANCHÉ** (`_atelier/diag_enblend.py`, 7 exécutions au lieu de 61) :
60 premières couches → OK ; en ajoutant la 61ᵉ → plantage. La coupable est
`remap0060.tif`, **8192 × 683** — la largeur ENTIÈRE du canevas. Signature d'une couche
AU PÔLE : au zénith une seule photo s'étale sur les 360° de longitude. C'est la prise que
le préréglage « ciel complet » ajoute en dernier — d'où un défaut qui n'apparaît QUE sur
les panoramas à ciel complet.

**LA CAUSE EST UNE CONJONCTION**, ce qui explique pourquoi chaque hypothèse prise seule
était fausse : sans `--wrap`, la couture au méridien 0°/360° est traitée comme un bord
d'image ; sans `--no-optimize`, l'optimiseur de Dijkstra la suit hors du cadre et l'accès
mémoire part dans le vide. Mesuré : `--wrap` seul → sortie de 8 octets ; `--no-optimize`
seul → plantage ; **les deux → 69 Mo, 8192×4096, les 61 couches fusionnées**.

→ `patch_pano_wrap.py` ajoute `--wrap=horizontal` à la commande de BASE. Les paliers de
secours ajoutent `--no-optimize` quand il le faut, donc seulement sur les jeux à pôle.
⚠ **CE N'EST PAS QU'UN CONTOURNEMENT** : `--wrap` est le réglage JUSTE pour un
équirectangulaire. Tous les panoramas assemblés jusqu'ici avaient leur raccord 0°/360°
mélangé comme un bord d'image — invisible sur un ciel uni, visible sur un horizon net.

LEÇON : trois suppositions n'ont rien donné, la bissection a donné la réponse en sept
essais. Quand une hypothèse est démentie deux fois, arrêter d'en formuler une troisième et
construire l'instrument qui mesure.

### ⚠⚠ `File` NON IMPORTÉ — tout le site à terre (2026-07-28)

`patch_travaux_pano.py` écrivait `file: UploadFile = File(...)`. `UploadFile` est bien dans
app.py, `File` NON — et ces noms sont évalués À L'IMPORT DU MODULE. `NameError` au
démarrage, service en boucle d'échec (19 redémarrages), **502 sur tout le site**.
⚠ MÊME FAMILLE que le piège `Request` du 2026-07-27, déjà documenté : « un bloc injecté ne
doit rien supposer des imports du fichier cible ». J'avais vérifié un des deux noms.

⚠⚠ **ET LE DIAGNOSTIC A TRAÎNÉ SUR UN TEST QUI MENTAIT.** `python -c "import app" 2>&1 |
tail -25` a répondu « tout va bien » DEUX fois (Python système, puis venv). Faux : la trace
part sur stderr (non tamponné), les `print` sur stdout (tamponné quand redirigé) et se
vident À LA SORTIE — ils chassent le traceback hors des dernières lignes.
RÈGLE : pour voir une erreur d'import, **jeter stdout** — `python -c "import app" > /dev/null`.
Le silence vaut réussite. J'avais construit un test structurellement incapable de voir ce
qu'il cherchait, puis conclu de son silence.
⚠ AU PASSAGE : le service tourne avec `/root/cineflight_web/.venv/bin/python` — la note
« Python SYSTÈME, pas de venv » plus haut dans ce fichier est PÉRIMÉE.

### ⚠⚠ CLOUDFLARE : 100 Mo PAR REQUÊTE (2026-07-28) — envoi par lots
Un envoi de 249 Mo a été coupé **avant d'atteindre le serveur** : rien dans les journaux
nginx, rien dans ceux du service, et côté client une erreur SSL sans explication. Plan
gratuit Cloudflare = 100 Mo maximum par requête. Un jeu de 96 photos pleine résolution
approche le gigaoctet → la capture 3D aurait échoué SYSTÉMATIQUEMENT, après le vol.
→ `ClientModele3D.decouper()` (app) et `envoyer_jeu.py` (essais) découpent à **70 Mo**
(marge pour l'enrobage multipart). Chaîne : `POST /api/modele3d` (1er lot, rend l'id) →
`/api/modele3d/{id}/ajouter` (suivants) → `/api/modele3d/{id}/finir`.
⚠ Le jeu n'est proposé à l'atelier QU'APRÈS `finir` : un envoi interrompu ne peut pas
partir en reconstruction à moitié.

### ⚠⚠ CETTE GARANTIE ÉTAIT FAUSSE — jeu tronqué reconstruit en silence (2026-07-28)

Écrite dans la documentation, absente du code. `creer_modele` (premier lot) déclarait
`etat="en_attente_ressources"` — l'un des états que `/api/travaux` propose à l'atelier.
Le travail devenait donc visible **dès le premier lot**, pendant que les suivants montaient.
MESURÉ : l'expéditeur annonce `total 30`, l'ouvrier télécharge **11 photos**, reconstruit
dessus et dépose le modèle. Reproduit deux fois de suite (Essai 2, Essai 3), 11/30 chaque
fois. Aucune ligne d'alerte : le seul indice était un décompte dans une trace de progression.
⚠ PORTÉE : la course entre l'envoi et l'interrogation se produit à CHAQUE capture. Le
client aurait reçu un modèle calculé sur un tiers de ses photos, sans avertissement.
→ DEUX correctifs, parce qu'une garantie tenue par un seul bout n'en est pas une :
1. `patch_modele3d_reception.py` — `creer_modele` pose `etat="reception"` et ne lance rien.
   La décision de reconstruire appartient à `/finir`, seul endroit qui sait le jeu complet.
2. `ouvrier_cineflight.py` — RECOMPTE la liste après le transfert. Si elle a grandi, le jeu
   n'était pas complet : rien n'est reconstruit, le travail est repris au tour suivant.
   Une panne réseau sur ce second appel ne condamne pas le travail (on ne juge pas sur
   l'absence de mesure).
LEÇON : un état intermédiaire doit être posé par le code qui l'introduit, pas seulement
décrit dans la documentation. Ce que le commentaire promettait, personne ne l'appliquait.

### ⚠ ARCHIVE EN FLUX ABANDONNÉE (2026-07-28) — transfert fichier par fichier
`photos.zip` construisait l'archive en mémoire par morceaux : après chaque photo il lisait
le tampon puis le TRONQUAIT. Or `ZipFile` suit sa position d'écriture pour composer le
répertoire central — remettre le tampon à zéro détruit ces décalages. Le flux devenait
invalide et le téléchargement mourait à 97 Mo sur 249 (`ChunkedEncodingError`).
Mauvaise idée dès le départ : ni taille annoncée, ni reprise, et une erreur emporte tout.
→ `patch_travaux_fichiers.py` : `GET /api/travaux/{id}/liste` (noms + tailles) et
`GET /api/travaux/{id}/photo/{nom}` (taille annoncée). Chaque photo se retente seule, une
erreur ne coûte qu'elle-même, la progression est juste. Même principe que le rapatriement
depuis le drone, qui a déjà fait ses preuves. Sécurité : `basename` + vérification que le
fichier est bien dans le dossier du travail (sinon `../../etc/passwd`).

### RECONSTRUCTION AUTOMATIQUE PAR RealityScan (2026-07-28) — CHAÎNE BOUCLÉE

`_atelier/traitement.py` enchaîne la CLI documentée d'Epic. Exécutable trouvé en BALAYANT
`Program Files\Epic Games\RealityScan*` (le numéro de version est dans le nom du dossier —
relevé `RealityScan_2.2` ; une liste figée casserait l'atelier à la prochaine mise à jour).
Séquence : `-addFolder -align -selectMaximalComponent -setReconstructionRegionAuto
-calculateNormalModel -selectMarginalTriangles -removeSelectedTriangles -simplify 500000
-calculateTexture -renameSelectedModel -save -exportModel -quit`.
- **500 000 triangles** : un casque autonome tient environ le million pour TOUTE la scène,
  et le rendu stéréo dessine tout deux fois.
- **Texture APRÈS simplification** — l'inverse texture un maillage qu'on va jeter.
- Lancé sur un FIL DE FOND (un calcul dure des heures ; la boucle d'interrogation doit
  continuer), verrou global : **une seule reconstruction à la fois** (une seule 3090).

⚠ **LE CODE DE RETOUR DE RealityScan NE PROUVE RIEN** : il rend 0 même quand rien n'a été
produit. Seule l'existence d'un `.glb` > 1 Kio fait foi. Même famille que le `py_compile`
satisfait d'un fichier vide, et que le `enblend` dont j'avais vérifié la COMMANDE au lieu
du RÉSULTAT.

**Le fichier de paramètres d'export : œuf et poule.** RealityScan n'accepte pour
`-exportModel` qu'un XML issu de sa propre boîte de dialogue, laquelle ne s'ouvre que s'il
existe déjà un modèle. → Mode à DEUX temps : sans `export_params.xml` on tourne
**interface visible** (un dialogue caché est un blocage sans message) ; le premier export
produit un `.glb.rsInfo` dont on extrait le bloc `<ModelExport>` ; avec le fichier, on
passe en `-headless`. `_atelier/export_params.xml` est au dépôt, avec 4 écarts assumés :
`embedTextures 0→1` (sinon la texture part en PNG SÉPARÉ — le serveur ne reçoit que le
`.glb`, le modèle s'affiche GRIS chez le client, sans aucun message : constaté),
`exportToOneTexture 0→1`, `oneTextureMaxSide 8192→4096` (8192 est la limite haute d'un GPU
mobile, s'y coller ne laisse rien au reste), `exportVertexNormals 0→1` (sans normales les
visionneuses glTF recalculent à plat → maillage facetté).

⚠ **Compte Epic** : `-headless` ressort de l'ombre si une fenêtre de connexion s'affiche.
Se connecter une fois dans l'application sur la machine. Abandon après 6 h avec message —
un processus muet qui attend un clic est indiscernable d'un processus qui travaille.

**BOUT EN BOUT VÉRIFIÉ (2026-07-28, 04:45)** : 30 photos → serveur → PC → RealityScan
(18 s, sans interface) → `.glb` 29 Mo texture embarquée → dépôt automatique → affichage sur
`cineflight.ca/modele3d/67f2277a0a99`. Aucune intervention entre les deux bouts.
⚠ **CE QUI N'EST PAS PROUVÉ : la reconstruction elle-même.** Ces 30 photos venaient d'un
PANORAMA — caméra fixe, aucune parallaxe, donc aucune profondeur à reconstruire. Le modèle
obtenu est une bouillie d'éclats, et 18 s est le temps d'un calcul qui n'a rien trouvé.
C'est l'OUTILLAGE qui est validé. Il reste à voler une orbite réelle.

⚠ Défaut corrigé au passage : un travail DÉJÀ rapatrié n'entrait pas dans la liste des
dépôts surveillés (`telecharger` rendait `None` pour « déjà là » comme pour « échec ») →
le maillage n'était jamais envoyé tout seul, il fallait effacer le dossier pour réarmer.
Le dépôt attend deux relevés de taille identiques : RealityScan écrit progressivement, et
un fichier tronqué serait accepté par le serveur sans que rien ne le signale.

### ANNEAU DE FAÇADE — viser la mi-hauteur, pas le sol (2026-07-28)

DÉFAUT DE CONCEPTION, trouvé en répondant à « on peut améliorer ? ». Toute la géométrie de
`CaptureOrbite3D` visait le SOL au centre de l'orbite : une façade verticale n'était donc
jamais photographiée de face, même par l'anneau le plus rasant (−24° au mieux). Or ce qui
n'est vu que de biais se reconstruit en surfaces étirées — visible dès qu'on s'approche
d'un mur dans la visionneuse.
→ `planifier(..., hauteurSujetM)` : le point visé monte à la MI-HAUTEUR du sujet, et
`alt = hauteurCentre + R·tan|pitch|`. `anneauFacade()` place un anneau à cette hauteur,
regard quasi horizontal (mesuré : sujet de 20 m à 30 m de rayon → **−3,8° à 12 m**).
- **Le plancher de 12 m reste souverain** : s'il oblige à monter au-dessus de la mi-hauteur,
  l'inclinaison se creuse d'autant. On ne descend pas pour un angle, près d'un mur moins
  que jamais. Le plafond de 90 m reste absolu lui aussi.
- **DEUX conditions d'ajout** : une hauteur DÉCLARÉE (`hauteurSujetM > 0` — sans elle il n'y
  a pas de façade, juste un anneau rasant visant le sol) ET ≥ 8° de nouveauté angulaire
  (`ECART_ANGULAIRE_MIN_DEG` — sur un sujet bas il tomberait à 2-3° de l'anneau existant :
  18 photos et 1 min 30 de vol pour rien).
  ⚠ La première condition manquait au premier jet : à 50 m de rayon, l'anneau s'ajoutait
  même pour `hauteurSujetM=0` → **120 clichés au lieu de 96**, alors que j'avais annoncé un
  plan inchangé. Démenti par le test existant
  `la_qualite_maximale_quatre_anneaux_reste_realisable_sur_les_trois_rayons`.
  LEÇON (encore) : une non-régression se vérifie sur TOUTE la plage, pas sur le cas qu'on
  a en tête. Je l'avais contrôlée à 30 m et affirmée partout.
- UI : une question « Hauteur du sujet » avant la confirmation, valeur approximative, 0 par
  défaut. Saisie illisible → 0, jamais un plantage ni une hauteur inventée. Le message de
  confirmation annonce le nombre RÉEL d'anneaux (il disait « 3 » en dur).

### PLANCHER ABAISSÉ À 8 m — case à cocher, jamais un défaut (2026-07-28)

Sur une maison ordinaire (8-12 m), le plancher de 12 m force un regard à −11°..−24° : la
façade repart en raccourci, précisément le défaut que l'anneau devait corriger. À 8 m elle
devient quasi frontale (mesuré : sujet 10 m à 30 m de rayon → **−5,7°**).
⚠ MAIS à 8 m le drone vole **sous la cime des arbres et à hauteur des fils** (6-10 m), que
l'évitement d'obstacles ne voit pas fiablement. Le plancher de 12 m ne protège pas du sol :
il protège de ça.
→ `ALT_MIN_FACADE_M = 8.0`, appliqué UNIQUEMENT à l'anneau de façade et SEULEMENT si le
pilote coche la case, vol par vol, après avoir regardé le site. Même doctrine que le mode
banc : une borne de sécurité s'abaisse par une décision humaine tracée. Un plancher global
à 8 m a été ÉCARTÉ — il s'appliquerait aussi aux sites que personne n'a évalués, et une
borne relâchée « en général » ne se resserre jamais.
- Avertissement affiché SEULEMENT à la coche (permanent, il devient du décor).
- `Plan.plancherM` et `Plan.hauteurSujetM` reportés au journal :
  `hauteur_sujet=… plancher=… PLANCHER_ABAISSE=OUI alt_min_plan=…`.
- Fail-closed : toute valeur hors bornes est ramenée dans [8, 12] (test sur −50, 0, 2, 40).

### CARTE AVANT LA CAPTURE — `CarteOrbite3DActivity` (2026-07-28)

Le plan est juste sur le papier ; ce qu'aucun calcul ne connaît, c'est ce qu'il y a AUTOUR.
Écran satellite (osmdroid + tuiles Esri, comme les autres cartes) avec le **cercle rouge**
de la trajectoire et un **point jaune par station** — les points, pas seulement le cercle :
le drone s'ARRÊTE à chacun, et un obstacle sur une station pèse plus qu'un obstacle frôlé
entre deux. Boutons Annuler / Lancer ; le plan est TRANSPORTÉ (jamais recalculé là-bas,
sinon les deux écrans pourraient diverger) et consommé au retour dans les deux cas.
⚠ Écrit à l'écran, pas seulement ici : l'image satellite est ancienne et vue du dessus —
elle ne montre **ni la hauteur des arbres ni les câbles**. Elle aide à décider, elle ne
remplace pas un regard sur le site.

**APERÇU AU SOL** : drone éteint ou posé, la carte s'ouvre centrée sur le TÉLÉPHONE, avec
un bandeau qui le dit et **aucun bouton de lancement** — un cercle qu'on prendrait pour le
plan réel serait pire que pas de carte. Utile pour repérer arbres et fils avant de partir.

⚠⚠ **LE CERCLE AFFICHÉ NE VENAIT PAS DU MÊME CALCUL QUE LE PLAN.** Constaté au premier
essai : les stations tombaient HORS du cercle rouge. La géométrie du plan était pourtant
vérifiée juste (approximation plane et formule géodésique concordent au millimètre à 20 m,
mesuré). C'était le tracé, confié à `Polygon.pointsAsCircle` d'osmdroid, dont le modèle de
Terre n'est pas le nôtre. → `CaptureOrbite3D.pointsCercle()` : le cercle et les stations
partagent désormais la MÊME formule, ils coïncident par construction, et
`le_cercle_affiche_passe_exactement_par_les_stations` l'exige sur 4 rayons.
LEÇON : une carte de sécurité doit être dessinée par le code qui calcule la trajectoire.
Deux sources pour la même vérité, c'est une source de trop.

**DÉFAUTS D'AFFICHAGE corrigés au passage** (deux empilés, le premier masquait le second) :
1. Champ, case et avertissement créés avec le contexte de l'ACTIVITÉ n'héritent pas de
   `DialogCineFlight` → texte sombre sur fond `#1C2126`, invisibles. Couleurs explicites.
2. `MainActivity` est en **PAYSAGE** : un dialogue portant à la fois un long `setMessage`
   ET une vue personnalisée écrase la seconde à zéro — l'écran n'affichait que le titre.
   → plus de `setMessage`, tout dans un `ScrollView`.
- 7 tests JVM ajoutés, dont `le_plancher_d_altitude_reste_souverain_sur_un_sujet_bas` et
  `aucun_anneau_de_facade_quand_il_n_apporterait_rien`.
⚠ NON VOLÉ. Comme tout le mode Modèle 3D.

### AUDIT DU VOL DÉCOUVERTE (2026-07-28) — 3 défauts, tous silencieux

Demandé après avoir constaté qu'AUCUN journal `DECOUVERTE` n'existe (0 sur 49 vols) :
l'instrumentation est pourtant en place, au bon endroit (`lancerVol()`, avant tout).
Le mode n'a donc jamais été mené jusqu'au décollage depuis que le journal existe.

1. **Séquence lancée SANS Virtual Stick.** Après 15 s d'attente, `attendreVsPuisPiloter`
   démarrait la boucle QUAND MÊME : `majVoyantPilote(false)` passait le voyant au rouge et
   l'app envoyait ses commandes dans le vide pendant vingt secondes, sans une ligne au
   journal. Le drone restait en stationnaire → « il ne se passe rien », sans explication.
   → Le délai dépassé ANNULE la séquence, écrit une anomalie et rend la main.
2. **Vidéo jamais arrêtée sur le chemin d'échec.** Démarrée au décollage, elle n'était
   coupée que dans `fin()`. Or `arreter()` est le chemin des échecs ET du passage en
   arrière-plan : un appel entrant laissait la caméra tourner indéfiniment, remplissant la
   carte sans que personne le sache. → Coupée aussi dans `arreter()`.
3. **Montée non aboutie → atterrissage ACCIDENTEL et muet.** Le garde-fou `t < 20.0`
   sortait de la phase de montée ; ensuite `tMonteeFinie` valant −1, toutes les conditions
   suivantes étaient fausses et on tombait dans `else -> atterrir()`. Le comportement final
   était bon, mais atteint par accident et sans un mot. → Condition explicite, anomalie
   journalisée (altitude, durée, cible) et message à l'écran.

Vérifié par simulation : montée normale → séquence complète ; montée très lente ou
throttle sans effet → abandon annoncé à t=20 s.

### REVUE DE CHRISTIAN — nettoyage unique + codes de journal (2026-07-28)

**`nettoyerMission(raison, libererVs)` : SORTIE UNIQUE.** `fin()`, `arreter()` et
`poserAuto()` y passent tous. Motif exact de sa demande : deux chemins censés faire la même
chose finissent toujours par diverger — c'est ce qui avait laissé la vidéo tourner. Ordre
imposé : commandes coupées → vidéo arrêtée → autorité libérée. `libererVs=false` pendant un
atterrissage automatique (le SDK se pose seul, couper l'autorité au milieu n'apporte rien).
VÉRIFIÉ par lecture : `arreterEnregistrement` et `activerVirtualStick(false)` n'apparaissent
plus QU'À L'INTÉRIEUR de `nettoyerMission`. Aucun autre chemin de sortie.

**Codes structurés au journal**, au lieu d'une phrase : `VS_NON_ACCORDE`, `MONTEE_TIMEOUT`,
`ALTITUDE_CIBLE_NON_ATTEINTE`, `BATTERIE_CRITIQUE`, `ATTERRISSAGE_SECURITE cause=…`,
`NETTOYAGE_MISSION raison=… video_coupee=… vs_libere=…`. La phrase explique, le code se
compte — c'est ce qui rend un journal exploitable sur des dizaines de vols.

**`BATT_CRITIQUE = 15 %` pendant la démo.** Le contrôle d'avant décollage (30 %) ne dit rien
de ce qui suit : une cellule fatiguée s'effondre en quelques secondes sous charge.

**Vérifié sans modification** : l'état affiché ne passe jamais à « mission en cours » avant
l'acquittement du VS (`boucle()` n'est plus appelée que sur `pret`, l'écran affiche
« stabilisation » pendant l'attente) ; le plafond est déjà un plafond DUR
(`if (throttle > 0 && alt >= ALT_PLAFOND_M) throttle = 0`), pas une cible.

⚠ RESTE OUVERT — **arrière-plan prolongé**. Aujourd'hui : `onPause` → stationnaire + main
rendue, sans limite de temps. Christian propose un délai court puis une action de sécurité
définie (atterrissage ou RTH). NON IMPLÉMENTÉ volontairement : c'est un NOUVEAU comportement
autonome, qui poserait l'aéronef alors que le pilote a peut-être repris les manches. À
décider et à qualifier, pas à ajouter en passant.

### ⚠⚠ PREMIER ESSAI EN VOL DU MODÈLE 3D (2026-07-28) — 4 ÉCHECS, cause trouvée

Journaux `vol_20260728_0918..0921_MODELE_3D.log` : quatre tentatives, chacune arrêtée en
**moins d'une seconde**, toutes avec la même ligne :
```
!! visite interrompue pendant transit : virtual_stick=false mode_auto=true
```
CAUSE : `executerCapture3D` ne DEMANDE JAMAIS le Virtual Stick. Il n'est demandé que dans
`decoller()` (bouton DÉCOLLER de l'app), dans le panorama, et dans `basculerMode(true)`.
Or la capture 3D EXIGE `pilote.enVol` — elle ne passe donc pas par le chemin de décollage
qui l'aurait obtenu, et si `modeAuto` était DÉJÀ vrai, `basculerMode` n'est pas rappelé.
Un pilote qui décolle aux manches, ou qui était déjà en mode auto, part sans autorité.
⚠ C'est EXACTEMENT le défaut documenté pour Phase 3 le 2026-07-22 (« le VS n'est demandé
que dans decoller() »), jamais appliqué à ce mode. Troisième écran touché par la même
famille de défaut, après Phase3 et MainActivity.
→ CORRECTIF : le VS est demandé explicitement AVANT le premier cliché, avec ATTENTE de
l'acquittement du SDK (4 s), ligne `EVT virtual stick avant capture : accorde=…`, et refus
ANNONCÉ À L'ÉCRAN avant le vol (`ma_3d_vs_refuse`) au lieu d'un arrêt silencieux au premier
transit. Une capture qui ne peut pas commander l'aéronef doit le dire au pilote, pas
l'écrire dans un journal qu'il lira le lendemain.
⚠ RESTE À VÉRIFIER EN VOL : que le SDK accorde effectivement le VS dans cette situation.
Le correctif garantit qu'on le DEMANDE et qu'on SAIT s'il est refusé — pas qu'il est
accordé.
Relevé au passage, sain : `format photo : déjà en 4:3 (RATIO_4COLON3)` sur les 4 essais —
la sonde `SondeFormatPhoto`, jusqu'ici jamais exécutée, fonctionne et trouve la bonne clé.

### AUDIT DU COMPORTEMENT DE VOL — MODÈLE 3D (2026-07-28) — 4 défauts

**1. L'ALTITUDE N'ENTRAIT PAS DANS LE CRITÈRE D'ARRIVÉE.** `allerA` ne testait que la
distance HORIZONTALE (`if (dist < tolM) break`). Le drone pouvait être au bon point au sol
et encore des dizaines de mètres plus bas — la photo partait quand même. Or sur une orbite,
l'altitude EST la géométrie de l'anneau.
MESURÉ par simulation : montée vers l'anneau haut (5 m → 54 m, 30 m de distance) →
l'ancien critère déclarait l'arrivée à **25,7 m, soit 28 m sous la cible**. Un tiers du plan
photographié au mauvais endroit, sans une ligne au journal.
→ `dist < tolM && |alt − altCible| < 1,5 m`.

**2. L'ANTI-BLOCAGE DE 120 s CONTINUAIT COMME SI DE RIEN N'ÉTAIT.** Le `break` rendait
`true` : l'appelant croyait le point atteint, orientait et déclenchait. Deux minutes sans
arriver signifie vent, évitement d'obstacle qui bloque, ou commandes sans effet — aucun de
ces cas ne justifie de photographier. → `TRANSIT_TIMEOUT` au journal + mission arrêtée.

**3. ⚠⚠ LA MISSION SURVIVAIT À L'ARRIÈRE-PLAN.** `onStop` arrêtait la boucle pilote mais
laissait `visiteEnCours=true`. La coroutine continuait donc à « voler » : `allerA`
soumettait des commandes à une boucle ARRÊTÉE, le transit n'aboutissait jamais, et
l'anti-blocage faisait prendre la photo quand même. Scénario complet : **54 clichés du même
point, sur près de deux heures**, aéronef en stationnaire, personne devant l'écran.
→ `onStop` interrompt toute mission (`MISSION_INTERROMPUE cause=ecran_arriere_plan`).
Même règle que Phase 3 et le vol découverte — troisième application de la même doctrine.

**4. Queue de mission non gardée.** Depuis (3), la fin de capture peut s'exécuter écran
absent → `BadTokenException` en affichant un dialogue. Tout est sous `try/catch` : perdre
la proposition d'assemblage est fâcheux, planter pendant un RTH l'est davantage.

**AJOUTÉ** : `CAP_NON_ATTEINT` quand `orienterVers` expire (on CONTINUE — quelques degrés
ne ruinent pas une reconstruction, mais si toutes les prises portent cette ligne, c'est le
lacet qu'il faut corriger, pas les photos).

**VÉRIFIÉ SAIN** : le moteur est bien générique (aucun centre, aucun bouclage) ; batterie
contrôlée à chaque cliché ET dans `allerA` ; arrêt propre si un quart des photos est refusé.

### DEUX MOTIFS DE PLUS (2026-07-28) — logique PURE écrite, UI NON câblée

**Orbite vers l'EXTÉRIEUR** (`CaptureOrbite3D.planifierVersExterieur`) — pour un lieu CREUX
(cour, carrière, gradins). ⚠ CE N'EST PAS L'ORBITE NORMALE AVEC UN SIGNE INVERSÉ :
- vers l'intérieur, l'altitude DÉCOULE de l'inclinaison (`R·tan|pitch|`), le point visé est
  le centre ; vers l'extérieur il n'y a AUCUN point visé — l'altitude est choisie, et
  l'inclinaison décide seulement du cadrage. Dériver l'altitude n'aurait aucun sens.
- le RECOUVREMENT change de formule : vers l'intérieur deux prises regardent le même objet
  depuis deux points (le rayon se simplifie) ; vers l'extérieur on pivote devant des choses
  lointaines → formule de PANORAMA `(champ − pas)/champ`. Plus permissive, et c'est le signe
  que ces images se JUXTAPOSENT au lieu de se croiser.
→ PORTÉE HONNÊTE : ce mode MONTRE un lieu, il n'en reconstruit pas la géométrie fine.

**Quadrillage** (`cine/QuadrillageAerien.kt`, PUR, 13 tests) — pour un TERRAIN ENTIER.
Lignes parallèles en boustrophédon + second balayage PERPENDICULAIRE + passage oblique.
- **C'est l'ALTITUDE qui commande tout** : elle fixe l'empreinte, la finesse et le nombre de
  photos. Doubler l'altitude divise par 4 les clichés et par 2 la finesse.
  `distanceAuSolCmParPixel` le CHIFFRE (60 m → 2,59 cm/px) au lieu de le laisser deviner.
- **Champ VERTICAL ≠ horizontal** : 82° h → **66,2° v** en 4:3. Les confondre fausse le
  recouvrement dans l'axe de vol, celui qui compte le plus. Test dédié.
- **Second balayage indispensable** : sans lui les surfaces verticales ne sont vues que d'un
  côté et se reconstruisent en biseau. Il double les photos, c'est le prix.
- **Oblique** : un quadrillage strictement nadir donne un modèle écrasé — toits justes,
  façades absentes. Même défaut que l'orbite qui visait le sol.
- Refus chiffrés : 400 clichés max, altitude 20–120 m, côtés 20–400 m, recouvrement
  frontal ≥ 70 % / latéral ≥ 60 %. Mesuré : 300×400 m à 40 m → 1567 clichés → REFUSÉ.
⚠ L'orbite inversée n'est PAS câblée à l'UI. Le quadrillage l'est (voir ci-dessous).

### QUADRILLAGE — interface (2026-07-28)

Parcours : Recettes → « Quadrillage du terrain » → altitude + case oblique → **marquer le
COIN A au drone** → **coin opposé** → récapitulatif chiffré → carte → Lancer.
- **La zone se définit par DEUX COINS marqués au drone**, pas par une saisie ni sur l'image
  satellite : le pilote regarde le terrain. Même geste que les « points marqués » de la
  visite, déjà éprouvé. Position du DRONE, jamais du téléphone.
- ⚠ Deux points ne définissent pas l'orientation d'un rectangle (il en faudrait trois) →
  **rectangle aligné nord-sud / est-ouest**, ce qui est PRÉVISIBLE. Le SENS DE VOL, lui,
  suit automatiquement le plus grand côté : moins de virages, moins de batterie.
  ⚠⚠ **CORRECTION D'UNE AFFIRMATION TROP CONFIANTE (revue de Christian, 2026-07-28)** :
  j'avais écrit qu'un terrain oblique ne coûtait « que de la batterie, pas un risque ».
  FAUX. Sur un terrain en biais, le rectangle cardinal DÉPASSE la zone voulue — le drone
  peut survoler le voisin, un chemin, ou s'approcher d'obstacles étrangers au terrain.
  → Avertissement NON CONDITIONNEL au récapitulatif (deux points ne permettent pas de
  DÉTECTER le biais, donc on ne peut que prévenir) + renvoi à la carte, seul vrai contrôle.
  → À FAIRE : marquage à TROIS points (coin, puis côté et orientation, puis largeur) pour
  suivre un terrain oblique. C'est la vraie solution ; l'avertissement n'est qu'un palliatif.

### REVUE DE CHRISTIAN (2026-07-28) — moteur générique VÉRIFIÉ, batterie ajoutée

Question posée : « le quadrillage se convertit en plan d'orbite » — le moteur n'impose-t-il
pas des comportements d'orbite (viser un centre, refermer le parcours, recalculer le lacet,
relier le dernier point au premier) ?
→ VÉRIFIÉ DANS LE CODE, et la réponse est non. `executerCapture3D` fait, pour chaque
cliché : `allerA(lat, lon, alt)` → `orienterVers(cap)` → nacelle → photo. Aucun centre,
aucun bouclage, aucun lacet dérivé, aucun lien dernier→premier. Le cap est une valeur
TRANSPORTÉE.
⚠ MAIS LES NOMS MENTAIENT : `Cliche.capVersCentreDeg` contenait un cap de balayage.
Renommé `capDeg`. Un champ dont le nom décrit COMMENT il a été calculé plutôt que CE QU'IL
CONTIENT finit par tromper — et ici le lecteur suivant commande un aéronef.
→ AJOUTÉ : `BATTERIE_PLANCHER_MISSION_PCT = 25`, contrôlé À CHAQUE CLICHÉ. La batterie
n'était vérifiée qu'AVANT le décollage, sur une estimation supposant un vent nul ; un vent
de face peut doubler la consommation. Le retour automatique du firmware existe mais se
déclenche plus bas et décide seul du moment.
RESTE À ÉPROUVER EN VOL (rien de tout cela n'est prouvé par les tests) : ordre réel des
lignes, stabilité du lacet en demi-tour, arrêt d'urgence pendant un virage, déclenchement
effectif de chaque photo, fin de mission. Premier essai : petit rectangle, peu de lignes,
sans viser une reconstruction.
- **L'exécution réutilise `executerCapture3D`** via `Plan.versPlanOrbite()`. Écrire un
  second moteur de vol dupliquerait les garde-fous (autorité, arrêt d'urgence, reprise
  manuelle, journal) avec la certitude qu'ils divergeraient. Un seul chemin, éprouvé.
- `CarteOrbite3DActivity` dessine un RECTANGLE quand `coins_lats/lons` sont fournis, le
  cercle sinon. Mêmes garde-fous qu'avant : espace carte, batterie, aperçu au sol.
⚠ NON VOLÉ.

### AUDIT DU COMPORTEMENT DE VOL — QUADRILLAGE (2026-07-28)

Le quadrillage réutilisant `executerCapture3D`, les 4 défauts corrigés pour l'orbite
(altitude dans l'arrivée, `TRANSIT_TIMEOUT`, arrêt à `onStop`, queue gardée) le couvrent
aussi. Deux défauts lui sont PROPRES :

**1. ⚠⚠ DURÉE SOUS-ESTIMÉE D'UN FACTEUR 2,2.** Le planificateur supposait 6 m/s et 4 s par
cliché ; l'exécuteur vole à **3 m/s**, RALENTIT dans les dix derniers mètres, et attend
2 s + 0,6 s + 1,2 s + déclenchement à chaque station. Mesuré sur 100 × 100 m à 60 m
(104 photos) : **11 min 26 s annoncées, 24 min 47 s réelles**.
⚠ PORTÉE : la batterie exigée avant décollage se calcule sur cette durée. Sous-estimer d'un
facteur deux fait partir une mission qui revient à moitié faite — et un jeu photogrammétrique
incomplet ne se rattrape pas, il se revole.
→ `dureeSautS()` reproduit la loi d'approche de `allerA`, et la durée somme les sauts RÉELS
(les transits entre passages sont longs, ceux d'une ligne courts, la loi n'est pas linéaire).
Le retour au point de départ est INCLUS. Une seule loi, deux usages : deux modèles finissent
toujours par diverger.
Conséquence assumée : beaucoup de quadrillages sont désormais annoncés comme dépassant une
batterie, et le sont réellement. 100 × 150 m à 40 m → 64 min, donc refusé. C'est la vérité.

**2. Le journal annonçait des valeurs d'ORBITE.** Le plan converti portait
`hauteur_sujet=0m plancher=12m` — un quadrillage n'a pas de sujet et son plancher est de
20 m. Des valeurs fausses écrites avec l'autorité d'une mesure.
→ `Plan.motif` (`ORBITE`|`QUADRILLAGE`) ; le journal écrit `passages=` au lieu d'`anneaux=`
et n'écrit hauteur/plancher que pour une orbite.

**VÉRIFIÉ SAIN** : le lacet s'inverse bien à chaque ligne (boustrophédon), donc le passage
oblique couvre les DEUX directions de vol.
⚠ LIMITE CONNUE : les façades perpendiculaires au sens de vol ne sont vues qu'en nadir (le
second balayage n'a pas d'oblique). Un bâtiment orienté en travers sera moins bien rendu.

### VOIE RETENUE (2026-07-27) : reconstruction sur le PC (RTX 3090), dépôt sur le serveur
Christian a une **RTX 3090** (24 Go VRAM, 10 496 cœurs CUDA) — très au-dessus des exigences
de **RealityScan** (ex-RealityCapture, Epic) : gratuit sous 1 M$ de revenus, 16-32 Go de RAM,
GPU NVIDIA CUDA OBLIGATOIRE pour mailler/texturer (sans lui : alignement seulement).
Flux : vol → l'app envoie les photos (serveur = « en attente », il les GARDE) → traitement
sur le PC → dépôt du maillage :
- `POST /api/modele3d/{id}/modele` (rattache à un jeu de photos existant)
- `POST /api/modele3d/glb` (dépôt seul, traitement hors ligne) — `curl -F titre= -F file=@…`
- `GET /modele3d/{id}` bascule AUTOMATIQUEMENT du suivi vers la visionneuse dès que
  `modele.glb` existe. A-Frame + gltf-model ; déplacement au MANCHE en casque (composant
  `deplacement-manette` maison, ~15 lignes, pour éviter une dépendance CDN de plus),
  ZQSD/souris ailleurs. Composant `ajuster` : recentre et ramène le maillage à ~20 unités
  (un export en unités arbitraires apparaît sinon microscopique ou tout autour de la caméra).
- Refus si l'en-tête n'est pas `glTF` ou si le fichier fait < 1 Kio : un mauvais format
  donnerait une page NOIRE chez le client, sans indice.
- ⚠ ÉCHELLE NON MÉTRIQUE garantie : ne pas présenter cette vue comme un relevé.
⚠ `client_max_body_size` nginx passé de **50M à 2G** (un maillage texturé = 100-500 Mo,
sinon 413 muet). La limite vidéo de 50 Mo reste appliquée côté app (`MAX_VID`, stream_key.py).
DÉPLOYÉ ET VÉRIFIÉ 2026-07-27 (empreinte `1bfa2ea4…`, service actif, nginx rechargé).

### PREMIER VOL DE RELIEF (2026-07-27) — capture BONNE, chaîne aval défaillante

Vol : montée 40 m, cap figé −3°, 25 photos/œil, **décalage mesuré 1,89 m pour 2,00 visés**
(à 11 cm près — la ligne de base de 2 m est validée, ne pas la changer). Dérive de 3 m vers
l'est pendant la montée « verticale » (vent) : sans effet, l'écart est mesuré après
stabilisation. Photos : 4032×2268, ISO 100, 1/8000..1/1500, netteté médiane très saine.

**QUATRE défauts trouvés APRÈS le vol, tous invisibles pendant.**

1. **Le panorama volait le journal au relief.** `JournalVol` est un singleton ; chaque
   sous-panorama ouvrait un NOUVEAU fichier → la trace du relief s'arrêtait sur
   « oeil GAUCHE ». → `demarrerOuEtape()` : un mode composite garde SON journal, le
   sous-mode écrit une ligne `ETAPE`. Seul celui qui a ouvert a le droit de clore
   (`panoramaAOuvertLeJournal`).
2. **`CONFIG capDepart` mentait** : la ligne écrivait le cap MESURÉ alors que la grille
   était construite sur le cap FORCÉ → le journal semblait dire que les deux yeux avaient
   des caps différents (−3 puis −5) alors que `visé=-3` des deux côtés prouvait l'inverse.
   → `capApplique=` ET `capMesure=` désormais.
3. **⚠⚠ 8 PHOTOS SUR 50 PERDUES AU RAPATRIEMENT, EN SILENCE.** Œil gauche 25, œil droit
   **17**. Et le pire n'est pas la perte : `assemblerLots` empilait les fichiers puis
   découpait par COMPTAGE → la coupure tombait au mauvais endroit et des photos de l'œil
   droit partaient dans l'assemblage de l'œil GAUCHE. Mélange indétectable à l'œil.
   → Indexation PAR POSITION (`arrayOfNulls`) : un échec laisse un trou à sa place et
   n'affecte que son lot. Une seconde tentative par photo. Lignes `!!` au journal
   (`rapatriement : N/50 non téléchargées`, `lot 2 : 17/25 photos seulement`).
4. **Assembleur serveur : géométrie de sortie VARIABLE** — voir ci-dessous.

### Assembleur `cine_panorama_stitch.py` — sphère complète imposée (2026-07-27)

Deux panoramas du MÊME lieu sortaient en 12000×6000 et 12000×3282 : une visionneuse plaque
l'image sur une sphère en supposant 360×180, donc le second était ÉTIRÉ de 98° à 180° —
paysage à la mauvaise hauteur, paire stéréo INFUSIONNABLE.

- `patch_pano_sphere.py` : `--fov=360x180`, canevas 2:1 FIXE, plafond **8192 px** (taille de
  texture maximale d'un GPU mobile — les 12000 px n'étaient jamais affichés en entier).
  Et `_remplir_ciel` réécrit : il ne comblait QUE les extrémités haute et basse de chaque
  colonne, un trou ENCLAVÉ restait noir (zénith) ; sans canal alpha il sortait aussitôt
  sans rien faire, en silence. Travail en uint8 (la version float32 demandait >1 Go).
- `patch_pano_enblend.py` (2e passe) : **forcer `pano_modify` ne suffit pas** — `enblend`
  produit une image de la taille de l'UNION des couches, donc recadrée au contenu. Sortie
  mesurée 8192×2321 malgré un canevas de projet correct. → `enblend -f LxH`.
  **LEÇON : j'avais vérifié la COMMANDE (juste) au lieu du RÉSULTAT. Seule la mesure du
  fichier produit a montré l'écart** — même famille que le SHA-256 qui avait attrapé le
  fichier vide déclaré valide par `py_compile`.
- `reassembler_pano.py` : régénère des panoramas DÉJÀ capturés (les photos restent dans
  `_pano_jobs/<id>/in/`), sans refaire le vol ; l'identifiant ne change pas, donc les liens
  `/vr/` et `/vr3d/` restent valides. **À lancer avec `nohup`** : 350 s par panorama, la
  console DigitalOcean coupe bien avant. Tri sur la date du dossier `in` (celle du travail
  change quand on réécrit `panorama.jpg`). Finit par une VÉRIFICATION du rapport 2:1.
- Résultat vérifié : les deux yeux en **8192×4096**.

### Assemblage GUIDÉ PAR LES ANGLES (2026-07-27) — chaîne complète, sens à confirmer

L'app connaissait le cap et l'inclinaison de CHAQUE cliché (`PanoramaGrille`) et jetait
cette information ; le serveur devait la redécouvrir dans les pixels. Deux conséquences
mesurées : les rangées hautes se plaçaient mal (le ciel n'a pas de texture → `cpfind` sans
points communs → taches noires au zénith), et surtout **les deux yeux d'une paire stéréo
n'atterrissaient pas dans le même repère** (horizon à +3,9° d'un côté, +1,2° de l'autre —
≈2,6° d'écart vertical, cinq fois la tolérance de fusion). Forcer le cap à la CAPTURE ne
force pas l'orientation à l'ASSEMBLAGE : Hugin optimise chaque panorama indépendamment.

Chaîne : `PanoramaAssemblage.assembler(angles=)` → champ multipart `angles` →
`patch_api_angles.py` écrit `_pano_jobs/<id>/angles.json` (sur DISQUE, pas en mémoire) →
`patch_pano_angles.py` écrit les positions dans le .pto et lance `autooptimiser -n`
(affiner, PAS `-a -m -l -s` qui rechoisiraient une orientation). Ligne `VOIE D'ASSEMBLAGE :
angles|auto` au journal serveur ; repli automatique sur l'ancienne voie à la moindre
défaillance ; sans angles, comportement strictement inchangé.
✔ VÉRIFIÉ 2026-07-27 : `angles.json` de 25 entrées, identiques aux deux yeux, départ
−169,1° et pas de 45°.
⚠ `SENS_YAW` (convention de signe du lacet Hugin vs cap boussole) NON tranché — se
détermine par `essai_angles.py` (assemble 3 fois : auto / +1 / −1, et mesure lequel
correspond). Un signe inversé donne une image MIROIR, invisible sur un paysage symétrique.
⚠ RELEVÉ AU PASSAGE : `Fill sky 58,9 %` — le preset Simple ne couvre que +30°..−90°, donc
**plus de la moitié de la sphère est du remplissage**. À revoir (preset couvrant plus haut).

### ⚠⚠ AUCUN ASSEMBLAGE NE DÉMARRAIT PLUS — une parenthèse (2026-07-28)

Trouvé en cherchant pourquoi deux jeux de 61 photos, reçus à 10 h 14 et 10 h 16, n'avaient
produit aucune image. `ps aux` : aucun `nona`, `enblend`, `cpfind` — le calcul n'avait
jamais commencé. Le journal du service portait la cause :

```
args=(job_id, din, out_jpg, int(largeur_max, _pano_angles))
TypeError: 'list' object cannot be interpreted as an integer
```

Le `, _pano_angles` ajouté par `patch_api_angles.py` s'était glissé **à l'intérieur** de
l'appel à `int()`, qui l'a pris pour une base numérique. L'exception tombait juste avant le
démarrage du fil de calcul : photos reçues, écrites sur disque, et rien d'autre.
→ `patch_pano_parenthese.py` : `int(largeur_max), _pano_angles`.

⚠⚠ **LA COMPILATION NE PROUVE RIEN — troisième fois.** `int(a, b)` est une signature
LÉGITIME : les deux formes passent `py_compile` sans broncher (vérifié). Or c'est
exactement ce contrôle qui avait servi à valider le déploiement du patch. Même famille que
le fichier vide déclaré valide par `py_compile`, que le code de retour 0 de RealityScan
sans maillage produit, et que la commande `enblend` juste dont le RÉSULTAT était faux.
RÈGLE : un déploiement se valide par un essai de bout en bout, jamais par la compilation.

⚠ SYMPTÔME TROMPEUR : côté app, la tâche restait dans « assemblages en attente » — ce qui
faisait chercher du côté du téléphone et du drone. Le serveur répondait 500 et écrivait la
trace dans son journal, invisible tant qu'on ne le lisait pas. Le premier geste utile a été
`journalctl -u cineflight | grep -v clicker`.

### Assemblage DIFFÉRÉ (2026-07-27) — « maintenant ou plus tard »
Rapatrier 50 photos + attendre le serveur = ~20 min pendant lesquelles le drone est
immobilisé, souvent à la meilleure lumière. À la fin d'une capture, choix explicite.
`cine/AssemblagesEnAttente.kt` (file en prefs JSON, 5 tests) + `AssemblagesActivity`
(Outils → Assemblages en attente, compteur dans le libellé).
- **On mémorise les NOMS DE FICHIERS**, pas « les N dernières photos » : dès qu'un autre vol
  a lieu, « les dernières » ne désignent plus les bonnes. Relevés drone encore connecté
  (listing seul, aucun téléchargement).
- Une photo introuvable laisse un trou À SA PLACE (pas de décalage en cascade).
- La tâche n'est retirée QUE si l'assemblage a abouti : un échec réseau ne fait pas
  disparaître la trace d'un vol.
- Supprimer une tâche n'efface JAMAIS les photos (dit dans la confirmation).
- ⚠ Ne pas formater la carte avant d'avoir vidé la file (rappelé à la mise en file).
- ⚠⚠ DÉFAUT (2026-07-28) : **« Assembler maintenant » ne faisait RIEN**. L'écran de la file
  renvoyait l'identifiant à `MainActivity` dans un Intent `FLAG_ACTIVITY_REORDER_TO_FRONT` ;
  or `MainActivity` n'a pas de `onNewIntent`, donc `getIntent()` rend toujours l'intention
  de LANCEMENT de l'app. `getLongExtra` lisait 0, et le bouton refermait l'écran sans un mot.
  → La demande passe par les préférences (`demanderTraitement` / `prendreDemande`, qui
  efface en lisant) : plus de dépendance au mode de lancement, à l'ordre de la pile ni à la
  délivrance d'une intention. Drone éteint, on obtient maintenant « drone requis » au lieu
  du silence.
  LEÇON : un Intent envoyé à une activité déjà vivante n'arrive PAS si elle ne l'accepte
  pas explicitement. Le silence complet était le symptôme le plus coûteux à diagnostiquer.

### Espace carte vérifié AVANT capture (2026-07-27)
`getStorageLeftCapacity` était lu puis JETÉ (seules les minutes vidéo en sortaient) →
`PontCockpitImpl.octetsLibresCarte`. `cine/EspaceCarte.kt` (PUR, 7 tests) vérifie relief,
visite et modèle 3D avant décollage.
- Dimensionné sur **128 photos réelles mesurées** : médiane 7,0 Mo mais **95ᵉ centile
  11,4 Mo** → constante 12 Mo + 20 % de marge. Dimensionner sur la moyenne reviendrait à
  tomber en panne une fois sur deux sur les sujets détaillés (herbe, feuillage), qui sont
  justement les nôtres. 96 photos → 1,38 Go exigés.
- Refus CHIFFRÉ : libre, nécessaire, et combien de photos la carte tiendrait réellement.
- ⚠ ESPACE INCONNU → **on laisse voler** avec un avertissement. Ce SDK ment régulièrement
  sur le stockage ; bloquer faute d'avoir su lire rendrait l'app inutilisable. Verrouillé
  par le test `un_espace_inconnu_ne_bloque_pas_le_vol`.

### Écran CARTE MÉMOIRE (2026-07-27) — inventaire, rapatriement, formatage
`CarteDroneActivity` (paysage) + `cine/BilanCarte.kt` (PUR, 7 tests) + `SondeFormatageCarte`.
- Inventaire : photos / vidéos avec leur poids séparé, total occupé. Lecture SEULE (listing,
  aucun téléchargement) — quelques secondes.
- **Verdict de formatage à TROIS conditions** : carte lue, rien à rapatrier, file
  d'assemblages vide. Le bouton n'EXISTE pas sinon — pas un avertissement qu'on balaie.
  Tant que la carte n'a pas pu être lue, le verdict reste SUSPENDU : un « sans risque »
  prononcé dans l'ignorance vaut moins que rien.
- Double confirmation, la seconde par **appui maintenu 3 s** (un geste qu'on ne fait pas
  par réflexe, contrairement à deux boutons au même endroit).
- Bouton « Tout rapatrier » SUR PLACE (vidéos d'abord, interruptible) : un avertissement qui
  oblige à chercher le remède ailleurs n'est utile qu'à moitié.
- ⚠ `BilanCarte` compare aux fichiers du dossier de l'app : un fichier copié AILLEURS
  (PC) ressort « non transféré ». VOULU — avertir à tort coûte une vérification, se taire
  à tort coûte un fichier. Verrouillé par `le_doute_penche_vers_l_avertissement`.
- ⚠ `KeyFormatStorage` NON confirmée en MSDK 5.18 : liste blanche de 2 noms, échec qui se
  DIT au lieu de laisser croire que c'est fait.

### Presets panorama — couverture verticale (2026-07-27)
Le plafond de nacelle était bridé à **+30°** dans la boucle panorama alors que le Mini 4 Pro
monte à **+60°** : tout le haut n'était jamais photographié et le serveur COMBLAIT —
`Fill sky 53 à 59 %` mesuré, avec des traînées verticales visibles en casque.
→ Plafond porté à +60 (journalisé quand on demande au-dessus de 30, pour savoir si la
nacelle a suivi) + 2 presets `CIEL_COMPLET` (41 photos) et `CIEL_COMPLET_HQ` (61).
Presets rangés par NOMBRE DE PHOTOS croissant, avec la durée affichée.
`dureeEstimeeS()` CALIBRÉE sur un vol réel : 25 photos en 218 s le 2026-07-27 → 8,7 s par
cliché dont 3 s de délais configurés, donc 5,7 s d'overhead fixe. La formule redonne 217 s.

### ⚠⚠ LE VERROU D'EXPOSITION N'A JAMAIS TENU (2026-07-28) — mesuré, pas supposé

SYMPTÔME : le panorama `eca54f3db18e`, géométriquement juste, montre des RECTANGLES de
luminosité — chaque photo apparaît en bloc. Visible surtout depuis que la couture n'est
plus optimisée (`--no-optimize`, imposé par le plantage d'enblend au pôle) : le fondu
multibande masque de PETITS écarts, pas des écarts francs.

MESURE (`_atelier/diag_exposition.py`) : on lit le paramètre `Eev` de chaque image dans le
projet Hugin après `autooptimiser -m` — l'exposition que Hugin a dû corriger.
**Écart total : 2,48 EV**, et surtout il OSCILLE avec l'azimut : clair vers les photos
000-014, creux à −2,37 vers 024-036, remontée vers 052-060. C'est la signature d'une
exposition restée AUTOMATIQUE qui s'adapte en balayant — face au soleil puis dos au soleil.
Un verrou qui tient donne une ligne plate, pas un cycle.

CAUSE DANS LE CODE : `verrouillerExposition()` posait `expoVerrouillee = true` juste après
avoir DEMANDÉ le mode manuel, sans jamais relire ce que la caméra avait retenu — et son
résultat était jeté par un `try { } catch { }` chez l'appelant. Un état d'INTENTION, pas de
fait. **Même famille que « armé sans détecteur » et « mode soccer pré-armé »**, la
cinquième occurrence de ce motif dans ce projet.
⚠ Et le contraste était sous nos yeux : le résultat de `verrouillerBalanceBlancs()` partait
au journal depuis le 27 juillet ; celui de l'exposition, non.

→ CORRECTIF : `verrouillerExposition()` rend une DESCRIPTION (comme la balance des blancs),
elle part au journal, et une VÉRIFICATION différée de 1,2 s relit `KeyExposureMode`,
`KeyISO`, `KeyShutterSpeed` et écrit ce qui a réellement été retenu —
`EXPOSITION_NON_TENUE mode=…` en anomalie si le mode n'est pas MANUAL. Lecture sur le fil
principal : lire les clés depuis un fil de fond avait déstabilisé la liaison le 2026-07-24.
Le refus « lecture ISO/vitesse impossible » est journalisé lui aussi, au lieu du seul Logcat.

⚠ RESTE À VÉRIFIER EN VOL : que le mode MANUAL est effectivement accordé. Le correctif
garantit qu'on le DEMANDE et qu'on SAIT s'il est refusé — pas qu'il est obtenu. C'est la
même réserve que pour le Virtual Stick de la capture 3D.

⚠ SECOND DÉFAUT, DISTINCT : les sentiers se DÉDOUBLENT aux jointures. La voie
`angles_seuls` place les images d'après le cap rapporté par le drone sans laisser
l'optimiseur ajuster — à 1° près, sur 8192 px de large, cela fait déjà 23 px de décalage.

### VOIE ANCRÉE — ESSAYÉE, MESURÉE, RÉTROGRADÉE (2026-07-28)

IDÉE : optimiser les positions en excluant l'image 0 des variables du `.pto`. L'ancrage
empêche alors l'optimiseur de faire pivoter l'ensemble, tout en corrigeant les décalages
entre images voisines. C'est la manière standard sous Hugin, et elle devait donner
« l'horizon droit ET les jointures continues ».

MESURE, même jeu de 61 photos, même préréglage :

| voie | ciel comblé | résultat visuel |
|---|---|---|
| `angles_seuls` | **9,0 %** | sphère droite, sentiers dédoublés |
| `angles_affines` | **27,8 %** | sphère droite, **tiers supérieur en aplat gris** |

L'ancrage a bien empêché la rotation — c'était son objet — mais il a laissé l'optimiseur
DISPERSER les images, creusant des trous que le remplissage a dû combler.

HYPOTHÈSE NON VÉRIFIÉE sur la cause : j'ai libéré trois paramètres par image (cap,
inclinaison, roulis). Or seul le CAP est bruité — il vient de la boussole. L'inclinaison et
le roulis viennent de la NACELLE, mécaniquement précise. Les libérer laisse les images
dériver verticalement. **Une variante n'optimisant que le lacet reste à éprouver.**

→ `patch_pano_ancree_rang.py` : `angles_seuls` repasse en tête. La voie ancrée reste en
SECOND — si le placement brut échoue un jour, elle vaut mieux que `angles_optimises`, qui
fait pivoter la sphère. On ne supprime pas un outil qu'on vient de construire ; on le met
au rang que la mesure lui donne.

⚠ Quatrième hypothèse démentie de la journée. Le dédoublement des sentiers reste OUVERT.

### Exposition, balance des blancs, photométrie (2026-07-27) — vérifié sur sources
- **Balance des blancs FIGÉE** au début de chaque panorama (`verrouillerBalanceBlancs`).
  C'est le seul point où toutes les sources s'accordent : en auto elle dérive d'une image à
  l'autre. Un écart de COULEUR se rattrape très mal au raccord ; un écart de LUMINOSITÉ,
  Hugin sait l'égaliser. On verrouille donc ce que le logiciel ne sait pas réparer.
  On fige la valeur COURANTE, sans imposer 5500 K (qui trahirait une lumière de fin de jour).
  ⚠ `KeyWhiteBalance` non confirmée en MSDK 5.18 — liste blanche, échec qui se DIT.
- **Exposition : verrouillage CONSERVÉ.** L'avis dominant (et notre mesure du 26/07) va
  dans ce sens ; PANOVOLO recommandant l'auto est l'exception. Ne pas basculer sans essai.
- **⚠⚠ RÉGRESSION DE MON FAIT, corrigée** : en passant à la voie « angles seuls » pour
  empêcher l'optimiseur GÉOMÉTRIQUE de basculer les sphères, j'avais supprimé TOUTE
  optimisation — donc aussi `-m`, la correction photométrique que l'ancienne chaîne
  `-a -m -l -s` appliquait. Plus rien n'égalisait la lumière ; seul le verrouillage
  d'exposition masquait le trou. → `patch_pano_photometrie.py` : `autooptimiser -m` sur la
  voie angles, APRÈS l'écriture des positions (le `-m` ne touche pas la géométrie).
  Échec sans conséquence : la photométrie est un confort, la géométrie est le produit.
  ⚠ Au-delà d'environ 3 EV d'écart, le mélange des jointures ne suffit plus (doc Hugin).

### Qualité photo JPEG / DNG (2026-07-27)
`Reglages.getFormatPhoto()` : 0=JPEG · 1=DNG · 2=DNG+JPEG (**défaut**), appliqué à la caméra
avant chaque capture par `PontCockpitImpl.reglerFormatPhoto` (liste blanche stricte).
- Le JPEG sert à l'assemblage automatique ; le DNG reste sur la carte pour reprendre les
  meilleurs panoramas sur PC. **Notre serveur n'utilise PAS le DNG** — « DNG seul » prive
  donc de l'assemblage automatique, et la description du choix le dit.
- ⚠⚠ **`seulementJpeg()` INDISPENSABLE** : en DNG+JPEG le drone écrit DEUX fichiers par
  déclenchement et `listerPhotos` rend les deux (122 entrées pour 61 prises). Sans filtre,
  « les N dernières » enverraient un mélange, et les DNG — que `BitmapFactory` ne décode
  pas — disparaîtraient à la réduction en décalant TOUS les angles d'un cran : l'assemblage
  guidé deviendrait PIRE que l'automatique. Appliqué aux 4 points de sélection.
- `EspaceCarte.octetsParPrise(format)` : 42 Mo en DNG+JPEG → 61 photos = 2,5 Go.

### Réglages caméra AFFICHÉS avant le vol (2026-07-27)
`resumeReglagesCamera()` lit format / résolution-cadence / mode DANS LA CAMÉRA (pas dans les
prefs de l'app : les deux divergent, et c'est la caméra qui décide de ce qui sera écrit).
Collé dans les 3 fenêtres de confirmation, avec une alerte si la photo n'est pas en 4:3.
Motif : le 16:9 tenait depuis des semaines sans que rien ne l'affiche. Une clé absente est
OMISE, jamais remplacée par une valeur supposée.

### Cadences 100 et 200 i/s — ralenti (2026-07-27)
Ajoutées aux réglages avec une explication PAR VALEUR (la conséquence, pas la définition).
⚠ CORRECTIONS DE CHRISTIAN, à ne pas réintroduire : la cadence ne double pas le poids des
fichiers (c'est le DÉBIT et le codec) ; et 100 i/s existe en 4K **et** en 1080p — c'est la
combinaison 4K/100 qui impose le H.265. Seul le 200 i/s est limité au 1080p.
Le ralenti sert aussi aux PAYSAGES (rendu type Aerial d'Apple TV), mais l'effet tient
surtout à un déplacement TRÈS lent et stabilisé — Apple ne publie pas ses cadences.

### Journal : fermeture APRÈS la chaîne aval (2026-07-27)
`terminer()` était appelé avant le rapatriement et l'assemblage — donc les anomalies de la
phase la plus fragile (8 photos perdues au vol précédent) partaient dans un fichier CLOS,
c'est-à-dire nulle part. Le journal se ferme désormais dans `finirStereo`.

### ⚠ org.json en test JVM (2026-07-27)
Android ne fournit qu'une COQUILLE d'`org.json` aux tests unitaires : toutes les méthodes
rendent `null`. Un test de sérialisation échouait donc par `NullPointerException` sans que
le code testé soit en cause. → `testImplementation 'org.json:json:20240303'` (portée TEST
uniquement). À savoir avant de « corriger » un code parfaitement sain.

### Format photo 16:9 → sonde 4:3 (2026-07-27, NON ÉPROUVÉE)
Les photos sortaient en 4032×2268 (16:9) alors que le capteur est en 4:3 (4032×3024) : un
quart de la HAUTEUR jeté, donc du recouvrement VERTICAL perdu entre les anneaux d'une
capture 3D. Le réglage venait de la caméra (DJI Fly), l'app n'y touchait pas.
→ `control/SondeFormatPhoto.kt` : LISTE BLANCHE de 2 noms de clé et 2 classes d'énum,
lecture avant écriture, aucun repli « premier candidat », et journalisation de ce qui a
réellement été fait (`EVT format photo :` ou `!! format photo NON forcé :`).
⚠ `KeyPhotoRatio` n'est PAS confirmée en MSDK 5.18 — la sonde est écrite pour échouer
proprement et DIRE ce qu'elle a trouvé (les constantes sont listées au logcat).
Repli sûr en attendant : passer la photo en 4:3 dans DJI Fly.
5 tests JVM sur la reconnaissance du libellé (`RATIO_16_COLON_9` et `RATIO_3_COLON_4`
doivent être REJETÉS ; un symbole sans le mot RATIO aussi — garde-fou anti-`KeyFlightMode`).

### Garde-fous communs aux vols automatiques de visite (2026-07-27)
`autoriteDeVolIntacte(etape)` vérifié à CHAQUE cycle de `monterA`/`allerA`/`orienterVers` :
si le Virtual Stick n'est plus accordé ou le mode auto perdu, `soumettre()` part dans le
vide et la boucle tournerait jusqu'à son délai maximal EN CROYANT PILOTER. → arrêt + ligne
`!!` au journal + message écran. Et **ni atterrissage ni RTH de fin si `!modeAuto`** :
reprendre l'aéronef des mains du pilote qui vient de reprendre les manches serait pire que
de ne rien faire.

## JOURNAL DE VOL PERSISTANT — TOUS LES MODES (2026-07-26)

MOTIF : les vols du 25/07 n'ont PAS pu être analysés le lendemain. Le logcat est circulaire
(écrasé en heures) et les FlightRecord DJI sont chiffrés. Trois défauts n'ont été trouvés
que par LECTURE DU CODE, faute de trace : orbite (roll non journalisé), décollage d'essai
(boucles concurrentes), suivi athlète (aucune instrumentation).
→ `control/JournalVol.kt` — SINGLETON (un aéronef = un vol à la fois), un FICHIER PAR VOL
dans `files/vols/vol_AAAAMMJJ_HHMMSS_MODE.log`, récupérable par
`adb pull /sdcard/Android/data/ca.cineflight.solo/files/vols/`. Purge auto : 60 derniers.
Écriture sur fil de fond, états throttlés à ~1 Hz : jamais de charge sur le pilotage.

COUVERTURE (aucun vol sans trace) :
- `SUIVI_ATHLETE` / `SUIVI_BOITIER` / `VISION` (Phase3) : positions drone ET cible, distance,
  cap du sujet, angle, cadrage, **les 4 axes dont le ROLL**, état source (âge/précision),
  predPret, raison de refus, batterie, satellites. Événements : angle, cadrage, arrêt, RTH.
- `DECOUVERTE` (PremierVol) : altitude RÉELLE vs commande, phase (t=…s) — c'est ce qui
  manquait pour prouver « le drone ne monte pas et dérive ».
- `PANORAMA` : preset, cap visé vs cap réel, pas courant, chaque photo prise, photo REFUSÉE.
- `MISSION_VS` : fichier KMZ, waypoints, progression, arrêt+RTH.
- `MANUEL` : ouvert automatiquement dès que l'aéronef est EN VOL depuis l'écran principal
  et qu'aucun autre mode n'a ouvert de journal ; fermé à la pose.
- En-tête CONFIG systématique (profil, cadrage, plafonds, `INVERSER_ROLL_PITCH`, modèle…) :
  sans lui on ignore avec quels réglages le vol a eu lieu.
- Lignes `!!` = anomalies (photo refusée, RTH refusé, **commandes envoyées sans Virtual
  Stick confirmé** — cas où l'app croit piloter et l'aéronef ignore).

## ⚠⚠ PRODUCTEURS CONCURRENTS — MainActivity commandait en arrière-plan (2026-07-26)

CONSTAT PILOTE : au « décollage d'essai » (Vol découverte), **le drone ne montait pas et
s'éloignait horizontalement**. Trouvé par LECTURE du code (le logcat du jour était écrasé,
les FlightRecord DJI sont chiffrés) :
`MainActivity` n'arrêtait sa boucle de pilotage (`PiloteDrone`, 15 Hz) qu'à `onDestroy()`.
Android NE DÉTRUIT PAS l'écran principal quand un autre écran s'ouvre par-dessus — il le
met en arrière-plan. DEUX boucles commandaient donc l'aéronef simultanément : celle de
l'écran ouvert (PremierVol : throttle +0,4 m/s) et celle de MainActivity restée vivante.
Les commandes s'écrasent → montée annulée + dérive horizontale résiduelle.
→ C'est le MÊME défaut que celui documenté/corrigé pour `Phase3Activity` le 2026-07-22
(arrêt de boucle à onStop + `AutoriteCommandeDrone`), jamais appliqué à MainActivity.
→ CORRECTIF : `MainActivity.onStop()` → `pilote.arreter()` + ligne `CineFlightVol`.
Aucune relance automatique (comme Phase3) : reprendre le pilotage reste explicite.
✔ VÉRIFIÉ : `PremierVolActivity` se protégeait déjà (arrêt à `onPause`) ; Phase2/2b idem.
LEÇON : tout écran qui peut commander le drone doit CESSER d'émettre dès qu'il quitte le
premier plan — `onDestroy` n'est JAMAIS une garantie de temps sous Android.

## Balise LED + bip « Find My Drone » (2026-07-25)

Écran Find My Drone (menu Outils → Drone) : dernière position mémorisée en continu (prefs,
survit à la perte de liaison et au redémarrage), flèche boussole + distance, itinéraire
Maps, **💡 clignotement des feux** et **🔊 bip moteurs**.
- BIP : `FlightControllerKey.KeyESCBeepEnabled` — **FONCTIONNE** (Mini 4 Pro). C'est la
  fonction « Find My Drone » de DJI ; porte plus loin que la LED en plein jour.
- FEUX : `LEDsSettings` (front / navigation / status) — **FONCTIONNE** (feux arrière rouges
  observés). ⚠ `signalEtatVol()` (suivi actif) ne touche QUE la LED avant : les feux de
  NAVIGATION restent au firmware (visibilité réglementaire de l'aéronef en vol).
- PROJECTEUR VENTRAL (la lumière qui éclaire le sol) : **NON pilotable par le MSDK** sur ce
  drone. Clés d'éclairage réellement présentes dans FlightControllerKey : uniquement
  `KeyFrontAtmosphereLightColor`, `KeyFrontAtmosphereLightMode`, `KeyMultiLightSyncEnable`.
  Aucune clé Bottom/Auxiliary/Fill. Le projecteur reste géré en AUTO par le firmware.

⚠⚠ DÉFAUT DE MA PART, CORRIGÉ : le filtre de découverte « nom contient Light » attrapait
« F-LIGHT » → le code a retenu `KeyFlightMode` et **tenté d'y écrire** (refusé UNSUPPORTED,
sans effet, mais c'était une écriture à l'aveugle dans une clé de PILOTAGE). Remplacé par
une LISTE BLANCHE stricte de noms de lumière, sans aucun repli « premier candidat ».
RÈGLE : ne jamais faire de `setValue` sur une clé découverte par correspondance partielle.

## Photo SDK refusée -472 « WEAK_GPS » — CAUSE NON ÉTABLIE, enquête en cours (2026-07-25)

`KeyStartShootPhoto` → `CANNOT_START_TASK_ON_WEAK_GPS` (-472), Mini 4 Pro fw 07.00.00.07.
DEUX hypothèses successivement RÉFUTÉES par la mesure :
1. « caméra coincée en panorama » — DIAG ciblé : `mode_cible=PHOTO_NORMAL`, et le refus
   persiste après `KeyStopShootPhoto` (accepté) + `PHOTO_NORMAL` forcé (accepté).
2. « firmware refuse sans GPS » — le refus persiste à **15-16 satellites**, home point
   capturé. Le libellé WEAK_GPS du firmware ne décrit PAS la vraie condition.
**RÉSOLU (2026-07-25, 06:20) — cause : ÉTAT CAMÉRA RÉMANENT, réparé par DJI Fly.**
Test croisé : DJI Fly (installé par adb, APK officiel) → photo ET vidéo fonctionnent →
drone sain. Retour dans CineFlight : `Photo ACCEPTÉE (variante=0)` du premier coup.
La caméra était dans un état corrompu que le MSDK ne pouvait NI voir (lectures ciblées =
cache, disaient PHOTO_NORMAL) NI réparer (KeyStopShootPhoto accepté mais inopérant,
forçage de mode accepté mais inopérant, 3 formes de clé refusées -472, avec 0 comme avec
16 satellites). UNE ouverture de DJI Fly réinitialise la caméra.
→ PROCÉDURE TERRAIN : si -472 persistant malgré la chaîne de l'app → ouvrir DJI Fly une
fois (photo test), fermer, revenir dans CineFlight. DJI Fly reste installé sur le SM-A546W.
→ La chaîne durcie de l'app (lecture mode → stop tâche → PHOTO_NORMAL + 400 ms → tir
3 variantes + récupération WEAK_GPS) fonctionne comme conçue sur une caméra saine ; elle
est CONSERVÉE (elle gère les états récupérables ; DJI Fly gère l'irrécupérable).
LEÇON ×3 : un code d'erreur DJI ne décrit pas sa condition réelle (WEAK_GPS ≠ GPS),
les getValue rendent le CACHE (une caméra corrompue ment), et le test croisé DJI Fly
aurait dû être fait DÈS LE DÉBUT — une heure de fausses pistes économisée.

Corrections faites au passage dans `PontCockpitImpl.declencherPhotoConfirmee` :
- L'ancienne boucle « forcer mode SINGLE » était du CODE MORT : `KeyPhotoShootMode`,
  `KeyShootPhotoMode`, `CameraShootPhotoMode`, `KeyCameraFlatMode` n'existent PAS en
  MSDK v5 (doc officielle CameraKey). Supprimée.
- Séquence réelle : lire mode (diagnostic) → `KeyStopShootPhoto` si mode ≠ PHOTO_NORMAL
  (seul moyen documenté de sortir d'une tâche pano/interval laissée par DJI Fly) →
  forcer `KeyCameraMode=PHOTO_NORMAL` (400 ms de stabilisation) → tir ciblé
  DEFAULT→WIDE. Récupération WEAK_GPS unique (stop+mode+retir) : utile si un jour la
  caméra est VRAIMENT coincée en tâche.
- `lireCameraBrutCible()` : lecture CIBLÉE (la lecture non ciblée peut rendre le cache
  SDK au lieu de l'état réel — #506). Ligne `DIAG WEAK_GPS` au Logcat sur chaque refus.

MÊME DÉFAUT sur l'icône disquette (carte SD « absente » alors qu'une carte est dans le
drone) : `majStockage()` tentait 7 noms de clés qui n'existent PAS en v5. Réécrit sur
`KeyCameraStorageInfos` (la seule clé stockage v5) : liste `CameraStorageInfo`, entrée
SDCARD, `getStorageState()` + repli capacité restante > 0 ; minutes vidéo via
`getAvailableVideoDuration()`. Helpers morts `lireBoolCamera`/`lireIntCamera` supprimés.

PHOTOTHÈQUE (2026-07-25, résolu) — « aucune photo » alors que la carte en portait 9 :
**`MediaFileFilter.PHOTO` rend 0 sur Mini 4 Pro** pendant que VIDEO liste 10 fichiers et
ALL les 19. Troisième mensonge SDK du jour. `MediaDrone.listerPhotos` : repli AUTO sur le
filtre ALL + tri par extension (.jpg/.dng) si PHOTO rend vide ; repli mémoire INTERNE si
la carte SD rend vide ; journal `brut=N (noms...)` à chaque étape. AJOUT : les VIDÉOS du
drone dans la Photothèque (`listerVideos`, refactor `listerMediasAvecEssais` filtre+
extensions ; badge ▶ dans la grille, partage `video/*`). Distinct de « Mes vidéos »
(VideosSDActivity = carte RETIRÉE + adaptateur USB/SAF) : Photothèque = accès RADIO sans
démonter la carte. Vérifié au journal : 9 photos + 10 vidéos listées, téléchargements OK.
Voyant REC : affiche désormais la durée `● REC 00:12` (KeyRecordingTime du drone, repli
chrono app), juste même si l'enregistrement est lancé à la RC.

## ⚠⚠ NE JAMAIS appeler le SDK DJI depuis plusieurs fils (2026-07-24)

Cause confirmée d'une série de DÉCONNEXIONS du drone (« déconnecté mais batterie affichée ») :
un « correctif anti-ANR » déplaçait `majStockage()` (lectures carte SD par `getValue`) sur un
FIL DE FOND. L'accès CONCURRENT au SDK (fil de fond + fil principal en même temps) déstabilise
la liaison → `FlightControllerKey.KeyConnection` passe à false. La batterie restait affichée
(valeur en cache d'une autre clé) → symptôme trompeur.
→ RÈGLE : `getValue`/`setValue`/`performAction` du KeyManager restent sur le FIL PRINCIPAL
(ou le fil d'origine). Les `listen` sont OK (le SDK pousse sur son propre fil). NE PAS lancer
de thread perso qui tape le SDK. `majStockage` est resté sur le fil principal, chaque appel.

⚠ AUTRE piège écarté le même jour : les installs `assembleDebug` NE poussent PAS l'APK. Un
disque plein faisait échouer `installDebug` en silence → on testait un VIEUX build pendant des
heures. Toujours `installDebug`, émulateur fermé, espace libre suffisant, vérifier « Installed
on 1 device ».

## Intégration CineFlight Athlete (balise GPS logicielle du sujet) — 2026-07-24

Doc de réf. : `uploads/Integration_CineFlight_Athlete_vers_CineFlight.docx`. L'iPhone porté
par le SUJET pousse sa position au serveur ; CineFlight Android la lit et suit. Le téléphone
athlète NE COMMANDE JAMAIS le drone (§18 : balise, pas télécommande ; serveur = frontière de
confiance ; Android = seul décideur du mouvement). « Seule la source de position change. »

### Serveur (`_serveur_stream_key/athlete.py`, router FastAPI, EN MÉMOIRE)
- `POST /api/v1/athlete/telemetry` (+ alias `/athlet/` défensif) — réception TOLÉRANTE (pas de
  Pydantic strict qui rendait 422). Journalise `[ATHLETE] telemetry recu: {…}`.
- ⚠ L'iPhone envoie `athlete_id`/`athlete_number` (PAS `subject_id`) et `battery_percent` :
  l'endpoint accepte ces alias. N'exige que identifiant + lat/lon valides.
- `GET /api/v1/subjects` (liste) et `GET /api/v1/subjects/{id}/latest` (dernière position).
- ⚠ `source_sequence` toujours 0 côté iPhone → anti-doublon par séquence INACTIF (sans effet
  pour un seul téléphone). Le `timestamp` iPhone GÈLE quand le sujet est immobile (fix GPS non
  rafraîchi) → NE PAS dédupliquer là-dessus. La fraîcheur fiable = `received_age_s` (serveur).

### Android — modèle athlète (package `ca.cineflight.stage.athlete`, PUR + testé JVM)
- `SourceSujet` {CAMERA_ONLY, ATHLETE_PHONE, RTK_BOX, VEHICLE_GPS}.
- `TelemetrieAthlete` + parser tolérant (org.json). `ageS()` = réception si dispo, sinon mesure.
- ⚠ SUJET IMMOBILE — hypothèse « speed=-1 » NON CONFIRMÉE (2026-07-25). Le payload RÉEL
  relevé au journal serveur porte `speed_mps: 0` (pas -1) et **AUCUN champ de cap**.
  La normalisation (parser Android + athlete.py : vitesse<0 → 0, cap<0 → NaN) est
  DÉPLOYÉE et reste une garde utile, mais ce n'était PAS la cause. LEÇON (encore) : lire
  le payload avant de conclure.
- **CAUSE MESURÉE : `horizontal_accuracy_m = 10,1 m` > seuil 8 m** (`precisionMaxSuiviM`)
  → `AdaptateurSuiviAthlete` bloque (`precision_insuffisante`) et l'évaluateur rend
  DEGRADE. À l'arrêt, iOS cesse de rafraîchir le fix (le `timestamp` iPhone GÈLE — vu
  figé 71 s) et la précision se DÉGRADE (5,8 m en marchant → 10,1 m à l'arrêt).
  → RÉSOLU par l'IMMOBILITÉ INERTIELLE (décision Christian : « solution professionnelle »).
  `ImmobiliteInertielle.kt` (PUR, 10 tests) consomme le bloc CoreMotion selon les règles
  d'usage optimal : fenêtre agrégée (~2,4 s @ 10 Hz côté iPhone), PIC en plus de la
  moyenne (un pas isolé se voit au pic), accélération ET rotation (demi-tour sur place),
  confiance ≥ 0,70, `orientation_changed` → pas immobile, hystérésis 2 fenêtres à
  l'ENTRÉE / sortie IMMÉDIATE, fail-closed champs absents. Seuils calés sur le relevé
  réel immobile (0,06 m/s² · pic 0,07 · rot 0,002 · conf 0,99).
  ANCRAGE : immobile confirmé → `AdaptateurSuiviAthlete` sert la DERNIÈRE position
  ACCEPTÉE (ancre stable, le drone ne suit plus la dérive GPS), précision tolérée
  jusqu'à 25 m SEULEMENT dans ce mode (raison=immobile_ancre_inertiel) ; le seuil de
  8 m reste ENTIER en mouvement. `EvaluateurSourceAthlete` : PRET stable (plus
  d'oscillation PRET↔DEGRADE) ; bornes 25 m / âge → PERDU intactes, immobile ou pas.
  UN SEUL détecteur dans Phase3 (même verdict pour évaluateur + adaptateur).
  Tests : +6 adaptateur (ancre servie, non-régression 8 m, fail-closed sans ancre,
  borne 25 m, barrage d'âge, reprise sans faux saut) · +4 évaluateur · +1 parser.
- **AUCUN CAP ENVOYÉ** par l'iPhone (ni `course`, ni `heading`) → `heading_deg: null`
  toujours. MÊME CONSTAT côté BOÎTIER RTK : `heading_deg=--` sur tout le vol du 25/07.
  CONSÉQUENCE MESURÉE : `capEffectif` restait NaN → `tickSuivi` tombait dans la branche
  de repli (offset FIXE plein sud) → **les 4 angles de prise de vue (derrière / devant /
  gauche / droite) donnaient TOUS la même position**. Seule « plongée » fonctionnait.
  → CORRIGÉ par `CapParDeplacement.kt` (PUR, 7 tests) : cap reconstruit depuis la
  trajectoire GPS. Seuil 3 m (sous lequel c'est du bruit → aucun cap produit), lissage
  circulaire 0,35 (un point aberrant ne fait pas pivoter le drone, passage 359°→1° géré),
  péremption 20 s (mieux vaut PAS de cap qu'un cap faux), cap conservé à l'arrêt.
  Branché dans les DEUX sources (`lirePositionAuto` + `lirePositionAthlete`), en COMBLEMENT
  seulement : un cap fourni par la source reste prioritaire. Remis à zéro à chaque
  `demarrerSuivi` (aucun cap hérité d'un vol précédent).
- Champs iPhone qui étaient JETÉS, maintenant transportés (athlete.py + parser + liste) :
  `athlete_name` (la sélection affiche « Vsrre » au lieu de SUJET-73BE742D),
  `vertical_accuracy_m`, `device_id`, `profile_mode`, `competition_number`,
  `schema_version`, `source`, et le bloc **CoreMotion complet** (accélération, rotation,
  pics, confiance, orientation_changed, cadence ~10 Hz sur fenêtre 2,4 s) — jusqu'ici
  stocké mais seul `motion_state` ressortait. AUCUNE décision de vol ne s'y appuie
  encore ; c'est la matière première pour un « immobile » fiable.
- `EvaluateurSourceAthlete` : états PRÊT/DÉGRADÉ/PERDU/REPRISE, fail-closed, reprise = 3
  échantillons. Seuils calés sur la cadence RÉELLE de l'iPhone (~2 s/POST) : `ageMaxPretS=2.5`,
  `ageMaxDegradeS=5.0` (à 1,5 s c'était orange en permanence). Précision PRÊT ≤ 8 m.
- `ClientAthlete` : `lireDerniere()` + `listerSujets()` (fail-closed → ABSENTE / liste vide).
- `AdaptateurSuiviAthlete` (étape 5) : télémétrie → décision « suivre / stationnaire ».
  Garde-fous spec §10/§16 : âge ≤ 3 s, précision ≤ 8 m, vitesse ≤ 30 m/s, REJET de saut GPS
  (garde l'ancienne cible ; ré-ancre après 3 sauts d'affilée). NE COMMANDE PAS le drone.

### Android — écran `AthleteActivity` (affichage seul, étapes 3-4)
Poll ~3 Hz de `/latest`. Liste CLIQUABLE des athlètes connectés (plus de saisie manuelle) via
`/subjects`. Affiche position/précision/2 âges (RÉCEPTION vs GPS)/batterie/état. Journal des
transitions horodaté + bouton « 📤 Partager le journal » (ACTION_SEND). NE COMMANDE PAS le drone.
Mémorise l'athlète choisi dans prefs `cineflight`/`athlete_subject_id` (pour le suivi Phase3).

### Étape 5 — suivi Virtual Stick en simulateur (drapeau `SUIVI_ATHLETE_SIMU`, défaut false)
Quand true, `Phase3Activity.lirePositionAthlete()` remplace `lirePositionAuto()` dans le poller :
lit `/subjects/{id}/latest`, passe par `AdaptateurSuiviAthlete`, remplit les MÊMES
`autoLat/autoLon/autoCap/autoAgeS` + nourrit le prédicteur EXISTANT. `tickSuivi` INCHANGÉ.
- `autoRtk="FLOAT"` si adaptateur autorise (GPS-grade → translation prudente), `"LOST"` sinon
  (→ HOVER fail-closed). Profil forcé PERSONNE (MARCHE : recul 4 m, dist min 3,5 m, haut 3 m).
- `demarrerSuivi` : doctrine RTK SAUTÉE en mode athlète (le GPS téléphone n'est pas du RTK) ;
  gate = adaptateur autorise (reseauOk) + Virtual Stick confirmé (§16).
- ⚠ SIMULATEUR DJI, hélices retirées. `subject_id` lu depuis les prefs (écran Athlète le sauve).
- ⚠ NON QUALIFIÉ : à éprouver en simulateur, puis remettre `SUIVI_ATHLETE_SIMU=false`.
- Mode OBSERVATION `SUIVI_ATHLETE_OBSERVER` (défaut true) : le suivi CALCULE et AFFICHE la
  commande (ligne `OBS athlète — …` dans txtSuivi) mais N'ACTIVE PAS le VS et N'ENVOIE RIEN
  → hélices peuvent rester montées. `false` = vol réel (VS actif, le drone suit). Corrections
  faites en cours de mise au point : seuil d'âge athlète 3 s (pas RTK_AGE_MAX_S=2 s, sinon
  HOVER permanent), `autoRtk="FIX"` (FLOAT verrouillait la translation), `athleteSubjectId`
  lu frais (pas lazy), `athleteRaison` affichée au lieu du message « position voiture ».
  Pour l'observation à l'intérieur : activer le SIMULATEUR DJI (fournit une position drone).

### Suite de tests au 2026-07-25 : 85 classes · 872 cas · 0 échec
(v. 2026-07-22 : 77 classes / 786 cas → +8 classes, +86 cas). Les 5 classes de la chaîne
athlète/prédiction ont bien été EXÉCUTÉES (vérifié à l'horodatage des XML de résultats,
pas au seul « BUILD SUCCESSFUL » — Gradle peut rendre vert sans rien rejouer).
⚠ Toute modification de `SafetyLimits` invalide CONFIG_ID + le condensat de preuves.

### Prédiction athlète — config DÉDIÉE (2026-07-25, à éprouver en OBSERVER)
La config de prédiction RTK voiture (profil MARCHE : `ageMaxS=0,50`, régression sur 1 s,
`interruptionMaxS=1,25`) rendait la prédiction MATHÉMATIQUEMENT inopérante à la cadence
iPhone (~1 POST/2 s, âge 0..2 s) : `predPret` jamais vrai → aucune anticipation → suivi
par bonds de 2 s. → `ProfilSujetMobile.configurationPredictionAthlete()` : fenêtres de
CADENCE élargies (ageMax 2,6 s · purge 5 s · interruption 4 s · contrôle 2,5 s · horizon
2,8 s · régression 6,4/3,0 s · pipeline 0,35 s · min 4 échantillons), barrières de
QUALITÉ et modèles de mouvement INCHANGÉS (verrouillé par test). Branchée dans
`Phase3Activity.predicteur` seulement si `SUIVI_ATHLETE_SIMU`. Tests JVM
`ConfigurationPredictionAthleteTest` (8 cas), dont le décisif : même marche synthétique
(1,5 m/s, POST/2 s relus à 5 Hz) → `pret` avec la config athlète, JAMAIS avec la config
voiture (preuve du défaut d'origine).
RESTENT (décision Christian requise) : cadence iPhone 2 s → 1 Hz (le vrai goulot),
V_MAX_HORIZ 2 m/s (course = 3-5 m/s) et profil COURSE — après qualification seulement.
⚠ Incohérence relevée : `SUIVI_ATHLETE_OBSERVER=false` dans le code (CLAUDE.md disait
« défaut true ») — en l'état, SUIVI_ATHLETE_SIMU=true arme le vol réel sans observation.

### ⚠⚠ VOL BOÎTIER 2026-07-25 — le drone ORBITE au lieu de suivre (vol #7, 10:39-10:41)

Constat pilote : « au lieu de me suivre, le drone tournait DE LOIN autour de moi ».
Journal : pitch SATURÉ 2,0 m/s + yaw −4,5°/s CONSTANTS pendant 45 s = cercle r≈25 m
(v/ω). Altitude 3,00 m tenue parfaitement ; STOP + atterrissage demandés par le pilote ;
sortie VS non acquittée (ok=false) à 10:40:49.
DIAGNOSTIC : signature exacte d'une commande « avant » exécutée EN LATÉRAL — le piège
pitch/roll CONNU du Virtual Stick DJI. `TraductionAxes.versDji` est VÉRIFIÉ CORRECT
(rotation monde→corps, convention boussole ✓). Le suspect est la SÉMANTIQUE SDK :
`INVERSER_ROLL_PITCH=false` (PontDji), jamais vérifié — E-01 n'a testé QUE le throttle,
les axes horizontaux sont invérifiables au sol.
→ **CORRIGÉ (décision Christian) : `INVERSER_ROLL_PITCH = true`** (PontDji). La géométrie
du vol n'a pas d'autre explication (rotation TraductionAxes vérifiée correcte) et le
commentaire du drapeau prévoyait exactement ce basculement. Tous les chemins passent par
`envoyerVitesses` → corrigés d'un coup ; les chemins throttle/yaw seuls (E-03, soccer 2D,
PremierVol) sont inchangés par construction.
→ Bouton « 🧭 TEST AXES (SIMULATEUR) » ajouté (Phase 3, mode dev) : pitch +1 m/s 4 s en
sim, compare la direction du déplacement simulé au cap. Verdict logcat tag `TestAxes`.
CONTRE-VÉRIFICATION avant le prochain vol de suivi : SIM ON → DÉCOLLER → TEST AXES →
attendu « PITCH=AVANT ✓ ». Si le test rend autre chose, c'est LUI qui a raison — revenir
me voir avec le verdict.

Anomalies SECONDAIRES du même journal (à traiter ensuite) :
- Chaîne V4 boîtier : STREAM_STALE permanent (âge ~750 ms), `source_seq` FIGÉ (19449),
  `server_age_ms` cassé (47 h) → fusion V4 jamais prête → suivi en repli brut. Chantier :
  horodatage boîtier→serveur→app. Profil V4 mémorisé = VELO alors que sujet à PIED.
- Voix « Commandes automatiques interrompues » répétée ~7 s pendant PANORAMA (l'attente
  photo affame le watchdog de cycle 350 ms) ; faux « carte mémoire pleine » ; fausse
  alerte VLOS au décollage ; double assemblage pano sans verrou.

### Étape 6 — fusion YOLO (cadrage) : DÉJÀ EN PLACE, s'applique à la personne
La fusion GPS+vision de `tickSuivi` est GÉNÉRIQUE : GPS → translation, YOLO → correction
BORNÉE de yaw + nacelle (7ter et 9bis), la vision NE TOUCHE JAMAIS la translation (§9).
`classesSuivi` (ligne ~697) = {0} PERSON quand profil MARCHE → YOLO suit la PERSONNE en mode
athlète. `demarrerYolo()` est dans le bloc de connexion (pas dans lirePositionAuto) → démarre
aussi en mode athlète. Rien à dupliquer. Ajouté : `yolo(cadrage)=OUI/non` dans la ligne OBS.
⚠ LIMITE : la correction vision ne s'applique QUE si UNE seule personne est vue (`yoloNbVoit==1`) ;
en groupe elle se désactive (repli GPS) — la désambiguïsation « quelle personne est l'athlète »
via le cap GPS reste à faire (raffinement ultérieur). RESTE : étape 7 (essai extérieur).
