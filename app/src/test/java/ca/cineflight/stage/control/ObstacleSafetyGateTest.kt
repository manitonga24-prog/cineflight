package ca.cineflight.stage.control

import ca.cineflight.stage.control.ObstacleSafetyGate.GateAction
import ca.cineflight.stage.control.ObstacleSafetyGate.SafetyState
import ca.cineflight.stage.control.ObstacleSafetyGate.VerticalSnapshot
import ca.cineflight.stage.control.ObstacleSafetyGate.Vitesses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'évaluateur PUR ObstacleSafetyGate (ÉTAPE 1 — vertical directionnel).
 *
 * Aucun drone/SDK/Android : evaluate() est pure (âges + état passés en argument).
 * Voir docs/PLAN_OBSTACLE_SAFETY_GATE_20260718.md §4.
 */
class ObstacleSafetyGateTest {

    private val EPS = 1e-6f
    private fun cmd(throttle: Float) = Vitesses(0f, 0f, throttle, 0f)
    private fun snap(up: Int?, upAge: Long = 10, down: Int? = null, downAge: Long = 10) =
        VerticalSnapshot(up, upAge, down, downAge)

    private fun eval(
        throttle: Float, perc: VerticalSnapshot?,
        origin: CommandOrigin = CommandOrigin.AUTOMATIC,
        state: SafetyState = SafetyState(), resume: Boolean = false
    ) = ObstacleSafetyGate.evaluate(cmd(throttle), perc, origin, state, resume)

    @Test fun montee_loin_pass() {
        val r = eval(1.0f, snap(up = 5000))
        assertEquals(GateAction.PASS, r.action); assertEquals(1.0f, r.command.throttle, EPS)
    }

    @Test fun montee_approche_slow_moitie() {
        val r = eval(1.0f, snap(up = 2000))   // milieu de [1000,3000] → facteur 0.5
        assertEquals(GateAction.SLOW, r.action); assertEquals(0.5f, r.command.throttle, EPS)
    }

    @Test fun montee_critique_block_et_verrou() {
        val r = eval(1.0f, snap(up = 800))
        assertEquals(GateAction.BLOCK_DIRECTION, r.action)
        assertEquals(0f, r.command.throttle, EPS)
        assertTrue(r.state.upSuspended)
    }

    @Test fun verrou_up_reste_suspendu_meme_si_distance_remonte() {
        val bloque = eval(1.0f, snap(up = 800)).state
        val r = eval(1.0f, snap(up = 5000), state = bloque)
        assertEquals(GateAction.SUSPENDED, r.action); assertEquals(0f, r.command.throttle, EPS)
    }

    @Test fun reprise_explicite_leve_le_verrou() {
        val bloque = eval(1.0f, snap(up = 800)).state
        val r = eval(1.0f, snap(up = 5000), state = bloque, resume = true)
        assertEquals(GateAction.PASS, r.action); assertEquals(1.0f, r.command.throttle, EPS)
    }

    @Test fun up_perime_stale() {
        val r = eval(1.0f, snap(up = 2000, upAge = 600))
        assertEquals(GateAction.PERCEPTION_STALE, r.action)
        assertEquals(0f, r.command.throttle, EPS); assertTrue(r.state.upSuspended)
    }

    @Test fun up_null_indisponible_jamais_pass() {
        assertEquals(GateAction.PERCEPTION_STALE, eval(1.0f, snap(up = null)).action)
    }

    @Test fun up_zero_negatif_sentinelle_indisponibles() {
        assertEquals(GateAction.PERCEPTION_STALE, eval(1.0f, snap(up = 0)).action)
        assertEquals(GateAction.PERCEPTION_STALE, eval(1.0f, snap(up = -5)).action)
        assertEquals(GateAction.PERCEPTION_STALE, eval(1.0f, snap(up = 60000)).action)
    }

    @Test fun canaux_independants_up_bloque_descente_libre() {
        val bloque = eval(1.0f, snap(up = 800)).state
        val r = eval(-1.0f, snap(up = null, down = 5000), state = bloque)
        assertEquals(GateAction.PASS, r.action); assertEquals(-1.0f, r.command.throttle, EPS)
    }

    @Test fun descente_critique_block() {
        val r = eval(-1.0f, snap(up = null, down = 800))
        assertEquals(GateAction.BLOCK_DIRECTION, r.action); assertTrue(r.state.downSuspended)
    }

    @Test fun throttle_zero_pass() {
        assertEquals(GateAction.PASS, eval(0f, snap(up = 800)).action)
    }

    @Test fun manuel_disabled() {
        val r = eval(1.0f, snap(up = 800), origin = CommandOrigin.MANUAL)
        assertEquals(GateAction.DISABLED, r.action); assertEquals(1.0f, r.command.throttle, EPS)
    }

    @Test fun test_origine_disabled() {
        assertEquals(GateAction.DISABLED, eval(1.0f, snap(up = 800), origin = CommandOrigin.TEST).action)
    }

    @Test fun hysteresis_reste_slow_sous_seuil_sortie() {
        val enSlow = SafetyState(upSlowing = true)
        val r = eval(1.0f, snap(up = 3100), state = enSlow)   // 3000 <= 3100 < 3200
        assertEquals(GateAction.SLOW, r.action)
    }

    @Test fun hysteresis_sort_au_dessus_seuil_sortie() {
        val enSlow = SafetyState(upSlowing = true)
        val r = eval(1.0f, snap(up = 3300), state = enSlow)   // > 3200
        assertEquals(GateAction.PASS, r.action)
    }

    @Test fun sans_memoire_slow_3100_est_pass() {
        assertEquals(GateAction.PASS, eval(1.0f, snap(up = 3100)).action)
    }

    @Test fun unknown_traite_comme_automatique_prudent() {
        val r = eval(1.0f, snap(up = 800), origin = CommandOrigin.UNKNOWN)
        assertEquals(GateAction.BLOCK_DIRECTION, r.action); assertEquals(0f, r.command.throttle, EPS)
    }
}
