package ca.cineflight.stage.sport.soccer

import kotlin.math.abs
import kotlin.math.sign

/**
 * RailVitesse — loi de vitesse commune (pur), SOURCE UNIQUE reutilisee par le
 * RailMotionController ET le SoccerMirrorPlanner (evite la duplication du calcul).
 */
object RailVitesse {

    /**
     * Vitesse proportionnelle a l'ecart, SIGNEE, bornee a [maxMps].
     * @return magnitude = min(|ecart|*gain, max) avec le signe de l'ecart.
     */
    fun proportionnelle(ecart: Float, gainMps: Float, maxMps: Float): Float {
        val magnitude = (abs(ecart) * gainMps).coerceIn(0f, maxMps)
        return magnitude * sign(ecart)
    }

    /**
     * Applique une LIMITE D'ACCELERATION : rapproche [derniere] de [cible] d'au plus
     * [maxDeltaV] (0 = pas de limite). Aussi utilisee pour une deceleration douce.
     */
    fun rampe(cible: Float, derniere: Float, maxDeltaV: Float): Float {
        if (maxDeltaV <= 0f) return cible
        val delta = (cible - derniere).coerceIn(-maxDeltaV, maxDeltaV)
        return derniere + delta
    }
}
