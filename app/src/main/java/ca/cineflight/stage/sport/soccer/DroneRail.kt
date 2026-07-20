package ca.cineflight.stage.sport.soccer

/**
 * RailPoint — point geographique PUR (lat/lon en degres), sans dependance carto.
 *
 * On n'utilise volontairement PAS org.osmdroid.util.GeoPoint ici : garder le
 * planificateur pur le rend testable sur la JVM et independant de la lib carto.
 * Un adaptateur (cote appelant) convertira GeoPoint <-> RailPoint au besoin.
 */
data class RailPoint(
    val lat: Double,
    val lon: Double,
)

/**
 * DroneRail — le RAIL VIRTUEL sur lequel le drone est autorise a se deplacer.
 *
 * Defini par ses deux extremites geographiques. [start] correspond a la position
 * normalisee 0.0 du rail, [end] a 1.0. La position du drone le long du rail est
 * toujours exprimee dans [0f, 1f] (fraction), independamment de la longueur reelle.
 *
 * NOTE : ce modele decrit le rail cote planification pure. L'altitude, la vitesse
 * max, la position de securite, etc. (profil SOCCER_RAIL) sont portes ailleurs et
 * ne concernent PAS la simple projection action -> position de rail.
 */
data class DroneRail(
    /** Extremite correspondant a la fraction 0.0 (ex. cote but gauche). */
    val start: RailPoint,
    /** Extremite correspondant a la fraction 1.0 (ex. cote but droit). */
    val end: RailPoint,
)

/**
 * RailTarget — resultat d'une projection : ou le drone devrait viser sur le rail.
 *
 * @param railFraction position visee le long du rail, TOUJOURS bornee dans [0f, 1f].
 * @param point coordonnee geographique correspondante (interpolee entre start et end).
 */
data class RailTarget(
    val railFraction: Float,
    val point: RailPoint,
)
