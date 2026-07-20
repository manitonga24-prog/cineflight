package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests phase de jeu + altitude par taille (approche C+D, plafond 35 m). */
class SoccerPhaseAltitudeTest {

    // --- Phase de jeu (table + plages) ---

    @Test fun jeu_lent_concentre_donne_duel_15_18() {
        val p = SoccerPhase()
        val ph = p.maj(vitesse = 0.05f, etalement = 0.05f)
        assertEquals(SoccerPhase.Phase.DUEL, ph)
        assertEquals(15.0, ph.altMinM, 0.01); assertEquals(18.0, ph.altMaxM, 0.01)
    }

    @Test fun jeu_rapide_donne_contre_attaque_22_28() {
        val p = SoccerPhase()
        // depuis NORMAL, monter franchement -> CONTRE_ATTAQUE
        val ph = p.maj(vitesse = 0.7f, etalement = 0.4f)
        assertEquals(SoccerPhase.Phase.CONTRE_ATTAQUE, ph)
        assertEquals(28.0, ph.altMaxM, 0.01)
    }

    @Test fun arret_de_jeu_force_vue_ensemble_25_35() {
        val p = SoccerPhase()
        val ph = p.maj(vitesse = 0f, etalement = 0f, arret = true)
        assertEquals(SoccerPhase.Phase.VUE_ENSEMBLE, ph)
        assertEquals(35.0, ph.altMaxM, 0.01)
    }

    @Test fun tres_etale_donne_vue_ensemble() {
        val p = SoccerPhase()
        val ph = p.maj(vitesse = 0.2f, etalement = 0.95f)
        assertEquals(SoccerPhase.Phase.VUE_ENSEMBLE, ph)
    }

    // --- Altitude par taille (asservissement) ---

    @Test fun joueurs_trop_grands_font_monter() {
        val a = SoccerAltitudePlanner(lissage = 1f, tailleCible = 0.12f)
        // joueurs 2x trop grands (0.24) -> altitude vers le haut de la plage.
        val alt = a.altitudeParTaille(0.24f, plageMinM = 18.0, plageMaxM = 22.0)
        assertEquals(22.0, alt, 0.5)   // borne au max de la plage
    }

    @Test fun joueurs_trop_petits_font_descendre() {
        val a = SoccerAltitudePlanner(lissage = 1f, tailleCible = 0.12f)
        val alt = a.altitudeParTaille(0.06f, plageMinM = 18.0, plageMaxM = 22.0)
        assertEquals(18.0, alt, 0.5)   // borne au min de la plage
    }

    @Test fun altitude_bornee_par_la_phase() {
        val a = SoccerAltitudePlanner(lissage = 1f)
        // meme si taille demande tres haut, la plage DUEL (15-18) borne a 18 max.
        val alt = a.altitudeParTaille(0.5f, plageMinM = 15.0, plageMaxM = 18.0)
        assertTrue(alt in 15.0..18.0)
    }

    @Test fun plafond_absolu_35m_respecte() {
        val a = SoccerAltitudePlanner(lissage = 1f, plafondAbsoluM = 35.0)
        // plage aberrante 25-100 -> plafonnee a 35.
        val alt = a.altitudeParTaille(0.5f, plageMinM = 25.0, plageMaxM = 100.0)
        assertTrue(alt in 25.0..35.0)
    }

    @Test fun taille_nan_ne_casse_pas() {
        val a = SoccerAltitudePlanner(lissage = 1f)
        val alt = a.altitudeParTaille(Float.NaN, 18.0, 22.0)
        assertTrue(!alt.isNaN() && alt in 18.0..22.0)
    }
}
