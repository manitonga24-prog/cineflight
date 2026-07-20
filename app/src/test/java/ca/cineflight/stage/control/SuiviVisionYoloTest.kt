package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * ===================== TORTURE DU SUIVI VISION-SEULE (YOLO, sans RTK) =====================
 * On martele le calcul de suivi avec des detections perdues, ambigues, non finies,
 * hors plage, et on exige : jamais de deplacement en mode CADRAGE_PIVOT, jamais
 * d'avance quand le sujet est trop proche, jamais de commande non finie.
 * "Non defini = non sur."
 * =========================================================================================
 */
class SuiviVisionYoloTest {

    private val suivi = SuiviVisionYolo()
    private val PIVOT = SuiviVisionYolo.ModeVision.CADRAGE_PIVOT
    private val DEPL = SuiviVisionYolo.ModeVision.DEPLACEMENT_BORNE

    // detection nette, centree, taille cible : point de depart valide.
    private fun bonne(cx: Float = 0.5f, cy: Float = 0.5f, h: Float = 0.45f) =
        SuiviVisionYolo.Observation(fraiche = true, nbCibles = 1, confiance = 0.9f, cx = cx, cy = cy, hauteurBoite = h)

    // =====================================================================
    //  BARRIERES FAIL-CLOSED (doivent donner HOLD)
    // =====================================================================
    @Test fun perdu_donne_hold() {
        val c = suivi.calculer(bonne().copy(fraiche = false), DEPL)
        assertTrue(!c.suit && c.avanceMps == 0f && c.yawDps == 0f)
    }

    @Test fun zero_ou_plusieurs_cibles_donne_hold() {
        assertTrue(!suivi.calculer(bonne().copy(nbCibles = 0), DEPL).suit)
        assertTrue(!suivi.calculer(bonne().copy(nbCibles = 2), DEPL).suit)
    }

    @Test fun confiance_faible_donne_hold() {
        assertTrue(!suivi.calculer(bonne().copy(confiance = 0.2f), DEPL).suit)
    }

    @Test fun detection_non_finie_donne_hold() {
        assertTrue(!suivi.calculer(bonne().copy(cx = Float.NaN), DEPL).suit)
        assertTrue(!suivi.calculer(bonne().copy(cy = Float.POSITIVE_INFINITY), DEPL).suit)
        assertTrue(!suivi.calculer(bonne().copy(hauteurBoite = Float.NaN), DEPL).suit)
    }

    @Test fun boite_hors_plage_donne_hold() {
        assertTrue("trop petite", !suivi.calculer(bonne(h = 0.02f), DEPL).suit)
        assertTrue("trop grande", !suivi.calculer(bonne(h = 0.95f), DEPL).suit)
    }

    // =====================================================================
    //  CADRAGE (rotation + nacelle)
    // =====================================================================
    @Test fun sujet_a_droite_tourne_a_droite() {
        val c = suivi.calculer(bonne(cx = 0.9f), PIVOT)
        assertTrue("yaw>0 pour sujet a droite", c.yawDps > 0f)
        assertTrue("yaw borne", c.yawDps <= 25f)
    }

    @Test fun sujet_a_gauche_tourne_a_gauche() {
        assertTrue(suivi.calculer(bonne(cx = 0.1f), PIVOT).yawDps < 0f)
    }

    @Test fun sujet_centre_ne_tourne_pas() {
        val c = suivi.calculer(bonne(cx = 0.5f, cy = 0.5f), PIVOT)
        assertEquals(0f, c.yawDps, 0f)
        assertEquals(0f, c.gimbalDeltaDeg, 0f)
    }

    @Test fun sujet_bas_incline_nacelle_vers_le_bas() {
        val c = suivi.calculer(bonne(cy = 0.9f), PIVOT)
        assertTrue("nacelle vers le bas (<0)", c.gimbalDeltaDeg < 0f)
        assertTrue("nudge borne", c.gimbalDeltaDeg >= -4f)
    }

    // =====================================================================
    //  DEPLACEMENT : uniquement en DEPLACEMENT_BORNE
    // =====================================================================
    @Test fun pivot_ne_deplace_jamais() {
        // sujet loin (petite boite) : en PIVOT, aucune avance possible.
        val c = suivi.calculer(bonne(h = 0.20f), PIVOT)
        assertEquals("CADRAGE_PIVOT => avance nulle", 0f, c.avanceMps, 0f)
        assertTrue(!c.deplacementAutorise)
    }

    @Test fun omni_sujet_loin_avance_borne() {
        val c = suivi.calculer(bonne(h = 0.20f), DEPL)   // boite < cible 0.45 => loin
        assertTrue("avance vers le sujet (>0)", c.avanceMps > 0f)
        assertTrue("avance borne a 1.2 m/s", c.avanceMps <= 1.2f)
        assertTrue(c.deplacementAutorise)
    }

    @Test fun omni_sujet_proche_recule() {
        val c = suivi.calculer(bonne(h = 0.55f), DEPL)   // boite > cible => proche => recule
        assertTrue("recul (<0)", c.avanceMps < 0f)
    }

    /** INVARIANT DE SURETE : sujet au plancher de proximite ou au-dela -> jamais d'avance. */
    @Test fun jamais_avancer_quand_trop_proche() {
        var h = 0.06f
        while (h <= 0.70f) {
            val c = suivi.calculer(bonne(h = h), DEPL)
            if (h >= 0.60f) {
                assertTrue("h=$h trop proche => avance interdite (<=0)", c.avanceMps <= 0f)
            }
            h += 0.01f
        }
    }

    /** Le PLANCHER coupe activement une avance qui, sans lui, serait positive. */
    @Test fun plancher_coupe_une_avance_positive() {
        // Config ou la cible est PLUS LOIN (grande boite) que le plancher : sans le
        // plancher, une boite a 0.62 (< cible 0.70) demanderait d'AVANCER. Le
        // plancher (0.60) doit ramener l'avance a 0.
        val cfg = SuiviVisionYolo.Config(hauteurCible = 0.70f, hauteurPlancher = 0.60f, hauteurBoiteMax = 0.80f)
        val s = SuiviVisionYolo(cfg)
        val c = s.calculer(bonne(h = 0.62f), DEPL)
        assertEquals("plancher => pas d'avance", 0f, c.avanceMps, 0f)
    }

    // =====================================================================
    //  ROBUSTESSE : jamais de sortie non finie (500 observations aleatoires)
    // =====================================================================
    @Test fun sorties_toujours_finies_500_alea() {
        val rnd = Random(20260714L)
        repeat(500) {
            val obs = SuiviVisionYolo.Observation(
                fraiche = rnd.nextBoolean(),
                nbCibles = rnd.nextInt(3),
                confiance = rnd.nextFloat(),
                cx = rnd.nextFloat() * 1.4f - 0.2f,
                cy = rnd.nextFloat() * 1.4f - 0.2f,
                hauteurBoite = rnd.nextFloat()
            )
            val mode = if (rnd.nextBoolean()) DEPL else PIVOT
            val c = suivi.calculer(obs, mode)
            assertTrue("avance finie", c.avanceMps.isFinite())
            assertTrue("yaw fini", c.yawDps.isFinite())
            assertTrue("gimbal fini", c.gimbalDeltaDeg.isFinite())
            if (mode == PIVOT) assertEquals("pivot: avance nulle", 0f, c.avanceMps, 0f)
        }
    }
}
