package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests EXHAUSTIFS du garde d'emission 2D (double verrou + arbitre). Coeur de securite :
 * une commande n'est emise QUE si flag reel + arme + vMax>0 + arbitre autorise. Un seul
 * verrou manquant -> commande neutre, jamais d'emission.
 */
class Emission2DGuardTest {

    private val EPS = 1e-4f
    private val G = Emission2DGuard

    /** Snapshot ou TOUTES les conditions de securite sont bonnes. */
    private fun okSnapshot() = FlightCommandArbiter.SafetySnapshot(
        pilotOverride = false, emergencyStop = false, obstacleGateAllows = true,
        virtualStickAvailable = true, inFlightCompatible = true, railLoadedAndValid = true,
        dronePositionFresh = true, actionFreshAndConfident = true, batteryOk = true,
        corridorClear = true, operatorNearRail = true,
    )

    private fun assertNeutre(r: Emission2DGuard.Resultat) {
        assertEquals(0f, r.command.pitch, EPS)
        assertEquals(0f, r.command.roll, EPS)
        assertEquals(0f, r.command.throttle, EPS)
        assertEquals(0f, r.command.yaw, EPS)
        assertFalse("ne doit PAS emettre", r.emettre)
    }

    // --- Chaque verrou coupe SEUL ---

    @Test fun flag_off_est_inerte() {
        val r = G.decider(0.5f, flagReel = false, operateurArme = true, vMaxMps = 0.5f, safety = okSnapshot())
        assertNeutre(r); assertEquals(Emission2DGuard.Etat.INERTE_FLAG_OFF, r.etat)
    }

    @Test fun non_arme_est_inerte() {
        val r = G.decider(0.5f, flagReel = true, operateurArme = false, vMaxMps = 0.5f, safety = okSnapshot())
        assertNeutre(r); assertEquals(Emission2DGuard.Etat.INERTE_NON_ARME, r.etat)
    }

    @Test fun vmax_zero_est_inerte() {
        val r = G.decider(0.5f, flagReel = true, operateurArme = true, vMaxMps = 0f, safety = okSnapshot())
        assertNeutre(r); assertEquals(Emission2DGuard.Etat.INERTE_VMAX_ZERO, r.etat)
    }

    @Test fun vmax_negatif_ou_nan_est_inerte() {
        assertNeutre(G.decider(0.5f, true, true, -1f, okSnapshot()))
        assertNeutre(G.decider(0.5f, true, true, Float.NaN, okSnapshot()))
    }

    // --- Tout OK -> emission reelle du throttle ---

    @Test fun tout_ok_emet_le_throttle() {
        val r = G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f, safety = okSnapshot())
        assertTrue("doit emettre", r.emettre)
        assertEquals(Emission2DGuard.Etat.ACTIVE, r.etat)
        assertEquals(0.4f, r.command.throttle, EPS)
        // altitude seule : aucun mouvement horizontal.
        assertEquals(0f, r.command.pitch, EPS)
        assertEquals(0f, r.command.roll, EPS)
        assertEquals(0f, r.command.yaw, EPS)
    }

    @Test fun throttle_borne_a_vmax() {
        val r = G.decider(5f, flagReel = true, operateurArme = true, vMaxMps = 0.3f, safety = okSnapshot())
        assertEquals(0.3f, r.command.throttle, EPS)
    }

    @Test fun throttle_nan_devient_zero_mais_reste_arme() {
        val r = G.decider(Float.NaN, flagReel = true, operateurArme = true, vMaxMps = 0.5f, safety = okSnapshot())
        assertEquals(0f, r.command.throttle, EPS)
    }

    // --- L'arbitre reste le juge : une condition de securite manquante bloque ---

    @Test fun arbitre_bloque_si_condition_securite_manque() {
        val r = G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f,
            safety = okSnapshot().copy(batteryOk = false))
        assertNeutre(r); assertEquals(Emission2DGuard.Etat.BLOQUE_ARBITRE, r.etat)
    }

    @Test fun urgence_bloque_meme_tout_verrou_ouvert() {
        val r = G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f,
            safety = okSnapshot().copy(emergencyStop = true))
        assertNeutre(r)
    }

    @Test fun pilote_override_ne_donne_pas_soccer() {
        // pilotOverride -> l'arbitre retient Pilot, pas SoccerRail -> pas d'emission soccer.
        val r = G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f,
            safety = okSnapshot().copy(pilotOverride = true))
        assertFalse(r.emettre)
    }

    // --- PREUVE EXHAUSTIVE : 2^9 combinaisons de securite x verrous ---

    private fun snap(bits: Int) = FlightCommandArbiter.SafetySnapshot(
        pilotOverride = false, emergencyStop = false,
        obstacleGateAllows = (bits and 0b000000001) != 0,
        virtualStickAvailable = (bits and 0b000000010) != 0,
        inFlightCompatible = (bits and 0b000000100) != 0,
        railLoadedAndValid = (bits and 0b000001000) != 0,
        dronePositionFresh = (bits and 0b000010000) != 0,
        actionFreshAndConfident = (bits and 0b000100000) != 0,
        batteryOk = (bits and 0b001000000) != 0,
        corridorClear = (bits and 0b010000000) != 0,
        operatorNearRail = (bits and 0b100000000) != 0,
    )

    @Test fun emission_seulement_si_tous_verrous_et_toute_la_securite() {
        val TOUT = 0b111111111
        var nbEmis = 0
        for (bits in 0..0b111111111) {
            val r = G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f, safety = snap(bits))
            if (bits == TOUT) {
                assertTrue("tout vrai doit emettre", r.emettre)
                assertEquals(0.4f, r.command.throttle, EPS)
                nbEmis++
            } else {
                assertFalse("bits=$bits : securite incomplete mais emission !", r.emettre)
                assertEquals("bits=$bits : throttle doit etre 0", 0f, r.command.throttle, EPS)
            }
        }
        assertEquals("un seul cas doit emettre", 1, nbEmis)
    }

    @Test fun aucun_verrou_ouvert_jamais_d_emission_meme_securite_parfaite() {
        // flag off OU non arme OU vMax 0 -> jamais d'emission, meme securite parfaite.
        assertFalse(G.decider(0.4f, false, true, 0.5f, okSnapshot()).emettre)
        assertFalse(G.decider(0.4f, true, false, 0.5f, okSnapshot()).emettre)
        assertFalse(G.decider(0.4f, true, true, 0f, okSnapshot()).emettre)
    }

    @Test fun defaut_projet_est_inerte() {
        // Config par defaut du projet : flag=false, vMax=0 -> jamais d'emission.
        val r = G.decider(0.4f, flagReel = false, operateurArme = true, vMaxMps = 0f, safety = okSnapshot())
        assertNeutre(r)
    }
}
