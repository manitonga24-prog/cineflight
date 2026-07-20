package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests de l'altitude adaptative (pur). min=15, max=40 par defaut. */
class SoccerAltitudePlannerTest {

    private val EPS = 1.5

    @Test fun jeu_concentre_et_lent_descend_vers_le_min() {
        val a = SoccerAltitudePlanner(lissage = 1f)   // pas de lissage pour un test net
        val alt = a.altitudeCible(etalement = 0f, vitesse = 0f)
        assertEquals(15.0, alt, EPS)
    }

    @Test fun jeu_etale_et_rapide_monte_vers_le_max() {
        val a = SoccerAltitudePlanner(lissage = 1f)
        val alt = a.altitudeCible(etalement = 1f, vitesse = 1f)
        assertEquals(22.0, alt, EPS)   // altMaxM par defaut = 22 m (plage operationnelle)
    }

    @Test fun toujours_borne_entre_min_et_max() {
        val a = SoccerAltitudePlanner(altMinM = 15.0, altMaxM = 40.0, lissage = 1f)
        val alt = a.altitudeCible(etalement = 2f, vitesse = 2f)   // hors [0,1]
        assertTrue(alt in 15.0..40.0)
    }

    @Test fun lissage_evite_saut_brusque() {
        val a = SoccerAltitudePlanner(lissage = 0.2f)
        a.altitudeCible(0f, 0f)                       // ~15
        val alt = a.altitudeCible(1f, 1f)             // vise 40 mais lisse
        assertTrue("ne saute pas direct a 40, recu $alt", alt < 25.0)
    }

    @Test fun etalement_depuis_joueurs_groupes_proche_zero() {
        val groupes = listOf(Point2D(0.50f, 0.50f), Point2D(0.51f, 0.50f), Point2D(0.50f, 0.51f))
        assertTrue(SoccerAltitudePlanner.etalementDepuisJoueurs(groupes) < 0.2f)
    }

    @Test fun etalement_depuis_joueurs_disperses_eleve() {
        val disperses = listOf(Point2D(0.1f, 0.1f), Point2D(0.9f, 0.9f), Point2D(0.1f, 0.9f), Point2D(0.9f, 0.1f))
        assertTrue(SoccerAltitudePlanner.etalementDepuisJoueurs(disperses) > 0.7f)
    }
}
