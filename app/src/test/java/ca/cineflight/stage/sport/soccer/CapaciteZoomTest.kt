package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests de la detection de capacite zoom (distinction optique/numerique/switch tele). */
class CapaciteZoomTest {

    @Test fun seul_optique_continu_est_pilotable() {
        assertTrue(CapaciteZoom.ZOOM_OPTICAL_CONTINUOUS.pilotableEnContinu())
        assertFalse(CapaciteZoom.ZOOM_DIGITAL_ONLY.pilotableEnContinu())
        assertFalse(CapaciteZoom.ZOOM_NONE.pilotableEnContinu())
        assertFalse(CapaciteZoom.CAMERA_SWITCH_TELE.pilotableEnContinu())
    }

    @Test fun detection_priorise_optique_continu() {
        // optique continu gagne meme si numerique/tele aussi presents.
        assertEquals(CapaciteZoom.ZOOM_OPTICAL_CONTINUOUS,
            CapaciteZoom.detecter(optiqueContinu = true, numerique = true, cameraTele = true))
    }

    @Test fun switch_tele_n_est_pas_un_zoom_continu() {
        // camera tele SANS optique continu -> switch, pas pilotable en continu.
        val c = CapaciteZoom.detecter(optiqueContinu = false, numerique = true, cameraTele = true)
        assertEquals(CapaciteZoom.CAMERA_SWITCH_TELE, c)
        assertFalse(c.pilotableEnContinu())
    }

    @Test fun numerique_seul_donne_digital_only() {
        val c = CapaciteZoom.detecter(optiqueContinu = false, numerique = true, cameraTele = false)
        assertEquals(CapaciteZoom.ZOOM_DIGITAL_ONLY, c)
    }

    @Test fun rien_donne_none() {
        assertEquals(CapaciteZoom.ZOOM_NONE,
            CapaciteZoom.detecter(optiqueContinu = false, numerique = false, cameraTele = false))
    }

    @Test fun mini_4_pro_est_digital_only_non_pilotable() {
        assertEquals(CapaciteZoom.ZOOM_DIGITAL_ONLY, CapaciteZoom.MINI_4_PRO)
        assertFalse(CapaciteZoom.MINI_4_PRO.pilotableEnContinu())
    }
}
