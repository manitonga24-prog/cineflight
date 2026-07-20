package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du deplacement en MODE MIROIR. Calcul pur, deterministe.
 * Invariant central : rien n'est applique (commandeMiroir toujours true) ; la vitesse
 * est theorique, signee, bornee ; les raisons refletent la doctrine.
 *
 * Config par defaut : confianceMin=0.50, zoneMorteFraction=0.03, gainMps=4, vitesseMaxMps=2.
 */
class SoccerMirrorPlannerTest {

    private val EPS = 1e-4f
    private val planner = SoccerMirrorPlanner()

    private val rail = DroneRail(
        start = RailPoint(lat = 45.0, lon = -73.0),
        end = RailPoint(lat = 45.0, lon = -72.999),
    )

    private fun est(pos: Float, conf: Float = 0.9f) = SoccerActionEstimate(
        positionNormalized = pos, confidence = conf,
        source = SoccerActionEstimate.Source.BALL, timestampMs = 0L,
    )

    // --- INVARIANT MIROIR : jamais applique ---

    @Test fun toute_decision_est_en_miroir() {
        val d = planner.decideMirror(est(0.90f), rail, positionRailActuelle = 0.10f)
        assertTrue(d.commandeMiroir)
    }

    // --- RAISON : action indisponible ---

    @Test fun action_null_donne_immobile_et_raison_indisponible() {
        val d = planner.decideMirror(null, rail, positionRailActuelle = 0.40f)
        assertNull(d.positionAction)
        assertEquals(0.40f, d.positionRailDemandee, EPS)   // reste sur place
        assertEquals(0f, d.vitesseDemandeeMps, EPS)
        assertEquals(SoccerMirrorPlanner.Raison.ACTION_INDISPONIBLE, d.raison)
        assertTrue(d.commandeMiroir)
    }

    // --- RAISON : confiance faible ---

    @Test fun confiance_faible_ne_propose_aucun_mouvement() {
        val d = planner.decideMirror(est(0.90f, conf = 0.20f), rail, positionRailActuelle = 0.10f)
        assertEquals(0.10f, d.positionRailDemandee, EPS)
        assertEquals(0f, d.vitesseDemandeeMps, EPS)
        assertEquals(SoccerMirrorPlanner.Raison.CONFIANCE_FAIBLE, d.raison)
    }

    // --- RAISON : zone morte ---

    @Test fun petit_ecart_reste_en_zone_morte() {
        // demande 0.42, actuelle 0.40 -> ecart 0.02 < 0.03
        val d = planner.decideMirror(est(0.42f), rail, positionRailActuelle = 0.40f)
        assertEquals(0.40f, d.positionRailDemandee, EPS)
        assertEquals(0f, d.vitesseDemandeeMps, EPS)
        assertEquals(SoccerMirrorPlanner.Raison.ZONE_MORTE, d.raison)
    }

    // --- RAISON : deplacement + sens + bornage ---

    @Test fun deplacement_vers_la_droite_vitesse_positive() {
        // demande 0.90, actuelle 0.10 -> ecart +0.80 ; 0.80*4=3.2 -> borne a 2.0
        val d = planner.decideMirror(est(0.90f), rail, positionRailActuelle = 0.10f)
        assertEquals(0.90f, d.positionRailDemandee, EPS)
        assertEquals(2f, d.vitesseDemandeeMps, EPS)        // borne a vitesseMaxMps
        assertEquals(SoccerMirrorPlanner.Raison.DEPLACEMENT, d.raison)
    }

    @Test fun deplacement_vers_la_gauche_vitesse_negative() {
        // demande 0.10, actuelle 0.90 -> ecart -0.80 -> borne a -2.0
        val d = planner.decideMirror(est(0.10f), rail, positionRailActuelle = 0.90f)
        assertEquals(0.10f, d.positionRailDemandee, EPS)
        assertEquals(-2f, d.vitesseDemandeeMps, EPS)
    }

    @Test fun petit_deplacement_hors_zone_morte_vitesse_proportionnelle() {
        // demande 0.30, actuelle 0.20 -> ecart +0.10 ; 0.10*4=0.4 (sous le plafond)
        val d = planner.decideMirror(est(0.30f), rail, positionRailActuelle = 0.20f)
        assertEquals(0.40f, d.vitesseDemandeeMps, EPS)
        assertEquals(SoccerMirrorPlanner.Raison.DEPLACEMENT, d.raison)
    }

    // --- BORNAGE de la position actuelle ---

    @Test fun position_actuelle_hors_plage_est_bornee() {
        val d = planner.decideMirror(est(0.50f), rail, positionRailActuelle = 1.50f)
        assertEquals(1f, d.positionRailActuelle, EPS)
    }

    // --- LOG structure ---

    @Test fun log_contient_tous_les_champs_demandes() {
        val d = planner.decideMirror(est(0.90f), rail, positionRailActuelle = 0.10f)
        val ligne = SoccerMirrorLog.ligne(d)
        assertTrue(ligne.contains("position_action="))
        assertTrue(ligne.contains("position_rail_actuelle="))
        assertTrue(ligne.contains("position_rail_demandee="))
        assertTrue(ligne.contains("vitesse_demandee="))
        assertTrue(ligne.contains("raison=DEPLACEMENT"))
        assertTrue(ligne.contains("confiance_yolo="))
        assertTrue(ligne.contains("commande_miroir=true"))
    }

    @Test fun log_action_null_affiche_null() {
        val d = planner.decideMirror(null, rail, positionRailActuelle = 0.40f)
        val ligne = SoccerMirrorLog.ligne(d)
        assertTrue(ligne.contains("position_action=null"))
        assertTrue(ligne.contains("raison=ACTION_INDISPONIBLE"))
    }
}
