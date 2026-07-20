package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Test

// Alias de TYPE (Kotlin) : un nom d'enum n'est pas une expression assignable a un val.
// On raccourcit les types pour garder E.COMPLET / M.DEPLACEMENT_BORNE lisibles.
private typealias E = CapacitesDrone.Evitement
private typealias M = SuiviVisionYolo.ModeVision

/**
 * Teste la DECISION de mode de suivi vision (SuiviVisionYolo.modeAutorise), qui
 * REUTILISE la classification materiel existante CapacitesDrone.analyser() : la
 * classification n'est PAS dupliquee. Regle fail-closed : DEPLACEMENT_BORNE
 * seulement si l'evitement est COMPLET ET reellement actif ; sinon CADRAGE_PIVOT.
 */
class CapacitesDroneTest {

    @Test fun complet_et_actif_autorise_le_deplacement() {
        assertEquals(M.DEPLACEMENT_BORNE, SuiviVisionYolo.modeAutorise(E.COMPLET, true))
    }

    @Test fun complet_mais_evitement_inactif_reste_en_pivot() {
        assertEquals(M.CADRAGE_PIVOT, SuiviVisionYolo.modeAutorise(E.COMPLET, false))
    }

    @Test fun sans_evitement_complet_toujours_pivot() {
        assertEquals(M.CADRAGE_PIVOT, SuiviVisionYolo.modeAutorise(E.AUCUN, true))
        assertEquals(M.CADRAGE_PIVOT, SuiviVisionYolo.modeAutorise(E.PARTIEL, true))
    }

    @Test fun classification_reutilisee_de_CapacitesDrone() {
        assertEquals(E.COMPLET, CapacitesDrone.analyser("DJI Mini 4 Pro").evitement)
        assertEquals(E.PARTIEL, CapacitesDrone.analyser("DJI Mini 3 Pro").evitement)
        assertEquals(E.AUCUN,  CapacitesDrone.analyser("DJI Mini 3").evitement)
    }

    @Test fun bout_a_bout_mini4pro_deplace_mini3_pivote() {
        assertEquals(M.DEPLACEMENT_BORNE,
            SuiviVisionYolo.modeAutorise(CapacitesDrone.analyser("DJI Mini 4 Pro").evitement, true))
        assertEquals(M.CADRAGE_PIVOT,
            SuiviVisionYolo.modeAutorise(CapacitesDrone.analyser("DJI Mini 3").evitement, true))
    }
}
