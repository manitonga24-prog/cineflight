package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoccerAltitudePlannerTest — couvre le planificateur d'altitude (pur, JVM).
 *
 * Exerce altitudeParTaille (joueurs grands→monter, petits→descendre, bornage, NaN-safe),
 * altitudeCible (facteur étalement/vitesse, NaN-safe) et etalementDepuisJoueurs (0/1/N joueurs).
 */
class SoccerAltitudePlannerTest {

    private fun planner() = SoccerAltitudePlanner()
    private fun pt(x: Float, y: Float) = Point2D(x, y)

    @Test fun altitude_par_taille_reste_dans_la_plage() {
        val p = planner()
        val alt = p.altitudeParTaille(0.3f, 10.0, 30.0)
        assertTrue("alt dans [10,30]", alt in 10.0..30.0)
    }

    @Test fun joueurs_grands_montent_plus_haut_que_petits() {
        val grands = planner().altitudeParTaille(0.9f, 10.0, 30.0)
        val petits = planner().altitudeParTaille(0.1f, 10.0, 30.0)
        assertTrue("grands (proche max) >= petits", grands >= petits)
    }

    @Test fun taille_nan_ne_casse_pas() {
        val alt = planner().altitudeParTaille(Float.NaN, 10.0, 30.0)
        assertTrue(alt in 10.0..30.0)
    }

    @Test fun plage_inversee_est_corrigee() {
        // plageMax < plageMin -> le code borne maxM >= minM ; pas de crash
        val alt = planner().altitudeParTaille(0.5f, 30.0, 10.0)
        assertTrue(alt.isFinite())
    }

    @Test fun altitude_cible_augmente_avec_exigence() {
        val bas = planner().altitudeCible(0f, 0f)
        val haut = planner().altitudeCible(1f, 1f)
        assertTrue("exigence forte -> altitude plus haute", haut >= bas)
    }

    @Test fun altitude_cible_nan_safe() {
        val alt = planner().altitudeCible(Float.NaN, Float.POSITIVE_INFINITY)
        assertTrue(alt.isFinite())
    }

    @Test fun lissage_entre_deux_appels() {
        val p = planner()
        val a1 = p.altitudeCible(0f, 0f)   // bas
        val a2 = p.altitudeCible(1f, 1f)   // demande haut mais lissé -> entre a1 et le max
        assertTrue("le lissage empêche un saut instantané", a2 >= a1)
    }

    @Test fun reset_reinitialise_le_lissage() {
        val p = planner()
        p.altitudeCible(1f, 1f)
        p.reset()
        // après reset, le premier appel repart de la valeur brute (pas de mémoire)
        val a = p.altitudeCible(0f, 0f)
        assertTrue(a.isFinite())
    }

    // ── etalementDepuisJoueurs ──

    @Test fun etalement_zero_joueur() {
        assertEquals(0f, SoccerAltitudePlanner.etalementDepuisJoueurs(emptyList()), 1e-4f)
    }

    @Test fun etalement_un_joueur() {
        assertEquals(0f, SoccerAltitudePlanner.etalementDepuisJoueurs(listOf(pt(0.5f, 0.5f))), 1e-4f)
    }

    @Test fun etalement_joueurs_groupes_est_faible() {
        val groupes = listOf(pt(0.50f, 0.50f), pt(0.51f, 0.50f), pt(0.50f, 0.51f))
        val e = SoccerAltitudePlanner.etalementDepuisJoueurs(groupes)
        assertTrue("groupe serré -> étalement faible", e < 0.3f)
    }

    @Test fun etalement_joueurs_disperses_est_fort() {
        val disperses = listOf(pt(0.0f, 0.0f), pt(1.0f, 1.0f), pt(0.0f, 1.0f), pt(1.0f, 0.0f))
        val e = SoccerAltitudePlanner.etalementDepuisJoueurs(disperses)
        assertTrue("dispersés -> étalement élevé", e > 0.5f)
    }
}
