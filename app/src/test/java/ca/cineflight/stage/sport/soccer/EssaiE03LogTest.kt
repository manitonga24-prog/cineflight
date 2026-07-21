package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Sc = EssaiE03Log.Scenario

/**
 * EssaiE03LogTest — couvre la journalisation/mesure de l'essai E-03 (pur, JVM).
 * Vérifie le calcul des délais T0..T6, la PERSISTANCE (critère central), le verdict
 * PASS/FAIL fail-closed, et le format de ligne exportable.
 */
class EssaiE03LogTest {

    private val MS = 1_000_000L
    private val L = EssaiE03Log(persistanceMaxMs = 500L)

    private fun m(t0: Long = 0, t1: Long = -1, t2: Long = -1, t3: Long = -1, t4: Long = -1,
                  t5: Long = -1, t6: Long = -1, v: Float = 0f, fin: Long = -1) =
        EssaiE03Log.Mesures(t0 * MS, t1 * MS, t2 * MS, t3 * MS, t4 * MS, t5 * MS, t6 * MS, v,
            if (fin < 0) -1 else fin * MS)

    // ── delta / persistance ──

    @Test fun delta_ms_correct() {
        assertEquals(150L, L.deltaMs(0L, 150L * MS))
        assertNull(L.deltaMs(-1L, 100L))
    }

    @Test fun persistance_zero_si_vitesse_nulle() {
        assertEquals(0L, L.persistanceMs(m(v = 0f)))
    }

    @Test fun persistance_mesuree_entre_T0_et_fin_vitesse() {
        // vitesse non nulle jusqu'à 300 ms après T0 -> persistance 300 ms
        assertEquals(300L, L.persistanceMs(m(t0 = 0, v = 0.5f, fin = 300)))
    }

    @Test fun persistance_null_si_non_mesuree() {
        assertNull(L.persistanceMs(m(t0 = 0, v = 0.5f, fin = -1)))
    }

    // ── acceptation (fail-closed) ──

    @Test fun persistance_sous_seuil_acceptee() {
        assertTrue(L.persistanceAcceptee(m(v = 0.5f, fin = 400)))   // 400 ≤ 500
    }

    @Test fun persistance_au_dela_du_seuil_refusee() {
        assertFalse(L.persistanceAcceptee(m(v = 0.5f, fin = 800)))  // 800 > 500
    }

    @Test fun persistance_non_mesuree_refusee_fail_closed() {
        assertFalse(L.persistanceAcceptee(m(v = 0.5f, fin = -1)))
    }

    // ── verdict scénario standard ──

    @Test fun verdict_pass_si_toute_la_chaine_et_persistance_ok() {
        val ok = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, t4 = 140, t5 = 150, t6 = 200, v = 0.5f, fin = 250)
        assertEquals("PASS", L.verdict(Sc.E03_10_DERNIERE_CMD_POSITIVE, ok))
    }

    @Test fun verdict_fail_si_persistance_trop_longue() {
        val trop = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, t4 = 140, v = 0.5f, fin = 900)
        assertEquals("FAIL", L.verdict(Sc.E03_10_DERNIERE_CMD_POSITIVE, trop))
    }

    @Test fun verdict_fail_si_sortie_vs_manquante() {
        val sansT4 = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, v = 0f)
        assertEquals("FAIL", L.verdict(Sc.E03_03_GEL_THREAD, sansT4))
    }

    @Test fun verdict_fs_exige_detection_et_effet() {
        val fsOk = m(t0 = 0, t1 = 100, t6 = 300, v = 0f)
        assertEquals("PASS", L.verdict(Sc.E03_FS1_PERTE_RC, fsOk))
        val fsSansEffet = m(t0 = 0, t1 = 100, v = 0f)
        assertEquals("FAIL", L.verdict(Sc.E03_FS1_PERTE_RC, fsSansEffet))
    }

    // ── ligne exportable ──

    @Test fun ligne_contient_les_champs_cles() {
        val mm = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, t4 = 140, t5 = 150, t6 = 200, v = 0.5f, fin = 250)
        val s = L.ligne(Sc.E03_10_DERNIERE_CMD_POSITIVE, repetition = 2, mm, configId = "0ab788385686e554")
        assertTrue(s.contains("scenario=E03_10_DERNIERE_CMD_POSITIVE"))
        assertTrue(s.contains("rep=2"))
        assertTrue(s.contains("config_id=0ab788385686e554"))
        assertTrue(s.contains("T4_ms=140"))
        assertTrue(s.contains("persist_ms=250"))
        assertTrue(s.contains("seuil_ms=500"))
        assertTrue(s.contains("verdict=PASS"))
    }

    @Test fun ligne_gere_les_horodatages_manquants() {
        val s = L.ligne(Sc.E03_01_ARRET_NORMAL, 1, m(t0 = 0, v = 0f), "cfg")
        assertTrue("T non mesurés notés '-'", s.contains("T1_ms=-"))
        assertTrue(s.contains("persist_ms=0"))
    }

    @Test fun les_16_scenarios_sont_definis() {
        assertEquals(16, EssaiE03Log.Scenario.values().size)
    }
}
