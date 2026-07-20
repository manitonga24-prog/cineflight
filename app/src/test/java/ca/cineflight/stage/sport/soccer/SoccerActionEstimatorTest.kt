package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de SoccerActionEstimator (V1). Calcul pur : aucun materiel, aucun SDK.
 * Couvre la doctrine : ballon prioritaire, sinon mediane joueurs, sinon null,
 * resultat toujours borne dans [0f, 1f].
 */
class SoccerActionEstimatorTest {

    private val estimator = SoccerActionEstimator()
    private val EPS = 1e-4f

    // --- Fabriques d'objets de detection ---

    private fun ball(cx: Float, conf: Float) = SoccerDetectedObject(
        label = "sports ball", confidence = conf,
        centerXNormalized = cx, centerYNormalized = 0.5f,
        widthNormalized = 0.02f, heightNormalized = 0.02f,
    )

    private fun person(cx: Float, conf: Float = 0.9f) = SoccerDetectedObject(
        label = "person", confidence = conf,
        centerXNormalized = cx, centerYNormalized = 0.5f,
        widthNormalized = 0.05f, heightNormalized = 0.20f,
    )

    private fun frame(vararg objs: SoccerDetectedObject) =
        SoccerDetectionFrame(timestampMs = 1_000L, objects = objs.toList())

    // --- BALLON ---

    @Test fun ballon_fiable_donne_sa_position_source_ball() {
        val e = estimator.estimate(frame(ball(cx = 0.80f, conf = 0.80f)))
        assertNotNull(e)
        assertEquals(0.80f, e!!.positionNormalized, EPS)
        assertEquals(SoccerActionEstimate.Source.BALL, e.source)
        assertEquals(0.80f, e.confidence, EPS)
    }

    @Test fun plusieurs_ballons_prend_le_plus_fiable() {
        val e = estimator.estimate(
            frame(
                ball(cx = 0.20f, conf = 0.60f),
                ball(cx = 0.90f, conf = 0.88f),   // le plus fiable
                ball(cx = 0.40f, conf = 0.70f),
            )
        )
        assertNotNull(e)
        assertEquals(0.90f, e!!.positionNormalized, EPS)
        assertEquals(SoccerActionEstimate.Source.BALL, e.source)
    }

    @Test fun ballon_trop_faible_bascule_sur_les_joueurs() {
        val e = estimator.estimate(
            frame(
                ball(cx = 0.10f, conf = 0.30f),   // sous le seuil 0.55 -> ignore
                person(0.70f), person(0.75f), person(0.80f),
            )
        )
        assertNotNull(e)
        assertEquals(SoccerActionEstimate.Source.PLAYERS, e!!.source)
        assertEquals(0.75f, e.positionNormalized, EPS)   // mediane de 0.70/0.75/0.80
    }

    // --- JOUEURS ---

    @Test fun joueurs_groupes_a_gauche_resultat_proche_025() {
        val e = estimator.estimate(frame(person(0.20f), person(0.25f), person(0.30f)))
        assertNotNull(e)
        assertEquals(SoccerActionEstimate.Source.PLAYERS, e!!.source)
        assertEquals(0.25f, e.positionNormalized, EPS)
    }

    @Test fun joueurs_groupes_a_droite_resultat_proche_075() {
        val e = estimator.estimate(frame(person(0.70f), person(0.75f), person(0.80f)))
        assertNotNull(e)
        assertEquals(0.75f, e!!.positionNormalized, EPS)
    }

    @Test fun mediane_paire_moyenne_les_deux_centraux() {
        // 4 joueurs : 0.10, 0.30, 0.50, 0.90 -> mediane = (0.30+0.50)/2 = 0.40
        val e = estimator.estimate(
            frame(person(0.10f), person(0.30f), person(0.50f), person(0.90f))
        )
        assertNotNull(e)
        assertEquals(0.40f, e!!.positionNormalized, EPS)
    }

    @Test fun moins_de_trois_joueurs_donne_null() {
        assertNull(estimator.estimate(frame(person(0.40f), person(0.60f))))
    }

    @Test fun aucune_detection_donne_null() {
        assertNull(estimator.estimate(frame()))
    }

    // --- BORNAGE ---

    @Test fun coordonnee_negative_est_bornee_a_zero() {
        val e = estimator.estimate(frame(ball(cx = -0.50f, conf = 0.90f)))
        assertNotNull(e)
        assertEquals(0f, e!!.positionNormalized, EPS)
    }

    @Test fun coordonnee_superieure_a_un_est_bornee_a_un() {
        val e = estimator.estimate(frame(ball(cx = 1.50f, conf = 0.90f)))
        assertNotNull(e)
        assertEquals(1f, e!!.positionNormalized, EPS)
    }

    @Test fun joueurs_hors_plage_bornes_avant_mediane() {
        // -0.5 -> 0, 0.5 -> 0.5, 1.5 -> 1  => mediane = 0.5
        val e = estimator.estimate(frame(person(-0.5f), person(0.5f), person(1.5f)))
        assertNotNull(e)
        assertEquals(0.5f, e!!.positionNormalized, EPS)
    }
}
