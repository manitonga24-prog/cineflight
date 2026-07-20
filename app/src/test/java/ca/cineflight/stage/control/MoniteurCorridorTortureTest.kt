package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ===================== TORTURE DU MONITEUR CORRIDOR =====================
 * Surveille que le sujet reste dans le corridor valide (securite du vol avant
 * cadrage). On lui balance des ages NaN, des sujets NaN, des seuils incoherents,
 * des trajectoires degenerees. Doctrine : "non defini = non sur".
 *
 * EXPOSE_ = ecrit pour ECHOUER sur le code actuel (vraie faille).
 * ROBUSTE_ = doit passer.
 * =======================================================================
 */
class MoniteurCorridorTortureTest {

    private val lat0 = 45.5
    private val lon0 = -73.56
    private val mLat = 111_320.0
    private fun mLon() = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun p(alongM: Double, lateralM: Double) =
        MoniteurCorridor.Point(lat0 + lateralM / mLat, lon0 + alongM / mLon())
    private fun traj() = listOf(p(0.0, 0.0), p(200.0, 0.0))   // ligne est-ouest
    private fun sujet(lateralM: Double) = p(50.0, lateralM)   // 50 m le long, ecart lateral variable

    // =====================================================================
    //  FAILLE ATTENDUE (ROUGE)
    // =====================================================================

    /** age RTK NaN : "NaN > max" faux -> le sujet n'est pas declare perdu. */
    @Test fun EXPOSE_age_NaN_doit_etre_RTK_PERDU() {
        val r = MoniteurCorridor().evaluer(traj(), sujet(5.0), Double.NaN)
        assertEquals("age NaN => non defini => RTK_PERDU",
            MoniteurCorridor.Statut.RTK_PERDU, r.statut)
    }

    // =====================================================================
    //  ROBUSTESSE (VERT)
    // =====================================================================

    @Test fun ROBUSTE_sujet_absent_ou_age_vieux_est_perdu() {
        assertEquals(MoniteurCorridor.Statut.RTK_PERDU,
            MoniteurCorridor().evaluer(traj(), null, 0.1).statut)
        assertEquals(MoniteurCorridor.Statut.RTK_PERDU,
            MoniteurCorridor().evaluer(traj(), sujet(5.0), 99.0).statut)   // age > 3 s
    }

    /** Sujet a position NaN : l'ecart lateral NaN tombe (par le else) sur HORS_CORRIDOR. */
    @Test fun ROBUSTE_sujet_NaN_est_hors_corridor() {
        val r = MoniteurCorridor().evaluer(traj(), MoniteurCorridor.Point(Double.NaN, Double.NaN), 0.1)
        assertEquals(MoniteurCorridor.Statut.HORS_CORRIDOR, r.statut)
        assertFalse(r.dansCorridor)
    }

    /** Statut monotone selon l'ecart lateral (seuils defaut 10/25/40, bornes incluses). */
    @Test fun ROBUSTE_statuts_selon_ecart_lateral() {
        val m = MoniteurCorridor()
        assertEquals(MoniteurCorridor.Statut.NORMAL, m.evaluer(traj(), sujet(5.0), 0.1).statut)
        assertEquals(MoniteurCorridor.Statut.NORMAL, m.evaluer(traj(), sujet(8.0), 0.1).statut)
        assertEquals(MoniteurCorridor.Statut.TOLERANCE, m.evaluer(traj(), sujet(18.0), 0.1).statut)
        assertEquals(MoniteurCorridor.Statut.PRUDENCE, m.evaluer(traj(), sujet(30.0), 0.1).statut)
        assertEquals(MoniteurCorridor.Statut.HORS_CORRIDOR, m.evaluer(traj(), sujet(50.0), 0.1).statut)
    }

    /** Seuils incoherents (ou NaN) ignores ; seuils serveur coherents appliques. */
    @Test fun ROBUSTE_appliquer_seuils_valide_les_entrees() {
        val m = MoniteurCorridor()
        m.appliquerSeuils(-5.0, 10.0, 20.0)          // normalM <= 0 -> ignore
        m.appliquerSeuils(10.0, 5.0, 20.0)           // tolerance < normal -> ignore
        m.appliquerSeuils(Double.NaN, 40.0, 60.0)    // NaN -> ignore
        // defauts conserves : 30 m => PRUDENCE (defaut 25..40)
        assertEquals(MoniteurCorridor.Statut.PRUDENCE, m.evaluer(traj(), sujet(30.0), 0.1).statut)
        // seuils serveur coherents -> appliques : 15 m devient NORMAL (<= 20)
        m.appliquerSeuils(20.0, 40.0, 60.0)
        assertEquals(MoniteurCorridor.Statut.NORMAL, m.evaluer(traj(), sujet(15.0), 0.1).statut)
    }

    @Test fun ROBUSTE_trajectoire_degeneree_ne_plante_pas() {
        // < 2 points -> corridor non defini -> RTK_PERDU
        assertEquals(MoniteurCorridor.Statut.RTK_PERDU,
            MoniteurCorridor().evaluer(listOf(p(0.0, 0.0)), sujet(5.0), 0.1).statut)
        // segment de longueur nulle -> pas de crash, ecart fini
        val r = MoniteurCorridor().evaluer(listOf(p(0.0, 0.0), p(0.0, 0.0)), sujet(5.0), 0.1)
        assertTrue("ecart lateral fini malgre segment degenere", r.ecartLateralM.isFinite())
    }
}
