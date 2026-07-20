package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'arbitre de commande unique (Phase 9C). Calcul pur, deterministe.
 * Cœur de securite : priorite pilote absolue, urgence, gate obstacle, et SoccerRail
 * autorise UNIQUEMENT si toutes les conditions sont vraies (sinon commande neutre).
 */
class FlightCommandArbiterTest {

    private val EPS = 1e-4f
    private val A = FlightCommandArbiter

    private val existing = AssainisseurVitesse.Vitesses(0.1f, 0.2f, 0.3f, 0.4f)
    private val soccer = AssainisseurVitesse.Vitesses(1f, 0f, 0f, 0f)
    private val pilot = AssainisseurVitesse.Vitesses(-1f, -1f, -1f, -1f)

    /** Instantane ou TOUTES les conditions sont bonnes (soccer autorisable). */
    private fun okSnapshot() = FlightCommandArbiter.SafetySnapshot(
        pilotOverride = false,
        emergencyStop = false,
        obstacleGateAllows = true,
        virtualStickAvailable = true,
        inFlightCompatible = true,
        railLoadedAndValid = true,
        dronePositionFresh = true,
        actionFreshAndConfident = true,
        batteryOk = true,
        corridorClear = true,
    )

    private fun decide(armed: Boolean, s: FlightCommandArbiter.SafetySnapshot) =
        A.decide(existing, soccer, pilot, soccerModeArmed = armed, safety = s)

    private fun assertNeutre(d: FlightCommandArbiter.FlightCommandDecision) {
        assertEquals(0f, d.command.pitch, EPS)
        assertEquals(0f, d.command.roll, EPS)
        assertEquals(0f, d.command.throttle, EPS)
        assertEquals(0f, d.command.yaw, EPS)
        assertFalse(d.allowed)
    }

    // --- PRIORITE 1 : PILOTE ---

    @Test fun pilote_a_la_priorite_absolue_meme_si_soccer_arme() {
        val d = decide(armed = true, okSnapshot().copy(pilotOverride = true))
        assertEquals(FlightCommandArbiter.FlightCommandSource.Pilot, d.source)
        assertEquals(pilot.pitch, d.command.pitch, EPS)
        assertTrue(d.allowed)
    }

    @Test fun pilote_prioritaire_meme_pendant_une_urgence() {
        // pilotOverride est teste AVANT emergencyStop -> le pilote reprend la main.
        val d = decide(armed = true, okSnapshot().copy(pilotOverride = true, emergencyStop = true))
        assertEquals(FlightCommandArbiter.FlightCommandSource.Pilot, d.source)
    }

    // --- PRIORITE 2 : URGENCE ---

    @Test fun urgence_donne_commande_neutre() {
        val d = decide(armed = true, okSnapshot().copy(emergencyStop = true))
        assertNeutre(d)
        assertTrue(d.reason.contains("urgence"))
    }

    // --- PRIORITE 3 : GATE OBSTACLE ---

    @Test fun gate_obstacle_refuse_bloque_le_soccer() {
        val d = decide(armed = true, okSnapshot().copy(obstacleGateAllows = false))
        assertNeutre(d)
        assertTrue(d.reason.contains("gate"))
    }

    // --- PRIORITE 6 : SOCCER autorise si TOUT est bon ---

    @Test fun soccer_autorise_quand_arme_et_tout_ok() {
        val d = decide(armed = true, okSnapshot())
        assertEquals(FlightCommandArbiter.FlightCommandSource.SoccerRail, d.source)
        assertEquals(soccer.pitch, d.command.pitch, EPS)
        assertTrue(d.allowed)
    }

    // --- MODE NON ARME : commande existante passe ---

    @Test fun mode_non_arme_laisse_passer_la_commande_existante() {
        val d = decide(armed = false, okSnapshot())
        assertEquals(FlightCommandArbiter.FlightCommandSource.ExistingAutomaticMode, d.source)
        assertEquals(existing.pitch, d.command.pitch, EPS)
        assertTrue(d.allowed)
    }

    // --- CHAQUE condition d'armement fausse -> NEUTRE ---

    @Test fun virtual_stick_indisponible_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(virtualStickAvailable = false)))
    }

    @Test fun etat_vol_incompatible_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(inFlightCompatible = false)))
    }

    @Test fun rail_non_valide_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(railLoadedAndValid = false)))
    }

    @Test fun position_drone_perimee_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(dronePositionFresh = false)))
    }

    @Test fun action_yolo_non_fiable_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(actionFreshAndConfident = false)))
    }

    @Test fun batterie_insuffisante_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(batteryOk = false)))
    }

    @Test fun corridor_occupe_bloque() {
        assertNeutre(decide(armed = true, okSnapshot().copy(corridorClear = false)))
    }

    @Test fun operateur_trop_loin_du_rail_bloque() {
        // Champ ajoute tardivement : DOIT bloquer aussi.
        val d = decide(armed = true, okSnapshot().copy(operatorNearRail = false))
        assertNeutre(d)
        assertTrue(d.reason.contains("operateur"))
    }

    // --- Une SEULE condition fausse suffit a bloquer (pas de contournement) ---

    @Test fun une_seule_condition_fausse_suffit() {
        // Tout bon sauf la batterie -> neutre, jamais la commande soccer.
        val d = decide(armed = true, okSnapshot().copy(batteryOk = false))
        assertNeutre(d)
        assertTrue(d.reason.contains("batterie"))
        assertEquals(0f, d.command.pitch, EPS)   // surtout PAS soccer.pitch (=1f)
    }

    // ============================================================================
    //  PREUVE EXHAUSTIVE : parcours des 2^9 = 512 combinaisons du SafetySnapshot.
    //  Invariant de securite ABSOLU : la commande SoccerRail n'est retenue QUE si
    //  les 9 conditions d'armement sont TOUTES vraies (pilote off, pas d'urgence).
    //  Toute autre combinaison, mode arme, DOIT donner une commande != soccer.
    // ============================================================================

    /** Construit un snapshot depuis un masque de 9 bits (1 = condition vraie). */
    private fun snapshotDepuisBits(bits: Int) = FlightCommandArbiter.SafetySnapshot(
        pilotOverride = false,                       // isole : teste ailleurs
        emergencyStop = false,                       // isole : teste ailleurs
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

    @Test fun soccer_retenu_uniquement_si_les_9_conditions_sont_vraies() {
        val TOUT_VRAI = 0b111111111   // 9 bits a 1
        var nbAutorise = 0
        for (bits in 0..0b111111111) {
            val d = decide(armed = true, snapshotDepuisBits(bits))
            val estSoccer = d.source == FlightCommandArbiter.FlightCommandSource.SoccerRail
            if (bits == TOUT_VRAI) {
                assertTrue("bits=$bits (tout vrai) doit autoriser soccer", estSoccer)
                assertTrue(d.allowed)
                nbAutorise++
            } else {
                // AU MOINS une condition fausse -> JAMAIS la commande soccer.
                assertFalse("bits=$bits a une condition fausse mais soccer retenu !", estSoccer)
                // et la commande ne doit jamais etre la commande soccer brute.
                assertFalse("bits=$bits : commande soccer emise a tort", commandeEstSoccer(d))
            }
        }
        assertEquals("un seul cas (tout vrai) doit autoriser soccer", 1, nbAutorise)
    }

    private fun commandeEstSoccer(d: FlightCommandArbiter.FlightCommandDecision): Boolean =
        kotlin.math.abs(d.command.pitch - soccer.pitch) < EPS &&
        kotlin.math.abs(d.command.roll - soccer.roll) < EPS &&
        kotlin.math.abs(d.command.throttle - soccer.throttle) < EPS &&
        kotlin.math.abs(d.command.yaw - soccer.yaw) < EPS

    @Test fun toutes_conditions_fausses_donne_neutre() {
        assertNeutre(decide(armed = true, snapshotDepuisBits(0)))
    }

    @Test fun pilote_gagne_sur_toutes_les_combinaisons() {
        // Meme avec toutes les conditions fausses, pilotOverride reprend la main.
        for (bits in 0..0b111111111) {
            val s = snapshotDepuisBits(bits).copy(pilotOverride = true)
            val d = decide(armed = true, s)
            assertEquals("bits=$bits : pilote doit gagner",
                FlightCommandArbiter.FlightCommandSource.Pilot, d.source)
        }
    }

    @Test fun urgence_gagne_sur_tout_sauf_pilote() {
        // urgence (sans pilote) -> neutre, quelle que soit la combinaison.
        for (bits in 0..0b111111111) {
            val s = snapshotDepuisBits(bits).copy(emergencyStop = true)
            val d = decide(armed = true, s)
            assertNeutre(d)
        }
    }

    @Test fun mode_non_arme_ne_produit_jamais_soccer_quelle_que_soit_la_securite() {
        for (bits in 0..0b111111111) {
            val d = decide(armed = false, snapshotDepuisBits(bits))
            assertFalse("bits=$bits non arme mais soccer retenu",
                d.source == FlightCommandArbiter.FlightCommandSource.SoccerRail)
        }
    }

    // --- Determinisme : meme entree -> meme sortie ---

    @Test fun decision_est_deterministe() {
        val s = okSnapshot()
        val d1 = decide(armed = true, s)
        val d2 = decide(armed = true, s)
        assertEquals(d1.source, d2.source)
        assertEquals(d1.allowed, d2.allowed)
        assertEquals(d1.command.throttle, d2.command.throttle, EPS)
        assertEquals(d1.reason, d2.reason)
    }

    // --- Chaque decision a une raison traçable non vide ---

    @Test fun toute_decision_a_une_raison() {
        for (bits in 0..0b111111111) {
            val d = decide(armed = true, snapshotDepuisBits(bits))
            assertTrue("bits=$bits : raison vide", d.reason.isNotBlank())
        }
    }

    // --- La commande neutre est bien un arret complet (invariant) ---

    @Test fun neutre_est_arret_complet() {
        assertEquals(0f, FlightCommandArbiter.NEUTRE.pitch, EPS)
        assertEquals(0f, FlightCommandArbiter.NEUTRE.roll, EPS)
        assertEquals(0f, FlightCommandArbiter.NEUTRE.throttle, EPS)
        assertEquals(0f, FlightCommandArbiter.NEUTRE.yaw, EPS)
    }
}
