package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des composants cinema/vol (sections 8-11) : anticipation, largeur de plan,
 * orientation camera, controleur 2D. Calcul pur, deterministe.
 */
class SoccerCinemaTest {

    private val EPS = 0.02f

    // --- Section 11 : anticipation ---

    @Test fun anticipation_avance_dans_la_direction_du_jeu() {
        val a = SoccerAnticipation(horizonS = 1f, avanceMaxFraction = 0.2f)
        val p = a.anticiper(Point2D(0.5f, 0.5f), 1f, 0f, 0.1f, fiable = true)
        assertTrue("doit avancer vers +x", p.x > 0.5f)
        assertEquals(0.5f, p.y, EPS)
    }

    @Test fun anticipation_bornee_meme_si_tres_rapide() {
        val a = SoccerAnticipation(horizonS = 2f, avanceMaxFraction = 0.15f)
        val p = a.anticiper(Point2D(0.5f, 0.5f), 1f, 0f, 5f, fiable = true)  // vitesse enorme
        assertTrue("avance bornee a 0.15", p.x <= 0.5f + 0.15f + EPS)
    }

    @Test fun anticipation_nulle_si_direction_non_fiable() {
        val a = SoccerAnticipation()
        val p = a.anticiper(Point2D(0.5f, 0.5f), 1f, 0f, 0.5f, fiable = false)
        assertEquals(0.5f, p.x, EPS)   // reste au centre
    }

    // --- Section 10 : largeur de plan ---

    @Test fun jeu_disperse_ou_rapide_donne_large() {
        val w = SoccerPlanWidth()
        assertEquals(SoccerPlanWidth.Plan.LARGE, w.maj(etalement = 0.9f, vitesse = 0.9f, certitude = 1f))
    }

    @Test fun incertitude_force_large() {
        val w = SoccerPlanWidth()
        assertEquals(SoccerPlanWidth.Plan.LARGE, w.maj(etalement = 0.1f, vitesse = 0.1f, certitude = 0.1f))
    }

    @Test fun action_stable_concentree_certaine_permet_rapproche() {
        val w = SoccerPlanWidth()
        // plusieurs iterations : le plan converge vers RAPPROCHE quand tout est calme.
        var p = w.maj(0.1f, 0.1f, 1f)
        repeat(3) { p = w.maj(0.1f, 0.1f, 1f) }
        assertEquals(SoccerPlanWidth.Plan.RAPPROCHE, p)
    }

    // --- Section 9 : orientation camera ---

    @Test fun petit_ecart_horizontal_nacelle_seule_pas_de_yaw() {
        val cam = SoccerCameraAim(seuilYaw = 0.25f)
        val a = cam.calculer(cx = 0.55f, cy = 0.5f, dirX = 0f)   // ecart ~0.05
        assertEquals(0f, a.yawDps, 0.001f)
        assertTrue(a.useNacelleOnly)
    }

    @Test fun gros_ecart_horizontal_declenche_le_yaw() {
        val cam = SoccerCameraAim(seuilYaw = 0.25f)
        val a = cam.calculer(cx = 0.95f, cy = 0.5f, dirX = 0f)   // action tres a droite
        assertTrue("yaw doit s'activer", a.yawDps > 0f)
        assertTrue(!a.useNacelleOnly)
    }

    @Test fun lead_room_decale_la_cible_selon_la_direction() {
        // jeu vers la droite : la cible ideale est a gauche du centre -> a cx=0.5,
        // l'action est donc "trop a droite" -> une petite correction existe.
        val cam = SoccerCameraAim(leadRoom = 0.12f, zoneMorteImage = 0.01f, seuilYaw = 0.05f)
        val a = cam.calculer(cx = 0.5f, cy = 0.5f, dirX = 1f)
        assertTrue("doit vouloir tourner (lead room)", a.yawDps > 0f)
    }

    // --- Section 8 : controleur 2D ---

    @Test fun cible_eloignee_donne_vitesse_vers_la_cible() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f, maxDeltaVMps = 100f)
        val o = c.compute(Point2D(0.2f, 0.2f), Point2D(0.8f, 0.2f), actionAgeMs = 0L, confidence = 0.9f)
        assertTrue("vx vers +x", o.vx > 0f)
        assertEquals(0f, o.vy, EPS)
        assertEquals(Soccer2DMotionController.Reason.MOVE, o.reason)
    }

    @Test fun cible_proche_zone_morte_arret() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f, deadband = 0.05f)
        val o = c.compute(Point2D(0.5f, 0.5f), Point2D(0.52f, 0.5f), actionAgeMs = 0L, confidence = 0.9f)
        assertEquals(Soccer2DMotionController.Reason.HOLD_DEADBAND, o.reason)
    }

    @Test fun action_perimee_2d_arret() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f, staleAfterMs = 500L)
        val o = c.compute(Point2D(0.2f, 0.2f), Point2D(0.8f, 0.8f), actionAgeMs = 999L, confidence = 0.9f)
        assertEquals(Soccer2DMotionController.Reason.ACTION_STALE, o.reason)
    }

    @Test fun confiance_faible_2d_arret() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f)
        val o = c.compute(Point2D(0.2f, 0.2f), Point2D(0.8f, 0.8f), actionAgeMs = 0L, confidence = 0.2f)
        assertEquals(Soccer2DMotionController.Reason.LOW_CONFIDENCE, o.reason)
    }

    @Test fun vitesse_2d_bornee_au_max() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f, maxDeltaVMps = 100f, gainMps = 100f)
        val o = c.compute(Point2D(0f, 0.5f), Point2D(1f, 0.5f), actionAgeMs = 0L, confidence = 0.9f)
        assertTrue("vx borne a 2", o.vx <= 2f + EPS)
    }
}
