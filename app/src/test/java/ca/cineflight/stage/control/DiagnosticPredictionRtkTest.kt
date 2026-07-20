package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DiagnosticPredictionRtkTest {
    private val baseMs = 1_783_680_000_000L

    @Test
    fun stationnaireFixDevientControlePret() {
        val moteur = DiagnosticPredictionRtk()
        var etat: DiagnosticPredictionRtk.EtatDiagnostic? = null
        for (i in 0..40) {
            val jitterE = if (i % 2 == 0) 0.015 else -0.015
            val p = deplacer(45.56, -73.66, jitterE, 0.0)
            etat = moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
            verifierInvariants(etat)
        }
        assertTrue(etat!!.pret)
        assertTrue(etat.controlePret)
        assertEquals(DiagnosticPredictionRtk.EtatMouvement.IMMOBILE, etat.etatMouvement)
    }

    @Test
    fun marcheRealisteCinqHzDevientControlePret() {
        val moteur = DiagnosticPredictionRtk()
        var controlesPrets = 0
        var dernier: DiagnosticPredictionRtk.EtatDiagnostic? = null

        repeat(150) { i ->
            val t = i * 0.20
            val estVrai = 1.10 * t
            val balancementNord = 0.045 * sin(2.0 * PI * 1.8 * t)
            val bruitEst = when (i % 7) {
                0 -> 0.025
                1 -> -0.018
                2 -> 0.012
                3 -> -0.022
                4 -> 0.006
                5 -> 0.015
                else -> -0.010
            }
            val bruitNord = when (i % 5) {
                0 -> 0.018
                1 -> -0.014
                2 -> 0.009
                3 -> -0.016
                else -> 0.004
            }
            val p = deplacer(45.56, -73.66, estVrai + bruitEst, balancementNord + bruitNord)
            dernier = moteur.mettreAJour(
                p.first,
                p.second,
                0.08,
                true,
                "FIX",
                baseMs + i * 200L
            )
            if (dernier!!.controlePret) controlesPrets++
            verifierInvariants(dernier!!)
        }

        assertTrue("Disponibilite 5 Hz insuffisante: $controlesPrets", controlesPrets >= 100)
        assertTrue(dernier!!.controlePret)
        assertTrue((dernier!!.probabiliteModelePct ?: 0) >= 70)
    }

    @Test
    fun marcheRealisteTroisHzDevientControlePret() {
        val moteur = DiagnosticPredictionRtk()
        var controlesPrets = 0
        var dernier: DiagnosticPredictionRtk.EtatDiagnostic? = null

        repeat(120) { i ->
            val t = i * 0.333
            val estVrai = 1.10 * t
            val balancementNord = 0.050 * sin(2.0 * PI * 1.7 * t)
            val bruitEst = when (i % 5) {
                0 -> 0.030
                1 -> -0.020
                2 -> 0.010
                3 -> -0.025
                else -> 0.005
            }
            val bruitNord = when (i % 4) {
                0 -> 0.020
                1 -> -0.015
                2 -> 0.008
                else -> -0.010
            }
            val p = deplacer(45.56, -73.66, estVrai + bruitEst, balancementNord + bruitNord)
            dernier = moteur.mettreAJour(
                p.first,
                p.second,
                0.12,
                true,
                "FIX",
                baseMs + i * 333L
            )
            if (dernier!!.controlePret) controlesPrets++
            verifierInvariants(dernier!!)
        }

        assertTrue("Disponibilite 3 Hz insuffisante: $controlesPrets", controlesPrets >= 75)
        assertTrue(dernier!!.controlePret)
    }

    @Test
    fun virageDouxFixResteCoherentEtBorne() {
        val moteur = DiagnosticPredictionRtk()
        var controlesPrets = 0
        var dernier: DiagnosticPredictionRtk.EtatDiagnostic? = null
        val rayonM = 10.0
        val vitesseAngulaireRadS = Math.toRadians(6.0)

        repeat(150) { i ->
            val t = i * 0.20
            val angle = vitesseAngulaireRadS * t
            val est = rayonM * sin(angle)
            val nord = rayonM * (1.0 - cos(angle))
            val jitter = if (i % 2 == 0) 0.015 else -0.015
            val p = deplacer(45.56, -73.66, est + jitter, nord)
            dernier = moteur.mettreAJour(
                p.first,
                p.second,
                0.08,
                true,
                "FIX",
                baseMs + i * 200L
            )
            if (dernier!!.controlePret) controlesPrets++
            verifierInvariants(dernier!!)
        }

        assertTrue("Virage jamais exploitable", controlesPrets >= 80)
        assertTrue(dernier!!.pret)
        assertTrue((dernier!!.horizonS) <= 0.80 + 1e-9)
        assertTrue((dernier!!.incertitudeM ?: Double.POSITIVE_INFINITY) <= 1.25)
    }

    @Test
    fun floatNePeutJamaisEtreControlePret() {
        val moteur = DiagnosticPredictionRtk()
        repeat(55) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            val etat = moteur.mettreAJour(p.first, p.second, 0.05, true, "FLOAT", baseMs + i * 200L)
            assertFalse(etat.controlePret)
            verifierInvariants(etat)
        }
    }

    @Test
    fun transitionFixVersFloatBloqueImmediatement() {
        val moteur = DiagnosticPredictionRtk()
        var dernier: DiagnosticPredictionRtk.EtatDiagnostic? = null
        repeat(60) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            dernier = moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
        }
        assertTrue(dernier!!.controlePret)

        val p = deplacer(45.56, -73.66, 12.0, 0.0)
        val degrade = moteur.mettreAJour(p.first, p.second, 0.05, true, "FLOAT", baseMs + 60 * 200L)
        assertFalse(degrade.controlePret)
        assertEquals(DiagnosticPredictionRtk.EtatMouvement.DEGRADE, degrade.etatMouvement)
    }

    @Test
    fun gpsBloqueEtPurgeImmediatement() {
        val moteur = DiagnosticPredictionRtk()
        repeat(60) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
        }

        val gps = moteur.mettreAJour(45.56, -73.66, 0.10, false, "GPS", baseMs + 12_000L)
        assertFalse(gps.pret)
        assertFalse(gps.controlePret)
        assertEquals("GPS_ONLY", gps.raison)
        assertEquals(0, gps.echantillons)
    }

    @Test
    fun retourFixAttendLaStabilisationComplete() {
        val moteur = DiagnosticPredictionRtk()
        repeat(60) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
        }

        val tFloat = baseMs + 12_000L
        moteur.mettreAJour(45.56, -73.66, 0.05, true, "FLOAT", tFloat)

        var avantTroisSecondes: DiagnosticPredictionRtk.EtatDiagnostic? = null
        repeat(14) { i ->
            val p = deplacer(45.56, -73.66, 12.2 + i * 0.20, 0.0)
            avantTroisSecondes = moteur.mettreAJour(
                p.first,
                p.second,
                0.05,
                true,
                "FIX",
                tFloat + 200L + i * 200L
            )
        }
        assertFalse(avantTroisSecondes!!.controlePret)

        var apresStabilisation = avantTroisSecondes
        for (i in 14 until 35) {
            val p = deplacer(45.56, -73.66, 12.2 + i * 0.20, 0.0)
            apresStabilisation = moteur.mettreAJour(
                p.first,
                p.second,
                0.05,
                true,
                "FIX",
                tFloat + 200L + i * 200L
            )
        }
        assertTrue(apresStabilisation!!.controlePret)
    }

    @Test
    fun horodatageNonMonotoneReinitialiseLeMoteur() {
        val moteur = DiagnosticPredictionRtk()
        repeat(12) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
        }
        val bloque = moteur.mettreAJour(45.56, -73.66, 0.05, true, "FIX", baseMs - 5_000L)
        assertFalse(bloque.pret)
        assertFalse(bloque.controlePret)
        assertEquals("HORODATAGE_NON_MONOTONE", bloque.raison)
    }

    @Test
    fun mesureTropRapprocheeNeGonflePasHistorique() {
        val moteur = DiagnosticPredictionRtk()
        repeat(20) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
        }

        val avant = moteur.mettreAJour(45.56, -73.66, 0.05, true, "FIX", baseMs + 4_000L)
        val repetee = moteur.mettreAJour(45.56, -73.66, 0.05, true, "FIX", baseMs + 4_030L)

        assertEquals("MESURE_REPETEE", repetee.statutMesure)
        assertEquals(avant.echantillons, repetee.echantillons)
        assertEquals(1, repetee.mesuresRepetees)
    }

    @Test
    fun historiqueMobileResteSousDixSecondes() {
        val moteur = DiagnosticPredictionRtk()
        var maxFenetre = 0.0
        repeat(100) { i ->
            val p = deplacer(45.56, -73.66, i * 0.20, 0.0)
            val etat = moteur.mettreAJour(p.first, p.second, 0.05, true, "FIX", baseMs + i * 200L)
            if ((etat.vitesseMps ?: 0.0) > 0.25) maxFenetre = maxOf(maxFenetre, etat.dureeHistoriqueS)
            verifierInvariants(etat)
        }
        assertTrue("Fenetre mobile excessive: $maxFenetre s", maxFenetre <= 10.05)
    }

    private fun verifierInvariants(etat: DiagnosticPredictionRtk.EtatDiagnostic) {
        if (etat.pret) {
            assertTrue((etat.confiancePct ?: 0) >= 35)
            assertTrue((etat.incertitudeM ?: Double.POSITIVE_INFINITY) <= 5.0)
        }
        if (etat.controlePret) {
            assertEquals("FIX", etat.qualiteRtk)
            assertTrue((etat.confiancePct ?: 0) >= 70)
            assertTrue((etat.incertitudeM ?: Double.POSITIVE_INFINITY) <= 1.50)
            assertTrue((etat.residuModeleM ?: Double.POSITIVE_INFINITY) <= 1.25)
            assertTrue((etat.probabiliteModelePct ?: 0) >= 70)
        }
    }

    private fun deplacer(lat: Double, lon: Double, estM: Double, nordM: Double): Pair<Double, Double> {
        val rayon = 6_371_000.0
        val dLat = nordM / rayon
        val dLon = estM / (rayon * cos(Math.toRadians(lat)))
        return lat + Math.toDegrees(dLat) to lon + Math.toDegrees(dLon)
    }
}
