package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Soccer2DMotionControllerTest — couvre le contrôleur de mouvement 2D (pur, JVM).
 *
 * Exerce les 4 raisons de sortie (MOVE, HOLD_DEADBAND, ACTION_STALE, LOW_CONFIDENCE),
 * le fail-safe NaN, et la rampe de vitesse. Ces branches gouvernent l'arrêt sûr du
 * mouvement — logique de sécurité.
 */
class Soccer2DMotionControllerTest {

    private fun ctrl(maxSpeed: Float = 2f) =
        Soccer2DMotionController(maxSpeedMps = maxSpeed)

    private fun pt(x: Float, y: Float) = Point2D(x, y)

    @Test fun entree_nan_donne_arret_low_confidence() {
        val c = ctrl()
        val r = c.compute(pt(Float.NaN, 0f), pt(1f, 1f), 0L, 1f)
        assertEquals(Soccer2DMotionController.Reason.LOW_CONFIDENCE, r.reason)
    }

    @Test fun cible_nan_donne_arret() {
        val c = ctrl()
        val r = c.compute(pt(0f, 0f), pt(Float.NaN, 1f), 0L, 1f)
        assertEquals(Soccer2DMotionController.Reason.LOW_CONFIDENCE, r.reason)
    }

    @Test fun confidence_nan_donne_arret() {
        val c = ctrl()
        val r = c.compute(pt(0f, 0f), pt(1f, 1f), 0L, Float.NaN)
        assertEquals(Soccer2DMotionController.Reason.LOW_CONFIDENCE, r.reason)
    }

    @Test fun action_perimee_donne_action_stale() {
        val c = ctrl()
        // âge > staleAfterMs (600) -> ACTION_STALE
        val r = c.compute(pt(0f, 0f), pt(5f, 5f), 1000L, 1f)
        assertEquals(Soccer2DMotionController.Reason.ACTION_STALE, r.reason)
    }

    @Test fun confiance_faible_donne_low_confidence() {
        val c = ctrl()
        // confidence < confidenceMin (0.5) -> LOW_CONFIDENCE
        val r = c.compute(pt(0f, 0f), pt(5f, 5f), 0L, 0.2f)
        assertEquals(Soccer2DMotionController.Reason.LOW_CONFIDENCE, r.reason)
    }

    @Test fun cible_atteinte_donne_hold_deadband() {
        val c = ctrl()
        // distance < deadband (0.03) -> HOLD_DEADBAND
        val r = c.compute(pt(1f, 1f), pt(1.01f, 1.01f), 0L, 1f)
        assertEquals(Soccer2DMotionController.Reason.HOLD_DEADBAND, r.reason)
    }

    @Test fun ecart_franc_donne_move() {
        val c = ctrl()
        val r = c.compute(pt(0f, 0f), pt(5f, 5f), 0L, 1f)
        assertEquals(Soccer2DMotionController.Reason.MOVE, r.reason)
        // au moins un axe bouge
        assertTrue(kotlin.math.abs(r.vx) > 0f || kotlin.math.abs(r.vy) > 0f)
    }

    @Test fun vitesse_bornee_par_maxspeed() {
        val c = ctrl(maxSpeed = 1f)
        // gros écart -> vitesse doit rester bornée à ±maxSpeed (après plusieurs rampes)
        var r = c.compute(pt(0f, 0f), pt(100f, 0f), 0L, 1f)
        repeat(20) { r = c.compute(pt(0f, 0f), pt(100f, 0f), 0L, 1f) }
        assertTrue("vx borné", kotlin.math.abs(r.vx) <= 1f + 1e-3f)
    }

    @Test fun rampe_limite_l_acceleration() {
        val c = ctrl(maxSpeed = 4f)
        // premier appel : la vitesse ne peut pas dépasser maxDeltaV (0.5) depuis 0
        val r = c.compute(pt(0f, 0f), pt(100f, 0f), 0L, 1f)
        assertTrue("premier pas limité par la rampe", kotlin.math.abs(r.vx) <= 0.5f + 1e-3f)
    }

    @Test fun reset_remet_vitesses_a_zero() {
        val c = ctrl()
        c.compute(pt(0f, 0f), pt(5f, 5f), 0L, 1f) // établit une vitesse
        c.reset()
        // après reset, le premier pas repart de 0 (borné par la rampe)
        val r = c.compute(pt(0f, 0f), pt(100f, 0f), 0L, 1f)
        assertTrue(kotlin.math.abs(r.vx) <= 0.5f + 1e-3f)
    }
}
