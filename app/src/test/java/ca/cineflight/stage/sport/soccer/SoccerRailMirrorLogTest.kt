package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests du formateur de log miroir de mouvement (Phase 9B). */
class SoccerRailMirrorLogTest {

    private fun out(v: Float, r: RailControlOutput.Reason) = RailControlOutput(v, r)

    @Test fun ligne_contient_tous_les_champs_et_invariant() {
        val l = SoccerRailMirrorLog.ligne(
            actionPosition = 0.72f,
            railCurrent = 0.41f,
            railTarget = 0.72f,
            output = out(0.8f, RailControlOutput.Reason.MOVE_RIGHT),
            commandSent = "commande_normale",
        )
        assertTrue(l.contains("action_position=0.720"))
        assertTrue(l.contains("rail_current=0.410"))
        assertTrue(l.contains("rail_target=0.720"))
        assertTrue(l.contains("requested_velocity=0.800"))
        assertTrue(l.contains("reason=MOVE_RIGHT"))
        assertTrue(l.contains("command_sent=commande_normale"))
        // INVARIANT 9B : jamais applique.
        assertTrue(l.contains("soccer_motion_applied=false"))
    }

    @Test fun action_null_affiche_null() {
        val l = SoccerRailMirrorLog.ligne(
            actionPosition = null,
            railCurrent = 0.50f,
            railTarget = 0.50f,
            output = out(0f, RailControlOutput.Reason.ACTION_STALE),
            commandSent = "hover",
        )
        assertTrue(l.contains("action_position=null"))
        assertTrue(l.contains("reason=ACTION_STALE"))
        assertTrue(l.contains("soccer_motion_applied=false"))
    }
}
