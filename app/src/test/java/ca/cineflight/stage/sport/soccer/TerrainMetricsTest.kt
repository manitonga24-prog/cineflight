package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Tests des dimensions reelles du terrain + retrait metrique + distance oblique.
 * Valide la plage operationnelle : altitude 15-22 m, retrait 15-22 m, oblique 21-31 m.
 */
class TerrainMetricsTest {

    // Terrain ~100 m (E-O) x ~64 m (N-S) a 45N.
    // 100 m lon a 45N ~ 0.001267 deg ; 64 m lat ~ 0.000576 deg.
    private val terrain = listOf(
        RailPoint(45.0000, -73.0000),
        RailPoint(45.0000, -72.998733),   // +100 m est
        RailPoint(45.000576, -72.998733), // +64 m nord
        RailPoint(45.000576, -73.0000),
    )

    @Test fun dimensions_terrain_approx_100x64() {
        val d = TerrainMetrics.dimensions(terrain)
        assertTrue(d.valide)
        assertEquals(100.0, d.largeurM, 5.0)
        assertEquals(64.0, d.longueurM, 5.0)
    }

    @Test fun polygone_insuffisant_invalide() {
        val d = TerrainMetrics.dimensions(listOf(RailPoint(45.0, -73.0), RailPoint(45.0, -72.999)))
        assertFalse(d.valide)
    }

    @Test fun conversion_18m_donne_fraction_raisonnable() {
        val d = TerrainMetrics.dimensions(terrain)   // ref ~ (100+64)/2 = 82 m
        val f = TerrainMetrics.metresEnFraction(18.0, d)
        assertEquals((18.0 / 82.0).toFloat(), f, 0.02f)
    }

    @Test fun sans_dimensions_repli_terrain_standard() {
        val invalide = TerrainMetrics.Dimensions(0.0, 0.0)
        // repli 100 m -> 18 m = 0.18
        assertEquals(0.18f, TerrainMetrics.metresEnFraction(18.0, invalide), 0.01f)
    }

    // --- Distance oblique : verif de la plage 21-31 m ---

    @Test fun distance_oblique_dans_la_plage_confirmee() {
        // Combinaisons extremes altitude/retrait (15-22).
        val obliqueMin = hypot(15.0, 15.0)   // ~21.2
        val obliqueMax = hypot(22.0, 22.0)   // ~31.1
        assertEquals(21.2, obliqueMin, 0.3)
        assertEquals(31.1, obliqueMax, 0.3)
        assertTrue(obliqueMin in 21.0..22.0 && obliqueMax in 30.5..31.5)
    }

    // --- Retrait metrique dans Soccer2DPlanner ---

    @Test fun planner_retrait_metrique_recule_derriere_l_action() {
        val d = TerrainMetrics.dimensions(terrain)
        val p = Soccer2DPlanner(retraitMetres = 18.0, decalageMetres = 0.0)
        // action au centre, jeu vers +x -> drone recule en -x, d'une fraction ~18/82.
        val c = p.calculer(Point2D(0.5f, 0.5f), Point2D(1f, 0f), emptyList(), d)
        val reculAttendu = (18.0 / 82.0).toFloat()
        assertEquals(0.5f - reculAttendu, c.x, 0.03f)
    }
}
