package ca.cineflight.stage.sport.soccer

/**
 * Soccer2DEmissionLog — ligne de journal HAUTE FREQUENCE de l'emission 2D (Phase 1.4).
 *
 * Trace, a CHAQUE cycle du chemin 2D (altitude seule), tout ce qu'il faut pour reconstituer
 * la decision d'emission APRES COUP (analyse des essais E-04..E-12, preuve pour le dossier) :
 *
 *   ts_ms gen throttle_req throttle_wd throttle_emis wd_frais wd_age_ms etat raison snapshot
 *
 * - gen           : numero de generation du SafetySnapshot (publication atomique, Phase 1.1).
 * - throttle_req  : throttle demande par le controleur d'altitude (avant toute barriere).
 * - throttle_wd   : throttle apres le WATCHDOG (Phase 1.2) — 0 si cycle mort.
 * - throttle_emis : throttle REELLEMENT emis apres l'arbitre/double verrou (0 si bloque).
 * - wd_frais/age  : etat du watchdog au moment de l'emission.
 * - etat/raison   : verdict de Emission2DGuard.
 * - snapshot      : les 11 conditions de securite (chaine binaire ordonnee + libelle).
 *
 * INVARIANT prouvable sur plusieurs sessions : tant que le flag reel est off OU vMax=0,
 * throttle_emis reste 0 et etat != ACTIVE. Le journal permet de le DEMONTRER.
 *
 * PURETE : aucune dependance Android (pas de Log). L'appelant fait :
 *   Log.i(Soccer2DEmissionLog.TAG, Soccer2DEmissionLog.ligne(...))
 */
object Soccer2DEmissionLog {

    const val TAG = "SOCCER_2D_EMISSION"

    /**
     * @param tsMs horodatage (epoch ms) du cycle.
     * @param generation numero de generation du snapshot publie atomiquement.
     * @param throttleDemandeMps throttle propose par le controleur d'altitude (avant watchdog).
     * @param throttleSurveilleMps throttle apres le watchdog (0 si cycle mort).
     * @param throttleEmisMps throttle reellement emis (0 si l'arbitre bloque).
     * @param watchdogFrais etat du watchdog (cycle frais ?) au moment de l'emission.
     * @param watchdogAgeMs age du dernier battement en ms (Long.MAX_VALUE si aucun).
     * @param etat verdict de Emission2DGuard.
     * @param raison raison textuelle du verdict.
     * @param snapshot instantane de securite trace (11 conditions).
     * @param emis true si une commande non neutre a ete reellement envoyee.
     */
    fun ligne(
        tsMs: Long,
        generation: Long,
        throttleDemandeMps: Float,
        throttleSurveilleMps: Float,
        throttleEmisMps: Float,
        watchdogFrais: Boolean,
        watchdogAgeMs: Long,
        etat: Emission2DGuard.Etat,
        raison: String,
        snapshot: FlightCommandArbiter.SafetySnapshot,
        emis: Boolean,
    ): String = buildString {
        append("ts_ms=").append(tsMs)
        append(" gen=").append(generation)
        append(" throttle_req=").append(f3(throttleDemandeMps))
        append(" throttle_wd=").append(f3(throttleSurveilleMps))
        append(" throttle_emis=").append(f3(throttleEmisMps))
        append(" wd_frais=").append(watchdogFrais)
        append(" wd_age_ms=").append(if (watchdogAgeMs == Long.MAX_VALUE) "inf" else watchdogAgeMs.toString())
        append(" etat=").append(etat.name)
        append(" raison=\"").append(raison).append('"')
        append(" emis=").append(emis)
        append(" snapshot=").append(snapshotBits(snapshot))
    }

    /**
     * 11 conditions de securite en chaine binaire ORDONNEE + libelle court. Ordre stable :
     * PO EM OG VS IF RV PF AC BO CC ON (voir libelle). 1 = condition vraie.
     */
    fun snapshotBits(s: FlightCommandArbiter.SafetySnapshot): String {
        val bits = buildString {
            append(b(s.pilotOverride))
            append(b(s.emergencyStop))
            append(b(s.obstacleGateAllows))
            append(b(s.virtualStickAvailable))
            append(b(s.inFlightCompatible))
            append(b(s.railLoadedAndValid))
            append(b(s.dronePositionFresh))
            append(b(s.actionFreshAndConfident))
            append(b(s.batteryOk))
            append(b(s.corridorClear))
            append(b(s.operatorNearRail))
        }
        return "$bits[PO,EM,OG,VS,IF,RV,PF,AC,BO,CC,ON]"
    }

    private fun b(v: Boolean): Char = if (v) '1' else '0'

    private fun f3(v: Float): String = SoccerLogFormat.f3(v)
}
