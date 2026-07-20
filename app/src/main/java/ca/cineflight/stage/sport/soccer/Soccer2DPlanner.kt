package ca.cineflight.stage.sport.soccer

/**
 * Soccer2DPlanner — MODE 2D : calcule la position en RETRAIT du drone (pur).
 *
 * Doctrine (document CineFlight Soccer, sections 6-7) : le drone ne se met PAS sur
 * l'action, il reste derriere la direction du jeu, avec un decalage lateral :
 *
 *   position = centre_action  -  direction_jeu * retrait  +  perpendiculaire * decalage
 *
 * VALEURS METIER EN METRES (option A) : le retrait et le decalage sont exprimes en
 * METRES reels (robuste : un terrain fait 90, 100 ou 120 m). Ils sont convertis en
 * FRACTION [0,1] a l'entree de calculer(), via la dimension de reference du terrain
 * (dims). Sans dims (test/repli), on utilise [retraitFractionDefaut].
 *
 * La position finale est TOUJOURS ramenee dans le terrain. Aucune commande drone ici.
 */
class Soccer2DPlanner(
    /** Retrait derriere l'action, en METRES (cible operationnelle 15-22 m). */
    private val retraitMetres: Double = 18.0,
    /** Decalage lateral, en METRES. */
    private val decalageMetres: Double = 6.0,
    /** Repli en FRACTION quand aucune dimension terrain n'est fournie (tests). */
    private val retraitFractionDefaut: Float = 0.20f,
    private val decalageFractionDefaut: Float = 0.08f,
) {

    /** Cible 2D calculee (unites terrain), garantie dans [0,1] et dans le terrain. */
    data class Cible2D(val x: Float, val y: Float)

    /**
     * Calcule la position visee du drone. Surcharge SANS dimensions : utilise les
     * fractions par defaut (compat tests / repli).
     */
    fun calculer(centreAction: Point2D, dir: Point2D, terrain: List<Point2D>): Cible2D =
        calculer(centreAction, dir, terrain, retraitFractionDefaut, decalageFractionDefaut)

    /**
     * Calcule la position visee avec RETRAIT REEL : convertit retraitMetres/decalageMetres
     * en fraction via les dimensions du terrain. C'est la voie a utiliser en production.
     * @param dims dimensions reelles du terrain (m).
     */
    fun calculer(centreAction: Point2D, dir: Point2D, terrain: List<Point2D>,
                 dims: TerrainMetrics.Dimensions): Cible2D {
        val retraitFrac = TerrainMetrics.metresEnFraction(retraitMetres, dims)
        val decalageFrac = TerrainMetrics.metresEnFraction(decalageMetres, dims)
        return calculer(centreAction, dir, terrain, retraitFrac, decalageFrac)
    }

    /** Cœur du calcul, en fraction terrain. */
    private fun calculer(centreAction: Point2D, dir: Point2D, terrain: List<Point2D>,
                         retrait: Float, decalageLateral: Float): Cible2D {
        // Perpendiculaire a la direction (rotation 90 deg) pour le decalage lateral.
        val px = -dir.y
        val py = dir.x
        var x = centreAction.x - dir.x * retrait + px * decalageLateral
        var y = centreAction.y - dir.y * retrait + py * decalageLateral

        // Bornage grossier dans [0,1].
        x = x.coerceIn(0f, 1f)
        y = y.coerceIn(0f, 1f)

        // Bornage dans le terrain : si la cible sort du polygone, on la ramene vers le
        // centre de l'action jusqu'a rentrer (dichotomie simple, sur). Si pas de terrain
        // (< 3 sommets), on garde la cible bornee [0,1].
        // Le test point-dans-polygone est la SOURCE UNIQUE TerrainGeofence.dansPolygone
        // (pas de copie de l'algorithme ici).
        if (terrain.size >= 3) {
            val xs = terrain.map { it.x.toDouble() }
            val ys = terrain.map { it.y.toDouble() }
            fun dedansXY(ax: Float, ay: Float) =
                TerrainGeofence.dansPolygone(ax.toDouble(), ay.toDouble(), xs, ys)
            if (!dedansXY(x, y)) {
                var lo = 0f; var hi = 1f
                repeat(12) {
                    val t = (lo + hi) / 2f
                    val tx = x + (centreAction.x - x) * t
                    val ty = y + (centreAction.y - y) * t
                    if (dedansXY(tx, ty)) hi = t else lo = t
                }
                x += (centreAction.x - x) * hi
                y += (centreAction.y - y) * hi
            }
        }
        return Cible2D(x, y)
    }
}
