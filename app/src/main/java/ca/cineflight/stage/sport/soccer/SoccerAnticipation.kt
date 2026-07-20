package ca.cineflight.stage.sport.soccer

/**
 * SoccerAnticipation — ANTICIPATION du jeu (section 11), pure (aucun SDK/Android).
 *
 * A partir du centre d'action, de la direction et de la vitesse du jeu, predit la
 * position PROBABLE dans un horizon court (1-2 s) et rend une position "anticipee"
 * = centre + direction * (vitesse * horizon), pour que le drone/cadrage se place un
 * peu en avance sur l'action.
 *
 * DOCTRINE (verbatim doc) : l'anticipation doit rester LIMITEE. Une prediction trop
 * agressive enverrait le drone du mauvais cote si le jeu change soudain de direction.
 * On borne donc l'avance a [avanceMaxFraction] (unites terrain) et on ne l'applique
 * que si la direction est FIABLE. Resultat borne dans [0,1].
 */
class SoccerAnticipation(
    /** Horizon de prediction (s). ~1.5 s = doux. */
    private val horizonS: Float = 1.5f,
    /** Avance maximale autorisee (unites terrain), quel que soit la vitesse. */
    private val avanceMaxFraction: Float = 0.15f,
) {

    /**
     * Position anticipee (unites terrain [0,1]).
     * @param centre centre d'action courant.
     * @param dirX,dirY direction du jeu (unitaire) ; (0,0) = inconnue.
     * @param vitesse norme de vitesse du jeu (unites terrain / s).
     * @param fiable la direction est-elle fiable ? Sinon, pas d'anticipation.
     */
    fun anticiper(centre: Point2D, dirX: Float, dirY: Float, vitesse: Float, fiable: Boolean): Point2D {
        if (!fiable) return centre
        // Avance = vitesse * horizon, bornee. Applique le long de la direction unitaire.
        val avance = (vitesse * horizonS).coerceIn(0f, avanceMaxFraction)
        val x = (centre.x + dirX * avance).coerceIn(0f, 1f)
        val y = (centre.y + dirY * avance).coerceIn(0f, 1f)
        return Point2D(x, y)
    }
}
