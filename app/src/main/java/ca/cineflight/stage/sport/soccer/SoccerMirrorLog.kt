package ca.cineflight.stage.sport.soccer

/**
 * SoccerMirrorLog — formate une [SoccerMirrorPlanner.MirrorDecision] en une ligne de
 * journal STABLE et lisible, contenant exactement les champs demandes par la spec :
 *
 *   position_action position_rail_actuelle position_rail_demandee
 *   vitesse_demandee raison confiance_yolo commande_miroir
 *
 * PURETE : aucune dependance Android (pas de Log). L'appelant fait, cote MainActivity :
 *   Log.i("SOCCER_MIRROR", SoccerMirrorLog.ligne(decision))
 * ce qui garde ce formateur 100% testable sur la JVM.
 *
 * Format volontairement "clef=valeur" separe par espaces : facile a grep et a parser.
 */
object SoccerMirrorLog {

    const val TAG = "SOCCER_MIRROR"

    /** Ligne de journal structuree pour [d]. */
    fun ligne(d: SoccerMirrorPlanner.MirrorDecision): String {
        val action = d.positionAction?.let { f3(it) } ?: "null"
        return buildString {
            append("position_action=").append(action)
            append(" position_rail_actuelle=").append(f3(d.positionRailActuelle))
            append(" position_rail_demandee=").append(f3(d.positionRailDemandee))
            append(" vitesse_demandee=").append(f3(d.vitesseDemandeeMps))
            append(" raison=").append(d.raison.name)
            append(" confiance_yolo=").append(f3(d.confianceYolo))
            append(" commande_miroir=").append(d.commandeMiroir)
        }
    }

    /** Formatage stable a 3 decimales, insensible a la locale (toujours un point). */
    private fun f3(v: Float): String = SoccerLogFormat.f3(v)
}
