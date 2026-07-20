package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests de ConditionsVol (partie PURE : couleur/pastille des feux meteo).
 *
 * NB : le gros de ConditionsVol est un appel reseau (recuperer), non testable en
 * JVM sans serveur ; sa doctrine "best-effort, ne bloque jamais" se traduit ici
 * par : un feu inconnu / indisponible -> couleur NEUTRE (gris), jamais vert ni
 * rouge. On verifie donc surtout qu'une meteo absente n'affiche pas un faux
 * vert (rassurant a tort) ni un faux rouge (bloquant a tort).
 */
class ConditionsVolTest {

    private val VERT = 0xFF34C759.toInt()
    private val JAUNE = 0xFFFF9500.toInt()
    private val ROUGE = 0xFFFF3B30.toInt()
    private val GRIS = 0xFF8E8E93.toInt()

    @Test fun couleurs_des_feux_connus() {
        assertEquals(VERT, ConditionsVol.couleur("vert"))
        assertEquals(JAUNE, ConditionsVol.couleur("jaune"))
        assertEquals(ROUGE, ConditionsVol.couleur("rouge"))
    }

    @Test fun feu_inconnu_ou_indisponible_est_neutre() {
        assertEquals("indisponible -> gris", GRIS, ConditionsVol.couleur("indisponible"))
        assertEquals("valeur inattendue -> gris", GRIS, ConditionsVol.couleur("banane"))
        assertEquals("chaine vide -> gris", GRIS, ConditionsVol.couleur(""))
    }

    @Test fun une_meteo_absente_n_est_ni_vert_ni_rouge() {
        val neutre = ConditionsVol.couleur("indisponible")
        assertNotEquals("ne doit pas rassurer a tort (faux vert)", VERT, neutre)
        assertNotEquals("ne doit pas bloquer a tort (faux rouge)", ROUGE, neutre)
    }

    @Test fun pastilles_distinctes_et_neutre_par_defaut() {
        // sans embarquer les emojis (fichier ASCII) : on verifie la correspondance
        val v = ConditionsVol.pastille("vert")
        val r = ConditionsVol.pastille("rouge")
        val neutre = ConditionsVol.pastille("indisponible")
        assertNotEquals("vert et rouge doivent differer", v, r)
        assertNotEquals("vert et neutre doivent differer", v, neutre)
        assertEquals("tout inconnu tombe sur la meme pastille neutre",
            neutre, ConditionsVol.pastille("nimportequoi"))
    }
}
