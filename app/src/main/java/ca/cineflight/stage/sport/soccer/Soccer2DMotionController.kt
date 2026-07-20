package ca.cineflight.stage.sport.soccer

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Soccer2DMotionController — CONTROLEUR DE TRAJECTOIRE 2D (section 8), pur (aucun SDK).
 *
 * Transforme une CIBLE 2D (position visee dans le terrain) en une commande de vitesse
 * 2D (vx = est/ouest, vy = nord/sud, unites m/s), sous les memes contraintes de
 * securite que le mode rail :
 *   - zone morte (pas de micro-corrections) ;
 *   - vitesse proportionnelle a l'ecart, bornee (loi commune RailVitesse) ;
 *   - limite d'ACCELERATION par axe (rampe commune RailVitesse) ;
 *   - donnee perimee OU confiance faible -> arret (rampe vers 0).
 *
 * Les entrees position sont en unites terrain [0,1] ; on convertit l'ecart en "vitesse"
 * via le gain. La conversion en repere corps drone (pitch/roll) est faite par l'appelant
 * (comme pour le rail). Rien ici ne touche le SDK.
 */
class Soccer2DMotionController(
    private val maxSpeedMps: Float,
    private val deadband: Float = 0.03f,
    private val staleAfterMs: Long = 600L,
    private val gainMps: Float = 4f,
    private val maxDeltaVMps: Float = 0.5f,
    private val confidenceMin: Float = 0.50f,
) {

    /** Sortie : vitesses 2D (m/s) + raison. */
    data class Output2D(val vx: Float, val vy: Float, val reason: Reason)
    enum class Reason { MOVE, HOLD_DEADBAND, ACTION_STALE, LOW_CONFIDENCE }

    private var lastVx = 0f
    private var lastVy = 0f

    fun reset() { lastVx = 0f; lastVy = 0f }

    /**
     * @param actuelle position actuelle du drone (unites terrain).
     * @param cible position visee (unites terrain).
     * @param actionAgeMs age de la derniere action (ms).
     * @param confidence confiance [0,1].
     */
    fun compute(actuelle: Point2D, cible: Point2D, actionAgeMs: Long, confidence: Float): Output2D {
        // Assainissement fail-safe : toute entree non finie -> arret.
        if (!actuelle.x.isFinite() || !actuelle.y.isFinite() ||
            !cible.x.isFinite() || !cible.y.isFinite() || !confidence.isFinite()) {
            return arret(Reason.LOW_CONFIDENCE)
        }
        // Perime / confiance faible -> arret (rampe vers 0 sur les deux axes).
        if (staleAfterMs > 0 && actionAgeMs > staleAfterMs) return arret(Reason.ACTION_STALE)
        if (confidence < confidenceMin) return arret(Reason.LOW_CONFIDENCE)

        val ex = cible.x - actuelle.x
        val ey = cible.y - actuelle.y
        // Zone morte sur la DISTANCE (pas par axe) pour eviter les micro-corrections.
        if (hypot(ex, ey) < deadband) return arret(Reason.HOLD_DEADBAND)

        // Vitesse proportionnelle bornee par axe (loi commune), puis rampe par axe.
        val cibleVx = RailVitesse.proportionnelle(ex, gainMps, maxSpeedMps)
        val cibleVy = RailVitesse.proportionnelle(ey, gainMps, maxSpeedMps)
        lastVx = RailVitesse.rampe(cibleVx, lastVx, maxDeltaVMps)
        lastVy = RailVitesse.rampe(cibleVy, lastVy, maxDeltaVMps)
        return Output2D(lastVx, lastVy, Reason.MOVE)
    }

    /** Arret : rampe les deux axes vers 0 (deceleration limitee). */
    private fun arret(raison: Reason): Output2D {
        lastVx = RailVitesse.rampe(0f, lastVx, maxDeltaVMps)
        lastVy = RailVitesse.rampe(0f, lastVy, maxDeltaVMps)
        return Output2D(lastVx, lastVy, raison)
    }
}
