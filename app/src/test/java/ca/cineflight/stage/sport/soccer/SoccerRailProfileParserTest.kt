package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests du parseur de profil SOCCER_RAIL. org.json est fourni par Robolectric
 * (meme convention que les autres tests du projet qui touchent Android/org.json).
 */
@RunWith(RobolectricTestRunner::class)
class SoccerRailProfileParserTest {

    private val EPS = 1e-6
    private val EPS_F = 1e-4f

    // JSON valide, aligne sur la spec Phase 3 (+ safety_verdict obligatoire).
    private val jsonValide = """
        {
          "mode": "SOCCER_RAIL",
          "rail_start": { "lat": 45.0, "lon": -73.0 },
          "rail_end":   { "lat": 45.0, "lon": -72.999 },
          "altitude_agl_m": 25,
          "max_speed_mps": 2,
          "safe_position": 0.5,
          "spectator_zones": [],
          "takeoff_zone": {},
          "safety_verdict": { "approved": true, "reason": "" }
        }
    """.trimIndent()

    private fun ok(json: String): SoccerRailProfile {
        val r = SoccerRailProfileParser.parse(json)
        assertTrue("attendu Ok mais recu $r", r is SoccerRailProfileParser.Resultat.Ok)
        return (r as SoccerRailProfileParser.Resultat.Ok).profile
    }

    private fun erreur(json: String): String {
        val r = SoccerRailProfileParser.parse(json)
        assertTrue("attendu Erreur mais recu $r", r is SoccerRailProfileParser.Resultat.Erreur)
        return (r as SoccerRailProfileParser.Resultat.Erreur).message
    }

    // --- VALIDE ---

    @Test fun profil_valide_est_parse() {
        val p = ok(jsonValide)
        assertEquals("SOCCER_RAIL", p.mode)
        assertEquals(45.0, p.rail.start.lat, EPS)
        assertEquals(-73.0, p.rail.start.lon, EPS)
        assertEquals(-72.999, p.rail.end.lon, EPS)
        assertEquals(25.0, p.altitudeAglM, EPS)
        assertEquals(2.0, p.maxSpeedMps, EPS)
        assertEquals(0.5f, p.safePosition, EPS_F)
        assertTrue(p.safetyVerdict.approved)
        assertTrue(p.spectatorZones.isEmpty())
        assertTrue(p.takeoffZone.isEmpty())
    }

    @Test fun rail_du_profil_est_utilisable_par_le_planner() {
        val p = ok(jsonValide)
        val target = SoccerRailPlanner().calculateTarget(0.5f, p.rail)
        assertEquals(-72.9995, target.point.lon, EPS)   // centre du rail
    }

    // --- REJETS (fail-closed) ---

    @Test fun mode_incorrect_est_rejete() {
        val m = erreur(jsonValide.replace("SOCCER_RAIL", "AUTRE_MODE"))
        assertTrue(m.contains("mode"))
    }

    @Test fun rail_start_manquant_est_rejete() {
        val sansStart = """
            { "mode": "SOCCER_RAIL",
              "rail_end": { "lat": 45.0, "lon": -72.999 },
              "altitude_agl_m": 25, "max_speed_mps": 2, "safe_position": 0.5,
              "safety_verdict": { "approved": true, "reason": "" } }
        """.trimIndent()
        assertTrue(erreur(sansStart).contains("rail_start"))
    }

    @Test fun altitude_non_positive_est_rejetee() {
        assertTrue(erreur(jsonValide.replace("\"altitude_agl_m\": 25", "\"altitude_agl_m\": 0")).contains("altitude"))
    }

    @Test fun vitesse_negative_est_rejetee() {
        assertTrue(erreur(jsonValide.replace("\"max_speed_mps\": 2", "\"max_speed_mps\": -1")).contains("max_speed"))
    }

    @Test fun safe_position_hors_bornes_est_rejetee() {
        assertTrue(erreur(jsonValide.replace("\"safe_position\": 0.5", "\"safe_position\": 1.5")).contains("safe_position"))
    }

    @Test fun rail_degenere_est_rejete() {
        val degenere = jsonValide.replace(
            "\"rail_end\":   { \"lat\": 45.0, \"lon\": -72.999 }",
            "\"rail_end\":   { \"lat\": 45.0, \"lon\": -73.0 }"
        )
        assertTrue(erreur(degenere).contains("degenere"))
    }

    @Test fun safety_verdict_manquant_est_rejete() {
        val sansVerdict = """
            { "mode": "SOCCER_RAIL",
              "rail_start": { "lat": 45.0, "lon": -73.0 },
              "rail_end": { "lat": 45.0, "lon": -72.999 },
              "altitude_agl_m": 25, "max_speed_mps": 2, "safe_position": 0.5 }
        """.trimIndent()
        assertTrue(erreur(sansVerdict).contains("safety_verdict"))
    }

    @Test fun verdict_non_approuve_reste_lisible() {
        val refuse = jsonValide.replace(
            "\"safety_verdict\": { \"approved\": true, \"reason\": \"\" }",
            "\"safety_verdict\": { \"approved\": false, \"reason\": \"trop pres du public\" }"
        )
        val p = ok(refuse)   // le profil est valide structurellement...
        assertTrue(!p.safetyVerdict.approved)   // ...mais NON approuve
        assertEquals("trop pres du public", p.safetyVerdict.reason)
    }

    @Test fun json_mal_forme_est_rejete() {
        assertTrue(erreur("{ ceci n'est pas du json").contains("JSON invalide"))
    }

    // --- ZONES ---

    @Test fun spectator_zones_sont_lues() {
        val avecZones = jsonValide.replace(
            "\"spectator_zones\": []",
            "\"spectator_zones\": [[{\"lat\":45.0,\"lon\":-73.0},{\"lat\":45.001,\"lon\":-73.0}]]"
        )
        val p = ok(avecZones)
        assertEquals(1, p.spectatorZones.size)
        assertEquals(2, p.spectatorZones[0].size)
    }
}
