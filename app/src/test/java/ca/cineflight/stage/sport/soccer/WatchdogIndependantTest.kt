package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * WatchdogIndependantTest — couvre REQ-WDG-001 (watchdog sur thread STRICTEMENT independant).
 *
 * Deux familles de preuves :
 *  1) LOGIQUE PURE (verifier(nowNanos), sans thread) : fail-closed, latch one-shot,
 *     recul d'horloge, desarmement volontaire, reset explicite, exceptions contenues.
 *  2) THREADS REELS : un fil d'emission qui bat puis GELE reellement -> le thread B
 *     detecte et declenche les actions de mise en securite, meme si A ne s'execute plus.
 */
class WatchdogIndependantTest {

    private val MS = 1_000_000L  // nanos par milliseconde

    private fun wd(
        timeout: Long = 500L, periode: Long = 50L,
        horloge: (() -> Long)? = null,
        onDef: (Long) -> Unit,
    ) = if (horloge != null)
        WatchdogIndependant(timeout, periode, horloge, onDef)
    else
        WatchdogIndependant(timeout, periode, onDefaillance = onDef)

    // ── 1. LOGIQUE PURE ──────────────────────────────────────────────────────

    @Test fun non_arme_ne_declenche_jamais() {
        var declenche = false
        val w = wd { declenche = true }
        w.battement(0L)
        assertFalse(w.verifier(10_000L * MS))
        assertFalse(declenche)
    }

    @Test fun battement_frais_ne_declenche_pas() {
        var declenche = false
        val w = wd { declenche = true }
        w.armer(0L)
        w.battement(400L * MS)
        assertFalse(w.verifier(500L * MS))   // age 100 ms < 500 ms
        assertFalse(declenche)
    }

    @Test fun battement_perime_declenche_une_fois() {
        val n = AtomicInteger(0)
        val w = wd { n.incrementAndGet() }
        w.armer(0L)
        assertTrue(w.verifier(501L * MS))    // age 501 ms > 500 ms -> declenche
        assertFalse(w.verifier(502L * MS))   // latch : jamais deux fois
        assertFalse(w.verifier(10_000L * MS))
        assertEquals(1, n.get())
        assertTrue(w.defaillanceDeclenchee())
    }

    @Test fun armer_vaut_premier_battement_fail_closed() {
        // Si le fil d'emission ne bat JAMAIS apres l'armement, la defaillance survient
        // dans timeoutMs — PAS d'attente infinie (fail-closed).
        var age = -1L
        val w = wd { age = it }
        w.armer(0L)
        assertFalse(w.verifier(499L * MS))
        assertTrue(w.verifier(501L * MS))
        assertTrue(age >= 500L)
    }

    @Test fun age_limite_exact_ne_declenche_pas() {
        var declenche = false
        val w = wd { declenche = true }
        w.armer(0L)
        assertFalse(w.verifier(500L * MS))   // age == timeout : encore tolere
        assertFalse(declenche)
    }

    @Test fun recul_horloge_est_traite_comme_perime() {
        // Anomalie d'horloge (age negatif) -> fail-closed : defaillance.
        var declenche = false
        val w = wd { declenche = true }
        w.armer(1_000L * MS)
        assertTrue(w.verifier(0L))           // now < dernier battement
        assertTrue(declenche)
    }

    @Test fun desarmement_volontaire_suspend_la_surveillance() {
        var declenche = false
        val w = wd { declenche = true }
        w.armer(0L)
        w.desarmerSurveillance()
        assertFalse(w.verifier(10_000L * MS))
        assertFalse(declenche)
        assertFalse(w.defaillanceDeclenchee())
    }

    @Test fun defaillance_desarme_la_surveillance() {
        val w = wd { }
        w.armer(0L)
        w.verifier(501L * MS)
        assertFalse(w.surveillanceArmee())
    }

    @Test fun rearmement_impossible_sans_reset() {
        val n = AtomicInteger(0)
        val w = wd { n.incrementAndGet() }
        w.armer(0L)
        w.verifier(501L * MS)                // declenche + latch
        w.armer(1_000L * MS)                 // SANS reset : doit etre refuse
        assertFalse(w.surveillanceArmee())
        assertFalse(w.verifier(2_000L * MS))
        assertEquals(1, n.get())
    }

    @Test fun reset_explicite_puis_rearmement_fonctionne() {
        val n = AtomicInteger(0)
        val w = wd { n.incrementAndGet() }
        w.armer(0L)
        w.verifier(501L * MS)
        w.reset()
        assertFalse(w.defaillanceDeclenchee())
        w.armer(1_000L * MS)
        assertTrue(w.surveillanceArmee())
        assertTrue(w.verifier(1_502L * MS))  // nouvelle surveillance operationnelle
        assertEquals(2, n.get())
    }

    @Test fun exception_dans_onDefaillance_est_contenue_latch_pose() {
        val w = wd { throw RuntimeException("action de securite ratee") }
        w.armer(0L)
        assertTrue(w.verifier(501L * MS))    // ne propage pas l'exception
        assertTrue(w.defaillanceDeclenchee())
    }

    @Test fun parametres_invalides_refuses() {
        try { WatchdogIndependant(timeoutMs = 0, onDefaillance = {}); throw AssertionError("timeout 0 accepte") }
        catch (_: IllegalArgumentException) {}
        try { WatchdogIndependant(timeoutMs = 100, periodeMs = 100, onDefaillance = {}); throw AssertionError("periode >= timeout acceptee") }
        catch (_: IllegalArgumentException) {}
    }

    // ── 2. THREADS REELS : le fil d'emission GELE reellement ────────────────

    @Test(timeout = 5_000) fun fil_emission_gele_est_detecte_par_le_thread_independant() {
        val defaillance = CountDownLatch(1)
        val w = WatchdogIndependant(timeoutMs = 200L, periodeMs = 20L,
            onDefaillance = { defaillance.countDown() })

        val continuerABattre = java.util.concurrent.atomic.AtomicBoolean(true)
        val filEmission = Thread {
            while (continuerABattre.get()) {
                w.battement()
                try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread }
            }
            // GEL simule : le fil ne bat plus JAMAIS (dort indefiniment).
            try { Thread.sleep(60_000) } catch (_: InterruptedException) {}
        }
        filEmission.isDaemon = true

        w.armer()
        w.demarrer()
        filEmission.start()
        Thread.sleep(150)                     // phase saine : battements reguliers
        assertFalse("pas de defaillance pendant la phase saine", w.defaillanceDeclenchee())

        continuerABattre.set(false)           // ── GEL du fil d'emission ──
        val detecte = defaillance.await(2, TimeUnit.SECONDS)
        assertTrue("le thread independant doit detecter le gel", detecte)
        assertTrue(w.defaillanceDeclenchee())

        w.arreter()
        filEmission.interrupt()
    }

    @Test(timeout = 5_000) fun thread_b_survit_a_une_exception_des_actions() {
        // La 1re defaillance jette une exception ; apres reset+armer, le thread B doit
        // encore detecter la 2e defaillance (il n'est pas mort).
        val appels = AtomicInteger(0)
        val deuxieme = CountDownLatch(2)
        val w = WatchdogIndependant(timeoutMs = 120L, periodeMs = 20L, onDefaillance = {
            appels.incrementAndGet(); deuxieme.countDown()
            throw RuntimeException("boom")
        })
        w.demarrer()
        w.armer()                              // aucun battement -> defaillance ~120 ms
        Thread.sleep(400)
        assertEquals(1, appels.get())
        w.reset()
        w.armer()                              // 2e surveillance, toujours sans battement
        val ok = deuxieme.await(2, TimeUnit.SECONDS)
        assertTrue("le thread B doit survivre et re-detecter", ok)
        w.arreter()
    }

    @Test(timeout = 5_000) fun arreter_stoppe_le_thread_sans_defaillance_parasite() {
        val n = AtomicInteger(0)
        val w = WatchdogIndependant(timeoutMs = 200L, periodeMs = 20L,
            onDefaillance = { n.incrementAndGet() })
        w.demarrer()
        // surveillance jamais armee : rien ne doit se declencher.
        Thread.sleep(150)
        w.arreter()
        Thread.sleep(100)
        assertEquals(0, n.get())
    }

    @Test fun battements_concurrents_sont_surs() {
        // Plusieurs fils battent en meme temps : aucune corruption, pas de defaillance
        // tant que les battements restent frais.
        val w = wd(timeout = 500L, periode = 50L, horloge = null) { }
        w.armer(0L)
        val fils = (1..4).map { Thread { repeat(2_000) { w.battement() } } }
        fils.forEach { it.start() }; fils.forEach { it.join() }
        assertFalse(w.verifier())   // battements tout frais (horloge reelle)
        assertFalse(w.defaillanceDeclenchee())
    }
}
