package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoccerPhaseTest — couvre la machine à états des phases de jeu (pure, JVM).
 *
 * Chaque phase (DUEL, NORMAL, CONTRE_ATTAQUE, VUE_ENSEMBLE) porte une plage d'altitude.
 * Les tests exercent l'entrée dans chaque phase selon le score (rapidité/étalement),
 * l'arrêt de jeu (→ VUE_ENSEMBLE), l'hystérésis (zone morte) et le reset.
 */
class SoccerPhaseTest {

    private fun ph() = SoccerPhase()

    @Test fun jeu_tres_rapide_donne_vue_ensemble() {
        val r = ph().maj(1f, 1f)
        assertEquals(SoccerPhase.Phase.VUE_ENSEMBLE, r)
    }

    @Test fun jeu_moderement_rapide_donne_contre_attaque() {
        val r = ph().maj(0.7f, 0f) // score 0.7 > 0.55+h -> CONTRE_ATTAQUE
        assertEquals(SoccerPhase.Phase.CONTRE_ATTAQUE, r)
    }

    @Test fun jeu_moyen_donne_normal() {
        val r = ph().maj(0.45f, 0f) // score 0.45 dans NORMAL
        assertEquals(SoccerPhase.Phase.NORMAL, r)
    }

    @Test fun jeu_lent_et_groupe_donne_duel() {
        val r = ph().maj(0.05f, 0.05f) // score bas -> DUEL
        assertEquals(SoccerPhase.Phase.DUEL, r)
    }

    @Test fun arret_de_jeu_force_vue_ensemble() {
        // même avec un jeu lent, arret=true force VUE_ENSEMBLE
        val r = ph().maj(0f, 0f, arret = true)
        assertEquals(SoccerPhase.Phase.VUE_ENSEMBLE, r)
    }

    @Test fun etalement_fort_fait_monter() {
        // score = max(v, e) : étalement élevé seul suffit à monter
        val r = ph().maj(0f, 0.9f)
        assertEquals(SoccerPhase.Phase.VUE_ENSEMBLE, r)
    }

    @Test fun hysteresis_garde_la_phase_dans_la_zone_morte() {
        val p = ph()
        p.maj(0.45f, 0f) // NORMAL
        // petite variation dans la zone d'hystérésis -> reste NORMAL
        val r = p.maj(0.31f, 0f)
        assertEquals(SoccerPhase.Phase.NORMAL, r)
    }

    @Test fun chaque_phase_a_une_plage_altitude_croissante() {
        // les plages d'altitude montent avec l'intensité de la phase
        assertTrue(SoccerPhase.Phase.DUEL.altMaxM <= SoccerPhase.Phase.NORMAL.altMaxM)
        assertTrue(SoccerPhase.Phase.NORMAL.altMaxM <= SoccerPhase.Phase.CONTRE_ATTAQUE.altMaxM)
        assertTrue(SoccerPhase.Phase.CONTRE_ATTAQUE.altMaxM <= SoccerPhase.Phase.VUE_ENSEMBLE.altMaxM)
    }

    @Test fun reset_revient_a_normal() {
        val p = ph()
        p.maj(1f, 1f) // VUE_ENSEMBLE
        p.reset()
        // après reset, dans la zone morte on doit retrouver NORMAL
        val r = p.maj(0.45f, 0f)
        assertEquals(SoccerPhase.Phase.NORMAL, r)
    }

    @Test fun entrees_hors_bornes_ne_cassent_pas() {
        val r = ph().maj(5f, -3f) // coercé -> v=1, e=0 -> VUE_ENSEMBLE
        assertEquals(SoccerPhase.Phase.VUE_ENSEMBLE, r)
    }
}
