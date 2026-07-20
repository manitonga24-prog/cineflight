package ca.cineflight.stage.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Tests de STRESS et de COURSE de l'arbitre PiloteDrone.
 *
 * Objectif : provoquer les courses que l'architecture cherche a eliminer :
 *   - une commande deja lue par un tick emise APRES un arret (generationControle) ;
 *   - un entrelacement arret / emission (verrouEmissionSdk) ;
 *   - une ancienne session qui volerait la propriete d'une nouvelle ;
 *   - une valeur hors bornes qui passerait sous concurrence.
 *
 * Deux styles :
 *   1. un test de stress multi-thread (invariants verifies sur toutes les emissions) ;
 *   2. un test DETERMINISTE de la course tick <-> arret, rendu reproductible par le
 *      crochet interne `avantVerrouEmission` (nul en production).
 *
 * NB bornage : le fait pont retourne cap = 0 (versDji est alors l'identite), donc
 * la sortie Scene reste dans +/-2 comme la sortie Corps. A cap != 0, la rotation
 * Scene->corps peut legitimement atteindre +/-2*racine(2) : ce n'est pas teste ici
 * (couvert par les tests unitaires de traduction).
 */
@RunWith(RobolectricTestRunner::class)
class PiloteDroneStressTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ faux pont
    private class FauxPontDji : PiloteDrone.PontDji {
        data class Emission(
            val numero: Long,
            val timestampNs: Long,
            val thread: String,
            val pitch: Float, val roll: Float, val throttle: Float, val yaw: Float
        )
        val emissions = ConcurrentLinkedQueue<Emission>()
        val activations = ConcurrentLinkedQueue<Boolean>()
        private val compteur = AtomicLong(0L)
        @Volatile var dernierOrigin: CommandOrigin? = null
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: CommandOrigin) {
            dernierOrigin = origin
            emissions += Emission(
                compteur.incrementAndGet(), System.nanoTime(),
                Thread.currentThread().name, pitch, roll, throttle, yaw
            )
        }
        override fun activerVirtualStick(actif: Boolean) { activations += actif }
        override fun decoller(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun atterrir(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun capDroneDeg(): Float = 0f          // identite Scene->corps
        override fun batteriePourcent(): Int = 100
        override fun estConnecte(): Boolean = true
        override fun latitudeDrone(): Double = 0.0
        override fun longitudeDrone(): Double = 0.0
        override fun altitudeDrone(): Double = 0.0
        override fun gpsValide(): Boolean = true
        override fun orienterNacelle(pitchDeg: Float, yawDeg: Float, yawAbsolu: Boolean) {}
        override fun demarrerEnregistrement() {}
        override fun arreterEnregistrement() {}
        override fun enregistreEnCours(): Boolean = false
        override fun modeleDrone(): String = "TEST"
    }

    // --- reflexion : tick() prive + drapeau actif prive (tests uniquement) ---
    private fun forcerActif(p: PiloteDrone, actif: Boolean) {
        val f = PiloteDrone::class.java.getDeclaredField("actif"); f.isAccessible = true
        f.setBoolean(p, actif)
    }
    private fun tick(p: PiloteDrone, maintenant: Long) {
        val m = PiloteDrone::class.java.getDeclaredMethod("tick", java.lang.Long.TYPE)
        m.isAccessible = true; m.invoke(p, maintenant)
    }

    private fun estNul(e: FauxPontDji.Emission) =
        e.pitch == 0f && e.roll == 0f && e.throttle == 0f && e.yaw == 0f

    // =====================================================================
    //  1. STRESS multi-thread : bornes toujours respectees + silence apres arret
    // =====================================================================
    @Test fun stress_concurrence_bornes_et_arret() {
        val seedBase = 20260714L        // graine FIXE -> reproductible
        val iterations = 12             // CI. Augmenter (+ dureeMs) pour un run local lourd.
        val dureeMs = 120L

        for (it in 0 until iterations) {
            val seed = seedBase + it
            val rnd = Random(seed)
            val pont = FauxPontDji()
            val pilote = PiloteDrone(ctx = context, pont = pont)
            val scope = CoroutineScope(Dispatchers.Default)
            val stop = AtomicBoolean(false)
            val sessions = AtomicLong(0L)
            val threads = mutableListOf<Thread>()

            pilote.demarrer(scope)      // vraie boucle 15 Hz (consommateur)

            // producteur NORMAL (YOLO / reseau), valeurs volontairement hors bornes
            threads += thread(name = "normal") {
                while (!stop.get()) {
                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                        t = 0.0,
                        vx = rnd.nextFloat() * 8f - 4f, vy = rnd.nextFloat() * 8f - 4f,
                        vz = rnd.nextFloat() * 8f - 4f, yawRate = rnd.nextFloat() * 200f - 100f,
                        mode = if (rnd.nextInt(10) == 0) "stop" else "actif",
                        recuA = System.currentTimeMillis()))
                    if (rnd.nextInt(3) == 0) Thread.sleep(0, rnd.nextInt(400_000))
                }
            }
            // producteur SECURITE (prise / soumissions / liberation)
            threads += thread(name = "securite") {
                while (!stop.get()) {
                    val sid = sessions.incrementAndGet()
                    if (pilote.prendreControleSecurite(sid)) {
                        repeat(1 + rnd.nextInt(9)) {
                            pilote.soumettreSecurite(sid, CommandeCorps(
                                rnd.nextFloat() * 8f - 4f, rnd.nextFloat() * 8f - 4f,
                                rnd.nextFloat() * 8f - 4f, rnd.nextFloat() * 240f - 120f),
                                RaisonSecurite.BALAYAGE_360)
                            if (rnd.nextInt(3) == 0) Thread.sleep(rnd.nextInt(3).toLong())
                        }
                        pilote.libererSecurite(sid)
                    }
                }
            }
            // liberations TARDIVES avec de mauvais sessionId (callbacks en retard)
            threads += thread(name = "liberation-tardive") {
                while (!stop.get()) {
                    pilote.libererSecurite(rnd.nextInt(6).toLong())
                    Thread.sleep(0, 200_000)
                }
            }
            // CHAOS : arret synchrone puis redemarrage (churn generation + cycle de vie)
            threads += thread(name = "chaos-arret") {
                while (!stop.get()) {
                    Thread.sleep(1 + rnd.nextInt(8).toLong())
                    runBlocking { pilote.arreterEtAttendre() }
                    if (!stop.get()) pilote.demarrer(scope)
                }
            }

            Thread.sleep(dureeMs)
            stop.set(true)
            threads.forEach { t -> t.join(5000); assertFalse("seed=$seed deadlock: ${t.name}", t.isAlive) }
            runBlocking { pilote.arreterEtAttendre() }   // arret final garanti

            // ---- INVARIANT 1 : bornes TOUJOURS respectees, quelle que soit la course
            for (e in pont.emissions) {
                assertTrue("seed=$seed pitch hors bornes: ${e.pitch}", e.pitch in -2f..2f)
                assertTrue("seed=$seed roll hors bornes: ${e.roll}", e.roll in -2f..2f)
                assertTrue("seed=$seed throttle hors bornes: ${e.throttle}", e.throttle in -2f..2f)
                assertTrue("seed=$seed yaw hors bornes: ${e.yaw}", e.yaw in -60f..60f)
            }
            // ---- INVARIANT 2 : plus AUCUNE emission apres l'arret final
            val n = pont.emissions.size
            Thread.sleep(60)
            assertEquals("seed=$seed : emission apres arreterEtAttendre", n, pont.emissions.size)

            scope.cancel()
        }
    }

    // =====================================================================
    //  2. COURSE DETERMINISTE tick <-> arret (neutralisee par generationControle)
    // =====================================================================
    @Test fun course_tick_puis_arret_ne_laisse_passer_aucune_commande() {
        val pont = FauxPontDji()
        val pilote = PiloteDrone(ctx = context, pont = pont)
        // commande normale NON NULLE et fraiche : c'est elle qui NE doit PAS passer
        pilote.soumettre(RecepteurBridge.CommandeBridge(
            t = 0.0, vx = 0f, vy = 1.5f, vz = 0f, yawRate = 0f,
            mode = "actif", recuA = System.currentTimeMillis()))
        forcerActif(pilote, true)

        val tickEnPause = CountDownLatch(1)
        val libererTick = CountDownLatch(1)
        val premier = AtomicBoolean(true)
        // crochet : le 1er tick se fige juste apres avoir lu gen + choisi sa sortie,
        // AVANT le verrou d'emission.
        pilote.avantVerrouEmission = {
            if (premier.compareAndSet(true, false)) {
                tickEnPause.countDown()
                libererTick.await()
            }
        }

        val t = thread(name = "tick-engage") { tick(pilote, System.currentTimeMillis()) }

        tickEnPause.await()          // le tick est fige, commande non nulle deja choisie
        pilote.arretUrgence()        // change la generation + actif=false + hover sous verrou
        libererTick.countDown()      // on libere le tick : il re-verifie la generation
        t.join(5000)
        assertFalse("deadlock tick", t.isAlive)

        // le tick a re-verifie la generation -> differente -> il N'EMET PAS.
        assertFalse(
            "un tick deja engage ne doit JAMAIS emettre apres un changement de generation",
            pont.emissions.any { !estNul(it) })
        assertTrue("l'arret d'urgence doit avoir emis un hover",
            pont.emissions.any { estNul(it) })
        assertTrue("le Virtual Stick doit avoir ete desactive",
            pont.activations.contains(false))
    }

    // =====================================================================
    //  3. demarrer() et arreterEtAttendre() concurrents : pas de deadlock,
    //     pas de boucle fantome qui emet apres l'arret.
    // =====================================================================
    @Test fun demarrer_et_arreterEtAttendre_concurrents() {
        repeat(50) {
            val pont = FauxPontDji()
            val pilote = PiloteDrone(ctx = context, pont = pont)
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                val t1 = thread(name = "demarrer") { pilote.demarrer(scope) }
                val t2 = thread(name = "arret") { runBlocking { pilote.arreterEtAttendre() } }
                t1.join(5000); t2.join(5000)
                assertFalse("deadlock demarrer", t1.isAlive)
                assertFalse("deadlock arret", t2.isAlive)

                runBlocking { pilote.arreterEtAttendre() }   // arret final
                val n = pont.emissions.size
                Thread.sleep(40)
                assertEquals("boucle fantome apres arret", n, pont.emissions.size)
            } finally {
                scope.cancel()
            }
        }
    }
}
