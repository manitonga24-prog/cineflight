package ca.cineflight.stage.control

import ca.cineflight.stage.sentinelle.EtatCapteurs
import ca.cineflight.stage.sentinelle.NoyauSecurite
import ca.cineflight.stage.sentinelle.PontDrone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Tests d'ExecuteurMouvement : le maillon qui transforme une CIBLE (position visee
 * par un mouvement) en INTENTION de vitesse, et la SOUMET AU NOYAU (jamais au pont
 * directement). C'est ici que "le mouvement s'execute" au niveau des vitesses.
 *
 * On verifie les garde-fous d'execution :
 *   - zone morte : a la cible, aucune vitesse (pas de tremblement) ;
 *   - rampe douce : la vitesse ne saute pas (variation <= rampeMaxParTick au 1er tick) ;
 *   - yaw plafonne a vitesseYawMaxDegS ;
 *   - GEL sur danger : le noyau bloque -> transmis=false et le pont recoit (0,0,0,0) ;
 *   - ecretage geo-barriere : une cible hors zone est signalee (cibleRamenee) ;
 *   - tout passe PAR LE NOYAU : quand il transmet, le pont recoit exactement l'intention.
 */
class ExecuteurMouvementTest {

    private class FauxPont : PontDrone {
        data class V(val p: Double, val r: Double, val t: Double, val y: Double)
        var derniere: V? = null
        override fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double) {
            derniere = V(pitch, roll, throttle, yaw)
        }
        override fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean) {}
    }

    private val sujLat = 45.0
    private val sujLon = -73.0

    // grande geo-barriere (~1.1 km de cote) : ne bride rien dans les cas nominaux
    private val geoLarge = GeoBarriere(listOf(
        GeoBarriere.Point(44.99, -73.01), GeoBarriere.Point(44.99, -72.99),
        GeoBarriere.Point(45.01, -72.99), GeoBarriere.Point(45.01, -73.01)
    ))

    private fun cible(lat: Double, lon: Double, alt: Double, yaw: Double) =
        GenerateurMouvement.Cible(lat, lon, alt, yaw, -45.0)

    private fun neuf() = ExecuteurMouvement(NoyauSecurite(FauxPont()))

    // ------------------------------------------------------------- zone morte
    @Test fun a_la_cible_aucune_vitesse() {
        val exe = neuf()
        // cible = position drone, meme cap : rien a faire
        val r = exe.tick(sujLat, sujLon, 40.0, 90.0,
            cible(sujLat, sujLon, 40.0, 90.0), geoLarge, EtatCapteurs())
        assertTrue("capteurs sains -> transmis", r.transmis)
        assertEquals("pitch nul", 0.0, r.pitch, 1e-9)
        assertEquals("roll nul", 0.0, r.roll, 1e-9)
        assertEquals("throttle nul", 0.0, r.throttle, 1e-9)
        assertEquals("yaw nul", 0.0, r.yaw, 1e-9)
    }

    // ------------------------------------------------------------- rampe douce
    @Test fun premier_tick_limite_par_la_rampe() {
        val pont = FauxPont()
        val exe = ExecuteurMouvement(NoyauSecurite(pont))
        // cible 100 m au NORD, drone cap 0 (face nord), meme altitude et yaw
        val cibleNord = cible(sujLat + 100.0 / 111_320.0, sujLon, 40.0, 0.0)
        val r = exe.tick(sujLat, sujLon, 40.0, 0.0, cibleNord, geoLarge, EtatCapteurs())
        assertTrue("transmis", r.transmis)
        assertTrue("avance (pitch>0)", r.pitch > 0.0)
        assertTrue("pitch limite par la rampe (<=0.15)", r.pitch <= 0.15 + 1e-9)
        assertTrue("roll ~ nul (cible droit devant)", kotlin.math.abs(r.roll) < 1e-9)
        assertTrue("norme limitee", hypot(r.pitch, r.roll) <= 0.15 * 1.4143 + 1e-6)
        // passe par le noyau : le pont a recu EXACTEMENT l'intention transmise
        val v = pont.derniere!!
        assertEquals(r.pitch, v.p, 1e-9); assertEquals(r.roll, v.r, 1e-9)
        assertEquals(r.throttle, v.t, 1e-9); assertEquals(r.yaw, v.y, 1e-9)
    }

    // ------------------------------------------------------------- yaw plafonne
    @Test fun yaw_proportionnel_puis_plafonne() {
        // drone sur la cible (aucune translation) mais mauvais cap : seul le yaw agit
        val moitie = neuf().tick(sujLat, sujLon, 40.0, 0.0,
            cible(sujLat, sujLon, 40.0, 90.0), geoLarge, EtatCapteurs())
        assertEquals("erreur 90 deg -> 45*90/180 = 22.5", 22.5, moitie.yaw, 1e-6)
        val max = neuf().tick(sujLat, sujLon, 40.0, 0.0,
            cible(sujLat, sujLon, 40.0, 180.0), geoLarge, EtatCapteurs())
        assertEquals("erreur 180 deg -> plafond 45", 45.0, max.yaw, 1e-6)
        assertTrue("yaw jamais au-dela du plafond", kotlin.math.abs(max.yaw) <= 45.0 + 1e-9)
    }

    // ------------------------------------------------------------- gel sur danger
    @Test fun gel_sur_danger_le_pont_recoit_zero() {
        val pont = FauxPont()
        val exe = ExecuteurMouvement(NoyauSecurite(pont))
        // grosse cible au nord + STOP pilote : le noyau doit geler
        val cibleNord = cible(sujLat + 100.0 / 111_320.0, sujLon, 60.0, 0.0)
        val r = exe.tick(sujLat, sujLon, 40.0, 0.0, cibleNord, geoLarge,
            EtatCapteurs(stopPilote = true))
        assertFalse("danger -> non transmis", r.transmis)
        assertEquals("le noyau gele : le pont a recu (0,0,0,0)",
            FauxPont.V(0.0, 0.0, 0.0, 0.0), pont.derniere)
    }

    // -------------------------------------------------- ecretage geo-barriere
    @Test fun cible_hors_barriere_est_signalee() {
        // petite geo (~66 m) autour du sujet ; drone au centre ; cible a ~1 km -> hors zone
        val petiteGeo = GeoBarriere(listOf(
            GeoBarriere.Point(44.9997, -73.0003), GeoBarriere.Point(44.9997, -72.9997),
            GeoBarriere.Point(45.0003, -72.9997), GeoBarriere.Point(45.0003, -73.0003)
        ))
        val cibleLoin = cible(sujLat + 0.01, sujLon, 40.0, 0.0)
        val r = neuf().tick(sujLat, sujLon, 40.0, 0.0, cibleLoin, petiteGeo, EtatCapteurs())
        assertTrue("cible hors zone -> ecretee (signalee)", r.cibleRamenee)
    }
}
