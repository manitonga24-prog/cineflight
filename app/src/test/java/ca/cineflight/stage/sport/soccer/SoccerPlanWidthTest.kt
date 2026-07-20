package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SoccerPlanWidthTest — couvre la machine à états de la largeur de plan (pure, JVM).
 *
 * Objectif : exercer TOUTES les transitions LARGE/MOYEN/RAPPROCHE dans les deux sens,
 * ainsi que l'hystérésis (pas de changement dans la zone morte). Chaque test vise une
 * branche du when() pour maximiser la couverture des branches.
 *
 * Rappel de la logique : besoinLarge = max(etalement, vitesse, 1-certitude).
 *   - haut (> 0.66)  -> tend vers LARGE
 *   - bas  (< 0.33)  -> tend vers RAPPROCHE
 *   - milieu         -> MOYEN
 */
class SoccerPlanWidthTest {

    // valeurs faisant monter/descendre besoinLarge de façon contrôlée
    // besoinLarge = max(e, v, 1-c). Pour forcer une valeur X : e=X, v=0, c=1 -> besoin=X.
    private fun maj(pw: SoccerPlanWidth, besoin: Float) = pw.maj(besoin, 0f, 1f)

    @Test fun demarre_large_le_plus_sur() {
        val pw = SoccerPlanWidth()
        // sans appel, le plan initial est LARGE : un premier maj à valeur haute le confirme
        assertEquals(SoccerPlanWidth.Plan.LARGE, maj(pw, 0.9f))
    }

    @Test fun large_reste_large_si_besoin_haut() {
        val pw = SoccerPlanWidth()
        assertEquals(SoccerPlanWidth.Plan.LARGE, maj(pw, 0.9f))
        assertEquals(SoccerPlanWidth.Plan.LARGE, maj(pw, 0.8f))
    }

    @Test fun large_vers_moyen_quand_besoin_baisse() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f) // LARGE
        // descend sous sLarge-h (0.66-0.1=0.56) mais au-dessus de sRapproche -> MOYEN
        assertEquals(SoccerPlanWidth.Plan.MOYEN, maj(pw, 0.5f))
    }

    @Test fun large_vers_rapproche_quand_besoin_tres_bas() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f) // LARGE
        // descend sous sRapproche-h (0.33-0.1=0.23) -> RAPPROCHE directement
        assertEquals(SoccerPlanWidth.Plan.RAPPROCHE, maj(pw, 0.1f))
    }

    @Test fun moyen_vers_large_quand_besoin_remonte() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.5f) // -> MOYEN
        // remonte au-dessus de sLarge+h (0.66+0.1=0.76) -> LARGE
        assertEquals(SoccerPlanWidth.Plan.LARGE, maj(pw, 0.9f))
    }

    @Test fun moyen_vers_rapproche_quand_besoin_tres_bas() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.5f) // -> MOYEN
        // descend sous sRapproche-h -> RAPPROCHE
        assertEquals(SoccerPlanWidth.Plan.RAPPROCHE, maj(pw, 0.1f))
    }

    @Test fun moyen_reste_moyen_dans_zone_morte() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.5f) // -> MOYEN
        assertEquals(SoccerPlanWidth.Plan.MOYEN, maj(pw, 0.5f))
    }

    @Test fun rapproche_reste_rapproche_si_besoin_bas() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.1f) // -> RAPPROCHE
        assertEquals(SoccerPlanWidth.Plan.RAPPROCHE, maj(pw, 0.1f))
    }

    @Test fun rapproche_vers_moyen_quand_besoin_remonte() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.1f) // -> RAPPROCHE
        // remonte au-dessus de sRapproche+h (0.33+0.1=0.43) mais sous sLarge+h -> MOYEN
        assertEquals(SoccerPlanWidth.Plan.MOYEN, maj(pw, 0.5f))
    }

    @Test fun rapproche_vers_large_quand_besoin_tres_haut() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.1f) // -> RAPPROCHE
        // remonte au-dessus de sLarge+h -> LARGE directement
        assertEquals(SoccerPlanWidth.Plan.LARGE, maj(pw, 0.9f))
    }

    @Test fun incertitude_forte_force_large() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.1f) // tend vers rapproche mais on part de LARGE
        // certitude=0 -> 1-c=1 -> besoinLarge=1 -> LARGE
        assertEquals(SoccerPlanWidth.Plan.LARGE, pw.maj(0f, 0f, 0f))
    }

    @Test fun reset_revient_a_large() {
        val pw = SoccerPlanWidth()
        maj(pw, 0.9f); maj(pw, 0.1f) // -> RAPPROCHE
        pw.reset()
        // après reset, part de LARGE : valeur haute confirme LARGE
        assertEquals(SoccerPlanWidth.Plan.LARGE, maj(pw, 0.9f))
    }

    @Test fun entrees_hors_bornes_sont_bornees() {
        val pw = SoccerPlanWidth()
        // etalement > 1 et < 0 doivent être coercés sans crash
        val r = pw.maj(5f, -2f, 3f)
        // besoinLarge = max(1, 0, 1-1=0) = 1 -> LARGE
        assertEquals(SoccerPlanWidth.Plan.LARGE, r)
    }
}
