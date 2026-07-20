package ca.cineflight.stage.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * TEST D'ENDURANCE LONGUE DUREE (soak) de l'arbitre PiloteDrone.
 *
 * Contrairement au #16 de PiloteDroneTempsReelTest (mecanique, mono-thread, tick
 * par reflexion, ~qques secondes), CELUI-CI fait tourner la VRAIE boucle 15 Hz
 * (demarrer/arreterEtAttendre) pendant une longue duree reelle, sous une charge
 * CONCURRENTE soutenue, en verifiant les invariants EN CONTINU :
 *
 *   - aucune valeur illegale ne sort JAMAIS (bornes +-2 / +-60, toujours finie),
 *     verifiee A CHAUD a chaque emission (echec immediat si viole) ;
 *   - pas de blocage : tous les threads se terminent (join borne) ;
 *   - pas de fuite memoire : le tas ne doit pas croitre sans borne sur 15 min ;
 *   - plus AUCUNE emission apres l'arret final.
 *
 * Desactive par defaut (il est long). Activer avec :  -Dsoak=1
 * Duree reglable (defaut 15 min) avec :               -Dsoak.minutes=N
 *
 * Exemple : .\gradlew testDebugUnitTest --tests "*PiloteDroneSoakTest" -Dsoak=1 -i
 */
@RunWith(RobolectricTestRunner::class)
class PiloteDroneSoakTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Faux pont qui NE STOCKE PAS les emissions (sinon 15 min * 15 Hz gonflerait
     * lui-meme la memoire et masquerait une vraie fuite). Il verifie les bornes
     * A CHAUD, compte, et capture seulement la PREMIERE violation eventuelle.
     */
    private class PontSoak : PiloteDrone.PontDji {
        val emissions = AtomicLong(0L)
        @Volatile var violation: String? = null
        val vsDesactive = AtomicBoolean(false)
        @Volatile var dernierOrigin: CommandOrigin? = null
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: CommandOrigin) {
            dernierOrigin = origin
            emissions.incrementAndGet()
            val ok = pitch.isFinite() && pitch in -2f..2f &&
                     roll.isFinite() && roll in -2f..2f &&
                     throttle.isFinite() && throttle in -2f..2f &&
                     yaw.isFinite() && yaw in -60f..60f
            if (!ok && violation == null) {
                violation = "emission illegale: p=$pitch r=$roll t=$throttle y=$yaw"
            }
        }
        override fun activerVirtualStick(actif: Boolean) { if (!actif) vsDesactive.set(true) }
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

    private fun tasUtiliseMo(): Long {
        val rt = Runtime.getRuntime()
        System.gc(); Thread.sleep(60); System.gc(); Thread.sleep(60)
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
    }

    @Test fun soak_boucle_reelle_sous_charge_concurrente() {
        assumeTrue("soak desactive par defaut (relancer avec -Dsoak=1)",
            System.getProperty("soak") == "1")
        // .ifBlank : le build.gradle transmet "" quand -Dsoak.minutes est absent
        // (pas null), donc on retombe sur 15 pour "" comme pour null.
        val minutes = System.getProperty("soak.minutes").orEmpty().ifBlank { "15" }.toLong()
        val dureeMs = minutes * 60_000L
        println("SOAK demarre : duree=$minutes min (~${dureeMs} ms), boucle 15 Hz reelle + charge concurrente")

        val pont = PontSoak()
        val pilote = PiloteDrone(ctx = context, pont = pont)
        val scope = CoroutineScope(Dispatchers.Default)
        val stop = AtomicBoolean(false)
        val sessions = AtomicLong(0L)
        val threads = mutableListOf<Thread>()
        var tasBaseMo = 0L
        var tasMaxMo = 0L

        try {
            pilote.demarrer(scope)                       // la vraie boucle 15 Hz (consommateur unique)

            // producteur NORMAL (YOLO / reseau) : valeurs volontairement hors bornes
            threads += thread(name = "soak-normal") {
                val rnd = Random(1001L)
                while (!stop.get()) {
                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                        t = 0.0,
                        vx = rnd.nextFloat() * 10f - 5f, vy = rnd.nextFloat() * 10f - 5f,
                        vz = rnd.nextFloat() * 10f - 5f, yawRate = rnd.nextFloat() * 300f - 150f,
                        mode = if (rnd.nextInt(12) == 0) "stop" else "actif",
                        recuA = System.currentTimeMillis()))
                    if (rnd.nextInt(4) == 0) Thread.sleep(0, 300_000)
                }
            }
            // producteur SECURITE : prise / rafale de soumissions / liberation, en boucle
            threads += thread(name = "soak-securite") {
                val rnd = Random(2002L)
                while (!stop.get()) {
                    val sid = sessions.incrementAndGet()
                    if (pilote.prendreControleSecurite(sid)) {
                        repeat(1 + rnd.nextInt(12)) {
                            pilote.soumettreSecurite(sid, CommandeCorps(
                                rnd.nextFloat() * 10f - 5f, rnd.nextFloat() * 10f - 5f,
                                rnd.nextFloat() * 10f - 5f, rnd.nextFloat() * 320f - 160f),
                                RaisonSecurite.BALAYAGE_360)
                            if (rnd.nextInt(3) == 0) Thread.sleep(rnd.nextInt(2).toLong())
                        }
                        pilote.libererSecurite(sid)
                    }
                }
            }
            // liberations TARDIVES a mauvais sessionId (callbacks en retard) : sans effet attendu
            threads += thread(name = "soak-liberation-tardive") {
                val rnd = Random(3003L)
                while (!stop.get()) {
                    pilote.libererSecurite(rnd.nextInt(50).toLong())
                    Thread.sleep(0, 500_000)
                }
            }

            // ---- surveillance CONTINUE : bornes + fuite memoire, sur toute la duree ----
            Thread.sleep(5_000)                          // rodage avant la mesure de reference
            tasBaseMo = tasUtiliseMo(); tasMaxMo = tasBaseMo
            val debut = System.nanoTime()
            var prochainRapport = 0L
            while ((System.nanoTime() - debut) / 1_000_000 < dureeMs) {
                assertNull("violation de bornes detectee a chaud : ${pont.violation}", pont.violation)
                Thread.sleep(2_000)
                val ecouleMs = (System.nanoTime() - debut) / 1_000_000
                if (ecouleMs >= prochainRapport) {       // point memoire + progression chaque ~60 s
                    val mo = tasUtiliseMo()
                    if (mo > tasMaxMo) tasMaxMo = mo
                    println("SOAK t=${ecouleMs / 1000}s emissions=${pont.emissions.get()} " +
                            "sessions=${sessions.get()} tas=${mo}Mo (base=${tasBaseMo}Mo max=${tasMaxMo}Mo)")
                    prochainRapport = ecouleMs + 60_000
                }
            }

            stop.set(true)
            threads.forEach { t -> t.join(10_000); assertFalse("deadlock: ${t.name}", t.isAlive) }
            runBlocking { pilote.arreterEtAttendre() }   // arret final synchrone

            // ---- INVARIANT 1 : aucune emission illegale de toute la duree ----
            assertNull("emission illegale pendant le soak : ${pont.violation}", pont.violation)
            // ---- INVARIANT 2 : plus aucune emission apres l'arret final ----
            val apresArret = pont.emissions.get()
            Thread.sleep(500)
            assertEquals("boucle fantome : emission apres arreterEtAttendre",
                apresArret, pont.emissions.get())
            assertTrue("le Virtual Stick doit avoir ete desactive a l'arret", pont.vsDesactive.get())
            // ---- INVARIANT 3 : pas de fuite memoire manifeste ----
            val tasFinMo = tasUtiliseMo()
            val croissanceMo = tasFinMo - tasBaseMo
            println("SOAK termine : ${pont.emissions.get()} emissions, ${sessions.get()} sessions, " +
                    "tas base=${tasBaseMo}Mo fin=${tasFinMo}Mo max=${tasMaxMo}Mo (croissance=${croissanceMo}Mo)")
            assertTrue("fuite memoire probable : le tas a cru de ${croissanceMo} Mo sur $minutes min",
                croissanceMo < 128L)
            // sanity : la boucle a bien tourne longtemps (au moins ~10 emissions/s en moyenne)
            assertTrue("boucle anormalement lente (emissions=${pont.emissions.get()})",
                pont.emissions.get() > minutes * 60L * 10L)
        } finally {
            stop.set(true)
            runBlocking { pilote.arreterEtAttendre() }
            scope.cancel()
        }
    }
}
