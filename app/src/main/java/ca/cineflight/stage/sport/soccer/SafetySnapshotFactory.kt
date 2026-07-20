package ca.cineflight.stage.sport.soccer

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SafetySnapshotFactory — PUBLICATION ATOMIQUE de l'instantane de securite (NC-T2-001).
 *
 * PROBLEME RESOLU. Auparavant, l'appelant (Phase3Activity) construisait le SafetySnapshot
 * en lisant une a une des variables @Volatile distinctes (modeManuel, vsActif, enVol, RTK...).
 * Chaque lecture etait visible, mais l'ENSEMBLE ne l'etait pas : entre la lecture du premier
 * champ et celle du dernier, une autre thread pouvait modifier certaines variables. Le snapshot
 * pouvait donc MELANGER des etats provenant d'instants differents ("tearing" inter-champs).
 *
 * SOLUTION. On capture d'abord TOUS les signaux bruts en une seule construction immuable
 * ({@link RawSafetySample}). La fabrique transforme cet echantillon UNIQUE en un SafetySnapshot
 * immuable, publie de facon atomique avec un NUMERO DE GENERATION monotone. Un lecteur voit
 * toujours un snapshot COHERENT (issu d'un seul echantillon) et jamais un etat partiel.
 *
 * Cette classe est PURE (aucun SDK/Android) et donc testable, y compris sous concurrence.
 * FAIL-CLOSED : les valeurs par defaut de RawSafetySample bloquent le soccer (tout a false,
 * sauf les cas ou "true" est le defaut sur de compat, ex. operatorNearRail garde la semantique
 * d'origine du SafetySnapshot).
 */
class SafetySnapshotFactory {

    /** Snapshot publie + sa generation. Immuable ; toujours coherent. */
    data class Published(
        val snapshot: FlightCommandArbiter.SafetySnapshot,
        /** Numero monotone croissant, unique par publication. */
        val generation: Long,
    )

    private val genCounter = AtomicLong(0L)
    // La reference elle-meme est publiee atomiquement : un lecteur voit soit l'ancien
    // Published complet, soit le nouveau complet, jamais un melange.
    private val current = AtomicReference<Published?>(null)

    /**
     * Publie un nouveau snapshot a partir d'un echantillon brut capture EN UNE PASSE.
     * Incremente la generation et remplace atomiquement la reference courante.
     * @return le Published (snapshot + generation) qui vient d'etre publie.
     */
    fun publish(sample: RawSafetySample): Published {
        val gen = genCounter.incrementAndGet()
        val published = Published(sample.toSnapshot(), gen)
        current.set(published)   // publication atomique de la reference complete
        return published
    }

    /** Dernier snapshot publie, ou null si rien n'a encore ete publie. Lecture atomique. */
    fun latest(): Published? = current.get()

    companion object {
        /**
         * Construit directement un SafetySnapshot immuable a partir d'un echantillon brut,
         * SANS passer par l'etat de publication (utile la ou on veut juste la conversion
         * atomique champ->champ garantie par l'immuabilite de RawSafetySample).
         */
        fun snapshotDe(sample: RawSafetySample): FlightCommandArbiter.SafetySnapshot =
            sample.toSnapshot()
    }
}

/**
 * RawSafetySample — PHOTO INSTANTANEE des signaux bruts de securite, capturee en UNE SEULE
 * construction. Une fois cree, il est immuable : le passer a la fabrique garantit qu'aucun
 * champ ne peut changer "en route". C'est le point de capture unique qui remplace les lectures
 * eparpillees de variables @Volatile.
 *
 * Les valeurs par defaut appliquent le principe FAIL-CLOSED (ce qui n'est pas prouve vrai
 * bloque le soccer), en coherence avec les defauts du SafetySnapshot.
 */
data class RawSafetySample(
    val pilotOverride: Boolean = false,
    val emergencyStop: Boolean = false,
    val obstacleGateAllows: Boolean = false,
    val virtualStickAvailable: Boolean = false,
    val inFlightCompatible: Boolean = false,
    val railLoadedAndValid: Boolean = false,
    val dronePositionFresh: Boolean = false,
    val actionFreshAndConfident: Boolean = false,
    val batteryOk: Boolean = false,
    val corridorClear: Boolean = false,
    val operatorNearRail: Boolean = true,
) {
    /** Conversion 1:1 vers le SafetySnapshot immuable de l'arbitre. */
    fun toSnapshot(): FlightCommandArbiter.SafetySnapshot =
        FlightCommandArbiter.SafetySnapshot(
            pilotOverride = pilotOverride,
            emergencyStop = emergencyStop,
            obstacleGateAllows = obstacleGateAllows,
            virtualStickAvailable = virtualStickAvailable,
            inFlightCompatible = inFlightCompatible,
            railLoadedAndValid = railLoadedAndValid,
            dronePositionFresh = dronePositionFresh,
            actionFreshAndConfident = actionFreshAndConfident,
            batteryOk = batteryOk,
            corridorClear = corridorClear,
            operatorNearRail = operatorNearRail,
        )
}
