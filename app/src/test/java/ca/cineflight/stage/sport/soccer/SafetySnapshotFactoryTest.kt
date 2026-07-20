package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests de la PUBLICATION ATOMIQUE du SafetySnapshot (NC-T2-001).
 *
 * Le cœur : prouver qu'un snapshot est TOUJOURS coherent (issu d'un seul echantillon) et
 * qu'aucune lecture concurrente ne peut observer un melange de deux echantillons ("tearing"),
 * meme sous forte concurrence lecteurs/ecrivains.
 */
class SafetySnapshotFactoryTest {

    // Deux "familles" d'echantillons totalement opposees. Un snapshot valide doit etre
    // ENTIEREMENT de la famille A ou ENTIEREMENT de la famille B, jamais un panache.
    private val sampleTrue = RawSafetySample(
        pilotOverride = true, emergencyStop = true, obstacleGateAllows = true,
        virtualStickAvailable = true, inFlightCompatible = true, railLoadedAndValid = true,
        dronePositionFresh = true, actionFreshAndConfident = true, batteryOk = true,
        corridorClear = true, operatorNearRail = true,
    )
    private val sampleFalse = RawSafetySample(
        pilotOverride = false, emergencyStop = false, obstacleGateAllows = false,
        virtualStickAvailable = false, inFlightCompatible = false, railLoadedAndValid = false,
        dronePositionFresh = false, actionFreshAndConfident = false, batteryOk = false,
        corridorClear = false, operatorNearRail = false,
    )

    /** Vrai si le snapshot est homogene (tous champs vrais OU tous faux) = coherent. */
    private fun estHomogene(s: FlightCommandArbiter.SafetySnapshot): Boolean {
        val champs = listOf(
            s.pilotOverride, s.emergencyStop, s.obstacleGateAllows, s.virtualStickAvailable,
            s.inFlightCompatible, s.railLoadedAndValid, s.dronePositionFresh,
            s.actionFreshAndConfident, s.batteryOk, s.corridorClear, s.operatorNearRail,
        )
        return champs.all { it } || champs.none { it }
    }

    // --- BASE : conversion et immuabilite ---

    @Test fun conversion_raw_vers_snapshot_est_fidele() {
        val snap = SafetySnapshotFactory.snapshotDe(sampleTrue)
        assertTrue(snap.pilotOverride)
        assertTrue(snap.batteryOk)
        assertTrue(snap.operatorNearRail)
        assertTrue(estHomogene(snap))
    }

    @Test fun defauts_raw_sample_sont_fail_closed() {
        // Par defaut, tout ce qui n'est pas prouve vrai bloque (sauf operatorNearRail = compat).
        val d = RawSafetySample()
        assertTrue(!d.pilotOverride && !d.emergencyStop && !d.obstacleGateAllows)
        assertTrue(!d.virtualStickAvailable && !d.inFlightCompatible && !d.railLoadedAndValid)
        assertTrue(!d.dronePositionFresh && !d.actionFreshAndConfident && !d.batteryOk)
        assertTrue(!d.corridorClear)
        assertTrue(d.operatorNearRail) // seul defaut a true (compat historique)
    }

    // --- GENERATION ---

    @Test fun latest_est_null_avant_toute_publication() {
        assertNull(SafetySnapshotFactory().latest())
    }

    @Test fun generation_croit_strictement_a_chaque_publication() {
        val f = SafetySnapshotFactory()
        val g1 = f.publish(sampleFalse).generation
        val g2 = f.publish(sampleTrue).generation
        val g3 = f.publish(sampleFalse).generation
        assertEquals(1L, g1)
        assertEquals(2L, g2)
        assertEquals(3L, g3)
    }

    @Test fun latest_reflete_la_derniere_publication() {
        val f = SafetySnapshotFactory()
        f.publish(sampleFalse)
        val pubT = f.publish(sampleTrue)
        val latest = f.latest()
        assertNotNull(latest)
        assertEquals(pubT.generation, latest!!.generation)
        assertTrue(latest.snapshot.pilotOverride) // = famille "true"
    }

    // --- CONCURRENCE : le cœur du NC-T2-001 ---

    @Test fun aucun_snapshot_ne_melange_deux_echantillons_sous_concurrence() {
        val f = SafetySnapshotFactory()
        f.publish(sampleFalse) // amorce
        val running = AtomicBoolean(true)
        val tearingDetecte = AtomicBoolean(false)
        val nbLecteurs = 6
        val pool = Executors.newFixedThreadPool(nbLecteurs + 2)
        val start = CountDownLatch(1)

        // 2 ecrivains alternent en permanence entre les 2 familles opposees.
        repeat(2) { w ->
            pool.submit {
                start.await()
                var i = 0
                while (running.get()) {
                    f.publish(if ((i + w) % 2 == 0) sampleTrue else sampleFalse)
                    i++
                }
            }
        }
        // Lecteurs : chaque snapshot lu DOIT etre homogene (jamais un panache).
        repeat(nbLecteurs) {
            pool.submit {
                start.await()
                while (running.get()) {
                    val p = f.latest()
                    if (p != null && !estHomogene(p.snapshot)) {
                        tearingDetecte.set(true)
                        running.set(false)
                    }
                }
            }
        }

        start.countDown()
        Thread.sleep(600)      // laisse tourner ~0,6 s de course lecteurs/ecrivains
        running.set(false)
        pool.shutdown()
        assertTrue("le pool n'a pas termine", pool.awaitTermination(5, TimeUnit.SECONDS))

        assertTrue("TEARING detecte : un snapshot melangeait deux echantillons",
            !tearingDetecte.get())
    }

    @Test fun toutes_les_generations_publiees_sont_uniques_sous_concurrence() {
        val f = SafetySnapshotFactory()
        val nbThreads = 8
        val parThread = 500
        val pool = Executors.newFixedThreadPool(nbThreads)
        val start = CountDownLatch(1)
        val vues = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val collisions = AtomicBoolean(false)

        repeat(nbThreads) {
            pool.submit {
                start.await()
                repeat(parThread) {
                    val g = f.publish(sampleTrue).generation
                    if (!vues.add(g)) collisions.set(true) // deja vue -> collision
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertTrue("des numeros de generation ont collisionne", !collisions.get())
        assertEquals("nombre total de publications incorrect",
            (nbThreads * parThread).toLong(), vues.size.toLong())
        // La derniere generation observee = exactement le nombre total de publications.
        assertEquals((nbThreads * parThread).toLong(), f.latest()!!.generation)
    }

    @Test fun snapshot_publie_est_une_copie_stable_non_affectee_par_les_publications_suivantes() {
        val f = SafetySnapshotFactory()
        val pubT = f.publish(sampleTrue)
        // Publier ensuite un echantillon oppose ne doit PAS alterer le snapshot deja obtenu.
        f.publish(sampleFalse)
        assertTrue(pubT.snapshot.pilotOverride)
        assertTrue(estHomogene(pubT.snapshot))
        assertEquals(1L, pubT.generation)
    }
}
