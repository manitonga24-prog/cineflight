package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du geoperage terrain (point dans polygone). Calcul pur.
 * Terrain carre : lat 45.000..45.001, lon -73.001..-73.000.
 */
class TerrainGeofenceTest {

    private val carre = listOf(
        RailPoint(45.000, -73.001),
        RailPoint(45.000, -73.000),
        RailPoint(45.001, -73.000),
        RailPoint(45.001, -73.001),
    )

    @Test fun point_au_centre_est_dedans() {
        assertTrue(TerrainGeofence.contient(RailPoint(45.0005, -73.0005), carre))
    }

    @Test fun point_a_l_exterieur_est_dehors() {
        assertFalse(TerrainGeofence.contient(RailPoint(45.0005, -73.0020), carre)) // trop a l'ouest
        assertFalse(TerrainGeofence.contient(RailPoint(45.0020, -73.0005), carre)) // trop au nord
    }

    @Test fun terrain_moins_de_3_sommets_toujours_dehors() {
        assertFalse(TerrainGeofence.contient(RailPoint(45.0005, -73.0005), emptyList()))
        assertFalse(TerrainGeofence.contient(RailPoint(45.0005, -73.0005),
            listOf(RailPoint(45.0, -73.0), RailPoint(45.001, -73.0))))
    }

    @Test fun point_juste_dans_un_coin_est_dedans() {
        assertTrue(TerrainGeofence.contient(RailPoint(45.0001, -73.0009), carre))
    }
}
