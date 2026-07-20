package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de RailVitesse — loi de vitesse commune (proportionnelle + rampe).
 * Composant CRITIQUE : partage par RailMotionController et les controleurs 2D.
 */
class RailVitesseTest {

    private val EPS = 1e-4f

    // --- proportionnelle ---

    @Test fun ecart_positif_vitesse_positive() {
        assertEquals(0.4f, RailVitesse.proportionnelle(0.1f, 4f, 2f), EPS)
    }

    @Test fun ecart_negatif_vitesse_negative() {
        assertEquals(-0.4f, RailVitesse.proportionnelle(-0.1f, 4f, 2f), EPS)
    }

    @Test fun ecart_nul_vitesse_nulle() {
        assertEquals(0f, RailVitesse.proportionnelle(0f, 4f, 2f), EPS)
    }

    @Test fun vitesse_bornee_au_plafond_positif() {
        assertEquals(2f, RailVitesse.proportionnelle(10f, 4f, 2f), EPS)   // 40 -> 2
    }

    @Test fun vitesse_bornee_au_plafond_negatif() {
        assertEquals(-2f, RailVitesse.proportionnelle(-10f, 4f, 2f), EPS)
    }

    @Test fun plafond_zero_donne_toujours_zero() {
        assertEquals(0f, RailVitesse.proportionnelle(5f, 4f, 0f), EPS)   // maxSpeed=0 -> immobile
    }

    // --- rampe (limite d'acceleration) ---

    @Test fun rampe_limite_la_hausse() {
        // de 0 vers 2 avec delta max 0.5 -> 0.5
        assertEquals(0.5f, RailVitesse.rampe(2f, 0f, 0.5f), EPS)
    }

    @Test fun rampe_limite_la_baisse() {
        // de 1 vers 0 avec delta max 0.5 -> 0.5
        assertEquals(0.5f, RailVitesse.rampe(0f, 1f, 0.5f), EPS)
    }

    @Test fun rampe_atteint_la_cible_si_dans_le_delta() {
        assertEquals(1.2f, RailVitesse.rampe(1.2f, 1f, 0.5f), EPS)   // ecart 0.2 < 0.5
    }

    @Test fun rampe_desactivee_si_delta_zero() {
        assertEquals(2f, RailVitesse.rampe(2f, 0f, 0f), EPS)   // pas de limite -> cible direct
    }

    @Test fun rampe_convergence_en_plusieurs_pas() {
        var v = 0f
        repeat(10) { v = RailVitesse.rampe(2f, v, 0.5f) }
        assertTrue("converge vers 2 sans depasser", v in 1.99f..2.01f)
    }
}
