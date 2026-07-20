package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests du CHIEN DE GARDE de cycle (Phase 1.2). Logique pure et deterministe : on injecte
 * une horloge monotone (nanos) pour simuler le temps sans dependre de l'horloge reelle.
 *
 * Invariant de securite : si la boucle se fige (plus de battement recent), l'emission doit
 * etre forcee a ZERO. FAIL-CLOSED avant tout premier battement.
 */
class CommandeWatchdogTest {

    private val EPS = 1e-6f
    private val MS = 1_000_000L          // 1 ms en nanos
    private val TIMEOUT = 500L           // ms

    private fun wd() = CommandeWatchdog(timeoutMs = TIMEOUT)

    // --- FAIL-CLOSED initial ---

    @Test fun avant_tout_battement_le_cycle_est_non_frais() {
        val w = wd()
        assertFalse(w.cycleFrais(0L))
        assertFalse(w.cycleFrais(1_000_000_000L))
        assertEquals(Long.MAX_VALUE, w.ageMs(0L))
    }

    @Test fun avant_tout_battement_le_throttle_est_force_a_zero() {
        val w = wd()
        assertEquals(0f, w.filtrerThrottle(0.3f, 12345L), EPS)
    }

    // --- FRAICHEUR NOMINALE ---

    @Test fun juste_apres_un_battement_le_cycle_est_frais() {
        val w = wd()
        val t = 10_000L * MS
        w.battement(t)
        assertTrue(w.cycleFrais(t))
        assertEquals(0L, w.ageMs(t))
    }

    @Test fun throttle_passe_si_cycle_frais() {
        val w = wd()
        val t = 5_000L * MS
        w.battement(t)
        assertEquals(0.25f, w.filtrerThrottle(0.25f, t + 100 * MS), EPS) // 100 ms < 500 ms
    }

    // --- LIMITE EXACTE DU TIMEOUT ---

    @Test fun a_la_limite_exacte_du_timeout_encore_frais() {
        val w = wd()
        val t = 0L
        w.battement(t)
        assertTrue(w.cycleFrais(t + TIMEOUT * MS))        // age == timeout -> tolere (<=)
    }

    @Test fun un_nano_au_dela_du_timeout_devient_mort() {
        val w = wd()
        val t = 0L
        w.battement(t)
        assertFalse(w.cycleFrais(t + TIMEOUT * MS + 1L))  // strictement au-dela -> mort
        assertEquals(0f, w.filtrerThrottle(0.3f, t + TIMEOUT * MS + 1L), EPS)
    }

    // --- BOUCLE FIGEE ---

    @Test fun boucle_figee_force_le_throttle_a_zero() {
        val w = wd()
        val t0 = 1_000L * MS
        w.battement(t0)
        // La boucle ne bat plus ; 2 s plus tard on tente d'emettre.
        val plusTard = t0 + 2_000L * MS
        assertFalse(w.cycleFrais(plusTard))
        assertEquals(0f, w.filtrerThrottle(0.3f, plusTard), EPS)
        assertTrue(w.ageMs(plusTard) >= 1_999L)
    }

    @Test fun un_nouveau_battement_reanime_le_cycle() {
        val w = wd()
        w.battement(0L)
        val mort = 1_000L * MS
        assertFalse(w.cycleFrais(mort))          // mort
        w.battement(mort)                         // la boucle repart
        assertTrue(w.cycleFrais(mort))            // ranime
        assertEquals(0.2f, w.filtrerThrottle(0.2f, mort + 50 * MS), EPS)
    }

    // --- HORLOGE INCOHERENTE ---

    @Test fun horloge_qui_recule_est_traitee_fail_closed() {
        val w = wd()
        w.battement(10_000L * MS)
        // now < dernier battement -> age negatif -> fail-closed.
        assertFalse(w.cycleFrais(9_000L * MS))
        assertEquals(0f, w.filtrerThrottle(0.3f, 9_000L * MS), EPS)
        assertEquals(Long.MAX_VALUE, w.ageMs(9_000L * MS))
    }

    // --- VALIDATION DU CONSTRUCTEUR ---

    @Test(expected = IllegalArgumentException::class)
    fun timeout_non_positif_est_refuse() {
        CommandeWatchdog(timeoutMs = 0L)
    }

    @Test fun timeout_par_defaut_est_500ms() {
        assertEquals(500L, CommandeWatchdog.DEFAUT_TIMEOUT_MS)
    }

    // --- CONCURRENCE : batteur vs lecteurs ---

    @Test fun lectures_concurrentes_restent_coherentes_pendant_les_battements() {
        val w = wd()
        val running = AtomicBoolean(true)
        val incoherence = AtomicBoolean(false)
        val base = System.nanoTime()
        val pool = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)

        // 1 batteur : bat "maintenant" en continu.
        pool.submit {
            start.await()
            while (running.get()) w.battement(System.nanoTime())
        }
        // 4 lecteurs : un battement recent (<= timeout) doit donner cycleFrais == true.
        repeat(4) {
            pool.submit {
                start.await()
                while (running.get()) {
                    val now = System.nanoTime()
                    val frais = w.cycleFrais(now)
                    val age = w.ageMs(now)
                    // Coherence : si l'age rapporte est <= timeout, cycleFrais doit etre vrai.
                    if (age <= TIMEOUT && !frais && age != Long.MAX_VALUE) {
                        incoherence.set(true); running.set(false)
                    }
                }
            }
        }
        start.countDown()
        Thread.sleep(400)
        running.set(false)
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse("incoherence age/fraicheur sous concurrence", incoherence.get())
        // base sert juste a s'assurer que nanoTime avance sur la duree du test.
        assertTrue(System.nanoTime() > base)
    }
}
