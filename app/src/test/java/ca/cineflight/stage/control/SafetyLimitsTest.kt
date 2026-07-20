package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafetyLimitsTest — vérifie que la source unique de vérité des seuils de sécurité
 * porte les valeurs attendues (celles en vigueur dans le code et documentées au dossier).
 *
 * Ces tests figent les valeurs : toute modification d'un seuil de sécurité casse un test,
 * forçant une revue explicite (et la mise à jour du dossier + re-test E-01..E-12).
 */
class SafetyLimitsTest {

    @Test fun fraicheur_yolo_600ms() {
        assertEquals(600L, SafetyLimits.YOLO_FRAIS_MS)
    }

    @Test fun age_rtk_2s() {
        assertEquals(2.0, SafetyLimits.RTK_AGE_MAX_S, 0.0)
    }

    @Test fun watchdog_500ms() {
        assertEquals(500L, SafetyLimits.WATCHDOG_TIMEOUT_MS)
    }

    @Test fun confiance_yolo_min_035() {
        assertEquals(0.35f, SafetyLimits.YOLO_CONF_MIN, 0f)
    }

    @Test fun batterie_min_40pct() {
        assertEquals(40, SafetyLimits.BATTERIE_MIN_PCT)
    }

    @Test fun plafond_operationnel_sous_reglementaire() {
        // Le plafond opérationnel (35 m) doit rester bien sous la limite réglementaire (122 m).
        assertTrue(SafetyLimits.ALT_OP_MAX_M < 122.0)
        assertTrue(SafetyLimits.ALT_OP_MAX_M <= SafetyLimits.ALT_MAX_M)
    }

    @Test fun vitesse_soccer_sous_plafond_general() {
        // La vitesse soccer 2D ne doit jamais dépasser le plafond horizontal général.
        assertTrue(SafetyLimits.SOCCER_2D_VMAX_MPS <= SafetyLimits.V_MAX_HORIZ_MPS)
    }

    @Test fun buffer_confinement_positif_et_coherent() {
        // Le buffer doit être strictement positif et refléter la valeur du §4.2quater.
        assertTrue(SafetyLimits.BUFFER_CONFINEMENT_M > 0.0)
        assertEquals(16.2, SafetyLimits.BUFFER_CONFINEMENT_M, 0.01)
    }

    @Test fun frequences_boucles_positives() {
        assertTrue(SafetyLimits.BOUCLE_PILOTE_HZ > 0)
        assertTrue(SafetyLimits.BOUCLE_TEST_AXES_HZ >= SafetyLimits.BOUCLE_PILOTE_HZ)
        assertTrue(SafetyLimits.POLLER_RTK_HZ > 0)
    }

    @Test fun coherence_ages_watchdog_vs_yolo() {
        // Le watchdog (500 ms) et la fraîcheur YOLO (600 ms) sont du même ordre de grandeur,
        // cohérents avec une boucle ~10 Hz.
        assertTrue(SafetyLimits.WATCHDOG_TIMEOUT_MS in 100L..1000L)
        assertTrue(SafetyLimits.YOLO_FRAIS_MS in 100L..1000L)
    }
}
