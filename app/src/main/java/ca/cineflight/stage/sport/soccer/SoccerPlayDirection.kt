package ca.cineflight.stage.sport.soccer

import kotlin.math.hypot

/**
 * Point 2D normalise dans le terrain : x et y dans [0f, 1f].
 * (Repere abstrait du terrain, pas de lat/lon ici : reste pur et testable.)
 */
data class Point2D(val x: Float, val y: Float)

/**
 * SoccerPlayDirection — estime la DIRECTION et la VITESSE du jeu (mouvement du centre
 * de l'action) a partir des positions successives. Pur (aucun SDK/Android).
 *
 * Sert au mode 2D : placer le drone EN RETRAIT, derriere la direction du jeu. On lisse
 * la direction (moyenne exponentielle) pour eviter qu'un changement brusque de position
 * ne fasse pivoter le retrait dans tous les sens.
 *
 * Etat interne : derniere position + direction lissee. Deterministe (le temps est
 * fourni par l'appelant via les timestamps).
 */
class SoccerPlayDirection(
    /** Lissage 0..1 : 0 = fige, 1 = suit instantanement (bruyant). ~0.3 = doux. */
    private val lissage: Float = 0.3f,
    /** Deplacement min (unites terrain) pour considerer une direction fiable. */
    private val deplacementMin: Float = 0.01f,
) {

    /** Direction estimee (vecteur unitaire) + norme de vitesse (unites terrain / s). */
    data class Etat(val dirX: Float, val dirY: Float, val vitesse: Float, val fiable: Boolean)

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastTs = 0L
    private var dirX = 0f
    private var dirY = 0f
    private var fiable = false

    fun reset() {
        lastX = Float.NaN; lastY = Float.NaN; lastTs = 0L
        dirX = 0f; dirY = 0f; fiable = false
    }

    /**
     * Ingere une nouvelle position du centre d'action et rend la direction lissee.
     * @param pos position du centre d'action (unites terrain [0,1]).
     * @param nowMs horodatage (ms).
     */
    fun update(pos: Point2D, nowMs: Long): Etat {
        if (lastX.isNaN()) {
            lastX = pos.x; lastY = pos.y; lastTs = nowMs
            return Etat(0f, 0f, 0f, false)
        }
        val dt = (nowMs - lastTs).coerceAtLeast(1L) / 1000.0f   // s
        val dx = pos.x - lastX
        val dy = pos.y - lastY
        val dep = hypot(dx, dy)
        val vitesse = dep / dt

        if (dep >= deplacementMin) {
            // direction instantanee (unitaire) puis lissage exponentiel.
            val ix = dx / dep
            val iy = dy / dep
            dirX = dirX * (1f - lissage) + ix * lissage
            dirY = dirY * (1f - lissage) + iy * lissage
            // renormalise la direction lissee.
            val n = hypot(dirX, dirY)
            if (n > 0f) { dirX /= n; dirY /= n }
            fiable = true
        }
        // sinon : deplacement trop petit -> on garde la derniere direction lissee.

        lastX = pos.x; lastY = pos.y; lastTs = nowMs
        return Etat(dirX, dirY, vitesse, fiable)
    }
}
