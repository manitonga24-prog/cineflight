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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests de ROBUSTESSE de l'arbitre PiloteDrone : panne SDK, valeurs non finies,
 * idempotence, double demarrage, callback tardif, preservation exacte.
 *
 * Valident notamment les deux durcissements de emettreSdk :
 *   - NaN / +-Infinity forces a 0 avant le SDK (coerceIn ne corrige pas NaN) ;
 *   - toute exception SDK capturee (la boucle 15 Hz ne meurt jamais).
 *
 * tick() prive + drapeau actif prive atteints par reflexion (tests uniquement) ;
 * aucune modification de production requise pour ces tests.
 */
@RunWith(RobolectricTestRunner::class)
class PiloteDroneRobustesseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FauxPont : PiloteDrone.PontDji {
        data class Vitesse(val pitch: Float, val roll: Float, val throttle: Float, val yaw: Float)
        val emissions = ConcurrentLinkedQueue<Vitesse>()
        val doitLever = AtomicBoolean(false)     // simule une panne SDK (exception)
        @Volatile var dernierOrigin: CommandOrigin? = null
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: CommandOrigin) {
            dernierOrigin = origin
            if (doitLever.get()) throw RuntimeException("panne SDK simulee")
            emissions += Vitesse(pitch, roll, throttle, yaw)
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
    private fun cmd(vx: Float = 0f, vy: Float = 0f, vz: Float = 0f, yawRate: Float = 0f,
                    recuA: Long = 0L) =
        RecepteurBridge.CommandeBridge(0.0, vx, vy, vz, yawRate, "actif", recuA)
    private fun derniere(p: FauxPont) = p.emissions.last()

    // ------------------------------------------------------------------ #1 panne SDK
    @Test fun panne_sdk_ne_tue_pas_la_boucle() {
        val pont = FauxPont(); val pilote = PiloteDrone(ctx = context, pont = pont)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            pont.doitLever.set(true)           // le SDK leve a chaque emission
            pilote.demarrer(scope)
            Thread.sleep(150)                  // plusieurs ticks : exceptions toutes captees
            // la boucle doit AVOIR SURVECU : on retire la panne, elle doit re-emettre
            pont.doitLever.set(false)
            pont.emissions.clear()
            Thread.sleep(150)
            assertTrue("la boucle 15 Hz doit survivre aux exceptions SDK",
                pont.emissions.isNotEmpty())
        } finally {
            runBlocking { pilote.arreterEtAttendre() }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------ #8 NaN / Infini
    @Test fun nan_et_infini_jamais_transmis_au_sdk() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN),
            RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(p, true)
        tick(p, 1L)
        val e = derniere(pont)
        // aucune valeur non finie ne doit atteindre le SDK
        assertTrue("pitch fini", e.pitch.isFinite())
        assertTrue("roll fini", e.roll.isFinite())
        assertTrue("throttle fini", e.throttle.isFinite())
        assertTrue("yaw fini", e.yaw.isFinite())
        // NaN -> 0 ; +Inf -> borne +2 ; -Inf -> borne -2 ; NaN -> 0
        assertEquals(FauxPont.Vitesse(0f, 2f, -2f, 0f), e)
    }

    // ------------------------------------------------------------------ #6 idempotence
    @Test fun arret_et_liberation_idempotents() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        forcerActif(p, true)
        p.arretUrgence(); p.arretUrgence()                 // 2x : pas d'exception
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.libererSecurite(1L))
        assertFalse("2e liberation de la meme session -> false", p.libererSecurite(1L))
        runBlocking { p.arreterEtAttendre(); p.arreterEtAttendre() }   // 2x : pas d'exception
        // etat coherent : on peut reprendre une session propre
        assertTrue("apres arrets repetes, une nouvelle session reste possible",
            p.prendreControleSecurite(2L))
    }

    // ------------------------------------------------------------------ #5 double demarrer
    @Test fun double_demarrer_une_seule_boucle() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            p.demarrer(scope); p.demarrer(scope); p.demarrer(scope)   // idempotent
            Thread.sleep(500)
            val n = pont.emissions.size
            // une seule boucle ~15 Hz -> ~7-8 emissions en 500 ms. Deux boucles -> ~15.
            assertTrue("boucle inactive (n=$n)", n >= 2)
            assertTrue("cadence doublee = plusieurs boucles (n=$n)", n <= 11)
        } finally {
            runBlocking { p.arreterEtAttendre() }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------ #12 callback tardif
    @Test fun callback_tardif_ancienne_session_ignore() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.libererSecurite(1L))
        assertTrue(p.prendreControleSecurite(2L))          // proprietaire courant = 2
        // callback tardif de la session 1 : refuse et sans effet
        assertFalse(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 1.0f, 0f),
            RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(p, true)
        tick(p, 1L)
        assertEquals("l'ancienne session ne doit rien injecter",
            FauxPont.Vitesse(0f, 0f, 0f, 0f), derniere(pont))
    }

    // ------------------------------------------------------------------ #9 valeurs distinctes
    @Test fun valeurs_corps_distinctes_preservees_exactement() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L,
            CommandeCorps(0.31f, -0.47f, 0.83f, -17.5f), RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(p, true)
        tick(p, 1L)
        // detecte inversion pitch/roll, changement de signe, permutation, passage versDji
        assertEquals(FauxPont.Vitesse(0.31f, -0.47f, 0.83f, -17.5f), derniere(pont))
    }
}
