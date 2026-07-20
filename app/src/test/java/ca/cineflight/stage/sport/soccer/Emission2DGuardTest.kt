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

    @Test fun emission_seulement_si_les_8_conditions_applicables_sont_vraies() {
        // BASELINE ALTITUDE_ONLY (audit v50, Option A) : le gate obstacle (bit 0) est HORS
        // PERIMETRE du chemin d'emission altitude. Les 8 conditions APPLICABLES (bits 1-8)
        // doivent toutes etre vraies ; le bit gate ne doit avoir AUCUN effet.
        val APPLICABLES = 0b111111110   // tout sauf le bit gate
        var nbEmis = 0
        for (bits in 0..0b111111111) {
            val r = G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f, safety = snap(bits))
            if ((bits and APPLICABLES) == APPLICABLES) {
                assertTrue("bits=$bits : 8 conditions vraies doivent emettre", r.emettre)
                assertEquals(0.4f, r.command.throttle, EPS)
                nbEmis++
            } else {
                assertFalse("bits=$bits : securite incomplete mais emission !", r.emettre)
                assertEquals("bits=$bits : throttle doit etre 0", 0f, r.command.throttle, EPS)
            }
        }
        assertEquals("exactement 2 cas emettent (gate=0 et gate=1, les 8 autres vraies)", 2, nbEmis)
    }

    @Test fun le_bit_gate_obstacle_n_a_aucun_effet_sur_le_chemin_altitude() {
        // PREUVE D'INDEPENDANCE : pour chacune des 512 combinaisons, basculer UNIQUEMENT
        // le bit gate ne change ni l'emission ni le throttle (retrait PROPRE de la
        // condition, pas un false->true).
        for (bits in 0..0b111111111) {
            val a = G.decider(0.4f, true, true, 0.5f, snap(bits))
            val b = G.decider(0.4f, true, true, 0.5f, snap(bits xor 0b000000001))
            assertEquals("bits=$bits : le bit gate change l'emission", a.emettre, b.emettre)
            assertEquals("bits=$bits : le bit gate change le throttle", a.command.throttle, b.command.throttle, EPS)
        }
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

    // ========================================================================
    //  PREUVE DE COUVERTURE DU CHEMIN 2D PAR CONDITION (Phase 1.3, NC-T2).
    //  Le test 2^9 prouve "aucune emission sauf tout-vrai" mais ne trace PAS
    //  quelle condition a bloque. Ci-dessous : pour CHACUNE des 9 conditions
    //  d'armement + les 2 conditions prioritaires, verrous 2D grands ouverts,
    //  on prouve que ce SEUL faux bloque le chemin 2D REEL avec la bonne raison.
    //  Cela demontre que les conditions de l'arbitre couvrent bien le chemin 2D
    //  (et pas seulement le mode rail).
    // ========================================================================

    /** Decide sur le chemin 2D avec TOUS les verrous 2D ouverts (flag+arme+vMax>0). */
    private fun decider2D(safety: FlightCommandArbiter.SafetySnapshot) =
        G.decider(0.4f, flagReel = true, operateurArme = true, vMaxMps = 0.5f, safety = safety)

    /** Prouve : ce snapshot bloque le chemin 2D, sans emission, avec la raison attendue. */
    private fun assertBloque2D(safety: FlightCommandArbiter.SafetySnapshot, raisonAttendue: String) {
        val r = decider2D(safety)
        assertNeutre(r)
        assertEquals(Emission2DGuard.Etat.BLOQUE_ARBITRE, r.etat)
        assertEquals("raison arbitre inattendue", raisonAttendue, r.raison)
    }

    @Test fun c2d_virtualStick_indisponible_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(virtualStickAvailable = false), "soccer bloque : virtual_stick_indisponible")
    }

    @Test fun c2d_etat_vol_incompatible_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(inFlightCompatible = false), "soccer bloque : etat_vol_incompatible")
    }

    @Test fun c2d_rail_non_valide_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(railLoadedAndValid = false), "soccer bloque : rail_non_valide")
    }

    @Test fun c2d_position_drone_perimee_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(dronePositionFresh = false), "soccer bloque : position_drone_perimee")
    }

    @Test fun c2d_action_yolo_non_fiable_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(actionFreshAndConfident = false), "soccer bloque : action_yolo_non_fiable")
    }

    @Test fun c2d_gate_obstacle_hors_perimetre_baseline_altitude() {
        // BASELINE ALTITUDE_ONLY (audit v50, Option A) : le gate obstacle n'est PAS une
        // condition du chemin altitude (aucun credit d'evitement d'obstacles revendique ;
        // obstacles traites par l'evaluation du site + procedures). gate=false, toutes les
        // autres conditions vraies -> l'emission altitude DOIT passer.
        val r = decider2D(okSnapshot().copy(obstacleGateAllows = false))
        assertTrue("gate hors perimetre : ne doit pas bloquer l'altitude", r.emettre)
        assertEquals(Emission2DGuard.Etat.ACTIVE, r.etat)
        assertEquals(0.4f, r.command.throttle, EPS)
        // et toujours AUCUN mouvement horizontal :
        assertEquals(0f, r.command.pitch, EPS)
        assertEquals(0f, r.command.roll, EPS)
        assertEquals(0f, r.command.yaw, EPS)
    }

    @Test fun c2d_batterie_insuffisante_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(batteryOk = false), "soccer bloque : batterie_insuffisante")
    }

    @Test fun c2d_corridor_occupe_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(corridorClear = false), "soccer bloque : corridor_occupe")
    }

    @Test fun c2d_operateur_trop_loin_bloque_le_2D() {
        assertBloque2D(okSnapshot().copy(operatorNearRail = false), "soccer bloque : operateur_trop_loin_du_rail")
    }

    // Conditions PRIORITAIRES sur le chemin 2D (interceptees avant la liste d'armement).

    @Test fun c2d_pilote_override_ecarte_le_soccer_2D() {
        val r = decider2D(okSnapshot().copy(pilotOverride = true))
        assertFalse("pilote prioritaire -> pas d'emission soccer 2D", r.emettre)
        assertEquals(Emission2DGuard.Etat.BLOQUE_ARBITRE, r.etat)
    }

    @Test fun c2d_urgence_bloque_le_soccer_2D() {
        val r = decider2D(okSnapshot().copy(emergencyStop = true))
        assertNeutre(r)
        assertEquals(Emission2DGuard.Etat.BLOQUE_ARBITRE, r.etat)
    }

    @Test fun c2d_les_8_conditions_applicables_couvrent_le_chemin_altitude_une_a_une() {
        // Synthese : partant d'un snapshot parfait, invalider CHAQUE condition APPLICABLE
        // une a une bloque le chemin altitude. Le gate obstacle est HORS PERIMETRE de la
        // baseline ALTITUDE_ONLY (retrait propre, audit v50) et n'apparait donc PAS ici —
        // son absence d'effet est prouvee par le_bit_gate_obstacle_n_a_aucun_effet.
        val invalidations: List<(FlightCommandArbiter.SafetySnapshot) -> FlightCommandArbiter.SafetySnapshot> = listOf(
            { it.copy(virtualStickAvailable = false) },
            { it.copy(inFlightCompatible = false) },
            { it.copy(railLoadedAndValid = false) },
            { it.copy(dronePositionFresh = false) },
            { it.copy(actionFreshAndConfident = false) },
            { it.copy(batteryOk = false) },
            { it.copy(corridorClear = false) },
            { it.copy(operatorNearRail = false) },
        )
        assertEquals("il doit y avoir 8 conditions d'armement applicables", 8, invalidations.size)
        for ((i, invalide) in invalidations.withIndex()) {
            val r = decider2D(invalide(okSnapshot()))
            assertFalse("condition #$i invalidee mais 2D emet quand meme", r.emettre)
            assertEquals("condition #$i : throttle doit etre 0", 0f, r.command.throttle, EPS)
        }
        // Et le controle positif : tout vrai emet bien.
        assertTrue(decider2D(okSnapshot()).emettre)
    }
}
