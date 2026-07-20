package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests d'AutorisationControleRtkVision : la politique fail-closed qui decide, a
 * chaque instant du suivi vehicule, dans QUEL MODE le drone a le droit de bouger.
 * Ce sont ces trois modes qui forment les TRANSITIONS du suivi :
 *   FIX_COMPLET  -> translation + altitude autorisees (suivi route plein) ;
 *   FLOAT_VISION -> tout mouvement verrouille, seul le gimbal suit (YOLO) ;
 *   BLOQUE       -> rien n'est autorise (le noyau gelera).
 *
 * Doctrine : "non defini = non sur". Le moindre defaut (reseau, stream, age,
 * cadence, RTCM, vision incertaine) fait tomber en BLOQUE.
 * (Les codes RTK/FIX/FLOAT ici sont des identifiants de code, pas du texte UI.)
 */
class AutorisationControleRtkVisionTest {

    private val a = AutorisationControleRtkVision()

    /** Position RTK PLEINEMENT valide et fiable (tous les garde-fous passent). */
    private fun posFix(
        rtk: StatutRtk = StatutRtk.FIX,
        ageS: Double? = 0.10,
        measuredRateHz: Double? = 10.0,
        sourceSequence: Long? = 1L,
        rtcmAgeMs: Long? = 500L,
        haccM: Double? = 0.20,
        networkConnected: Boolean = true,
        streamStatus: String = "LIVE",
        present: Boolean = true,
        valid: Boolean = true
    ) = ClientRtkSujet.PositionSujet(
        present = present, valid = valid, lat = 45.5, lon = -73.56, rtk = rtk,
        ageS = ageS, streamStatus = streamStatus, sourceSequence = sourceSequence,
        measuredRateHz = measuredRateHz, haccM = haccM, rtcmAgeMs = rtcmAgeMs,
        groundSpeedMps = 10.0, capDeg = 90.0, headingValid = true,
        networkConnected = networkConnected
    )

    private fun evaluer(
        pos: ClientRtkSujet.PositionSujet?,
        predictionReady: Boolean = true,
        visionRequise: Boolean = false,
        cibleVerrouillee: Boolean = true,
        yoloTrouve: Boolean = true,
        confiance: Float = 0.9f,
        nbCibles: Int = 1,
        hauteurBoite: Float = 0.30f
    ) = a.evaluer(pos, predictionReady, cibleVerrouillee, visionRequise,
        yoloTrouve, confiance, nbCibles, hauteurBoite)

    // -------------------------------------------------------------- FIX complet
    @Test fun fix_complet_autorise_translation_et_altitude() {
        val v = evaluer(posFix(), predictionReady = true, visionRequise = false)
        assertEquals(AutorisationControleRtkVision.Mode.FIX_COMPLET, v.mode)
        assertTrue(v.translationAutorisee); assertTrue(v.altitudeAutorisee)
        assertTrue(v.gimbalAutorise)
        assertFalse("le yaw reste au controleur en FIX", v.yawAutorise)
        assertTrue(v.actif)
    }

    @Test fun fix_non_fiable_bloque() {
        // haccM 2.0 (> 1.0) : controleFiable devient faux -> refus
        val v = evaluer(posFix(haccM = 2.0), predictionReady = true)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
        assertEquals("FIX_NON_FIABLE", v.raison)
    }

    @Test fun fix_modele_non_stabilise_bloque() {
        val v = evaluer(posFix(), predictionReady = false)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
        assertEquals("MODELE_NON_STABILISE", v.raison)
    }

    // -------------------------------------------------------------- FLOAT vision
    @Test fun float_vision_verrouille_toute_translation() {
        val v = evaluer(posFix(rtk = StatutRtk.FLOAT, haccM = 0.30), visionRequise = true)
        assertEquals(AutorisationControleRtkVision.Mode.FLOAT_VISION, v.mode)
        assertFalse("pas de translation en FLOAT", v.translationAutorisee)
        assertFalse("pas d'altitude en FLOAT", v.altitudeAutorisee)
        assertFalse("pas de yaw en FLOAT", v.yawAutorise)
        assertTrue("seul le gimbal suit (YOLO)", v.gimbalAutorise)
        assertTrue(v.actif)
    }

    @Test fun float_hacc_trop_elevee_bloque() {
        val v = evaluer(posFix(rtk = StatutRtk.FLOAT, haccM = 0.90), visionRequise = true)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
        assertEquals("FLOAT_HACC_TROP_ELEVEE", v.raison)
    }

    @Test fun float_sans_vision_bloque() {
        val v = evaluer(posFix(rtk = StatutRtk.FLOAT, haccM = 0.30), visionRequise = false)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
        assertEquals("FLOAT_EXIGE_YOLO", v.raison)
    }

    // -------------------------------------------------------------- BLOQUE (RTK)
    @Test fun gps_seul_bloque() {
        val v = evaluer(posFix(rtk = StatutRtk.GPS))
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
        assertEquals("GPS_ONLY", v.raison)
        assertFalse(v.actif)
    }

    // ----------------------------------------------- garde-fous fail-closed
    @Test fun refus_fail_closed_un_par_defaut() {
        assertEquals("AUCUNE_POSITION_RTK", evaluer(null).raison)
        assertEquals("WS_DECONNECTE", evaluer(posFix(networkConnected = false)).raison)
        assertEquals("STREAM_NO_DATA", evaluer(posFix(streamStatus = "NO_DATA")).raison)
        assertEquals("POSITION_INVALIDE", evaluer(posFix(valid = false)).raison)
        assertEquals("MESURE_TROP_ANCIENNE", evaluer(posFix(ageS = 0.50)).raison)
        assertEquals("CADENCE_INSUFFISANTE", evaluer(posFix(measuredRateHz = 2.0)).raison)
        assertEquals("SEQUENCE_ABSENTE", evaluer(posFix(sourceSequence = null)).raison)
        assertEquals("RTCM_TROP_ANCIEN", evaluer(posFix(rtcmAgeMs = 3_000L)).raison)
        // tous doivent etre en mode BLOQUE
        for (p in listOf<ClientRtkSujet.PositionSujet?>(
            null, posFix(networkConnected = false), posFix(valid = false))) {
            assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, evaluer(p).mode)
        }
    }

    // ----------------------------------------------- garde-fous VISION (FLOAT->YOLO)
    @Test fun garde_fous_vision_bloquent() {
        // base FIX valide + visionRequise=true : la vision est verifiee avant le RTK
        assertEquals("CIBLE_NON_VERROUILLEE",
            evaluer(posFix(), visionRequise = true, cibleVerrouillee = false).raison)
        assertEquals("YOLO_SUJET_PERDU",
            evaluer(posFix(), visionRequise = true, yoloTrouve = false).raison)
        assertEquals("YOLO_CIBLES_AMBIGUES",
            evaluer(posFix(), visionRequise = true, nbCibles = 2).raison)
        assertEquals("YOLO_CONFIANCE_INSUFFISANTE",
            evaluer(posFix(), visionRequise = true, confiance = 0.50f).raison)
        assertEquals("YOLO_DISTANCE_VISUELLE_HORS_PLAGE",
            evaluer(posFix(), visionRequise = true, hauteurBoite = 0.90f).raison)
    }

    // ----------------------------------------------- barriere materielle FLOAT
    @Test fun limiter_float_vision_annule_translation_et_borne_gimbal() {
        val brut = AutorisationControleRtkVision.Axes(
            vx = 5f, vy = 5f, vz = 5f, yawRate = 30f, gimbalPitch = 40f, gimbalYaw = -40f)
        val out = a.limiterFloatVision(brut)
        assertEquals(0f, out.vx, 0f); assertEquals(0f, out.vy, 0f); assertEquals(0f, out.vz, 0f)
        assertEquals("yaw annule en FLOAT", 0f, out.yawRate, 0f)
        assertEquals("gimbal plafonne a +15", 15f, out.gimbalPitch, 0f)
        assertEquals("gimbal plafonne a -15", -15f, out.gimbalYaw, 0f)
    }
}
