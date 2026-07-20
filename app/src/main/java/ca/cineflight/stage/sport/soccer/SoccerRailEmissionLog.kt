package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse

/**
 * SoccerRailEmissionLog — ligne de journal de l'EMISSION arbitree (Phase 9D).
 *
 * Trace, a chaque tick, la decision de l'arbitre ET ce qui a REELLEMENT ete envoye :
 *   real_enabled max_speed_mps winner soccer_command command_sent soccer_applied
 *
 * INVARIANT (double verrou) : tant que real_enabled=false OU max_speed=0, la commande
 * SoccerRail n'est jamais appliquee horizontalement ; command_sent reste EXISTING et
 * soccer_applied=false. Cette ligne permet de le PROUVER sur plusieurs sessions.
 *
 * PURETE : aucune dependance Android (pas de Log). L'appelant fait :
 *   Log.i(SoccerRailEmissionLog.TAG, SoccerRailEmissionLog.ligne(...))
 */
object SoccerRailEmissionLog {

    const val TAG = "SOCCER_RAIL_EMISSION"

    /**
     * @param realEnabled etat du flag d'emission reelle.
     * @param maxSpeedMps plafond de vitesse soccer configure.
     * @param winner source retenue par l'arbitre.
     * @param soccerCommand commande proposee par le pipeline soccer.
     * @param soccerApplied true seulement si la commande soccer a ete REELLEMENT envoyee.
     */
    fun ligne(
        realEnabled: Boolean,
        maxSpeedMps: Float,
        winner: FlightCommandArbiter.FlightCommandSource,
        soccerCommand: AssainisseurVitesse.Vitesses,
        soccerApplied: Boolean,
    ): String {
        val commandSent = if (soccerApplied) "SOCCER" else "EXISTING"
        return buildString {
            append("real_enabled=").append(realEnabled)
            append(" max_speed_mps=").append(f3(maxSpeedMps))
            append(" winner=").append(nomSource(winner))
            append(" soccer_command=").append(fmt(soccerCommand))
            append(" command_sent=").append(commandSent)
            append(" soccer_applied=").append(soccerApplied)
        }
    }

    private fun nomSource(s: FlightCommandArbiter.FlightCommandSource): String = when (s) {
        FlightCommandArbiter.FlightCommandSource.Pilot -> "PILOT"
        FlightCommandArbiter.FlightCommandSource.ExistingAutomaticMode -> "EXISTING"
        FlightCommandArbiter.FlightCommandSource.SoccerRail -> "SOCCER_RAIL"
    }

    private fun fmt(v: AssainisseurVitesse.Vitesses): String =
        "(p=${f3(v.pitch)},r=${f3(v.roll)},t=${f3(v.throttle)},y=${f3(v.yaw)})"

    private fun f3(v: Float): String = SoccerLogFormat.f3(v)
}
