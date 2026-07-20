package ca.cineflight.stage.sentinelle

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * PerceptionDiagLogger — journal de DIAGNOSTIC PASSIF pour le test au sol de l'evitement.
 *
 * BUT : capturer, sans jamais influencer le pilotage, la chaine complete :
 *   PERCEPTION_FRAME -> AVOIDANCE_INPUT -> AVOIDANCE_OUTPUT -> SDK_COMMAND_FINAL
 * afin de repondre au sol (helices enlevees) aux questions non prouvees a l'execution :
 * le drone transmet-il des obstacles ? quelle unite ? quelle valeur = "aucun obstacle" ?
 * la correction touche-t-elle le bon axe ? la commande corrigee arrive-t-elle au SDK ?
 *
 * ===================== GARDE-FOUS ABSOLUS =====================
 * REGLE : perdre un log est acceptable ; retarder une commande de vol ne l'est JAMAIS.
 *  - DESACTIVE par defaut (flag `actif=false`). Inerte tant qu'on ne l'active pas.
 *  - Ne modifie AUCUNE valeur vx/vy/vz/yaw et ne change AUCUN chemin de commande.
 *  - Les points d'appel ne font qu'empiler une chaine dans une FILE BORNEE NON BLOQUANTE
 *    (offer, jamais put) : si la file est pleine, on ABANDONNE l'entree (drop) au lieu
 *    de ralentir la boucle Virtual Stick.
 *  - AUCUNE ecriture disque : uniquement Logcat, et depuis un THREAD SEPARE (le consumer),
 *    jamais dans le callback DJI ni dans la boucle de controle.
 *  - AUCUNE exception ne peut remonter : tous les points d'entree sont blindes.
 *  - AUCUNE attente : les methodes appelees depuis le pilotage sont O(1) et non bloquantes.
 */
object PerceptionDiagLogger {

    private const val TAG = "PerceptionDiag"

    // Flag maitre. false = totalement inerte (aucune allocation, aucun thread).
    @Volatile var actif: Boolean = false
        private set

    // File bornee : si pleine, on drop (jamais de blocage du producteur).
    private const val CAPACITE = 512
    private val file = ArrayBlockingQueue<String>(CAPACITE)
    @Volatile private var consumer: Thread? = null
    private val comptesPerdus = AtomicLong(0)

    // Identifiant de cycle pour correler PERCEPTION_FRAME/INPUT/OUTPUT/FINAL d'un meme tick.
    private val compteurCycle = AtomicLong(0)

    /** Alloue un id de cycle (VS-<n>) a poser sur les 4 traces d'un meme tick de controle. */
    fun nouveauCycleId(): String = "VS-" + compteurCycle.incrementAndGet()

    /** Active le logger et demarre le thread consumer. Idempotent. A appeler hors boucle. */
    @Synchronized
    fun activer() {
        if (actif) return
        actif = true
        file.clear()
        comptesPerdus.set(0)
        val t = Thread({
            try {
                while (actif || file.isNotEmpty()) {
                    val ligne = file.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    try { Log.i(TAG, ligne) } catch (_: Throwable) {}
                }
            } catch (_: InterruptedException) {
                // arret demande
            } catch (_: Throwable) {
                // jamais laisser une exception tuer le thread silencieusement sans trace
                try { Log.e(TAG, "consumer arrete sur erreur") } catch (_: Throwable) {}
            }
        }, "PerceptionDiag-consumer")
        t.isDaemon = true
        consumer = t
        try { t.start() } catch (_: Throwable) { actif = false }
        emit("LOGGER actif=true capacite=$CAPACITE")
    }

    /** Desactive le logger. Le consumer se termine apres avoir vide la file. Idempotent. */
    @Synchronized
    fun desactiver() {
        if (!actif) return
        val perdus = comptesPerdus.get()
        emit("LOGGER actif=false logs_perdus=$perdus")
        actif = false
        try { consumer?.interrupt() } catch (_: Throwable) {}
        consumer = null
    }

    // Empilage non bloquant. Jamais d'exception, jamais d'attente.
    private fun emit(ligne: String) {
        if (!actif) return
        try {
            if (!file.offer(ligne)) comptesPerdus.incrementAndGet()   // drop si plein
        } catch (_: Throwable) { /* jamais remonter vers le pilotage */ }
    }

    // ---------- 1) TRAME DE PERCEPTION ----------
    /**
     * @param callbackIntervalMs temps REEL depuis le callback precedent (toute trame), -1 si premier.
     * @param lastValidFrameAgeMs age (ms) de la derniere trame a liste EXPLOITABLE, -1 si aucune.
     * @param lastValidFrameTs horodatage (ms) de cette derniere trame exploitable, -1 si aucune.
     * @param rawDistance valeur brute (min de la liste, telle que fournie par le SDK).
     * @param rawList liste brute complete des secteurs (pour voir 0 / sentinelles).
     * @param interpretedM valeur interpretee en metres selon l'hypothese unitAssumption.
     * @param unitAssumption "millimeters" | "meters" | "unknown".
     * @param sentinelDetected true si une valeur ressemble a "aucune detection" (0 / <=0).
     *
     * NOTE : on ne journalise PLUS un age_ms artificiellement a 0 au moment du callback.
     * On mesure la cadence reelle (callback_interval_ms) et la fraicheur reelle de la
     * derniere trame exploitable (last_valid_frame_age_ms / _timestamp).
     */
    fun perceptionFrame(
        cycleId: String,
        receptionMs: Long,
        sourceMs: Long?,
        callbackIntervalMs: Long,
        lastValidFrameAgeMs: Long,
        lastValidFrameTs: Long,
        aircraftModel: String,
        virtualStickActive: Boolean,
        sector: String,
        rawDistance: Int?,
        rawList: List<Int>?,
        rawType: String,
        interpretedM: Double?,
        unitAssumption: String,
        validity: Boolean,
        sentinelDetected: Boolean,
        listenerActive: Boolean
    ) {
        if (!actif) return
        emit(
            "PERCEPTION_FRAME cycle_id=$cycleId ts_reception=$receptionMs ts_source=${sourceMs ?: "?"}" +
            " callback_interval_ms=$callbackIntervalMs last_valid_frame_age_ms=$lastValidFrameAgeMs" +
            " last_valid_frame_timestamp=$lastValidFrameTs" +
            " model=$aircraftModel vs_active=$virtualStickActive sector=$sector" +
            " raw_distance=${rawDistance ?: "null"} raw_type=$rawType interpreted_m=${interpretedM ?: "null"}" +
            " unit_assumption=$unitAssumption valid=$validity sentinel=$sentinelDetected" +
            " listener_active=$listenerActive raw_list=${rawList ?: "null"}"
        )
    }

    // ---------- 2) ENTREE DE appliquerEvitement ----------
    fun avoidanceInput(
        cycleId: String,
        ts: Long,
        perceptionAgeMs: Long,
        modeEvitement: Int,
        aircraftModel: String,
        vxBefore: Float, vyBefore: Float, vzBefore: Float, yawBefore: Float,
        nearestSector: String,
        nearestRaw: Int?,
        nearestM: Double?
    ) {
        if (!actif) return
        emit(
            "AVOIDANCE_INPUT cycle_id=$cycleId ts=$ts perception_age_ms=$perceptionAgeMs mode=$modeEvitement" +
            " model=$aircraftModel vx_before=$vxBefore vy_before=$vyBefore vz_before=$vzBefore yaw_before=$yawBefore" +
            " nearest_sector=$nearestSector nearest_raw=${nearestRaw ?: "null"} nearest_m=${nearestM ?: "null"}"
        )
    }

    // ---------- 3) SORTIE DE appliquerEvitement ----------
    /** @param action UNCHANGED | SLOWED | BLOCKED | STOPPED. */
    fun avoidanceOutput(
        cycleId: String,
        vxAfter: Float, vyAfter: Float, vzAfter: Float, yawAfter: Float,
        action: String,
        reason: String
    ) {
        if (!actif) return
        emit(
            "AVOIDANCE_OUTPUT cycle_id=$cycleId vx_after=$vxAfter vy_after=$vyAfter vz_after=$vzAfter" +
            " yaw_after=$yawAfter action=$action reason=$reason"
        )
    }

    // ---------- 4) DERNIER POINT AVANT LE SDK ----------
    fun sdkCommandFinal(
        cycleId: String,
        vxFinal: Float, vyFinal: Float, vzFinal: Float, yawFinal: Float,
        coordinateSystem: String
    ) {
        if (!actif) return
        emit(
            "SDK_COMMAND_FINAL cycle_id=$cycleId vx_final=$vxFinal vy_final=$vyFinal vz_final=$vzFinal" +
            " yaw_final=$yawFinal coordinate_system=$coordinateSystem"
        )
    }

    // ---------- 5) TRANSITIONS D'OBSERVATION (maillon 2, chemin normal) ----------
    /** Journalise une TRANSITION d'etat du moniteur d'observation passive. Non
     * bloquant (meme file bornee). N'a AUCUN effet sur le pilotage. */
    fun transition(evenement: String, detail: String = "") {
        if (!actif) return
        emit("PERCEPTION_TRANSITION evt=$evenement $detail")
    }
}
