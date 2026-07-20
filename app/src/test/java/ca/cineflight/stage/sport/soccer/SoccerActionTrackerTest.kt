package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de SoccerActionTracker (filtre temporel). Calcul pur, deterministe :
 * le temps est fourni par l'appelant. Couvre la doctrine de stabilisation.
 */
class SoccerActionTrackerTest {

    private val EPS = 1e-4f

    // Config explicite pour des tests lisibles :
    //  confianceMin=0.50, mesuresStablesRequises=3, zoneMorte=0.05,
    //  toleranceCoherence=0.05, ageMaxMs=2000.
    private fun tracker() = SoccerActionTracker()

    private fun est(pos: Float, conf: Float = 0.9f, ts: Long = 0L) =
        SoccerActionEstimate(
            positionNormalized = pos,
            confidence = conf,
            source = SoccerActionEstimate.Source.BALL,
            timestampMs = ts,
        )

    // --- PREMIER ETAT ---

    @Test fun premiere_estimation_fiable_fixe_la_position() {
        val t = tracker()
        val out = t.update(est(0.40f, conf = 0.9f, ts = 100L), nowMs = 100L)
        assertEquals(0.40f, out.positionNormalized!!, EPS)
        assertEquals(SoccerActionTracker.Etat.MISE_A_JOUR, out.etat)
    }

    @Test fun sans_aucune_estimation_sortie_est_aucune() {
        val t = tracker()
        val out = t.update(null, nowMs = 0L)
        assertNull(out.positionNormalized)
        assertEquals(SoccerActionTracker.Etat.AUCUNE, out.etat)
    }

    // --- CONFIANCE FAIBLE : aucune nouvelle position ---

    @Test fun confiance_faible_ne_change_pas_la_position() {
        val t = tracker()
        t.update(est(0.30f, conf = 0.9f, ts = 0L), nowMs = 0L)      // stable = 0.30
        val out = t.update(est(0.90f, conf = 0.20f, ts = 50L), nowMs = 50L)  // faible -> ignore
        assertEquals(0.30f, out.positionNormalized!!, EPS)
        assertEquals(SoccerActionTracker.Etat.MAINTENUE, out.etat)
    }

    // --- ACTION PERDUE : conserver la position ---

    @Test fun action_perdue_conserve_la_derniere_position() {
        val t = tracker()
        t.update(est(0.60f, conf = 0.9f, ts = 0L), nowMs = 0L)       // stable = 0.60
        val out = t.update(null, nowMs = 500L)                       // perdue, dans le delai
        assertEquals(0.60f, out.positionNormalized!!, EPS)
        assertEquals(SoccerActionTracker.Etat.MAINTENUE, out.etat)
    }

    @Test fun action_perdue_trop_longtemps_est_perimee() {
        val t = tracker()
        t.update(est(0.60f, conf = 0.9f, ts = 0L), nowMs = 0L)       // stable a t=0
        val out = t.update(null, nowMs = 3_000L)                     // > ageMaxMs (2000)
        assertEquals(0.60f, out.positionNormalized!!, EPS)           // valeur conservee
        assertEquals(SoccerActionTracker.Etat.PERIMEE, out.etat)     // mais signalee perimee
    }

    // --- ZONE MORTE : petit deplacement -> rester en place ---

    @Test fun petit_deplacement_reste_en_place() {
        val t = tracker()
        t.update(est(0.50f, conf = 0.9f, ts = 0L), nowMs = 0L)       // stable = 0.50
        val out = t.update(est(0.53f, conf = 0.9f, ts = 50L), nowMs = 50L)  // ecart 0.03 < 0.05
        assertEquals(0.50f, out.positionNormalized!!, EPS)
        assertEquals(SoccerActionTracker.Etat.MAINTENUE, out.etat)
    }

    // --- DEPLACEMENT SIGNIFICATIF : confirmer sur N mesures ---

    @Test fun deplacement_isolate_ne_bouge_pas_avant_confirmation() {
        val t = tracker()
        t.update(est(0.20f, conf = 0.9f, ts = 0L), nowMs = 0L)       // stable = 0.20
        val out1 = t.update(est(0.80f, conf = 0.9f, ts = 50L), nowMs = 50L)   // 1re mesure
        assertEquals(0.20f, out1.positionNormalized!!, EPS)          // pas encore
        assertEquals(SoccerActionTracker.Etat.MAINTENUE, out1.etat)
        val out2 = t.update(est(0.80f, conf = 0.9f, ts = 100L), nowMs = 100L) // 2e mesure
        assertEquals(0.20f, out2.positionNormalized!!, EPS)          // toujours pas (2<3)
    }

    @Test fun deplacement_stable_trois_mesures_est_accepte() {
        val t = tracker()
        t.update(est(0.20f, conf = 0.9f, ts = 0L), nowMs = 0L)       // stable = 0.20
        t.update(est(0.80f, conf = 0.9f, ts = 50L), nowMs = 50L)     // candidat 1
        t.update(est(0.80f, conf = 0.9f, ts = 100L), nowMs = 100L)   // candidat 2
        val out = t.update(est(0.80f, conf = 0.9f, ts = 150L), nowMs = 150L)  // candidat 3 -> accepte
        assertEquals(0.80f, out.positionNormalized!!, EPS)
        assertEquals(SoccerActionTracker.Etat.MISE_A_JOUR, out.etat)
    }

    @Test fun deplacement_incoherent_reinitialise_la_confirmation() {
        val t = tracker()
        t.update(est(0.20f, conf = 0.9f, ts = 0L), nowMs = 0L)       // stable = 0.20
        t.update(est(0.80f, conf = 0.9f, ts = 50L), nowMs = 50L)     // candidat 0.80 (count=1)
        t.update(est(0.10f, conf = 0.9f, ts = 100L), nowMs = 100L)   // saute a 0.10 (count reset=1)
        val out = t.update(est(0.80f, conf = 0.9f, ts = 150L), nowMs = 150L) // 0.80 (count=1)
        // Aucune serie de 3 coherentes -> position stable inchangee.
        assertEquals(0.20f, out.positionNormalized!!, EPS)
        assertEquals(SoccerActionTracker.Etat.MAINTENUE, out.etat)
    }

    // --- RESET ---

    @Test fun reset_efface_la_position_stable() {
        val t = tracker()
        t.update(est(0.70f, conf = 0.9f, ts = 0L), nowMs = 0L)
        t.reset()
        val out = t.update(null, nowMs = 10L)
        assertNull(out.positionNormalized)
        assertEquals(SoccerActionTracker.Etat.AUCUNE, out.etat)
    }
}
