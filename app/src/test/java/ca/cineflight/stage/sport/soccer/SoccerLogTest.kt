package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests du formatage de log commun + SoccerMirrorLog (phase 8). */
class SoccerLogTest {

    // --- SoccerLogFormat.f3 : locale-safe, 3 decimales ---

    @Test fun f3_utilise_le_point_decimal() {
        // Meme dans une locale a virgule, on veut un point (parsable serveur).
        assertEquals("1.500", SoccerLogFormat.f3(1.5f))
    }

    @Test fun f3_trois_decimales() {
        assertEquals("0.100", SoccerLogFormat.f3(0.1f))
        assertEquals("-2.000", SoccerLogFormat.f3(-2f))
    }

    // --- SoccerMirrorLog : tous les champs + cas null ---

    @Test fun mirror_log_contient_tous_les_champs() {
        val d = SoccerMirrorPlanner.MirrorDecision(
            positionAction = 0.72f,
            positionRailActuelle = 0.41f,
            positionRailDemandee = 0.72f,
            cible = RailTarget(0.72f, RailPoint(45.0, -73.0)),
            vitesseDemandeeMps = 0.8f,
            raison = SoccerMirrorPlanner.Raison.DEPLACEMENT,
            confianceYolo = 0.9f,
        )
        val l = SoccerMirrorLog.ligne(d)
        assertTrue(l.contains("position_action=0.720"))
        assertTrue(l.contains("position_rail_actuelle=0.410"))
        assertTrue(l.contains("position_rail_demandee=0.720"))
        assertTrue(l.contains("vitesse_demandee=0.800"))
        assertTrue(l.contains("raison=DEPLACEMENT"))
        assertTrue(l.contains("confiance_yolo=0.900"))
        assertTrue(l.contains("commande_miroir=true"))
    }

    @Test fun mirror_log_action_null() {
        val d = SoccerMirrorPlanner.MirrorDecision(
            positionAction = null,
            positionRailActuelle = 0.5f,
            positionRailDemandee = 0.5f,
            cible = RailTarget(0.5f, RailPoint(45.0, -73.0)),
            vitesseDemandeeMps = 0f,
            raison = SoccerMirrorPlanner.Raison.ACTION_INDISPONIBLE,
            confianceYolo = 0f,
        )
        val l = SoccerMirrorLog.ligne(d)
        assertTrue(l.contains("position_action=null"))
        assertTrue(l.contains("raison=ACTION_INDISPONIBLE"))
    }
}
