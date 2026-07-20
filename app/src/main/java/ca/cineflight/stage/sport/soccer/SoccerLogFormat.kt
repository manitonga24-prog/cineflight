package ca.cineflight.stage.sport.soccer

/**
 * SoccerLogFormat — formatage commun des lignes de log soccer (source unique).
 * Evite la copie de f3() dans les trois formateurs (mirror / rail mirror / emission).
 */
object SoccerLogFormat {
    /** Float a 3 decimales, insensible a la locale (toujours un point). */
    fun f3(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
