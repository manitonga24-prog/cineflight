package ca.cineflight.stage.control

/**
 * Deuxième validation du Clicker.
 *
 * Cette couche vérifie l'environnement disponible au moment de la demande :
 * connexion et position du drone, batterie, zone de vol, corridor du sujet
 * et données d'obstacles immédiates.
 *
 * Elle ne commande jamais le drone. Elle produit seulement un verdict.
 */
data class ContexteEnvironnementClicker(
    val droneConnecte: Boolean,
    val positionDroneValide: Boolean,
    val batteriePct: Int?,
    val zonePresente: Boolean,
    val zoneValidee: Boolean,
    val droneDansZone: Boolean,
    val altitudeRequise: Boolean,
    val altitudeDroneConnue: Boolean,
    val altitudeDansZone: Boolean,
    val trajectoirePresente: Boolean,
    val statutCorridor: MoniteurCorridor.Statut?,
    val perceptionDisponible: Boolean,
    val distanceObstacleHorizontalMm: Int?
)

object ValidationEnvironnementClicker {

    const val BATTERIE_MIN_PCT = 20
    const val DISTANCE_OBSTACLE_MIN_MM = 4_000

    private val commandesSecurite = setOf(
        "PAUSE_HOVER",
        "STOP_HOVER"
    )

    fun valider(
        mouvement: String?,
        validationPosition: ValidationMouvement,
        contexte: ContexteEnvironnementClicker
    ): ValidationMouvement {
        if (!validationPosition.autorise) return validationPosition

        // PAUSE/HOLD restent disponibles même si l'environnement n'est pas complet.
        if (mouvement in commandesSecurite) return validationPosition

        if (!contexte.droneConnecte) {
            return bloque(
                "BLOCKED_DRONE_DISCONNECTED",
                "Drone non connecté"
            )
        }

        if (!contexte.positionDroneValide) {
            return bloque(
                "BLOCKED_DRONE_POSITION_UNKNOWN",
                "Position actuelle du drone indisponible"
            )
        }

        val batterie = contexte.batteriePct
            ?: return bloque(
                "BLOCKED_BATTERY_UNKNOWN",
                "Niveau de batterie du drone indisponible"
            )

        if (batterie < BATTERIE_MIN_PCT) {
            return bloque(
                "BLOCKED_BATTERY_LOW",
                "Batterie trop basse : $batterie pour cent"
            )
        }

        if (!contexte.zonePresente) {
            return bloque(
                "BLOCKED_ZONE_MISSING",
                "Zone de vol sécurisée absente"
            )
        }

        if (!contexte.zoneValidee) {
            return bloque(
                "BLOCKED_ZONE_NOT_VALIDATED",
                "Analyse de sécurité de la zone incomplète"
            )
        }

        if (!contexte.droneDansZone) {
            return bloque(
                "BLOCKED_DRONE_OUTSIDE_ZONE",
                "Drone hors de la zone de vol ou trop près de sa limite"
            )
        }

        if (contexte.altitudeRequise && !contexte.altitudeDroneConnue) {
            return bloque(
                "BLOCKED_ALTITUDE_UNKNOWN",
                "Altitude actuelle du drone indisponible"
            )
        }

        if (contexte.altitudeRequise && !contexte.altitudeDansZone) {
            return bloque(
                "BLOCKED_ALTITUDE_OUTSIDE_ZONE",
                "Altitude du drone hors des limites prévues"
            )
        }

        if (!contexte.trajectoirePresente) {
            return bloque(
                "BLOCKED_TRAJECTORY_MISSING",
                "Parcours de sécurité du sujet absent"
            )
        }

        val prudenceCorridor = when (contexte.statutCorridor) {
            null -> return bloque(
                "BLOCKED_CORRIDOR_UNAVAILABLE",
                "Comparaison avec le parcours de sécurité indisponible"
            )

            MoniteurCorridor.Statut.RTK_PERDU -> return bloque(
                "BLOCKED_CORRIDOR_POSITION_UNAVAILABLE",
                "Position du boîtier inutilisable pour vérifier le parcours"
            )

            MoniteurCorridor.Statut.HORS_CORRIDOR -> return bloque(
                "BLOCKED_CORRIDOR_OUTSIDE",
                "Boîtier de suivi hors du parcours de sécurité"
            )

            MoniteurCorridor.Statut.PRUDENCE -> return bloque(
                "BLOCKED_CORRIDOR_PRUDENCE",
                "Boîtier de suivi trop éloigné du parcours prévu"
            )

            MoniteurCorridor.Statut.TOLERANCE -> true
            MoniteurCorridor.Statut.NORMAL -> false
        }

        if (!contexte.perceptionDisponible) {
            return bloque(
                "BLOCKED_PERCEPTION_UNAVAILABLE",
                "Données récentes des capteurs d'obstacles indisponibles"
            )
        }

        val distanceObstacle = contexte.distanceObstacleHorizontalMm
            ?: return bloque(
                "BLOCKED_OBSTACLE_DISTANCE_UNKNOWN",
                "Distance du plus proche obstacle indisponible"
            )

        if (distanceObstacle <= 0) {
            return bloque(
                "BLOCKED_OBSTACLE_DISTANCE_UNKNOWN",
                "Distance du plus proche obstacle invalide"
            )
        }

        if (distanceObstacle < DISTANCE_OBSTACLE_MIN_MM) {
            return bloque(
                "BLOCKED_OBSTACLE_TOO_CLOSE",
                "Obstacle détecté à moins de quatre mètres"
            )
        }

        return if (prudenceCorridor) {
            ValidationMouvement(
                autorise = true,
                status = "ENVIRONMENT_VALIDATION_OK_WITH_CAUTION_NO_EXECUTION",
                raison = "Environnement validé avec léger écart au parcours. Aucun mouvement exécuté."
            )
        } else {
            ValidationMouvement(
                autorise = true,
                status = "ENVIRONMENT_VALIDATION_OK_NO_EXECUTION",
                raison = "Position, zone, parcours et obstacles validés. Aucun mouvement exécuté."
            )
        }
    }

    private fun bloque(status: String, raison: String) =
        ValidationMouvement(
            autorise = false,
            status = status,
            raison = raison
        )
}