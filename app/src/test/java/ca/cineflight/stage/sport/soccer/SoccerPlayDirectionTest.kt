package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoccerPlayDirectionTest — couvre l'estimateur de direction du jeu (pur, JVM).
 *
 * Exerce : premier appel (pas de direction), déplacement franc (direction fiable),
 * déplacement trop petit (garde l'ancienne direction), vitesse, et reset.
 */
class SoccerPlayDirectionTest {

    private fun dir() = SoccerPlayDirection()
    private fun pt(x: Float, y: Float) = Point2D(x, y)

    @Test fun premier_appel_pas_de_direction() {
        val e = dir().update(pt(0.5f, 0.5f), 1000L)
        assertFalse("pas fiable au premier appel", e.fiable)
        assertEquals(0f, e.dirX, 1e-4f)
        assertEquals(0f, e.dirY, 1e-4f)
    }

    @Test fun deplacement_franc_vers_la_droite() {
        val d = dir()
        d.update(pt(0.2f, 0.5f), 1000L)          // init
        val e = d.update(pt(0.6f, 0.5f), 1100L)  // bouge vers +x
        assertTrue("fiable après déplacement", e.fiable)
        assertTrue("direction vers la droite (dirX > 0)", e.dirX > 0f)
    }

    @Test fun deplacement_franc_vers_le_haut() {
        val d = dir()
        d.update(pt(0.5f, 0.2f), 1000L)
        val e = d.update(pt(0.5f, 0.7f), 1100L)  // bouge vers +y
        assertTrue(e.fiable)
        assertTrue("direction vers le bas de l'image (dirY > 0)", e.dirY > 0f)
    }

    @Test fun deplacement_trop_petit_garde_direction() {
        val d = dir()
        d.update(pt(0.2f, 0.5f), 1000L)
        d.update(pt(0.6f, 0.5f), 1100L)          // établit une direction fiable
        val e = d.update(pt(0.6001f, 0.5f), 1200L) // micro-déplacement < deplacementMin
        // reste fiable (garde l'ancienne direction lissée)
        assertTrue(e.fiable)
    }

    @Test fun vitesse_positive_quand_ca_bouge() {
        val d = dir()
        d.update(pt(0.2f, 0.5f), 1000L)
        val e = d.update(pt(0.6f, 0.5f), 1100L)
        assertTrue("vitesse > 0", e.vitesse > 0f)
    }

    @Test fun direction_est_unitaire() {
        val d = dir()
        d.update(pt(0.2f, 0.2f), 1000L)
        val e = d.update(pt(0.8f, 0.8f), 1100L)
        val norme = kotlin.math.hypot(e.dirX.toDouble(), e.dirY.toDouble())
        assertEquals("direction normalisée", 1.0, norme, 1e-3)
    }

    @Test fun reset_efface_l_etat() {
        val d = dir()
        d.update(pt(0.2f, 0.5f), 1000L)
        d.update(pt(0.6f, 0.5f), 1100L)
        d.reset()
        // après reset, le premier update repart de zéro (pas fiable)
        val e = d.update(pt(0.5f, 0.5f), 2000L)
        assertFalse(e.fiable)
    }
}
