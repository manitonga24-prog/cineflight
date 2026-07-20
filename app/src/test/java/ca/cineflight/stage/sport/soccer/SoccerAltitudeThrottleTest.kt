package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests du controleur altitude->throttle (Essai 1 emission reelle). Sens/zone morte/bornage/NaN. */
class SoccerAltitudeThrottleTest {

    @Test fun altitude_cible_plus_haute_donne_montee() {
        val c = SoccerAltitudeThrottle(kp = 0.3f, zoneMorteM = 0.30f)
        // opt=22, act=20 -> erreur +2 -> montee (throttle > 0 dans ce projet).
        val th = c.throttle(22.0, 20.0, vMaxMps = 1f)
        assertTrue("montee attendue (throttle>0), obtenu $th", th > 0f)
    }

    @Test fun altitude_cible_plus_basse_donne_descente() {
        val c = SoccerAltitudeThrottle(kp = 0.3f, zoneMorteM = 0.30f)
        val th = c.throttle(18.0, 20.0, vMaxMps = 1f)
        assertTrue("descente attendue (throttle<0), obtenu $th", th < 0f)
    }

    @Test fun zone_morte_donne_zero() {
        val c = SoccerAltitudeThrottle(kp = 0.3f, zoneMorteM = 0.30f)
        // ecart 0.2 m < zone morte 0.30 -> pas de mouvement.
        assertEquals(0f, c.throttle(20.2, 20.0, vMaxMps = 1f), 1e-6f)
    }

    @Test fun proportionnel_au_gain() {
        val c = SoccerAltitudeThrottle(kp = 0.3f, zoneMorteM = 0.30f)
        // erreur +2 * kp 0.3 = 0.6 m/s (sous vMax 1).
        assertEquals(0.6f, c.throttle(22.0, 20.0, vMaxMps = 1f), 1e-4f)
    }

    @Test fun borne_a_vmax() {
        val c = SoccerAltitudeThrottle(kp = 0.3f, zoneMorteM = 0.30f)
        // erreur +10 * 0.3 = 3 m/s, mais vMax 0.5 -> borne a 0.5.
        assertEquals(0.5f, c.throttle(30.0, 20.0, vMaxMps = 0.5f), 1e-4f)
        assertEquals(-0.5f, c.throttle(10.0, 20.0, vMaxMps = 0.5f), 1e-4f)
    }

    // --- DOUBLE VERROU : vMax 0 (Essai 1 defaut) -> aucun mouvement, meme gros ecart ---

    @Test fun vmax_zero_donne_toujours_zero() {
        val c = SoccerAltitudeThrottle()
        assertEquals(0f, c.throttle(35.0, 15.0, vMaxMps = 0f), 1e-6f)
    }

    @Test fun vmax_negatif_donne_zero() {
        val c = SoccerAltitudeThrottle()
        assertEquals(0f, c.throttle(35.0, 15.0, vMaxMps = -1f), 1e-6f)
    }

    // --- FAIL-SAFE : entrees invalides -> 0 ---

    @Test fun altitude_nan_donne_zero() {
        val c = SoccerAltitudeThrottle()
        assertEquals(0f, c.throttle(Double.NaN, 20.0, 1f), 1e-6f)
        assertEquals(0f, c.throttle(22.0, Double.NaN, 1f), 1e-6f)
        assertEquals(0f, c.throttle(22.0, 20.0, Float.NaN), 1e-6f)
    }

    @Test fun altitude_infinie_donne_zero() {
        val c = SoccerAltitudeThrottle()
        assertEquals(0f, c.throttle(Double.POSITIVE_INFINITY, 20.0, 1f), 1e-6f)
    }

    // --- Signe isole verifiable ---

    @Test fun sens_throttle_montee_est_positif_par_defaut() {
        // Documente la convention de ce projet (verticalThrottle + = montee).
        assertEquals(1f, SoccerAltitudeThrottle.SENS_THROTTLE_MONTEE, 1e-6f)
    }
}
