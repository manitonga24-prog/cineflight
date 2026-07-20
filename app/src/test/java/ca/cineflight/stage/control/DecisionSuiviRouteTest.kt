package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk
import ca.cineflight.stage.sentinelle.EtatCapteurs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de DecisionSuiviRoute : l'orchestrateur du suivi vehicule. Il enchaine les
 * trois modes selon l'etat RTK/vision et produit la DECISION (cible + axes autorises
 * + capteurs pour le noyau). C'est ici qu'on valide les TRANSITIONS du suivi :
 *
 *   FIX complet  -> une cible route est produite, translation autorisee, noyau OK ;
 *   FLOAT vision -> aucune cible route, translation verrouillee, seul le gimbal suit,
 *                   le noyau ne gele PAS (le suivi continue en nacelle) ;
 *   BLOQUE       -> aucune cible, tout interdit, rtkSujetOk force a false -> le noyau
 *                   gele (0,0,0,0). "Non defini = non sur."
 */
class DecisionSuiviRouteTest {

    private val lat0 = 45.5
    private val lon0 = -73.56
    private val mLat = 111_320.0
    private fun mLon() = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(estM: Double, nordM: Double) =
        GeoBarriere.Point(lat0 + nordM / mLat, lon0 + estM / mLon())

    // route droite est-ouest de 0 a 200 m
    private fun routeDroite() = ParcoursRoute(listOf(pt(0.0, 0.0), pt(200.0, 0.0)))

    /** Sujet a 50 m sur la route, RTK parametrable, sinon pleinement valide/fiable. */
    private fun sujet(rtk: StatutRtk = StatutRtk.FIX, haccM: Double? = 0.20) =
        ClientRtkSujet.PositionSujet(
            present = true, valid = true,
            lat = lat0, lon = lon0 + 50.0 / mLon(), rtk = rtk, ageS = 0.10,
            streamStatus = "LIVE", sourceSequence = 1L, measuredRateHz = 10.0,
            haccM = haccM, rtcmAgeMs = 500L, groundSpeedMps = 10.0,
            capDeg = 90.0, headingValid = true, networkConnected = true
        )

    private val deci = DecisionSuiviRoute()

    private fun decider(
        pos: ClientRtkSujet.PositionSujet?,
        base: EtatCapteurs = EtatCapteurs()
    ) = deci.decider(
        route = routeDroite(), position = pos, baseCapteurs = base,
        predictionControlReady = true, cibleVerrouillee = true, yoloTrouve = true,
        confianceYolo = 0.9f, nbCibles = 1, hauteurBoite = 0.30f
    )

    // ------------------------------------------------------------ FIX complet
    @Test fun fix_produit_une_cible_route_et_autorise_la_translation() {
        val d = decider(sujet(StatutRtk.FIX))
        assertEquals(AutorisationControleRtkVision.Mode.FIX_COMPLET, d.mode)
        assertNotNull("FIX doit produire une cible route", d.cibleRoute)
        assertTrue("translation autorisee en FIX", d.translationAutorisee)
        assertTrue("le noyau ne gele pas (rtkSujetOk)", d.capteurs.rtkSujetOk)
        assertTrue(d.actif)
    }

    // ------------------------------------------------------------ FLOAT vision
    @Test fun float_verrouille_la_translation_mais_garde_le_gimbal() {
        val d = decider(sujet(StatutRtk.FLOAT, haccM = 0.30))
        assertEquals(AutorisationControleRtkVision.Mode.FLOAT_VISION, d.mode)
        assertNull("aucune cible route en FLOAT", d.cibleRoute)
        assertFalse("translation verrouillee en FLOAT", d.translationAutorisee)
        assertTrue("le gimbal continue de suivre", d.gimbalAutorise)
        assertTrue("le noyau ne gele PAS : le suivi gimbal continue", d.capteurs.rtkSujetOk)
        assertTrue(d.actif)
    }

    // ------------------------------------------------------------ BLOQUE
    @Test fun gps_bloque_et_gele_le_noyau() {
        val d = decider(sujet(StatutRtk.GPS))
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, d.mode)
        assertNull(d.cibleRoute)
        assertFalse(d.translationAutorisee)
        assertFalse("BLOQUE -> rtkSujetOk=false -> noyau gele", d.capteurs.rtkSujetOk)
        assertFalse(d.actif)
    }

    @Test fun bloque_force_rtkSujetOk_false_meme_si_base_etait_vrai() {
        val d = decider(sujet(StatutRtk.LOST), base = EtatCapteurs(rtkSujetOk = true))
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, d.mode)
        assertFalse("la base saine ne doit pas rouvrir un mode bloque", d.capteurs.rtkSujetOk)
    }

    // ------------------------------------------------- TRANSITION entre modes
    @Test fun transition_fix_vers_bloque_quand_le_rtk_tombe() {
        // le vehicule etait suivi en FIX...
        val enFix = decider(sujet(StatutRtk.FIX))
        assertEquals(AutorisationControleRtkVision.Mode.FIX_COMPLET, enFix.mode)
        assertTrue(enFix.capteurs.rtkSujetOk)
        // ...puis le RTK tombe : la decision suivante doit basculer en BLOQUE et geler
        val enBloque = decider(sujet(StatutRtk.LOST))
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, enBloque.mode)
        assertFalse("la transition FIX->BLOQUE doit couper le suivi", enBloque.capteurs.rtkSujetOk)
        assertFalse(enBloque.actif)
    }
}
