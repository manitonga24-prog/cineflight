package ca.cineflight.stage.cine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la Grammaire : table deterministe (Scene x Effet) -> sequence de mouvements.
 * Verifie que chaque combinaison produit la BONNE suite de mouvements, que la duree
 * demandee est integralement repartie, que les amplitudes collent au mouvement, et
 * que toute combinaison absente retombe sur un repli sur.
 */
class GrammaireTest {

    // ---------------------------------------------------- une cellule connue
    @Test fun cellule_connue_donne_la_bonne_sequence() {
        val seq = Grammaire.generer(Scene.DANSEUR_SOLO, Effet.EMOTION, 30)
        assertEquals(3, seq.pas.size)
        assertEquals(listOf(Mouvement.APPROCHE, Mouvement.ORBITE, Mouvement.STATIQUE),
            seq.pas.map { it.mouvement })
        assertEquals(listOf(Plan.GROS, Plan.AMERICAIN, Plan.GROS), seq.pas.map { it.plan })
        assertTrue("vitesse dominante coherente", seq.pas.all { it.vitesse == Vitesse.LENTE })
        assertEquals(30, seq.dureeTotaleS)
    }

    // ---------------------------------------------------- repartition du temps
    @Test fun la_duree_est_integralement_repartie_reste_sur_le_dernier() {
        val seq = Grammaire.generer(Scene.DANSEUR_SOLO, Effet.EMOTION, 31)   // 3 pas
        assertEquals(listOf(10, 10, 11), seq.pas.map { it.dureeS })
        assertEquals(31, seq.dureeTotaleS)
    }

    @Test fun duree_totale_toujours_egale_au_demande() {
        for (total in listOf(0, 1, 7, 45, 60, 300)) {
            val seq = Grammaire.generer(Scene.GROUPE_DANSE, Effet.ENERGIE, total)
            assertEquals("total=$total doit etre integralement reparti", total, seq.dureeTotaleS)
            assertTrue("aucune duree negative", seq.pas.all { it.dureeS >= 0 })
        }
    }

    // ---------------------------------------------------- amplitudes / mouvement
    @Test fun amplitudes_collent_au_mouvement() {
        // (DANSEUR_SOLO, ENERGIE) = RAPIDE : ORBITE, TRAVELLING, ORBITE
        val seq = Grammaire.generer(Scene.DANSEUR_SOLO, Effet.ENERGIE, 30)
        val orbite = seq.pas.first { it.mouvement == Mouvement.ORBITE }
        val travelling = seq.pas.first { it.mouvement == Mouvement.TRAVELLING }
        assertTrue("orbite : rayon > 0, distance = 0", orbite.rayonM > 0f && orbite.distanceM == 0f)
        assertTrue("travelling : distance > 0, rayon = 0",
            travelling.distanceM > 0f && travelling.rayonM == 0f)
        // un mouvement statique n'a ni rayon ni distance
        val statique = Grammaire.generer(Scene.DANSEUR_SOLO, Effet.EMOTION, 30)
            .pas.first { it.mouvement == Mouvement.STATIQUE }
        assertTrue("statique : ni rayon ni distance", statique.rayonM == 0f && statique.distanceM == 0f)
    }

    // ---------------------------------------------------- repli si absent
    @Test fun combinaison_absente_retombe_sur_le_repli() {
        assertFalse("(PAYSAGE, PUISSANCE) ne doit pas exister",
            Grammaire.existe(Scene.PAYSAGE, Effet.PUISSANCE))
        val repli = Grammaire.generer(Scene.PAYSAGE, Effet.PUISSANCE, 20)
        // repli sur et doux : APPROCHE puis ORBITE, en AMERICAIN, LENTE
        assertEquals(listOf(Mouvement.APPROCHE, Mouvement.ORBITE), repli.pas.map { it.mouvement })
        assertTrue(repli.pas.all { it.vitesse == Vitesse.LENTE && it.plan == Plan.AMERICAIN })
        assertEquals(20, repli.dureeTotaleS)
    }

    @Test fun existe_reflete_la_table() {
        assertTrue(Grammaire.existe(Scene.DANSEUR_SOLO, Effet.EMOTION))
        assertFalse(Grammaire.existe(Scene.PAYSAGE, Effet.PUISSANCE))
    }

    // ---------------------------------------------------- exigence GPS (orbite)
    @Test fun orbite_exige_un_gps_ancre() {
        // (DANSEUR_SOLO, EMOTION) contient une ORBITE
        assertTrue(Grammaire.generer(Scene.DANSEUR_SOLO, Effet.EMOTION, 30).exigeGps())
        // (CHANTEUR, EMOTION) = APPROCHE + STATIQUE : pas d'orbite -> pas d'exigence GPS
        assertFalse(Grammaire.generer(Scene.CHANTEUR, Effet.EMOTION, 30).exigeGps())
    }

    // ---------------------------------------------------- TOUTE combinaison valide
    @Test fun chaque_scene_x_effet_produit_une_sequence_bien_formee() {
        var connues = 0
        for (scene in Scene.values()) {
            for (effet in Effet.values()) {
                val seq = Grammaire.generer(scene, effet, 60)
                assertTrue("$scene x $effet : sequence non vide", seq.pas.isNotEmpty())
                assertEquals("$scene x $effet : 60 s integralement repartis", 60, seq.dureeTotaleS)
                assertTrue("$scene x $effet : durees non negatives", seq.pas.all { it.dureeS >= 0 })
                // vitesse dominante unique dans une cellule
                assertEquals("$scene x $effet : vitesse homogene",
                    1, seq.pas.map { it.vitesse }.distinct().size)
                if (Grammaire.existe(scene, effet)) connues++
            }
        }
        println("GRAMMAIRE ok : ${Scene.values().size * Effet.values().size} combinaisons generees, " +
            "$connues cellules explicites, le reste en repli")
    }
}
