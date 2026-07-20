package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM du RailMotionController (Phase 9A). Calcul pur, deterministe.
 * On verifie : sens & bornage de la vitesse, HOLD/deadband, peremption, confiance,
 * limite d'acceleration, et l'invariant "jamais hors des limites".
 */
class RailMotionControllerTest {

    private val EPS = 1e-4f

    // Rampe permissive (maxDeltaV eleve) pour tester la vitesse CIBLE en un tick.
    private fun ctlSansRampe() = RailMotionController(
        maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L,
        gainMps = 4f, maxDeltaVMps = 100f, confidenceMin = 0.50f,
    )

    private fun inp(cur: Float, tgt: Float, age: Long = 0L, conf: Float = 0.9f) =
        RailControlInput(currentPosition = cur, targetPosition = tgt, actionAgeMs = age, confidence = conf)

    // --- SENS & BORNAGE ---

    @Test fun cible_a_droite_donne_vitesse_positive_bornee() {
        // ecart +0.80 ; 0.80*4=3.2 -> borne a 2.0
        val out = ctlSansRampe().compute(inp(cur = 0.10f, tgt = 0.90f))
        assertEquals(2f, out.requestedVelocityMps, EPS)
        assertEquals(RailControlOutput.Reason.MOVE_RIGHT, out.reason)
    }

    @Test fun cible_a_gauche_donne_vitesse_negative_bornee() {
        val out = ctlSansRampe().compute(inp(cur = 0.90f, tgt = 0.10f))
        assertEquals(-2f, out.requestedVelocityMps, EPS)
        assertEquals(RailControlOutput.Reason.MOVE_LEFT, out.reason)
    }

    @Test fun petit_ecart_hors_deadband_vitesse_proportionnelle() {
        // ecart +0.10 -> 0.10*4=0.4 (sous plafond)
        val out = ctlSansRampe().compute(inp(cur = 0.20f, tgt = 0.30f))
        assertEquals(0.4f, out.requestedVelocityMps, EPS)
    }

    // --- HOLD / DEADBAND ---

    @Test fun cible_proche_donne_hold_deadband() {
        // ecart 0.02 < deadband 0.03
        val out = ctlSansRampe().compute(inp(cur = 0.50f, tgt = 0.52f))
        assertEquals(0f, out.requestedVelocityMps, EPS)
        assertEquals(RailControlOutput.Reason.INSIDE_DEADBAND, out.reason)
    }

    // --- SECURITE : perimee / confiance ---

    @Test fun action_perimee_donne_zero() {
        val out = ctlSansRampe().compute(inp(cur = 0.10f, tgt = 0.90f, age = 800L))  // > 500
        assertEquals(0f, out.requestedVelocityMps, EPS)
        assertEquals(RailControlOutput.Reason.ACTION_STALE, out.reason)
    }

    @Test fun confiance_faible_donne_zero() {
        val out = ctlSansRampe().compute(inp(cur = 0.10f, tgt = 0.90f, conf = 0.20f))
        assertEquals(0f, out.requestedVelocityMps, EPS)
        assertEquals(RailControlOutput.Reason.LOW_CONFIDENCE, out.reason)
    }

    // --- BORNAGE cible hors rail ---

    @Test fun cible_hors_rail_est_bornee_puis_deplacement_borne() {
        // target 1.8 -> borne a 1.0 ; depuis 0.5 ecart +0.5 -> vitesse +2.0 (plafond)
        val out = ctlSansRampe().compute(inp(cur = 0.50f, tgt = 1.80f))
        assertEquals(2f, out.requestedVelocityMps, EPS)
        assertEquals(RailControlOutput.Reason.MOVE_RIGHT, out.reason)
    }

    @Test fun position_actuelle_hors_rail_est_bornee() {
        // current -0.5 -> 0 ; target 0.5 -> ecart +0.5 -> MOVE_RIGHT
        val out = ctlSansRampe().compute(inp(cur = -0.5f, tgt = 0.5f))
        assertEquals(RailControlOutput.Reason.MOVE_RIGHT, out.reason)
        assertTrue(out.requestedVelocityMps > 0f)
    }

    // --- LIMITE D'ACCELERATION ---

    @Test fun acceleration_limitee_monte_par_paliers() {
        // maxDeltaV=0.5 : la vitesse ne peut pas passer de 0 a 2 d'un coup.
        val c = RailMotionController(
            maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L,
            gainMps = 4f, maxDeltaVMps = 0.5f, confidenceMin = 0.50f,
        )
        val i = inp(cur = 0.10f, tgt = 0.90f)   // cible +2.0
        val v1 = c.compute(i).requestedVelocityMps
        val v2 = c.compute(i).requestedVelocityMps
        val v3 = c.compute(i).requestedVelocityMps
        assertEquals(0.5f, v1, EPS)   // +0.5 par tick
        assertEquals(1.0f, v2, EPS)
        assertEquals(1.5f, v3, EPS)
        // Jamais au-dela du plafond quel que soit le nombre de ticks.
        repeat(20) { c.compute(i) }
        assertTrue(c.compute(i).requestedVelocityMps <= 2f + EPS)
    }

    @Test fun deceleration_est_aussi_limitee() {
        val c = RailMotionController(
            maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L,
            gainMps = 4f, maxDeltaVMps = 0.5f, confidenceMin = 0.50f,
        )
        // On monte un peu...
        c.compute(inp(cur = 0.10f, tgt = 0.90f))   // v=0.5
        c.compute(inp(cur = 0.10f, tgt = 0.90f))   // v=1.0
        // ...puis action perimee : on ne tombe pas a 0 d'un coup, on decelere de 0.5.
        val out = c.compute(inp(cur = 0.10f, tgt = 0.90f, age = 999L))
        assertEquals(0.5f, out.requestedVelocityMps, EPS)   // 1.0 - 0.5
        assertEquals(RailControlOutput.Reason.ACTION_STALE, out.reason)
    }

    // --- RESET ---

    @Test fun reset_remet_la_vitesse_a_zero() {
        val c = RailMotionController(
            maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L,
            gainMps = 4f, maxDeltaVMps = 0.5f, confidenceMin = 0.50f,
        )
        c.compute(inp(cur = 0.10f, tgt = 0.90f))   // v=0.5
        c.reset()
        val out = c.compute(inp(cur = 0.10f, tgt = 0.90f))   // repart de 0 -> +0.5
        assertEquals(0.5f, out.requestedVelocityMps, EPS)
    }
}
