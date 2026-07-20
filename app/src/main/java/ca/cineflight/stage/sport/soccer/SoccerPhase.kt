package ca.cineflight.stage.sport.soccer

/**
 * SoccerPhase — DETECTEUR de phase de jeu + plage d'altitude autorisee (pur).
 *
 * Determine la phase courante selon la vitesse et l'etalement du jeu, et donne la
 * PLAGE d'altitude autorisee pour cette phase (table validee) :
 *
 *   DUEL           : jeu lent, tres concentre        -> 15-18 m
 *   NORMAL         : jeu courant                      -> 18-22 m
 *   CONTRE_ATTAQUE : jeu rapide                       -> 22-28 m
 *   VUE_ENSEMBLE   : tres etale / arret (corner...)   -> 25-35 m
 *
 * HYSTERESIS : on ne change de phase que si le signal s'ecarte franchement, pour
 * eviter des sauts d'altitude a chaque frame. Deterministe.
 */
class SoccerPhase(
    private val hysteresis: Float = 0.08f,
) {

    enum class Phase(val altMinM: Double, val altMaxM: Double) {
        DUEL(15.0, 18.0),
        NORMAL(18.0, 22.0),
        CONTRE_ATTAQUE(22.0, 28.0),
        VUE_ENSEMBLE(25.0, 35.0),
    }

    private var courant: Phase = Phase.NORMAL

    fun reset() { courant = Phase.NORMAL }

    /**
     * Met a jour et retourne la phase.
     * @param vitesse rapidite du jeu [0,1].
     * @param etalement dispersion des joueurs [0,1].
     * @param arret true si le jeu est arrete (corner, coup franc) -> VUE_ENSEMBLE.
     */
    fun maj(vitesse: Float, etalement: Float, arret: Boolean = false): Phase {
        val v = vitesse.coerceIn(0f, 1f)
        val e = etalement.coerceIn(0f, 1f)

        // Score "besoin de hauteur" : rapide OU tres etale -> monter.
        val score = maxOf(v, e)
        val h = hysteresis

        courant = when {
            arret -> Phase.VUE_ENSEMBLE
            // seuils avec hysteresis : plus on est haut dans la phase, plus il faut
            // descendre franchement pour redescendre (et inversement).
            score > 0.80f + h -> Phase.VUE_ENSEMBLE
            score > 0.55f + h -> Phase.CONTRE_ATTAQUE
            score > 0.30f + h -> Phase.NORMAL
            score < 0.30f - h -> Phase.DUEL
            else -> courant   // dans la zone d'hysteresis : on garde la phase courante
        }
        return courant
    }
}
