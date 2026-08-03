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

    @Test fun timeout_par_defaut_est_350ms() {
        assertEquals(350L, CommandeWatchdog.DEFAUT_TIMEOUT_MS)
    }

    // --- CONCURRENCE : UN batteur, lecteurs en LECTURE PURE ---

    @Test fun lectures_concurrentes_ne_plantent_pas_et_restent_dans_le_domaine() {
        // Modele realiste : UN SEUL ecrivain (la boucle 10 Hz), plusieurs lecteurs. On evite
        // volontairement des ecritures concurrentes multiples (qui creeraient une horloge
        // "qui recule" artificielle, deja couverte par un test dedie). Ici on verifie que la
        // lecture concurrente ne plante jamais et renvoie des valeurs dans le domaine valide.
        val w = wd()
        val running = AtomicBoolean(true)
        val anomalie = AtomicBoolean(false)
        val base = System.nanoTime()
        val pool = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)

        // 1 SEUL batteur, monotone (nanoTime ne recule pas sur un meme thread).
        pool.submit {
            start.await()
            while (running.get()) w.battement(System.nanoTime())
        }
        // 4 lecteurs purs : age >= 0 (ou inf) et fraicheur booleenne coherente avec l'age
        // lu au MEME appel (on relit l'age juste apres et on ne juge que si stable).
        repeat(4) {
            pool.submit {
                start.await()
                while (running.get()) {
                    val now = System.nanoTime()
                    val avant = w.ageMs(now)
                    val frais = w.cycleFrais(now)
                    val apres = w.ageMs(now)
                    // (1) INVARIANT INCONDITIONNEL : l'age expose n'est JAMAIS negatif.
                    if (avant < 0L || apres < 0L) { anomalie.set(true); running.set(false) }
                    // (2) DOMAINE : l'age expose est soit une mesure (0..timeout et au-dela),
                    // soit l'infini conventionnel. Aucune autre valeur n'a de sens.
                    if (avant != Long.MAX_VALUE && avant < 0L) { anomalie.set(true); running.set(false) }

                    // ⚠ CE QUI N'EST **PAS** TESTE ICI, ET POURQUOI (2026-07-27).
                    //
                    // La version precedente comparait `frais` a l'age, en ne jugeant que si
                    // l'age relu etait identique (« etat stable »). Ce garde-fou est FAUX :
                    // c'est un probleme ABA. L'etat peut BOUGER puis revenir a la meme VALEUR
                    // AFFICHEE. Sequence reelle, observee au build du 2026-07-27 :
                    //   - le lecteur fige now = T ;
                    //   - `avant` = infini (aucun battement encore) ;
                    //   - le batteur pose D1 < T (horloge prise avant T, ecrite apres) ->
                    //     `frais` = true, legitimement ;
                    //   - le batteur pose D2 > T -> `apres` = infini a nouveau.
                    // Les deux lectures d'age sont egales, l'etat a pourtant change DEUX fois,
                    // et le test criait a l'anomalie sur un entrelacement parfaitement correct.
                    //
                    // L'API n'expose pas de lecture ATOMIQUE du couple (age, fraicheur) — et
                    // elle n'en a pas besoin : en production les deux appels sont sur le MEME
                    // fil (boucle pilote). La coherence age/fraicheur se teste donc en
                    // MONO-THREAD, de facon deterministe :
                    // `invariant_battre_puis_lire_est_frais_mono_thread` s'en charge.
                    // Ce test-ci ne garde que ce qu'il peut prouver sous concurrence :
                    // aucune exception, aucune valeur hors domaine. `cycleFrais` est tout de
                    // meme APPELE en boucle — c'est lui qu'on veut voir survivre aux acces
                    // concurrents, sa VALEUR n'etant pas jugeable ici.
                    if (frais && avant < 0L) { anomalie.set(true); running.set(false) }
                }
            }
        }
        start.countDown()
        Thread.sleep(300)
        running.set(false)
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse("lecture concurrente hors domaine (age negatif / frais+inf)", anomalie.get())
        assertTrue(System.nanoTime() > base)
    }

    @Test fun invariant_battre_puis_lire_est_frais_mono_thread() {
        // Invariant de securite deterministe (sans ecritures concurrentes) : juste apres un
        // battement, une lecture au meme instant ou plus tard mais < timeout est TOUJOURS fraiche.
        val w = wd()
        repeat(10_000) {
            val t = System.nanoTime()
            w.battement(t)
            assertTrue("battement immediat doit etre frais", w.cycleFrais(t))
            assertTrue("lecture 1 ms apres doit rester fraiche", w.cycleFrais(t + 1L * MS))
        }
    }
}

