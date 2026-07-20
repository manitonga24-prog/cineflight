package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'adaptateur YOLO -> SoccerDetectionFrame.
 * Verifie la conversion coin-haut-gauche -> centre, le bornage, et le label neutre.
 */
class SoccerYoloAdapterTest {

    private val EPS = 1e-4f

    @Test fun convertit_coin_vers_centre() {
        // boite : coin (0.20, 0.30), taille (0.10, 0.20) -> centre (0.25, 0.40)
        val frame = SoccerYoloAdapter.toFrame(
            listOf(SoccerYoloAdapter.BoxSource(x = 0.20f, y = 0.30f, w = 0.10f, h = 0.20f, conf = 0.9f)),
            timestampMs = 42L,
        )
        assertEquals(1, frame.objects.size)
        val o = frame.objects[0]
        assertEquals(0.25f, o.centerXNormalized, EPS)
        assertEquals(0.40f, o.centerYNormalized, EPS)
        assertEquals(42L, frame.timestampMs)
    }

    @Test fun toutes_les_boites_sont_des_joueurs() {
        val frame = SoccerYoloAdapter.toFrame(
            listOf(
                SoccerYoloAdapter.BoxSource(0.1f, 0.1f, 0.05f, 0.1f, 0.8f),
                SoccerYoloAdapter.BoxSource(0.7f, 0.2f, 0.05f, 0.1f, 0.7f),
            ),
            timestampMs = 0L,
        )
        assertTrue(frame.objects.all { it.label == "person" })
    }

    @Test fun centre_est_borne_dans_zero_un() {
        // coin 0.95 + demi-largeur 0.10 = 1.05 -> borne a 1.0
        val frame = SoccerYoloAdapter.toFrame(
            listOf(SoccerYoloAdapter.BoxSource(x = 0.95f, y = 0.95f, w = 0.20f, h = 0.20f, conf = 0.9f)),
            timestampMs = 0L,
        )
        val o = frame.objects[0]
        assertEquals(1f, o.centerXNormalized, EPS)
        assertEquals(1f, o.centerYNormalized, EPS)
    }

    @Test fun liste_vide_donne_frame_vide() {
        val frame = SoccerYoloAdapter.toFrame(emptyList(), timestampMs = 7L)
        assertTrue(frame.objects.isEmpty())
        assertEquals(7L, frame.timestampMs)
    }

    @Test fun chaine_complete_mediane_des_joueurs() {
        // 3 joueurs centres a 0.20, 0.50, 0.80 -> estimateur mediane = 0.50
        val frame = SoccerYoloAdapter.toFrame(
            listOf(
                SoccerYoloAdapter.BoxSource(x = 0.175f, y = 0.4f, w = 0.05f, h = 0.2f, conf = 0.9f), // centre 0.20
                SoccerYoloAdapter.BoxSource(x = 0.475f, y = 0.4f, w = 0.05f, h = 0.2f, conf = 0.9f), // centre 0.50
                SoccerYoloAdapter.BoxSource(x = 0.775f, y = 0.4f, w = 0.05f, h = 0.2f, conf = 0.9f), // centre 0.80
            ),
            timestampMs = 0L,
        )
        val e = SoccerActionEstimator().estimate(frame)
        assertEquals(0.50f, e!!.positionNormalized, 1e-3f)
        assertEquals(SoccerActionEstimate.Source.PLAYERS, e.source)
    }
}
