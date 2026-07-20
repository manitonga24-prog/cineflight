package ca.cineflight.stage.sport.soccer

/**
 * SoccerActionEstimator — CALCUL PUR (aucun SDK, aucun Android) qui transforme une
 * frame de detections YOLO en une position horizontale d'action, dans [0f, 1f].
 *
 *   0.0 = action pres du but gauche   0.5 = centre   1.0 = but droit
 *
 * DOCTRINE (V1, volontairement simple et prudente) :
 *   1. Si un ballon assez fiable est present -> on prend sa position horizontale.
 *   2. Sinon, si assez de joueurs fiables -> mediane horizontale des joueurs.
 *   3. Sinon -> null (aucune information exploitable : on n'invente rien).
 *   4. Le resultat est TOUJOURS borne dans [0f, 1f].
 *
 * PORTEE : cette classe OBSERVE seulement. Elle ne commande ni drone, ni nacelle,
 * ni rail. La stabilisation temporelle et la projection sur le rail sont des etapes
 * ulterieures et distinctes.
 */
class SoccerActionEstimator(
    private val ballConfidenceMin: Float = 0.55f,
    private val playerConfidenceMin: Float = 0.50f,
    private val minimumPlayers: Int = 3,
) {

    private companion object {
        const val LABEL_BALL = "sports ball"
        const val LABEL_PERSON = "person"
    }

    /**
     * Estime la position de l'action pour [frame].
     * @return une estimation bornee, ou null si l'information est insuffisante.
     */
    fun estimate(frame: SoccerDetectionFrame): SoccerActionEstimate? {
        // 1) Ballon le plus fiable au-dessus du seuil.
        val ball = frame.objects
            .filter { it.label.equals(LABEL_BALL, ignoreCase = true) && it.confidence >= ballConfidenceMin }
            .maxByOrNull { it.confidence }

        if (ball != null) {
            return SoccerActionEstimate(
                positionNormalized = ball.centerXNormalized.coerceIn(0f, 1f),
                confidence = ball.confidence,
                source = SoccerActionEstimate.Source.BALL,
                timestampMs = frame.timestampMs,
            )
        }

        // 2) Joueurs fiables ; il en faut un minimum pour une mediane credible.
        val players = frame.objects
            .filter { it.label.equals(LABEL_PERSON, ignoreCase = true) && it.confidence >= playerConfidenceMin }

        if (players.size < minimumPlayers) {
            return null
        }

        val sortedX = players
            .map { it.centerXNormalized.coerceIn(0f, 1f) }
            .sorted()

        val medianX = if (sortedX.size % 2 == 1) {
            sortedX[sortedX.size / 2]
        } else {
            val right = sortedX.size / 2
            (sortedX[right - 1] + sortedX[right]) / 2f
        }

        val confianceMoyenne = players
            .map { it.confidence }
            .average()
            .toFloat()

        return SoccerActionEstimate(
            positionNormalized = medianX.coerceIn(0f, 1f),
            confidence = confianceMoyenne,
            source = SoccerActionEstimate.Source.PLAYERS,
            timestampMs = frame.timestampMs,
        )
    }
}
