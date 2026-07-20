package ca.cineflight.stage.sentinelle

import android.os.SystemClock
import android.util.Log
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.PerceptionInfo
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener

/**
 * LecteurPerception - brique de LECTURE et DIAGNOSTIC de la perception DJI.
 *
 * OBJECTIF (avance sans risque) :
 *   - Lire IPerceptionManager (capteurs d'evitement d'obstacles du drone).
 *   - LOGUER toutes les valeurs recues (tag "LecteurPerception"), pour que,
 *     des reception d'un drone a capteurs (Mini 4 Pro, Mavic 4 Pro...), on voie
 *     IMMEDIATEMENT dans Logcat ce que le drone fournit reellement.
 *   - Exposer des getters simples (distanceHorizontale(), etc.) pour la FUTURE
 *     fusion avec le score d'ouverture - SANS coder la fusion ici.
 *
 * NON DESTRUCTIF :
 *   - Lecture seule. N'appelle AUCUN setter, ne change RIEN au comportement du drone.
 *   - Sur un drone SANS evitement omnidirectionnel (ex. Mini 3), les listeners
 *     ne recoivent rien : les getters renvoient null. La Sentinelle continue alors
 *     sur le score visuel seul (aucune regression).
 *
 * API confirmee par la doc officielle IPerceptionManager :
 *   - PerceptionManager.getInstance()  (package dji.v5.manager.aircraft.perception)
 *   - addPerceptionInformationListener / removePerceptionInformationListener   (MSDK 5.0.0)
 *   - addObstacleDataListener / removeObstacleDataListener                     (MSDK 5.1.0)
 *   - ObstacleData : horizontalObstacleDistance, upwardObstacleDistance,
 *                    downwardObstacleDistance, horizontalAngleInterval (metres)
 *   - PerceptionInfo : isHorizontal/Upward/DownwardObstacleAvoidanceEnabled,
 *                      isVisionPositioningEnabled, obstacleAvoidanceType,
 *                      horizontalObstacleAvoidanceWarningDistance, ...
 *
 * NOTE: Le projet est en SDK 5.10.0 -> addObstacleDataListener (5.1.0) est dispo.
 *
 * HORLOGE MONOTONE INJECTABLE (ObstacleSafetyGate) :
 *   Les timestamps des mesures et le calcul des ages/fraicheur consommes par le gate
 *   proviennent d'UNE SEULE horloge MONOTONE, injectee via [MonotonicClock]. En production
 *   c'est SystemClock.elapsedRealtime() ; en test on injecte une horloge deterministe. Ainsi
 *   les ages des snapshots deviennent testables en JVM PURE, sans drone/SDK/Android.
 *
 *   REGLE : une duree/fraicheur se calcule TOUJOURS avec deux valeurs de la MEME horloge
 *   monotone. On ne soustrait JAMAIS System.currentTimeMillis() (horloge MURALE, conservee
 *   telle quelle pour les dates de diagnostic/journaux/affichage) d'un timestamp monotone.
 */
class LecteurPerception(
    // Horloge MONOTONE injectable. Defaut prod = SystemClock.elapsedRealtime(). En test,
    // injecter une horloge deterministe (ex. MonotonicClock { 10_000L }). Sert UNIQUEMENT
    // aux timestamps des mesures verticales/horizontales et au calcul des ages du gate.
    // (Type MonotonicClock : top-level du package sentinelle, partage avec PerceptionSnapshotStore.)
    private val monotonicClock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
) {

    // Magasin PUR des snapshots (publication atomique + calcul d'age monotone). Toute la
    // logique de fraicheur vit ici, testable en JVM sans DJI. Le callback DJI se contente de
    // lui passer des valeurs brutes ; les lectures snapshotVertical()/Horizontal() delegent.
    private val snapshotStore = PerceptionSnapshotStore(monotonicClock)

    private val TAG = "LecteurPerception"

    @Volatile private var actif = false

    // Dernieres valeurs recues (null = jamais recu / non supporte par le drone).
    @Volatile private var derniereDistanceHorizontale: List<Int>? = null
    @Volatile private var derniereDistanceHaut: Int? = null
    @Volatile private var derniereDistanceBas: Int? = null
    @Volatile private var dernierAngleHorizontal: Int? = null
    @Volatile private var horizontalActif: Boolean = false
    // Etat d'evitement LU (jamais ecrit) : pour diagnostic uniquement.
    @Volatile private var typeEvitement: String? = null      // BRAKE / BYPASS / CLOSE / null
    // Resultat du GETTER direct getObstacleAvoidanceType(). Journalise a cote de la valeur
    // du listener pour COMPARAISON ; on ne prejuge pas de laquelle est correcte.
    @Volatile private var typeEvitementGetter: String? = null
    @Volatile private var visionActive: Boolean = false
    @Volatile private var warnHDist: Any? = null
    @Volatile private var brakeHDist: Any? = null
    @Volatile private var derniereMajMs: Long = 0L
    // Mesure REELLE de la cadence/fraicheur des callbacks (pour prouver quantitativement
    // que les trames sont rares ou perimees, au lieu de le deduire visuellement).
    @Volatile private var dernierCallbackMs: Long = 0L        // horodatage du callback precedent (toute trame)
    @Volatile private var derniereTrameValideMs: Long = 0L    // horodatage de la derniere trame a LISTE EXPLOITABLE

    // ObstacleSafetyGate — ÉTAPE 1/2 : les snapshots VERTICAL et HORIZONTAL (valeurs +
    // timestamps monotones, publication/lecture atomiques, calcul d'age a la lecture) sont
    // desormais portes par snapshotStore (PerceptionSnapshotStore). Le callback DJI ne fait
    // que lui transmettre les valeurs brutes.

    // Fournisseurs de contexte pour le logger diagnostic (optionnels, branches par
    // MainActivity). Nuls par defaut : le lecteur ne depend de rien. Lecture seule.
    @Volatile var fournisseurModele: (() -> String)? = null
    @Volatile var fournisseurVsActif: (() -> Boolean)? = null

    /**
     * HOOK DE COMPTAGE : invoque UNE SEULE FOIS PAR CALLBACK DJI reel (jamais depuis un
     * moniteur periodique). Permet a MainActivity de compter les trames sans recompter
     * la derniere valeur memorisee. Parametres :
     *   listeVide  : true si la liste horizontale est vide/null (trame sans mesure).
     *   minMm      : distance minimale (mm) de la liste, ou null si vide.
     *   intervalleMs : intervalle reel depuis le callback precedent, -1 si premier.
     * Nul par defaut : aucune dependance. Blindé cote appelant. Lecture seule.
     */
    @Volatile var onTrameComptee: ((listeVide: Boolean, minMm: Int?, intervalleMs: Long) -> Unit)? = null

    // Listener des INFOS de perception (etats des capteurs, distances de reglage).
    private val infoListener = PerceptionInformationListener { info: PerceptionInfo? ->
        if (info == null) return@PerceptionInformationListener
        try {
            horizontalActif = info.isHorizontalObstacleAvoidanceEnabled
            typeEvitement = try { info.obstacleAvoidanceType?.toString() } catch (_: Throwable) { null }
            visionActive = try { info.isVisionPositioningEnabled } catch (_: Throwable) { false }
            warnHDist = try { info.horizontalObstacleAvoidanceWarningDistance } catch (_: Throwable) { null }
            brakeHDist = try { info.horizontalObstacleAvoidanceBrakingDistance } catch (_: Throwable) { null }
            Log.i(TAG, "INFO oa[H=${info.isHorizontalObstacleAvoidanceEnabled}" +
                    " U=${info.isUpwardObstacleAvoidanceEnabled}" +
                    " D=${info.isDownwardObstacleAvoidanceEnabled}]" +
                    " vision=${info.isVisionPositioningEnabled}" +
                    " type=${info.obstacleAvoidanceType}" +
                    " warnH=${info.horizontalObstacleAvoidanceWarningDistance}" +
                    " brakeH=${info.horizontalObstacleAvoidanceBrakingDistance}")
        } catch (e: Throwable) {
            Log.e(TAG, "lecture PerceptionInfo: $e")
        }
    }

    // Listener des DONNEES d'obstacle (les distances reelles a fusionner).
    private val obstacleListener = ObstacleDataListener { data: ObstacleData? ->
        if (data == null) return@ObstacleDataListener
        try {
            val maintenant = System.currentTimeMillis()
            // Intervalle REEL depuis le callback precedent (toute trame, valide ou vide).
            // -1 si c'est le tout premier callback (aucun precedent a comparer).
            val callbackIntervalMs = if (dernierCallbackMs > 0L) maintenant - dernierCallbackMs else -1L
            dernierCallbackMs = maintenant
            derniereDistanceHorizontale = data.horizontalObstacleDistance
            derniereDistanceHaut = data.upwardObstacleDistance
            derniereDistanceBas = data.downwardObstacleDistance
            dernierAngleHorizontal = data.horizontalAngleInterval
            derniereMajMs = maintenant
            // ObstacleSafetyGate étape 1/2 : publie les snapshots VERTICAL et HORIZONTAL via
            // le store pur (valeurs brutes -> timestamps monotones internes, ecriture atomique).
            // Le consommateur ne lira jamais un melange trame/timestamp. Horloge MONOTONE injectee.
            try {
                snapshotStore.publierVertical(
                    upwardMm = data.upwardObstacleDistance,
                    downwardMm = data.downwardObstacleDistance
                )
                snapshotStore.publierHorizontal(data.horizontalObstacleDistance)
            } catch (_: Throwable) {}
            // Fraicheur : une trame est "exploitable" si sa liste horizontale est non vide.
            val listeCourante = data.horizontalObstacleDistance
            val trameExploitable = (listeCourante != null && listeCourante.isNotEmpty())
            if (trameExploitable) derniereTrameValideMs = maintenant
            // Age de la DERNIERE trame exploitable (0 si la trame courante l'est ; -1 si aucune vue).
            val lastValidAgeMs = if (derniereTrameValideMs > 0L) maintenant - derniereTrameValideMs else -1L
            // COMPTAGE : exactement une fois par callback DJI reel. Blindé : ne jamais
            // laisser une exception de l'abonne remonter dans le callback DJI.
            try {
                onTrameComptee?.invoke(!trameExploitable, if (trameExploitable) listeCourante?.minOrNull() else null, callbackIntervalMs)
            } catch (_: Throwable) {}
            // DIAGNOSTIC PASSIF (off par defaut) : empile une PERCEPTION_FRAME. Non bloquant,
            // jamais d'exception remontante. N'influence RIEN.
            try {
                if (ca.cineflight.stage.sentinelle.PerceptionDiagLogger.actif) {
                    val liste = listeCourante
                    val raw = liste?.minOrNull()
                    val sentinel = (raw != null && raw <= 0)
                    // Deux interpretations affichees cote a cote via unit_assumption ; ici on
                    // journalise la BRUTE + une interpretation "mm" (hypothese du code d'evitement).
                    val interpM = raw?.let { it / 1000.0 }
                    val cid = ca.cineflight.stage.sentinelle.PerceptionDiagLogger.nouveauCycleId()
                    ca.cineflight.stage.sentinelle.PerceptionDiagLogger.perceptionFrame(
                        cycleId = cid,
                        receptionMs = derniereMajMs,
                        sourceMs = null,
                        callbackIntervalMs = callbackIntervalMs,
                        lastValidFrameAgeMs = lastValidAgeMs,
                        lastValidFrameTs = if (derniereTrameValideMs > 0L) derniereTrameValideMs else -1L,
                        aircraftModel = (fournisseurModele?.invoke() ?: "?"),
                        virtualStickActive = (fournisseurVsActif?.invoke() ?: false),
                        sector = "MIN_ALL",
                        rawDistance = raw,
                        rawList = liste,
                        rawType = "horizontalObstacleDistance",
                        interpretedM = interpM,
                        unitAssumption = "millimeters",
                        validity = trameExploitable,
                        sentinelDetected = sentinel,
                        listenerActive = actif
                    )
                }
            } catch (_: Throwable) {}
            Log.i(TAG, "OBSTACLE H=${data.horizontalObstacleDistance}m" +
                    " U=${data.upwardObstacleDistance}m" +
                    " D=${data.downwardObstacleDistance}m" +
                    " angleH=${data.horizontalAngleInterval}")
        } catch (e: Throwable) {
            Log.e(TAG, "lecture ObstacleData: $e")
        }
    }

    /** Demarre l'ecoute de la perception. Idempotent. */
    fun demarrer() {
        if (actif) return
        actif = true
        try {
            val pm = PerceptionManager.getInstance()
            pm.addPerceptionInformationListener(infoListener)
            pm.addObstacleDataListener(obstacleListener)
            Log.i(TAG, "demarre (listeners enregistres)")
        } catch (e: Throwable) {
            // Permettre une nouvelle tentative après connexion ultérieure du drone.
            actif = false
            Log.w(TAG, "perception indisponible sur ce drone: $e")
        }
    }

    /** Arrete l'ecoute et remet les valeurs a null. Idempotent. */
    fun arreter() {
        if (!actif) return
        actif = false
        try {
            val pm = PerceptionManager.getInstance()
            pm.removePerceptionInformationListener(infoListener)
            pm.removeObstacleDataListener(obstacleListener)
        } catch (e: Throwable) {
            Log.w(TAG, "arret perception: $e")
        }
        derniereDistanceHorizontale = null
        derniereDistanceHaut = null
        derniereDistanceBas = null
        dernierAngleHorizontal = null
        horizontalActif = false
        typeEvitement = null
        visionActive = false
        warnHDist = null
        brakeHDist = null
        derniereMajMs = 0L
        dernierCallbackMs = 0L
        derniereTrameValideMs = 0L
        snapshotStore.effacer()      // ObstacleSafetyGate : plus de perception -> INDISPONIBLE
        // Ne PAS nettoyer onTrameComptee ici : demarrer()/arreter() peuvent s'enchainer
        // (re-abonnement idempotent) et le hook est pose une fois par MainActivity. Le
        // comptage ne se declenche de toute facon que sur un vrai callback DJI.
        Log.i(TAG, "arrete")
    }

    // ---- Getters pour la FUTURE fusion (lecture seule, AUCUNE decision ici) ----

    /** Vrai si on a recu des donnees d'obstacle recentes (< 2s). */
    fun perceptionDisponible(): Boolean =
        derniereMajMs > 0L && (System.currentTimeMillis() - derniereMajMs) < 2000L

    /** Horodatage (ms) de la derniere trame a liste EXPLOITABLE, ou 0 si aucune vue. Lecture seule. */
    fun derniereTrameValideMs(): Long = derniereTrameValideMs

    /**
     * Age (ms) de la derniere trame exploitable a l'instant de l'appel, ou -1 si aucune vue.
     * Permet a l'observateur de mesurer la fraicheur sans dependre d'un callback recent.
     */
    fun ageDerniereTrameValideMs(): Long =
        if (derniereTrameValideMs > 0L) System.currentTimeMillis() - derniereTrameValideMs else -1L

    /**
     * ObstacleSafetyGate étape 1 : lecture ATOMIQUE du snapshot vertical, avec ages FRAIS
     * calcules a l'instant de l'appel (horloge monotone - timestamp du canal). A appeler UNE
     * FOIS par tick. Renvoie null si aucun callback vertical n'a encore ete publie.
     * Age = Long.MAX_VALUE pour un canal jamais recu (mm null) -> INDISPONIBLE cote gate.
     * Delegue au store pur (logique identique en prod et en test).
     */
    fun snapshotVertical(): ca.cineflight.stage.control.ObstacleSafetyGate.VerticalSnapshot? =
        snapshotStore.snapshotVertical()

    /**
     * ObstacleSafetyGate étape 2 : lecture ATOMIQUE du snapshot horizontal, age FRAIS calcule
     * a l'appel. A appeler UNE FOIS par tick. null si aucun callback publie. Delegue au store.
     */
    fun snapshotHorizontal(): ca.cineflight.stage.control.ObstacleSafetyGate.HorizontalSnapshot? =
        snapshotStore.snapshotHorizontal()

    /**
     * ObstacleSafetyGate : lecture COMBINEE vertical + horizontal au MEME instant monotone, a
     * appeler UNE FOIS par tick miroir. Les ages des deux volets sont coherents entre eux. C'est
     * cet objet qui est a la fois journalise (PERCEPTION_SAMPLE) et transmis au gate. Delegue au store.
     */
    fun snapshotPourGate(): PerceptionSnapshotStore.GatePerceptionSnapshot =
        snapshotStore.snapshotPourGate()

    /** Distance horizontale au plus proche obstacle (m), ou null si indispo. */
    fun distanceHorizontale(): Int? = if (perceptionDisponible()) derniereDistanceHorizontale?.minOrNull() else null

    /**
     * Liste COMPLETE des distances horizontales par secteur angulaire (mm), ou null.
     * Le secteur i couvre l'angle (i * intervalleAngulaire()) degres autour du drone.
     * Permet un evitement DIRECTIONNEL (s'ecarter du bon cote) plutot que reculer aveuglement.
     * A interpreter une fois la structure reelle observee en vol (taille, orientation du 0).
     */
    fun distanceParSecteur(): List<Int>? = if (perceptionDisponible()) derniereDistanceHorizontale else null

    /** Intervalle angulaire (degres) entre deux mesures de distanceParSecteur(), ou null. */
    fun intervalleAngulaire(): Int? = if (perceptionDisponible()) dernierAngleHorizontal else null

    /** Resultat d'analyse directionnelle : secteur le plus degage. */
    data class SecteurDegage(val index: Int, val angleDeg: Int, val distance: Int, val nbSecteurs: Int)

    /**
     * Trouve le secteur horizontal le PLUS DEGAGE (distance max), pour SUGGERER
     * au pilote une direction ou l'espace est le plus grand. PRUDENT : ne donne
     * que l'index/angle brut, pas "droite/gauche" (la correspondance secteur->cardinal
     * doit etre confirmee en vol avant toute interpretation). Lecture seule.
     * @return null si pas de donnees ou liste vide.
     */
    fun secteurLePlusDegage(): SecteurDegage? {
        val liste = if (perceptionDisponible()) derniereDistanceHorizontale else null
        if (liste == null || liste.isEmpty()) return null
        val interval = dernierAngleHorizontal ?: 0
        var idxMax = 0
        var distMax = liste[0]
        for (i in liste.indices) {
            if (liste[i] > distMax) { distMax = liste[i]; idxMax = i }
        }
        val angle = if (interval > 0) idxMax * interval else -1
        return SecteurDegage(idxMax, angle, distMax, liste.size)
    }

    /** Distance vers le haut (m), ou null si indispo. */
    fun distanceHaut(): Int? = if (perceptionDisponible()) derniereDistanceHaut else null

    /** Distance vers le bas (m), ou null si indispo. */
    fun distanceBas(): Int? = if (perceptionDisponible()) derniereDistanceBas else null

    /**
     * INTERROGE le type d'evitement via le GETTER direct getObstacleAvoidanceType().
     * LECTURE SEULE : aucun setter, aucune modification du comportement du drone.
     *
     * Pourquoi ce getter EN PLUS du PerceptionInformationListener : l'issue DJI #618
     * documente un defaut du LISTENER — lors d'un changement de mode, il peut ne pas
     * emettre de nouveau callback, et l'objet PerceptionInfo precedemment fourni peut
     * etre mute silencieusement. #618 NE demontre PAS que ce getter direct est plus
     * fiable, ni qu'il est instable ; elle ne concerne que le listener. On lit donc les
     * DEUX et on les journalise cote a cote pour comparaison, sans prejuger de la source
     * correcte. Toute divergence observee (ex. getter CLOSE puis BRAKE d'une lecture a
     * l'autre) doit rester decrite comme un changement d'etat observe dont la cause n'est
     * PAS identifiee dans les logs — et non comme une preuve que le getter "ment".
     *
     * Le resultat est journalise via onResultat("BRAKE"|"BYPASS"|"CLOSE") ou onErreur(description).
     */
    fun interrogerTypeEvitement(onResultat: (String) -> Unit, onErreur: (String) -> Unit) {
        try {
            PerceptionManager.getInstance().getObstacleAvoidanceType(
                object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType> {
                    override fun onSuccess(t: dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType?) {
                        val nom = t?.toString() ?: "null"
                        typeEvitementGetter = nom
                        try { onResultat(nom) } catch (_: Throwable) {}
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        val desc = try { error.description() } catch (_: Throwable) { error.toString() }
                        try { onErreur(desc) } catch (_: Throwable) {}
                    }
                })
        } catch (e: Throwable) {
            try { onErreur("exception: ${e.message}") } catch (_: Throwable) {}
        }
    }

    /** Dernier type d'evitement obtenu par le GETTER direct (journalise a cote du listener pour comparaison). */
    fun typeEvitementGetterLu(): String? = typeEvitementGetter

    /**
     * SETTER (TEST A/B) : change le type d'evitement natif DJI. C'est un ECRITURE reelle
     * sur le drone, a n'utiliser QUE pour le test de diagnostic manuel, au sol, helices
     * retirees. type = "BRAKE" | "BYPASS" | "CLOSE". Journalise via onFait / onErreur.
     * Ne touche PAS aux commandes de vol ; active seulement l'evitement NATIF du drone.
     */
    fun definirTypeEvitement(type: String, onFait: (String) -> Unit, onErreur: (String) -> Unit) {
        try {
            val cible = when (type.uppercase()) {
                "BRAKE" -> dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType.BRAKE
                "BYPASS" -> dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType.BYPASS
                "CLOSE" -> dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType.CLOSE
                else -> { onErreur("type inconnu: $type"); return }
            }
            PerceptionManager.getInstance().setObstacleAvoidanceType(cible,
                object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        try { onFait(type.uppercase()) } catch (_: Throwable) {}
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        val desc = try { error.description() } catch (_: Throwable) { error.toString() }
                        try { onErreur(desc) } catch (_: Throwable) {}
                    }
                })
        } catch (e: Throwable) {
            try { onErreur("exception: ${e.message}") } catch (_: Throwable) {}
        }
    }

    /** Type d'evitement LU sur le drone (BRAKE/BYPASS/CLOSE/null). Lecture seule. */
    fun typeEvitementLu(): String? = typeEvitement
    /** Vrai si le vision positioning est actif (lu). */
    fun visionPositioningActive(): Boolean = visionActive
    /** Distance d'avertissement H reglee sur le drone (lue, format SDK). */
    fun distanceAvertissementH(): Any? = warnHDist
    /** Distance de freinage H reglee sur le drone (lue, format SDK). */
    fun distanceFreinageH(): Any? = brakeHDist
    /** Vrai si l'evitement horizontal est actif sur le drone. */
    fun evitementHorizontalActif(): Boolean = horizontalActif

    /**
     * MODE OBSERVATION : logue une trame complete et lisible de la perception.
     * A appeler periodiquement (ex. 1 Hz) au PREMIER vol avec un drone a capteurs,
     * pour DECOUVRIR la structure reelle des donnees (nombre de secteurs, intervalle,
     * orientation du secteur 0, unite reelle) AVANT de coder un evitement directionnel.
     * Lecture pure : ne touche a rien.
     */
    fun journaliserTrame() {
        if (!perceptionDisponible()) {
            Log.i(TAG, "TRAME: aucune donnee recente (drone sans evitement omni, ou non connecte)")
            return
        }
        val liste = derniereDistanceHorizontale
        val interval = dernierAngleHorizontal ?: -1
        if (liste == null || liste.isEmpty()) {
            Log.i(TAG, "TRAME: liste horizontale vide. haut=${derniereDistanceHaut} bas=${derniereDistanceBas}")
            return
        }
        val n = liste.size
        val minVal = liste.min()
        val idxMin = liste.indexOf(minVal)
        val angleMin = if (interval > 0) idxMin * interval else -1
        val maxVal = liste.max()
        Log.i(TAG, "TRAME: secteurs=$n intervalle=${interval}deg " +
                "couverture=${if (interval > 0) n * interval else -1}deg")
        Log.i(TAG, "TRAME: plus_proche=${minVal} (secteur=$idxMin angle=${angleMin}deg) " +
                "plus_loin=${maxVal}")
        Log.i(TAG, "TRAME: haut=${derniereDistanceHaut} bas=${derniereDistanceBas}")
        // liste brute complete (pour voir la forme reelle : valeurs, 0, valeurs sentinelles)
        Log.i(TAG, "TRAME: brut=$liste")
    }
}

