package ca.cineflight.stage.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests TEMPS REEL / robustesse complementaires de PiloteDrone :
 *   #7  blocage de l'appel SDK -> pas de deadlock, l'arret reste possible ;
 *   #15 frequence reelle de la boucle ~15 Hz (jamais 30/45) ;
 *   #18 paquet reseau en retard / perime -> hover, jamais reprise d'une vieille commande ;
 *   #16 endurance (~1 M soumissions) activable par -Dendurance=1.
 */
@RunWith(RobolectricTestRunner::class)
class PiloteDroneTempsReelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FauxPont(private val delaiMs: Long = 0L) : PiloteDrone.PontDji {
        val emissions = ConcurrentLinkedQueue<FloatArray>()
        @Volatile var dernierOrigin: CommandOrigin? = null
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: CommandOrigin) {
            dernierOrigin = origin
            if (delaiMs > 0) try { Thread.sleep(delaiMs) } catch (_: InterruptedException) {}
            emissions += floatArrayOf(pitch, roll, throttle, yaw)
        }
        override fun activerVirtualStick(actif: Boolean) {}
        override fun decoller(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun atterrir(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun capDroneDeg(): Float = 0f
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

    private fun forcerActif(p: PiloteDrone, actif: Boolean) {
        val f = PiloteDrone::class.java.getDeclaredField("actif"); f.isAccessible = true
        f.setBoolean(p, actif)
    }
    private fun tick(p: PiloteDrone, maintenant: Long) {
        val m = PiloteDrone::class.java.getDeclaredMethod("tick", java.lang.Long.TYPE)
        m.isAccessible = true; m.invoke(p, maintenant)
    }
    private fun cmd(vz: Float, recuA: Long) =
        RecepteurBridge.CommandeBridge(0.0, 0f, 0f, vz, 0f, "actif", recuA)
    private fun estHover(v: FloatArray) = v[0] == 0f && v[1] == 0f && v[2] == 0f && v[3] == 0f

    // ------------------------------------------------------ #18 paquet en retard / perime
    @Test fun paquet_perime_donne_hover_jamais_reprise() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        forcerActif(p, true)
        p.soumettre(cmd(0.5f, recuA = 1000L))
        tick(p, 1100L)                       // age 100 ms -> fraiche -> Scene
        assertTrue("fraiche -> emise (non hover)", !estHover(pont.emissions.last()))
        // paquet EN RETARD (recuA ancien) ecrase le creneau (latest-wins)
        p.soumettre(cmd(0.9f, recuA = 200L))
        tick(p, 1100L)                       // age 900 ms > 400 -> perime -> hover
        assertTrue("paquet perime -> hover", estHover(pont.emissions.last()))
    }

    // ------------------------------------------------------ #15 frequence ~15 Hz
    @Test fun frequence_boucle_environ_15hz() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            p.demarrer(scope)
            Thread.sleep(2000)               // 2 s
            val n = pont.emissions.size
            runBlocking { p.arreterEtAttendre() }
            // 15 Hz * 2 s ~= 30. Une boucle unique : 18..45. Deux boucles -> ~60 (echoue).
            assertTrue("cadence anormale (attendu ~30, obtenu $n)", n in 18..45)
        } finally { scope.cancel() }
    }

    // ------------------------------------------------------ #7 SDK lent : pas de deadlock
    @Test fun sdk_lent_ne_bloque_pas_l_arret() {
        val pont = FauxPont(delaiMs = 60L)   // chaque emission dure 60 ms
        val p = PiloteDrone(ctx = context, pont = pont)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            p.demarrer(scope)
            Thread.sleep(200)
            val t0 = System.nanoTime()
            runBlocking { p.arreterEtAttendre() }         // doit finir malgre le SDK lent
            val dureeMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("arret bloque trop longtemps: $dureeMs ms", dureeMs < 3000)
            val n = pont.emissions.size
            Thread.sleep(200)
            assertEquals("emission apres l'arret (SDK lent)", n, pont.emissions.size)
        } finally { scope.cancel() }
    }

    // ------------------------------------------------------ #16 endurance (activable)
    @Test fun endurance_longue_activable() {
        assumeTrue("endurance desactivee par defaut (relancer avec -Dendurance=1)",
            System.getProperty("endurance") == "1")
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        forcerActif(p, true)
        val sessions = AtomicLong(0L)
        var horloge = 1000L
        repeat(1_000_000) { i ->
            when (i % 5) {
                0 -> p.soumettre(cmd((i % 7) * 0.1f, horloge))
                1 -> {
                    val s = sessions.incrementAndGet()
                    if (p.prendreControleSecurite(s))
                        p.soumettreSecurite(s, CommandeCorps(0f, 0f, 1f, 0f), RaisonSecurite.MONTEE_ASSISTEE)
                }
                2 -> p.libererSecurite(sessions.get())
                3 -> horloge += 100L
                else -> tick(p, horloge)
            }
        }
        for (v in pont.emissions) {
            assertTrue("hors bornes",
                v[0] in -2f..2f && v[1] in -2f..2f && v[2] in -2f..2f && v[3] in -60f..60f)
        }
        println("ENDURANCE ok : ${pont.emissions.size} emissions, ${sessions.get()} sessions")
    }
}
