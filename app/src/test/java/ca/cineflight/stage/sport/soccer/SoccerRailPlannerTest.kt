package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de SoccerRailPlanner (projection pure action -> rail).
 * Rail d'exemple (spec Phase 3) : lat constante 45.0, lon de -73.0 a -72.999.
 * Donc la fraction f donne lon = -73.0 + 0.001 * f, lat = 45.0.
 */
class SoccerRailPlannerTest {

    private val planner = SoccerRailPlanner()
    private val EPS_F = 1e-4f
    private val EPS_D = 1e-9

    private val rail = DroneRail(
        start = RailPoint(lat = 45.0, lon = -73.0),
        end = RailPoint(lat = 45.0, lon = -72.999),
    )

    // --- MAPPING DIRECT ---

    @Test fun action_20pct_donne_cible_20pct_du_rail() {
        val t = planner.calculateTarget(0.20f, rail)
        assertEquals(0.20f, t.railFraction, EPS_F)
        assertEquals(45.0, t.point.lat, EPS_D)
        assertEquals(-73.0 + 0.001 * 0.20, t.point.lon, EPS_D)   // -72.9998
    }

    @Test fun action_75pct_donne_cible_75pct_du_rail() {
        val t = planner.calculateTarget(0.75f, rail)
        assertEquals(0.75f, t.railFraction, EPS_F)
        assertEquals(-73.0 + 0.001 * 0.75, t.point.lon, EPS_D)   // -72.99925
    }

    // --- EXTREMITES ---

    @Test fun action_zero_donne_extremite_start() {
        val t = planner.calculateTarget(0f, rail)
        assertEquals(0f, t.railFraction, EPS_F)
        assertEquals(-73.0, t.point.lon, EPS_D)
        assertEquals(45.0, t.point.lat, EPS_D)
    }

    @Test fun action_un_donne_extremite_end() {
        val t = planner.calculateTarget(1f, rail)
        assertEquals(1f, t.railFraction, EPS_F)
        assertEquals(-72.999, t.point.lon, EPS_D)
    }

    @Test fun action_milieu_donne_centre_du_rail() {
        val t = planner.calculateTarget(0.5f, rail)
        assertEquals(0.5f, t.railFraction, EPS_F)
        assertEquals(-72.9995, t.point.lon, EPS_D)
    }

    // --- BORNAGE : jamais au-dela du rail ---

    @Test fun action_negative_est_bornee_au_debut_du_rail() {
        val t = planner.calculateTarget(-0.5f, rail)
        assertEquals(0f, t.railFraction, EPS_F)
        assertEquals(-73.0, t.point.lon, EPS_D)   // = start, pas au-dela
    }

    @Test fun action_superieure_a_un_est_bornee_a_la_fin_du_rail() {
        val t = planner.calculateTarget(1.8f, rail)
        assertEquals(1f, t.railFraction, EPS_F)
        assertEquals(-72.999, t.point.lon, EPS_D) // = end, pas au-dela
    }

    // --- RAIL DIAGONAL (interpolation lat ET lon) ---

    @Test fun rail_diagonal_interpole_les_deux_axes() {
        val diagonal = DroneRail(
            start = RailPoint(lat = 45.0, lon = -73.0),
            end = RailPoint(lat = 45.010, lon = -73.020),
        )
        val t = planner.calculateTarget(0.25f, diagonal)
        assertEquals(45.0 + 0.010 * 0.25, t.point.lat, EPS_D)     // 45.0025
        assertEquals(-73.0 + (-0.020) * 0.25, t.point.lon, EPS_D) // -73.005
    }
}
