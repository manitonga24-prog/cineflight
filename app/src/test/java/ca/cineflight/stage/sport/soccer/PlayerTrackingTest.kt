package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests du suivi multi-joueurs + centre du groupe principal (sections 4-5). */
class PlayerTrackingTest {

    private val EPS = 0.03f
    private fun det(cx: Float, cy: Float, w: Float = 0.05f, h: Float = 0.1f, conf: Float = 0.9f) =
        PlayerTracker.Detection(cx, cy, w, h, conf)

    // --- PlayerTracker ---

    @Test fun deux_joueurs_recoivent_deux_ids() {
        val t = PlayerTracker()
        val js = t.update(listOf(det(0.2f, 0.5f), det(0.8f, 0.5f)), 0L)
        assertEquals(2, js.size)
        assertEquals(2, js.map { it.id }.distinct().size)
    }

    @Test fun identite_persiste_entre_frames() {
        val t = PlayerTracker()
        val f1 = t.update(listOf(det(0.2f, 0.5f), det(0.8f, 0.5f)), 0L)
        // frame 2 : les memes joueurs ont bouge un peu.
        val f2 = t.update(listOf(det(0.22f, 0.5f), det(0.78f, 0.5f)), 100L)
        assertEquals(f1.map { it.id }.toSet(), f2.map { it.id }.toSet())   // memes ids
    }

    @Test fun nouveau_joueur_recoit_nouvel_id() {
        val t = PlayerTracker()
        t.update(listOf(det(0.2f, 0.5f)), 0L)
        val f2 = t.update(listOf(det(0.2f, 0.5f), det(0.9f, 0.9f)), 100L)
        assertEquals(2, f2.size)
    }

    @Test fun joueur_disparu_est_retire_apres_age_max() {
        val t = PlayerTracker(ageMaxMs = 500L)
        t.update(listOf(det(0.2f, 0.5f)), 0L)
        val f2 = t.update(emptyList(), 1000L)   // plus vu depuis > 500 ms
        assertTrue(f2.isEmpty())
    }

    @Test fun detections_non_finies_ignorees() {
        val t = PlayerTracker()
        val js = t.update(listOf(det(Float.NaN, 0.5f), det(0.5f, 0.5f)), 0L)
        assertEquals(1, js.size)   // le NaN est ignore
    }

    // --- ActionCenter ---

    @Test fun groupe_principal_ignore_le_joueur_isole() {
        // 3 joueurs groupes a gauche + 1 gardien isole a droite.
        val joueurs = listOf(
            0.2f to 0.5f, 0.22f to 0.52f, 0.24f to 0.48f,   // groupe
            0.95f to 0.5f,                                    // isole
        )
        val c = ActionCenter.calculer(joueurs, rayonGroupe = 0.1f)
        assertTrue(c.present)
        assertEquals(3, c.taille)                 // le groupe, sans l'isole
        assertEquals(0.22f, c.x, EPS)             // centre proche du groupe, pas tire a droite
    }

    @Test fun aucun_joueur_absent() {
        val c = ActionCenter.calculer(emptyList())
        assertTrue(!c.present)
    }

    @Test fun un_seul_joueur_centre_sur_lui() {
        val c = ActionCenter.calculer(listOf(0.6f to 0.4f))
        assertEquals(0.6f, c.x, EPS); assertEquals(0.4f, c.y, EPS)
    }

    @Test fun etalement_plus_grand_quand_groupe_disperse() {
        val serre = ActionCenter.calculer(listOf(0.5f to 0.5f, 0.51f to 0.5f, 0.5f to 0.51f), 0.3f)
        val large = ActionCenter.calculer(listOf(0.4f to 0.4f, 0.6f to 0.6f, 0.4f to 0.6f), 0.5f)
        assertTrue(large.etalement > serre.etalement)
    }
}
