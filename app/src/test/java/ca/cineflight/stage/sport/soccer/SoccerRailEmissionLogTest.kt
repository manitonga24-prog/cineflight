package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests du formateur de log d'emission arbitree (Phase 9D). */
class SoccerRailEmissionLogTest {

    private val soccerCmd = AssainisseurVitesse.Vitesses(1f, 0f, 0f, 0f)

    @Test fun flag_off_command_sent_existing_et_non_applique() {
        val l = SoccerRailEmissionLog.ligne(
            realEnabled = false,
            maxSpeedMps = 0f,
            winner = FlightCommandArbiter.FlightCommandSource.SoccerRail,
            soccerCommand = soccerCmd,
            soccerApplied = false,
        )
        assertTrue(l.contains("real_enabled=false"))
        assertTrue(l.contains("max_speed_mps=0.000"))
        assertTrue(l.contains("winner=SOCCER_RAIL"))
        assertTrue(l.contains("command_sent=EXISTING"))
        assertTrue(l.contains("soccer_applied=false"))
    }

    @Test fun soccer_applique_affiche_command_sent_soccer() {
        val l = SoccerRailEmissionLog.ligne(
            realEnabled = true,
            maxSpeedMps = 1.5f,
            winner = FlightCommandArbiter.FlightCommandSource.SoccerRail,
            soccerCommand = soccerCmd,
            soccerApplied = true,
        )
        assertTrue(l.contains("real_enabled=true"))
        assertTrue(l.contains("command_sent=SOCCER"))
        assertTrue(l.contains("soccer_applied=true"))
    }

    @Test fun winner_pilote_est_nomme() {
        val l = SoccerRailEmissionLog.ligne(
            realEnabled = false, maxSpeedMps = 0f,
            winner = FlightCommandArbiter.FlightCommandSource.Pilot,
            soccerCommand = soccerCmd, soccerApplied = false,
        )
        assertTrue(l.contains("winner=PILOT"))
    }

    @Test fun soccer_command_est_formatee() {
        val l = SoccerRailEmissionLog.ligne(
            realEnabled = false, maxSpeedMps = 0f,
            winner = FlightCommandArbiter.FlightCommandSource.ExistingAutomaticMode,
            soccerCommand = soccerCmd, soccerApplied = false,
        )
        assertTrue(l.contains("soccer_command=(p=1.000,r=0.000,t=0.000,y=0.000)"))
        assertTrue(l.contains("winner=EXISTING"))
    }
}
