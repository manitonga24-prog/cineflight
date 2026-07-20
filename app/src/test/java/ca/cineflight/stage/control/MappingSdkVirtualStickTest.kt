package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test « FAUX DRONE » du dernier maillon logiciel avant le SDK DJI (E-01 automatisé, PARTIE
 * LOGICIELLE). On vérifie le SIGNE et le MAPPING des commandes envoyées au VirtualStick, sans
 * matériel : MappingSdkVirtualStick joue le rôle d'espion sur ce qui PARTIRAIT au SDK.
 *
 * PORTÉE (honnête) : ce test prouve que le LOGICIEL envoie le bon signe (throttle+ = monter =
 * verticalThrottle+, etc.). Il NE prouve PAS que le drone RÉEL monte alors — cette correspondance
 * physique appartient au SDK/firmware DJI et se valide uniquement par l'essai E-01 physique.
 *
 * NOTE PRÉCISION : les valeurs sont des Float convertis en Double. On compare donc à la conversion
 * ATTENDUE (0.3f.toDouble()) et non au littéral Double 0.3, et avec une tolérance adaptée au Float.
 */
class MappingSdkVirtualStickTest {

    private val EPS = 1e-6   // tolérance adaptée à la précision Float (pas 1e-9)
    private val M = MappingSdkVirtualStick

    // --- SIGNE DU THROTTLE (axe critique de E-01) ---

    @Test fun throttle_positif_donne_verticalThrottle_positif_monter() {
        val p = M.versParam(0f, 0f, throttle = 0.3f, yaw = 0f, inverserRollPitch = false)
        assertTrue("throttle+ doit rester positif (MONTER)", p.verticalThrottle > 0.0)
        assertEquals(0.3f.toDouble(), p.verticalThrottle, EPS)
    }

    @Test fun throttle_negatif_donne_verticalThrottle_negatif_descendre() {
        val p = M.versParam(0f, 0f, throttle = -0.3f, yaw = 0f, inverserRollPitch = false)
        assertTrue("throttle- doit rester négatif (DESCENDRE)", p.verticalThrottle < 0.0)
        assertEquals((-0.3f).toDouble(), p.verticalThrottle, EPS)
    }

    @Test fun throttle_zero_donne_verticalThrottle_zero() {
        val p = M.versParam(0f, 0f, throttle = 0f, yaw = 0f, inverserRollPitch = false)
        assertEquals(0.0, p.verticalThrottle, EPS)
    }

    // --- PITCH / ROLL / YAW (convention directe, INVERSER_ROLL_PITCH = false) ---

    @Test fun convention_directe_pitch_roll_yaw() {
        val p = M.versParam(pitch = 0.5f, roll = 0.4f, throttle = 0.1f, yaw = 12f, inverserRollPitch = false)
        assertEquals(0.5f.toDouble(), p.pitch, EPS)   // avancer -> pitch+
        assertEquals(0.4f.toDouble(), p.roll, EPS)    // droite  -> roll+
        assertEquals(12f.toDouble(), p.yaw, EPS)      // rotation droite -> yaw+
        assertEquals(0.1f.toDouble(), p.verticalThrottle, EPS)
    }

    @Test fun pitch_negatif_reste_negatif_reculer() {
        val p = M.versParam(pitch = -0.5f, roll = 0f, throttle = 0f, yaw = 0f, inverserRollPitch = false)
        assertTrue(p.pitch < 0.0)
        assertEquals((-0.5f).toDouble(), p.pitch, EPS)
    }

    @Test fun roll_negatif_reste_negatif_gauche() {
        val p = M.versParam(pitch = 0f, roll = -0.4f, throttle = 0f, yaw = 0f, inverserRollPitch = false)
        assertTrue(p.roll < 0.0)
        assertEquals((-0.4f).toDouble(), p.roll, EPS)
    }

    @Test fun yaw_negatif_reste_negatif_gauche() {
        val p = M.versParam(pitch = 0f, roll = 0f, throttle = 0f, yaw = -20f, inverserRollPitch = false)
        assertTrue(p.yaw < 0.0)
        assertEquals((-20f).toDouble(), p.yaw, EPS)
    }

    // --- INVERSION ROLL/PITCH ---

    @Test fun inversion_echange_pitch_et_roll_seulement() {
        val direct = M.versParam(pitch = 0.5f, roll = 0.4f, throttle = 0.2f, yaw = 9f, inverserRollPitch = false)
        val inv = M.versParam(pitch = 0.5f, roll = 0.4f, throttle = 0.2f, yaw = 9f, inverserRollPitch = true)
        // pitch et roll sont échangés...
        assertEquals(direct.roll, inv.pitch, EPS)
        assertEquals(direct.pitch, inv.roll, EPS)
        // ...mais throttle et yaw ne changent JAMAIS avec l'inversion.
        assertEquals(direct.verticalThrottle, inv.verticalThrottle, EPS)
        assertEquals(direct.yaw, inv.yaw, EPS)
    }

    @Test fun inversion_ne_touche_pas_au_signe_du_throttle() {
        // Même sous inversion, monter reste monter (c'est l'invariant de sécurité de E-01).
        val p = M.versParam(pitch = 0f, roll = 0f, throttle = 0.3f, yaw = 0f, inverserRollPitch = true)
        assertTrue(p.verticalThrottle > 0.0)
        assertEquals(0.3f.toDouble(), p.verticalThrottle, EPS)
    }

    // --- COHÉRENCE : altitude seule (baseline Essai 1) ---

    @Test fun baseline_altitude_seule_seul_throttle_non_nul() {
        // En Essai 1, roll/pitch/yaw = 0, seul le throttle porte la commande.
        val p = M.versParam(pitch = 0f, roll = 0f, throttle = 0.25f, yaw = 0f, inverserRollPitch = false)
        assertEquals(0.0, p.pitch, EPS)
        assertEquals(0.0, p.roll, EPS)
        assertEquals(0.0, p.yaw, EPS)
        assertTrue(p.verticalThrottle > 0.0)
    }
}
