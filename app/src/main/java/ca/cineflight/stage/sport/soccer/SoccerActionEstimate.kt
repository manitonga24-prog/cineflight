package ca.cineflight.stage.sport.soccer

/**
 * SoccerActionEstimate — position horizontale ESTIMEE de l'action sur le terrain.
 *
 * [positionNormalized] resume "ou se passe l'action" le long de l'axe gauche-droite :
 *   0.0 = action pres du but gauche
 *   0.5 = action au centre du terrain
 *   1.0 = action pres du but droit
 *
 * IMPORTANT : cet objet est une OBSERVATION, pas une commande. Rien ici ne pilote
 * le drone, la nacelle, ni le rail. Le mapping vers une position de rail viendra
 * plus tard (SoccerRailPlanner), apres le filtre temporel.
 *
 * L'absence d'estimation est representee par `null` cote [SoccerActionEstimator]
 * (pas par [Source.UNAVAILABLE]) ; [Source.UNAVAILABLE] reste disponible si un
 * appelant veut materialiser explicitement l'absence.
 */
data class SoccerActionEstimate(
    /** Position horizontale de l'action, bornee dans [0f, 1f]. */
    val positionNormalized: Float,
    /** Confiance associee a l'estimation, dans [0f, 1f]. */
    val confidence: Float,
    /** D'ou vient l'estimation (ballon prioritaire, sinon joueurs). */
    val source: Source,
    /** Horodatage de la frame ayant produit l'estimation (ms). */
    val timestampMs: Long,
) {
    enum class Source {
        /** Estimation issue de la detection du ballon. */
        BALL,

        /** Estimation issue de la position mediane des joueurs. */
        PLAYERS,

        /** Aucune information exploitable (reserve ; l'estimateur renvoie plutot null). */
        UNAVAILABLE,
    }
}
