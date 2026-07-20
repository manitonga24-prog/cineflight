package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRtkV4Test {
    private fun positionBase(rtk: ClientRtkSujet.StatutRtk) = ClientRtkSujet.PositionSujet(
        present = true,
        valid = true,
        lat = 45.5,
        lon = -73.6,
        rtk = rtk,
        ageS = 0.10,
        streamStatus = "LIVE",
        sourceSequence = 10,
        measuredRateHz = 9.98,
        haccM = if (rtk == ClientRtkSujet.StatutRtk.FIX) 0.03 else 0.18,
        rtcmAgeMs = 250,
        networkConnected = true
    )

    @Test
    fun tousLesProfilsRespectentLesInvariants() {
        for (profil in ProfilSujetMobile.values()) {
            val prediction = profil.configurationPrediction()
            val suivi = profil.configurationSuivi()
            assertTrue(prediction.vitesseMaxMps >= 4.0)
            assertTrue(prediction.ageControleMaxS <= 0.30)
            assertTrue(prediction.ageControleVirageMaxS <= 0.30)
            assertTrue(suivi.limiteMinM > 0.0)
            assertTrue(suivi.prudenceM >= suivi.limiteMinM)
            assertTrue(suivi.distanceCibleM >= suivi.limiteMinM)
        }
    }

    @Test
    fun controleCompletResteFixSeulement() {
        val fix = positionBase(ClientRtkSujet.StatutRtk.FIX)
        assertTrue(fix.fiable)
        assertTrue(fix.controleFiable)
        assertFalse(fix.copy(rtk = ClientRtkSujet.StatutRtk.FLOAT).controleFiable)
        assertFalse(fix.copy(streamStatus = "STALE").controleFiable)
        assertFalse(fix.copy(ageS = 0.31).controleFiable)
        assertFalse(fix.copy(measuredRateHz = 4.99).controleFiable)
        assertFalse(fix.copy(sourceSequence = null).controleFiable)
        assertFalse(fix.copy(haccM = null).controleFiable)
        assertFalse(fix.copy(rtcmAgeMs = 5_001).controleFiable)
    }

    @Test
    fun fixEtYoloAutorisentLeControleComplet() {
        val politique = AutorisationControleRtkVision()
        val verdict = politique.evaluer(
            position = positionBase(ClientRtkSujet.StatutRtk.FIX),
            predictionControlReady = true,
            cibleVerrouillee = true,
            visionRequise = true,
            yoloTrouve = true,
            confianceYolo = 0.90f,
            nbCibles = 1,
            hauteurBoite = 0.35f
        )
        assertEquals(AutorisationControleRtkVision.Mode.FIX_COMPLET, verdict.mode)
        assertTrue(verdict.translationAutorisee)
        assertTrue(verdict.altitudeAutorisee)
    }

    @Test
    fun floatEtYoloAutoriseSeulementLaNacelle() {
        val politique = AutorisationControleRtkVision()
        val verdict = politique.evaluer(
            position = positionBase(ClientRtkSujet.StatutRtk.FLOAT),
            predictionControlReady = false,
            cibleVerrouillee = true,
            visionRequise = true,
            yoloTrouve = true,
            confianceYolo = 0.88f,
            nbCibles = 1,
            hauteurBoite = 0.30f
        )
        assertEquals(AutorisationControleRtkVision.Mode.FLOAT_VISION, verdict.mode)
        assertFalse(verdict.translationAutorisee)
        assertFalse(verdict.altitudeAutorisee)
        assertFalse(verdict.yawAutorise)
        assertTrue(verdict.gimbalAutorise)
    }

    @Test
    fun barriereFloatAnnuleToujoursLesTranslations() {
        val politique = AutorisationControleRtkVision()
        val limite = politique.limiterFloatVision(
            AutorisationControleRtkVision.Axes(
                vx = 0.8f,
                vy = -0.6f,
                vz = 0.5f,
                yawRate = 25f,
                gimbalPitch = -30f,
                gimbalYaw = 22f
            )
        )
        assertEquals(0f, limite.vx, 0f)
        assertEquals(0f, limite.vy, 0f)
        assertEquals(0f, limite.vz, 0f)
        assertEquals(0f, limite.yawRate, 0f)
        assertEquals(-15f, limite.gimbalPitch, 0f)
        assertEquals(15f, limite.gimbalYaw, 0f)
    }

    @Test
    fun floatEstBloqueSansVisionStable() {
        val politique = AutorisationControleRtkVision()
        val base = positionBase(ClientRtkSujet.StatutRtk.FLOAT)
        fun verdict(
            trouve: Boolean = true,
            confiance: Float = 0.90f,
            nb: Int = 1,
            hacc: Double = 0.18,
            age: Double = 0.10
        ) = politique.evaluer(
            position = base.copy(haccM = hacc, ageS = age),
            predictionControlReady = false,
            cibleVerrouillee = true,
            visionRequise = true,
            yoloTrouve = trouve,
            confianceYolo = confiance,
            nbCibles = nb,
            hauteurBoite = 0.30f
        )

        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, verdict(trouve = false).mode)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, verdict(confiance = 0.50f).mode)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, verdict(nb = 2).mode)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, verdict(hacc = 0.51).mode)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, verdict(age = 0.31).mode)
    }

    @Test
    fun gpsEtLostRestentBloques() {
        val politique = AutorisationControleRtkVision()
        for (rtk in listOf(ClientRtkSujet.StatutRtk.GPS, ClientRtkSujet.StatutRtk.LOST)) {
            val verdict = politique.evaluer(
                position = positionBase(rtk),
                predictionControlReady = true,
                cibleVerrouillee = true,
                visionRequise = true,
                yoloTrouve = true,
                confianceYolo = 0.95f,
                nbCibles = 1,
                hauteurBoite = 0.30f
            )
            assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, verdict.mode)
        }
    }

    @Test
    fun v42CadenceEtFraicheurSontStrictes() {
        assertEquals("4.2.0-android-float-vision", MoteurFusionRtkV4.VERSION)
        assertEquals(20_000_000L, MoteurFusionRtkV4.FUSION_PERIOD_NS)
        assertEquals(500L, ca.cineflight.stage.cine.ClientRtkSujetV4.LOCAL_STALE_MS)
        assertEquals(3_000L, ca.cineflight.stage.cine.ClientRtkSujetV4.LOCAL_DISCONNECTED_MS)
    }
}
