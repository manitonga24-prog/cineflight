package ca.cineflight.stage.sport.soccer

/**
 * SoccerAltitudePlanner — ALTITUDE ADAPTATIVE (pur, aucun SDK/Android).
 *
 * Fait varier l'altitude du drone entre [altMinM] et [altMaxM] selon le jeu, pour une
 * meilleure prise de vue :
 *   - jeu ETALE (joueurs disperses) ou RAPIDE -> MONTER (vue large, tout reste visible) ;
 *   - jeu CONCENTRE (joueurs groupes) et LENT -> DESCENDRE (plan plus serre, detail).
 *
 * Deux signaux d'entree, normalises [0,1] :
 *   - etalement : dispersion des joueurs (0 = tres groupes, 1 = tres etales) ;
 *   - vitesse   : rapidite du jeu (0 = statique, 1 = tres rapide).
 * L'altitude cible = interpolation entre min et max selon le max des deux (le plus
 * exigeant gagne : si ca va vite OU c'est etale, on monte). Lissee pour eviter les yo-yo.
 *
 * PORTEE : observation/planification. Rien ici ne commande le drone ; la valeur passe
 * ensuite par le limiteur de vitesse verticale + l'arbitre.
 */
class SoccerAltitudePlanner(
    private val altMinM: Double = 15.0,
    private val altMaxM: Double = 22.0,   // plage par defaut (surpassee par la phase)
    /** Poids relatif etalement vs vitesse (0.5 = egal). */
    private val poidsEtalement: Float = 0.6f,
    /** Lissage 0..1 : 0 = fige, 1 = instantane. ~0.2 = doux. */
    private val lissage: Float = 0.2f,
    /** Taille cible d'un joueur dans l'image (fraction hauteur [0,1]). ~0.12 = ~100px/800. */
    private val tailleCible: Float = 0.12f,
    /** Plafond absolu de securite (m), quelle que soit la phase. */
    private val plafondAbsoluM: Double = 35.0,
) {

    private var altCourante = Double.NaN

    fun reset() { altCourante = Double.NaN }

    /**
     * ALTITUDE PAR TAILLE DES JOUEURS (approche C+D), bornee par la plage de la phase.
     *
     * Principe : l'altitude n'est plus l'objectif. On vise a garder les joueurs a une
     * TAILLE cible dans l'image ([tailleCible]). Joueurs trop GRANDS (drone trop bas
     * ou trop pres) -> MONTER ; trop PETITS -> DESCENDRE. Le resultat est borne par la
     * plage [plageMinM, plageMaxM] de la PHASE de jeu, puis par le plafond absolu, puis lisse.
     *
     * @param tailleJoueurMoyenne hauteur moyenne des boites joueurs dans l'image [0,1].
     * @param plageMinM,plageMaxM plage d'altitude autorisee par la phase courante.
     */
    fun altitudeParTaille(tailleJoueurMoyenne: Float, plageMinM: Double, plageMaxM: Double): Double {
        val taille = (if (tailleJoueurMoyenne.isFinite()) tailleJoueurMoyenne else 0f).coerceIn(0.001f, 1f)
        val minM = plageMinM.coerceIn(altMinM, plafondAbsoluM)
        val maxM = plageMaxM.coerceIn(minM, plafondAbsoluM)

        // Altitude ideale ~ proportionnelle a taille_observee / taille_cible : si les
        // joueurs sont 2x trop grands, il faut ~2x plus haut. On part du milieu de plage.
        val ref = (minM + maxM) / 2.0
        val brute = (ref * (taille / tailleCible)).coerceIn(minM, maxM)

        altCourante = if (altCourante.isNaN()) brute
                      else altCourante + (brute - altCourante) * lissage
        return altCourante.coerceIn(minM, maxM)
    }

    /**
     * Calcule l'altitude cible (m), lissee et bornee.
     * @param etalement dispersion des joueurs [0,1].
     * @param vitesse rapidite du jeu [0,1].
     */
    fun altitudeCible(etalement: Float, vitesse: Float): Double {
        // Assainissement : NaN/Infini -> 0 (coerceIn ne nettoie PAS NaN). Fail-safe.
        val e = (if (etalement.isFinite()) etalement else 0f).coerceIn(0f, 1f)
        val v = (if (vitesse.isFinite()) vitesse else 0f).coerceIn(0f, 1f)
        // facteur d'exigence : combinaison ponderee, borne [0,1].
        val facteur = (e * poidsEtalement + v * (1f - poidsEtalement)).coerceIn(0f, 1f)
        val brute = altMinM + (altMaxM - altMinM) * facteur

        altCourante = if (altCourante.isNaN()) brute
                      else altCourante + (brute - altCourante) * lissage
        return altCourante.coerceIn(altMinM, altMaxM)
    }

    companion object {
        /**
         * Etalement [0,1] a partir des positions des joueurs (unites terrain). Base sur
         * l'ecart-type des positions, normalise. 0 joueur ou 1 -> 0 (groupe).
         */
        fun etalementDepuisJoueurs(joueurs: List<Point2D>): Float {
            if (joueurs.size < 2) return 0f
            val n = joueurs.size
            val mx = joueurs.sumOf { it.x.toDouble() } / n
            val my = joueurs.sumOf { it.y.toDouble() } / n
            var s = 0.0
            for (p in joueurs) {
                val dx = p.x - mx; val dy = p.y - my
                s += dx * dx + dy * dy
            }
            val ecartType = Math.sqrt(s / n)
            // ~0.3 (unites terrain) = tres etale -> 1.0. Borne.
            return (ecartType / 0.3).toFloat().coerceIn(0f, 1f)
        }
    }
}
