package ca.cineflight.stage.control

import ca.cineflight.stage.control.ObstacleSafetyGate.GateAction
import ca.cineflight.stage.control.ObstacleSafetyGate.HorizontalSnapshot
import ca.cineflight.stage.control.ObstacleSafetyGate.SafetyState
import ca.cineflight.stage.control.ObstacleSafetyGate.Vitesses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'évaluateur PUR horizontal (ÉTAPE 2 — conservateur, omnidirectionnel).
 *
 * Règle centrale : INDISPONIBLE ≠ dégagé. Filtrage sentinelle 60000. Étape NON activable
 * réellement (ETAPE2_HORIZONTAL_ACTIVABLE=false) tant que 60000 n'est pas prouvée en vol.
 */
class ObstacleSafetyGateHorizontalTest {

    private val EPS = 1e-6f
    private fun cmd(pitch: Float, roll: Float) = Vitesses(pitch, roll, 0f, 0f)
    private fun snap(l: List<Int>?, age: Long = 10) = HorizontalSnapshot(l, age)
    private fun eval(
        pitch: Float, roll: Float, l: List<Int>?,
        origin: CommandOrigin = CommandOrigin.AUTOMATIC,
        state: SafetyState = SafetyState(), resume: Boolean = false, age: Long = 10
    ) = ObstacleSafetyGate.evaluateHorizontal(cmd(pitch, roll), snap(l, age), origin, state, resume)

    // --- analyse / filtrage ---
    @Test fun filtre_sentinelle_min_correct() {
        val a = ObstacleSafetyGate.analyserHorizontal(snap(listOf(60000, 60000, 3000, 60000)))
        assertEquals(3000, a.minMm); assertEquals(3, a.nbFiltres); assertTrue(!a.indisponible)
    }
    @Test fun tout_sentinelle_indisponible() {
        val a = ObstacleSafetyGate.analyserHorizontal(snap(listOf(60000, 60000)))
        assertTrue(a.indisponible); assertNull(a.minMm)
    }
    @Test fun vide_null_indisponible() {
        assertTrue(ObstacleSafetyGate.analyserHorizontal(snap(emptyList())).indisponible)
        assertTrue(ObstacleSafetyGate.analyserHorizontal(snap(null)).indisponible)
    }
    @Test fun zero_negatif_filtres() {
        val a = ObstacleSafetyGate.analyserHorizontal(snap(listOf(0, -5, 3000)))
        assertEquals(3000, a.minMm); assertEquals(2, a.nbFiltres)
    }

    // --- barème ---
    @Test fun loin_pass_inchange() {
        val r = eval(1.0f, 0.5f, listOf(5000, 60000))
        assertEquals(GateAction.PASS, r.action)
        assertEquals(1.0f, r.command.pitch, EPS); assertEquals(0.5f, r.command.roll, EPS)
    }
    @Test fun approche_slow_moitie() {
        val r = eval(1.0f, 0f, listOf(3000, 60000))   // milieu [2000,4000]
        assertEquals(GateAction.SLOW, r.action); assertEquals(0.5f, r.command.pitch, EPS)
    }
    @Test fun critique_stop_translation_et_verrou() {
        val r = eval(1.0f, 1.0f, listOf(1500, 60000))
        assertEquals(GateAction.STOP_TRANSLATION, r.action)
        assertEquals(0f, r.command.pitch, EPS); assertEquals(0f, r.command.roll, EPS)
        assertTrue(r.state.horizSuspended)
    }
    @Test fun eloignement_aussi_stoppe_conservateur() {
        // Pas d'exception d'éloignement en horizontal avant calibration.
        val r = eval(-1.0f, 0f, listOf(1500))
        assertEquals(GateAction.STOP_TRANSLATION, r.action); assertEquals(0f, r.command.pitch, EPS)
    }
    @Test fun yaw_et_vertical_conserves() {
        val r = ObstacleSafetyGate.evaluateHorizontal(
            Vitesses(1.0f, 0f, 0.7f, 30f), snap(listOf(1500)),
            CommandOrigin.AUTOMATIC, SafetyState())
        // pitch stoppé, mais throttle et yaw intacts
        assertEquals(0f, r.command.pitch, EPS)
        assertEquals(0.7f, r.command.throttle, EPS); assertEquals(30f, r.command.yaw, EPS)
    }

    // --- INDISPONIBLE / verrou / reprise ---
    @Test fun mouvement_tout_sentinelle_stale() {
        val r = eval(1.0f, 0f, listOf(60000, 60000))
        assertEquals(GateAction.PERCEPTION_STALE, r.action)
        assertEquals(0f, r.command.pitch, EPS); assertTrue(r.state.horizSuspended)
    }
    @Test fun verrou_reste_suspendu() {
        val bloque = eval(1.0f, 0f, listOf(1500)).state
        val r = eval(1.0f, 0f, listOf(5000), state = bloque)
        assertEquals(GateAction.SUSPENDED, r.action); assertEquals(0f, r.command.pitch, EPS)
    }
    @Test fun reprise_explicite() {
        val bloque = eval(1.0f, 0f, listOf(1500)).state
        val r = eval(1.0f, 0f, listOf(5000), state = bloque, resume = true)
        assertEquals(GateAction.PASS, r.action); assertEquals(1.0f, r.command.pitch, EPS)
    }
    @Test fun perime_stale() {
        assertEquals(GateAction.PERCEPTION_STALE, eval(1.0f, 0f, listOf(3000), age = 600).action)
    }

    // --- origines / translation nulle / hysteresis ---
    @Test fun pas_de_translation_pass() {
        assertEquals(GateAction.PASS, eval(0f, 0f, listOf(1500)).action)
    }
    @Test fun manuel_disabled() {
        assertEquals(GateAction.DISABLED, eval(1.0f, 0f, listOf(1500), origin = CommandOrigin.MANUAL).action)
    }
    @Test fun unknown_prudent_stop() {
        val r = eval(1.0f, 0f, listOf(1500), origin = CommandOrigin.UNKNOWN)
        assertEquals(GateAction.STOP_TRANSLATION, r.action)
    }
    @Test fun hysteresis_reste_slow() {
        val enSlow = SafetyState(horizSlowing = true)
        assertEquals(GateAction.SLOW, eval(1.0f, 0f, listOf(4100), state = enSlow).action)
    }
    @Test fun hysteresis_sort() {
        val enSlow = SafetyState(horizSlowing = true)
        assertEquals(GateAction.PASS, eval(1.0f, 0f, listOf(4300), state = enSlow).action)
    }

    @Test fun garde_fou_etape2_off_par_defaut() {
        // L'étape 2 ne doit pas être activable tant que 60000 n'est pas prouvée.
        assertTrue(!ObstacleSafetyGate.ETAPE2_HORIZONTAL_ACTIVABLE)
    }
}
