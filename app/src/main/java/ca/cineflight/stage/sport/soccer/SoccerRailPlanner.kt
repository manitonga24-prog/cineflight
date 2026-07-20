package ca.cineflight.stage.sport.soccer

/**
 * SoccerRailPlanner — CALCUL PUR (aucun SDK, aucun Android) qui projette la position
 * d'action sur le terrain vers une position CIBLE sur le rail autorise.
 *
 * PRINCIPE (mapping direct, V1) :
 *   action a 0%   du terrain -> cible a 0%   du rail
 *   action a 20%             -> cible a 20%
 *   action a 75%             -> cible a 75%
 *   action a 100%            -> cible a 100%
 *
 * Le resultat est TOUJOURS borne entre les deux extremites du rail : quoi qu'il
 * arrive, la cible reste physiquement sur le rail (jamais au-dela). La coordonnee
 * geographique est interpolee lineairement entre [DroneRail.start] et [DroneRail.end].
 *
 * PORTEE : OBSERVATION / PLANIFICATION seulement. Ce composant ne commande NI drone,
 * NI nacelle. Il transforme une intention en une cible geographique bornee ; c'est
 * une couche ulterieure (limiteur de vitesse + gate obstacle + pont DJI) qui decide
 * si et comment s'y rendre.
 */
class SoccerRailPlanner {

    /**
     * Projette [actionPosition] (position d'action normalisee, 0=but gauche,
     * 1=but droit) sur [rail].
     *
     * @param actionPosition position d'action ; bornee dans [0f, 1f] par prudence.
     * @return une [RailTarget] dont [RailTarget.railFraction] est dans [0f, 1f] et
     *         dont le point est interpole entre les extremites du rail.
     */
    fun calculateTarget(actionPosition: Float, rail: DroneRail): RailTarget {
        // Mapping direct 1:1, borne sur le rail. (Une inversion ou un sous-segment
        // eventuel viendra plus tard ; V1 = projection pleine et directe.)
        val fraction = actionPosition.coerceIn(0f, 1f)

        val f = fraction.toDouble()
        val lat = rail.start.lat + (rail.end.lat - rail.start.lat) * f
        val lon = rail.start.lon + (rail.end.lon - rail.start.lon) * f

        return RailTarget(
            railFraction = fraction,
            point = RailPoint(lat = lat, lon = lon),
        )
    }
}
