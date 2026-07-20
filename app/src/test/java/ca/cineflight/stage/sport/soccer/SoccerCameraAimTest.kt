package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoccerCameraAimTest — couvre le contrôleur caméra/nacelle (pur, JVM).
 *
 * Exerce toutes les branches de calculer() : zone morte (aucune correction),
 * petit écart (nacelle seule), gros écart (yaw + nacelle), bornage du yaw et du tilt,
 * et la règle de lead room (positionIdealeX) selon la direction du jeu, y compris NaN.
 */
class SoccerCameraAimTest {

    private val aim = SoccerCameraAim()

    @Test fun zone_morte_aucune_correction_yaw() {
        // action pile sur la cible idéale (dirX=0 -> cible 0.5) -> errX ~ 0 -> zone morte
        val r = aim.calculer(0.5f, 0.5f, 0f)
        assertEquals(0f, r.yawDps, 1e-4f)
        assertTrue(r.useNacelleOnly)
    }

    @Test fun petit_ecart_nacelle_seule_pas_de_yaw() {
        // écart horizontal entre zoneMorte(0.04) et seuilYaw(0.25) -> nacelle seule
        val r = aim.calculer(0.65f, 0.5f, 0f) // cible 0.5, errX=0.15
        assertEquals(0f, r.yawDps, 1e-4f)
        assertTrue(r.useNacelleOnly)
    }

    @Test fun gros_ecart_droite_donne_yaw_positif() {
        // écart > seuilYaw -> yaw activé ; action à droite -> yaw > 0
        val r = aim.calculer(0.95f, 0.5f, 0f) // cible 0.5, errX=0.45
        assertTrue("yaw doit être positif", r.yawDps > 0f)
        assertFalse(r.useNacelleOnly)
    }

    @Test fun gros_ecart_gauche_donne_yaw_negatif() {
        val r = aim.calculer(0.05f, 0.5f, 0f) // cible 0.5, errX=-0.45
        assertTrue("yaw doit être négatif", r.yawDps < 0f)
        assertFalse(r.useNacelleOnly)
    }

    @Test fun yaw_borne_a_max() {
        // écart énorme -> yaw doit être borné à yawMaxDps (20)
        val r = aim.calculer(1.0f, 0.5f, -1f) // dir gauche décale cible à droite, gros errX
        assertTrue("yaw borné à ±20", kotlin.math.abs(r.yawDps) <= 20f + 1e-4f)
    }

    @Test fun tilt_vers_le_bas_si_action_basse() {
        // cy > 0.5 -> action trop basse -> tilt positif (incliner vers le bas)
        val r = aim.calculer(0.5f, 0.9f, 0f)
        assertTrue("tilt doit être positif", r.tiltStepDeg > 0f)
    }

    @Test fun tilt_vers_le_haut_si_action_haute() {
        val r = aim.calculer(0.5f, 0.1f, 0f)
        assertTrue("tilt doit être négatif", r.tiltStepDeg < 0f)
    }

    @Test fun tilt_borne_par_pas() {
        // cy extrême -> tilt borné à tiltStepMaxDeg (4)
        val r = aim.calculer(0.5f, 1.0f, 0f)
        assertTrue("tilt borné à ±4", kotlin.math.abs(r.tiltStepDeg) <= 4f + 1e-4f)
    }

    // ── Règle de lead room (positionIdealeX) ──

    @Test fun lead_room_jeu_droite_decale_cible_a_gauche() {
        // dirX > 0 (jeu vers la droite) -> cible décalée à GAUCHE (< 0.5)
        val cible = SoccerCameraAim.positionIdealeX(1f, 0.12f)
        assertTrue("cible < 0.5 quand jeu à droite", cible < 0.5f)
    }

    @Test fun lead_room_jeu_gauche_decale_cible_a_droite() {
        val cible = SoccerCameraAim.positionIdealeX(-1f, 0.12f)
        assertTrue("cible > 0.5 quand jeu à gauche", cible > 0.5f)
    }

    @Test fun lead_room_jeu_neutre_cible_centre() {
        // dirX dans la zone morte (|dirX| < 0.05) -> cible = 0.5
        val cible = SoccerCameraAim.positionIdealeX(0f, 0.12f)
        assertEquals(0.5f, cible, 1e-4f)
    }

    @Test fun lead_room_nan_safe() {
        // entrées non finies -> 0.5 (centre), pas de crash ni NaN
        assertEquals(0.5f, SoccerCameraAim.positionIdealeX(Float.NaN, 0.12f), 1e-4f)
        assertEquals(0.5f, SoccerCameraAim.positionIdealeX(1f, Float.NaN), 1e-4f)
    }
}
