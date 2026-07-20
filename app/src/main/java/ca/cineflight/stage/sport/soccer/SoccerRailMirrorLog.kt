package ca.cineflight.stage.sport.soccer

/**
 * SoccerRailMirrorLog — ligne de journal du MIROIR DE MOUVEMENT (Phase 9B).
 *
 * Contient exactement les champs demandes par la spec 9B :
 *   action_position rail_current rail_target requested_velocity
 *   reason command_sent soccer_motion_applied
 *
 * [soccer_motion_applied] est TOUJOURS false en 9B : le controleur de rail produit
 * une commande THEORIQUE, mais c'est la commande normale (existante) qui est envoyee
 * au drone. Cette ligne sert a observer, sur match enregistre/simule, la stabilite de
 * la position demandee et l'absence d'oscillation, sans aucun risque.
 *
 * PURETE : aucune dependance Android (pas de Log). L'appelant (Phase3Activity) fait :
 *   Log.i(SoccerRailMirrorLog.TAG, SoccerRailMirrorLog.ligne(...))
 */
object SoccerRailMirrorLog {

    const val TAG = "SOCCER_RAIL_MIRROR"

    /**
     * @param actionPosition position d'action stable [0,1] ou null si indisponible.
     * @param railCurrent position actuelle (simulee) du drone sur le rail [0,1].
     * @param output sortie du RailMotionController (vitesse theorique + raison).
     * @param commandSent description libre de la commande REELLEMENT envoyee (existante).
     */
    fun ligne(
        actionPosition: Float?,
        railCurrent: Float,
        railTarget: Float,
        output: RailControlOutput,
        commandSent: String,
    ): String {
        val action = actionPosition?.let { f3(it) } ?: "null"
        return buildString {
            append("action_position=").append(action)
            append(" rail_current=").append(f3(railCurrent))
            append(" rail_target=").append(f3(railTarget))
            append(" requested_velocity=").append(f3(output.requestedVelocityMps))
            append(" reason=").append(output.reason.name)
            append(" command_sent=").append(commandSent)
            append(" soccer_motion_applied=false")   // INVARIANT 9B
        }
    }

    private fun f3(v: Float): String = SoccerLogFormat.f3(v)
}
