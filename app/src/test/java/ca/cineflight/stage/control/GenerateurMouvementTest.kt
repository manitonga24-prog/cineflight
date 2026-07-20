package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Tests de GenerateurMouvement.cible : verifie que CHAQUE mouvement cinematographique
 * (ORBITE, TRAVELLING, REVEAL, RAPPROCHE) produit la bonne geometrie autour du sujet,
 * a chaque phase (0..1), et respecte les bornes de securite.
 *
 * Rappels du contrat :
 *   ORBITE     : distance + hauteur constantes ; l'azimut balaie amplitudeDeg.
 *   TRAVELLING : offset lateral fixe (+-90 selon cote) ; drone immobile / sujet fixe.
 *   REVEAL     : la distance ET la hauteur augmentent (devoile le decor).
 *   RAPPROCHE  : la distance diminue ; hauteur constante.
 *   TOUS       : le yaw vise TOUJOURS le sujet ; gimbal dans [-90,0] ; jamais plus
 *                pres que distanceMinM ; altitude dans [altMin, altMax].
 */
class GenerateurMouvementTest {

    private val gen = GenerateurMouvement()
    private val sujLat = 45.0
    private val sujLon = -73.0
    private val T = 1e-2   // tolerance metres/degres

    // offset (est, nord) en metres du sujet vers le drone
    private fun offset(c: GenerateurMouvement.Cible): Pair<Double, Double> {
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(sujLat))
        return ((c.lon - sujLon) * mLon) to ((c.lat - sujLat) * mLat)
    }
    private fun distanceSujet(c: GenerateurMouvement.Cible): Double {
        val (e, n) = offset(c); return hypot(e, n)
    }
    private fun azimutDeg(c: GenerateurMouvement.Cible): Double {
        val (e, n) = offset(c)
        var a = Math.toDegrees(atan2(e, n)); if (a < 0) a += 360.0; return a   // 0=N, 90=E
    }
    // cap attendu drone->sujet (le drone doit regarder le sujet)
    private fun yawVersSujetAttendu(c: GenerateurMouvement.Cible): Double {
        val (e, n) = offset(c)
        var a = Math.toDegrees(atan2(-e, -n)); if (a < 0) a += 360.0; return a
    }

    // ------------------------------------------------------------------ ORBITE
    @Test fun orbite_distance_et_altitude_constantes() {
        val p = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.ORBITE,
            distanceM = 35.0, hauteurM = 40.0, amplitudeDeg = 180.0)
        for (ph in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val c = gen.cible(sujLat, sujLon, p, ph)
            assertEquals("phase=$ph distance constante", 35.0, distanceSujet(c), T)
            assertEquals("phase=$ph altitude constante", 40.0, c.altM, T)
        }
    }

    @Test fun orbite_balaye_l_amplitude() {
        val p = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.ORBITE,
            azimutDepartDeg = 0.0, amplitudeDeg = 180.0)
        assertEquals("depart = azimut 0 (nord)", 0.0, azimutDeg(gen.cible(sujLat, sujLon, p, 0.0)), T)
        assertEquals("fin = azimut 180 (sud)", 180.0, azimutDeg(gen.cible(sujLat, sujLon, p, 1.0)), T)
        assertEquals("mi-parcours = azimut 90 (est)", 90.0, azimutDeg(gen.cible(sujLat, sujLon, p, 0.5)), T)
    }

    // -------------------------------------------------------------- TRAVELLING
    @Test fun travelling_offset_lateral_fixe() {
        val pD = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.TRAVELLING,
            azimutDepartDeg = 0.0, cote = "droite")
        // drone immobile relativement au sujet : phase 0 == phase 1
        val c0 = gen.cible(sujLat, sujLon, pD, 0.0)
        val c1 = gen.cible(sujLat, sujLon, pD, 1.0)
        assertEquals("travelling : pas de derive laterale", azimutDeg(c0), azimutDeg(c1), T)
        assertEquals("cote droite = azimut 90 (est)", 90.0, azimutDeg(c0), T)
        // cote gauche = azimut oppose (270 / ouest)
        val pG = pD.copy(cote = "gauche")
        assertEquals("cote gauche = azimut 270 (ouest)", 270.0,
            azimutDeg(gen.cible(sujLat, sujLon, pG, 0.0)), T)
    }

    // ------------------------------------------------------------------ REVEAL
    @Test fun reveal_s_eloigne_et_monte() {
        val p = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.REVEAL,
            distanceM = 30.0, hauteurM = 40.0)   // defauts : dFin=2x=60, hFin=1.6x=64
        val c0 = gen.cible(sujLat, sujLon, p, 0.0)
        val cMid = gen.cible(sujLat, sujLon, p, 0.5)
        val c1 = gen.cible(sujLat, sujLon, p, 1.0)
        assertEquals("depart distance", 30.0, distanceSujet(c0), T)
        assertEquals("depart hauteur", 40.0, c0.altM, T)
        assertTrue("la distance doit AUGMENTER",
            distanceSujet(c0) < distanceSujet(cMid) && distanceSujet(cMid) < distanceSujet(c1))
        assertTrue("la hauteur doit AUGMENTER", c0.altM < cMid.altM && cMid.altM < c1.altM)
        assertEquals("fin distance = 2x", 60.0, distanceSujet(c1), T)
        assertEquals("fin hauteur = 1.6x", 64.0, c1.altM, T)
    }

    // --------------------------------------------------------------- RAPPROCHE
    @Test fun rapproche_se_rapproche_hauteur_stable() {
        val p = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.RAPPROCHE,
            distanceM = 30.0, hauteurM = 40.0)   // defaut dFin = 0.5x = 15
        val c0 = gen.cible(sujLat, sujLon, p, 0.0)
        val c1 = gen.cible(sujLat, sujLon, p, 1.0)
        assertTrue("la distance doit DIMINUER", distanceSujet(c1) < distanceSujet(c0))
        assertEquals("depart distance", 30.0, distanceSujet(c0), T)
        assertEquals("fin distance = 0.5x", 15.0, distanceSujet(c1), T)
        assertEquals("hauteur stable", c0.altM, c1.altM, T)
    }

    // ------------------------------------------------------- bornes de securite
    @Test fun jamais_plus_pres_que_la_distance_min() {
        // on demande 3 m : la securite doit ramener a distanceMinM (8 m par defaut)
        val p = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.RAPPROCHE,
            distanceM = 3.0, distanceFinM = 1.0, distanceMinM = 8.0)
        for (ph in listOf(0.0, 0.5, 1.0)) {
            assertTrue("phase=$ph : jamais plus pres que 8 m",
                distanceSujet(gen.cible(sujLat, sujLon, p, ph)) >= 8.0 - T)
        }
    }

    @Test fun altitude_toujours_bornee() {
        val trop = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.ORBITE,
            hauteurM = 500.0, altMaxM = 120.0)
        assertEquals("altitude plafonnee", 120.0, gen.cible(sujLat, sujLon, trop, 0.0).altM, T)
        val bas = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.ORBITE,
            hauteurM = 2.0, altMinM = 10.0)
        assertEquals("altitude plancher", 10.0, gen.cible(sujLat, sujLon, bas, 0.0).altM, T)
    }

    // --------------------------------------------------- yaw + gimbal (cadrage)
    @Test fun le_yaw_vise_toujours_le_sujet() {
        for (type in GenerateurMouvement.TypeMouvement.values()) {
            val p = GenerateurMouvement.Params(type, azimutDepartDeg = 40.0, amplitudeDeg = 120.0)
            for (ph in listOf(0.0, 0.5, 1.0)) {
                val c = gen.cible(sujLat, sujLon, p, ph)
                assertEquals("$type phase=$ph : le drone doit regarder le sujet",
                    yawVersSujetAttendu(c), c.yawDeg, T)
            }
        }
    }

    @Test fun gimbal_pointe_vers_le_bas_et_borne() {
        val p = GenerateurMouvement.Params(GenerateurMouvement.TypeMouvement.ORBITE,
            distanceM = 40.0, hauteurM = 40.0, gimbalAuto = true)
        val c = gen.cible(sujLat, sujLon, p, 0.0)
        // auto = -atan(hauteur/distance) ; ici -atan(40/40) = -45 deg
        assertEquals(-45.0, c.gimbalDeg, T)
        assertTrue("gimbal borne [-90,0]", c.gimbalDeg in -90.0..0.0)
    }

    // ---------------------------------------------- PROPRIETE : cas aleatoires
    @Test fun proprietes_tous_mouvements_20k() {
        val rnd = Random(20260714L)
        val types = GenerateurMouvement.TypeMouvement.values()
        val n = 20_000
        for (i in 0 until n) {
            val p = GenerateurMouvement.Params(
                type = types[rnd.nextInt(types.size)],
                distanceM = 3.0 + rnd.nextDouble() * 90.0,
                hauteurM = -20.0 + rnd.nextDouble() * 200.0,
                azimutDepartDeg = rnd.nextDouble() * 360.0,
                amplitudeDeg = rnd.nextDouble() * 360.0,
                cote = if (rnd.nextBoolean()) "gauche" else "droite"
            )
            val c = gen.cible(sujLat, sujLon, p, rnd.nextDouble())
            assertTrue("i=$i distance >= min", distanceSujet(c) >= p.distanceMinM - T)
            assertTrue("i=$i altitude bornee", c.altM >= p.altMinM - T && c.altM <= p.altMaxM + T)
            assertTrue("i=$i gimbal borne", c.gimbalDeg in -90.0..0.0)
            assertTrue("i=$i yaw dans [0,360)", c.yawDeg >= 0.0 && c.yawDeg < 360.0 + T)
            assertTrue("i=$i yaw vise le sujet",
                abs(((c.yawDeg - yawVersSujetAttendu(c)) + 540.0) % 360.0 - 180.0) < 1e-2)
        }
        println("GENERATEUR-MOUVEMENT ok : $n cibles verifiees (4 mouvements, bornes, cadrage sujet)")
    }
}
