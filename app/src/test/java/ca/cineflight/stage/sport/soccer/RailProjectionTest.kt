package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la projection GNSS -> fraction de rail (passe E). Calcul pur.
 * On verifie EXPLICITEMENT l'axe et le signe : un deplacement vers "end" augmente
 * la fraction ; un deplacement vers "start" la diminue.
 *
 * Rail d'essai : lat 45.0 constante, lon -73.0 (start) -> -72.999 (end).
 * A cette latitude, 0.001 deg de lon ~ 78.6 m ; le rail est ~est-ouest.
 */
class RailProjectionTest {

    private val EPS = 1e-3f

    private val rail = DroneRail(
        start = RailPoint(lat = 45.0, lon = -73.0),
        end = RailPoint(lat = 45.0, lon = -72.999),
    )

    // --- EXTREMITES ---

    @Test fun point_sur_start_donne_zero() {
        assertEquals(0f, RailProjection.fraction(RailPoint(45.0, -73.0), rail), EPS)
    }

    @Test fun point_sur_end_donne_un() {
        assertEquals(1f, RailProjection.fraction(RailPoint(45.0, -72.999), rail), EPS)
    }

    @Test fun point_au_milieu_donne_moitie() {
        assertEquals(0.5f, RailProjection.fraction(RailPoint(45.0, -72.9995), rail), EPS)
    }

    @Test fun point_a_20pct_donne_020() {
        // lon = -73.0 + 0.001*0.2 = -72.9998
        assertEquals(0.2f, RailProjection.fraction(RailPoint(45.0, -72.9998), rail), EPS)
    }

    // --- AXE & SIGNE ---

    @Test fun deplacement_vers_end_augmente_la_fraction() {
        val f1 = RailProjection.fraction(RailPoint(45.0, -72.9997), rail)  // ~0.3
        val f2 = RailProjection.fraction(RailPoint(45.0, -72.9995), rail)  // ~0.5 (plus pres de end)
        assertTrue("aller vers end doit AUGMENTER la fraction", f2 > f1)
    }

    @Test fun deplacement_vers_start_diminue_la_fraction() {
        val f1 = RailProjection.fraction(RailPoint(45.0, -72.9995), rail)  // ~0.5
        val f2 = RailProjection.fraction(RailPoint(45.0, -72.9998), rail)  // ~0.2 (plus pres de start)
        assertTrue("aller vers start doit DIMINUER la fraction", f2 < f1)
    }

    // --- BORNAGE hors segment ---

    @Test fun point_avant_start_est_borne_a_zero() {
        // lon -73.0005 : au-dela de start dans le sens oppose a end -> borne a 0.
        assertEquals(0f, RailProjection.fraction(RailPoint(45.0, -73.0005), rail), EPS)
    }

    @Test fun point_apres_end_est_borne_a_un() {
        // lon -72.9985 : au-dela de end -> borne a 1.
        assertEquals(1f, RailProjection.fraction(RailPoint(45.0, -72.9985), rail), EPS)
    }

    // --- PROJECTION ORTHOGONALE (point lateral) ---

    @Test fun point_lateral_est_projete_orthogonalement() {
        // Point decale en LATITUDE (perpendiculaire au rail est-ouest) mais a la meme
        // longitude que le milieu -> sa projection reste ~0.5.
        val f = RailProjection.fraction(RailPoint(45.0002, -72.9995), rail)
        assertEquals(0.5f, f, EPS)
    }

    // --- RAIL DEGENERE (fail-safe) ---

    @Test fun rail_degenere_renvoie_zero() {
        val degenere = DroneRail(RailPoint(45.0, -73.0), RailPoint(45.0, -73.0))
        assertEquals(0f, RailProjection.fraction(RailPoint(45.0, -72.999), degenere), EPS)
    }

    // --- RAIL DIAGONAL (axe non aligne) ---

    @Test fun rail_diagonal_projette_correctement() {
        val diag = DroneRail(
            start = RailPoint(lat = 45.0, lon = -73.0),
            end = RailPoint(lat = 45.010, lon = -73.010),
        )
        // Point exactement au milieu geometrique -> ~0.5.
        val f = RailProjection.fraction(RailPoint(45.005, -73.005), diag)
        assertEquals(0.5f, f, EPS)
    }

    // --- DISTANCE au segment (metres) ---

    @Test fun point_sur_le_rail_distance_nulle() {
        // milieu du rail est-ouest -> distance ~0
        val d = RailProjection.distanceM(RailPoint(45.0, -72.9995), rail)
        assertTrue("distance devrait etre ~0, recu $d", d < 1.0)
    }

    @Test fun point_lateral_distance_correspond_a_l_ecart() {
        // decale de 0.001 deg de latitude (~111 m) perpendiculairement au rail est-ouest
        val d = RailProjection.distanceM(RailPoint(45.001, -72.9995), rail)
        assertEquals(111.0, d, 3.0)   // ~111 m, tolerance 3 m
    }

    @Test fun point_au_dela_du_bout_mesure_jusqu_au_bout() {
        // bien au-dela de end (lon -72.997), aligne sur l'axe : distance = distance a end
        val d = RailProjection.distanceM(RailPoint(45.0, -72.997), rail)
        // end est a -72.999 ; ecart 0.002 deg lon a 45N ~ 157 m
        assertTrue("distance devrait etre ~150+ m, recu $d", d > 140.0 && d < 175.0)
    }
}
