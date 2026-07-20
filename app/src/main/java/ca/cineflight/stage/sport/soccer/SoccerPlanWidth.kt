package ca.cineflight.stage.sport.soccer

/**
 * SoccerPlanWidth — LARGEUR DE PLAN adaptative (section 10), pure (aucun SDK/Android).
 *
 * Choisit la largeur du cadrage selon la situation :
 *   - LARGE   : joueurs disperses, jeu rapide, ou incertitude (plusieurs joueurs a garder) ;
 *   - MOYEN   : action concentree, deplacement previsible ;
 *   - RAPPROCHE : action stable, groupe limite (a utiliser avec prudence).
 *
 * HYSTERESIS : on ne change de plan que si le score s'ecarte franchement du seuil,
 * pour eviter les changements brusques de cadrage (regle cinema : pas de yo-yo).
 *
 * NB : ce composant DECIDE seulement la largeur (enum). L'application concrete (zoom
 * ou recul physique) est faite ailleurs ; le zoom ne doit jamais remplacer une
 * mauvaise position du drone (doc), il permet juste de garder de la distance.
 */
class SoccerPlanWidth(
    /** Marge d'hysteresis autour des seuils (0..1). Plus grand = plus stable. */
    private val hysteresis: Float = 0.1f,
) {

    enum class Plan { LARGE, MOYEN, RAPPROCHE }

    private var courant: Plan = Plan.LARGE   // demarre large (le plus sur)

    fun reset() { courant = Plan.LARGE }

    /**
     * Met a jour et retourne le plan.
     * @param etalement dispersion des joueurs [0,1].
     * @param vitesse rapidite du jeu [0,1].
     * @param certitude confiance du systeme [0,1] (0 = incertain -> LARGE).
     */
    fun maj(etalement: Float, vitesse: Float, certitude: Float): Plan {
        val e = etalement.coerceIn(0f, 1f)
        val v = vitesse.coerceIn(0f, 1f)
        val c = certitude.coerceIn(0f, 1f)

        // Score "besoin de largeur" : disperse / rapide / incertain -> monte.
        // (1 - certitude) pese fort : dans le doute, on elargit.
        val besoinLarge = maxOf(e, v, 1f - c)

        // Seuils avec hysteresis autour de 0.66 (large) et 0.33 (rapproche).
        val sLarge = 0.66f
        val sRapproche = 0.33f
        val h = hysteresis

        courant = when (courant) {
            Plan.LARGE ->
                if (besoinLarge < sLarge - h) { if (besoinLarge < sRapproche - h) Plan.RAPPROCHE else Plan.MOYEN }
                else Plan.LARGE
            Plan.MOYEN ->
                if (besoinLarge > sLarge + h) Plan.LARGE
                else if (besoinLarge < sRapproche - h) Plan.RAPPROCHE
                else Plan.MOYEN
            Plan.RAPPROCHE ->
                if (besoinLarge > sRapproche + h) { if (besoinLarge > sLarge + h) Plan.LARGE else Plan.MOYEN }
                else Plan.RAPPROCHE
        }
        return courant
    }
}
