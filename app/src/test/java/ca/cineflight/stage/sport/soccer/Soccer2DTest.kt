package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du mode 2D : direction du jeu + planificateur de position en retrait.
 * Calcul pur, deterministe (temps fourni par l'appelant).
 */
class Soccer2DTest {

    private val EPS = 0.05f

    // --- Direction du jeu ---

    @Test fun direction_vers_la_droite_est_detectee() {
        val d = SoccerPlayDirection(lissage = 1f)   // pas de lissage pour un test net
        d.update(Point2D(0.2f, 0.5f), 0L)
        val e = d.update(Point2D(0.4f, 0.5f), 1000L)   // deplacement +x
        assertTrue(e.fiable)
        assertEquals(1f, e.dirX, EPS)     // direction ~ +x
        assertEquals(0f, e.dirY, EPS)
    }

    @Test fun premiere_mesure_pas_fiable() {
        val d = SoccerPlayDirection()
        val e = d.update(Point2D(0.5f, 0.5f), 0L)
        assertTrue(!e.fiable)
    }

    @Test fun deplacement_infime_garde_direction_precedente() {
        val d = SoccerPlayDirection(lissage = 1f, deplacementMin = 0.02f)
        d.update(Point2D(0.2f, 0.5f), 0L)
        d.update(Point2D(0.4f, 0.5f), 1000L)          // etablit +x
        val e = d.update(Point2D(0.4005f, 0.5f), 2000L)  // bouge a peine
        assertEquals(1f, e.dirX, EPS)                  // garde +x
    }

    // --- Planificateur retrait 2D ---

    @Test fun position_est_en_retrait_derriere_la_direction() {
        val p = Soccer2DPlanner(retraitFractionDefaut = 0.2f, decalageFractionDefaut = 0f)
        // action au centre, jeu vers +x -> drone doit etre a GAUCHE (x plus petit).
        val c = p.calculer(Point2D(0.5f, 0.5f), Point2D(1f, 0f), emptyList())
        assertEquals(0.3f, c.x, EPS)   // 0.5 - 0.2
        assertEquals(0.5f, c.y, EPS)
    }

    @Test fun decalage_lateral_applique_perpendiculairement() {
        val p = Soccer2DPlanner(retraitFractionDefaut = 0f, decalageFractionDefaut = 0.1f)
        // jeu vers +x -> perpendiculaire = (0, +1) -> decalage en +y
        val c = p.calculer(Point2D(0.5f, 0.5f), Point2D(1f, 0f), emptyList())
        assertEquals(0.5f, c.x, EPS)
        assertEquals(0.6f, c.y, EPS)   // 0.5 + 0.1
    }

    @Test fun cible_hors_terrain_est_ramenee_dedans() {
        val terrain = listOf(
            Point2D(0.4f, 0.4f), Point2D(0.6f, 0.4f),
            Point2D(0.6f, 0.6f), Point2D(0.4f, 0.6f),
        )
        // action au centre du terrain, gros retrait qui sortirait a gauche.
        val p = Soccer2DPlanner(retraitFractionDefaut = 0.5f, decalageFractionDefaut = 0f)
        val c = p.calculer(Point2D(0.5f, 0.5f), Point2D(1f, 0f), terrain)
        // doit etre ramenee dans [0.4,0.6] x [0.4,0.6]
        assertTrue("x dans terrain", c.x in 0.39f..0.61f)
        assertTrue("y dans terrain", c.y in 0.39f..0.61f)
    }

    @Test fun sans_terrain_cible_bornee_0_1() {
        val p = Soccer2DPlanner(retraitFractionDefaut = 2f, decalageFractionDefaut = 0f)   // retrait enorme
        val c = p.calculer(Point2D(0.5f, 0.5f), Point2D(1f, 0f), emptyList())
        assertTrue(c.x in 0f..1f && c.y in 0f..1f)
    }
}
