package ca.cineflight.stage.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TEST PAR PROPRIETES (model-based) de l'arbitre PiloteDrone.
 *
 * On genere des dizaines de milliers de sequences aleatoires d'evenements
 * (commande normale, prise/soumission/liberation de securite, timeout, STOP,
 * redemarrage, deconnexion/reconnexion) et on maintient EN PARALLELE un modele
 * de reference. Apres CHAQUE tick, on verifie que la sortie reelle correspond au
 * modele, et donc les invariants :
 *
 *   - au plus un proprietaire securite (le modele n'a qu'un owner ; prise refusee
 *     si deja pris) ;
 *   - aucune sortie NORMALE pendant la propriete securite (owner != 0 -> sortie
 *     securite ou hover, jamais la commande normale) ;
 *   - aucune sortie hors limites (+-2 / +-60) ni non finie ;
 *   - timeout normal -> hover (jamais reprise d'une ancienne commande) ;
 *   - STOP vide les deux creneaux + la propriete (ticks suivants = hover) ;
 *   - aucune ancienne / mauvaise session acceptee.
 *
 * Deterministe (mono-thread, ticks par reflexion, graine fixe) => tout echec est
 * reproductible via la valeur seed affichee dans le message.
 *
 * (Le "<=1 boucle" et les vraies courses sont couverts par PiloteDroneStressTest.)
 */
@RunWith(RobolectricTestRunner::class)
class PiloteDroneProprieteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FauxPont : PiloteDrone.PontDji {
        data class Vitesse(val pitch: Float, val roll: Float, val throttle: Float, val yaw: Float)
        val emissions = ConcurrentLinkedQueue<Vitesse>()
        val doitLever = AtomicBoolean(false)
        @Volatile var dernierOrigin: CommandOrigin? = null
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: CommandOrigin) {
            dernierOrigin = origin
            if (doitLever.get()) throw RuntimeException("deconnexion simulee")
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

    // handles de reflexion mis en cache (75 000 ticks : on evite un lookup a chaque fois)
    private val champActif = PiloteDrone::class.java.getDeclaredField("actif").apply { isAccessible = true }
    private val methTick = PiloteDrone::class.java.getDeclaredMethod("tick", java.lang.Long.TYPE).apply { isAccessible = true }
    private fun forcerActif(p: PiloteDrone, actif: Boolean) = champActif.setBoolean(p, actif)
    private fun tick(p: PiloteDrone, maintenant: Long) { methTick.invoke(p, maintenant) }

    private data class NormCmd(val vx: Float, val vy: Float, val vz: Float,
                               val yawRate: Float, val mode: String, val recuA: Long)

    private val HOVER = FauxPont.Vitesse(0f, 0f, 0f, 0f)
    private fun cl(v: Float, lim: Float) = v.coerceIn(-lim, lim)
    private fun bornerCorps(v: FauxPont.Vitesse) =
        FauxPont.Vitesse(cl(v.pitch, 2f), cl(v.roll, 2f), cl(v.throttle, 2f), cl(v.yaw, 60f))

    // sortie attendue du CHEMIN NORMAL (cap 0 : pitch=vz, roll=vx, throttle=vy, yaw=yawRate)
    private fun prevoirNormal(nc: NormCmd?, horloge: Long): FauxPont.Vitesse {
        if (nc == null || nc.mode != "actif" || (horloge - nc.recuA) > 400L) return HOVER
        return FauxPont.Vitesse(cl(nc.vz, 2f), cl(nc.vx, 2f), cl(nc.vy, 2f), cl(nc.yawRate, 60f))
    }

    private fun rf(rnd: Random, ampl: Float) = rnd.nextFloat() * (2f * ampl) - ampl  // fini

    @Test fun proprietes_sequences_aleatoires() {
        val seedBase = 424242L
        val iterations = 3000
        val evenementsParIter = 25
        println("PROPRIETE seedBase=$seedBase iterations=$iterations events=$evenementsParIter " +
                "(~${iterations * evenementsParIter} evenements)")

        for (iter in 0 until iterations) {
            val seed = seedBase + iter
            val rnd = Random(seed)
            val pont = FauxPont()
            val p = PiloteDrone(ctx = context, pont = pont)
            forcerActif(p, true)

            // ----- modele -----
            var owner = 0L
            var secCmd: FauxPont.Vitesse? = null    // derniere consigne securite (raw)
            var norm: NormCmd? = null
            var horloge = 1000L
            var actif = true
            var connecte = true
            var compteurSession = 0L

            repeat(evenementsParIter) {
                when (rnd.nextInt(10)) {
                    0 -> { // COMMANDE_NORMALE
                        val nc = NormCmd(rf(rnd, 4f), rf(rnd, 4f), rf(rnd, 4f), rf(rnd, 100f),
                            if (rnd.nextInt(8) == 0) "stop" else "actif", horloge)
                        p.soumettre(RecepteurBridge.CommandeBridge(
                            0.0, nc.vx, nc.vy, nc.vz, nc.yawRate, nc.mode, nc.recuA))
                        norm = nc
                    }
                    1 -> { // PRISE_SECURITE
                        val sid = ++compteurSession
                        val r = p.prendreControleSecurite(sid)
                        assertEquals("seed=$seed prise: retour", owner == 0L, r)
                        if (r) { owner = sid; secCmd = null; norm = null }
                    }
                    2 -> { // COMMANDE_SECURITE (bonne session)
                        if (owner != 0L) {
                            val sc = FauxPont.Vitesse(rf(rnd, 4f), rf(rnd, 4f), rf(rnd, 4f), rf(rnd, 120f))
                            val r = p.soumettreSecurite(owner,
                                CommandeCorps(sc.pitch, sc.roll, sc.throttle, sc.yaw),
                                RaisonSecurite.BALAYAGE_360)
                            assertTrue("seed=$seed soumission bonne session", r)
                            secCmd = sc
                        }
                    }
                    3 -> { // COMMANDE_SECURITE (mauvaise session) : toujours refusee, sans effet
                        val bad = owner + 1L + rnd.nextInt(1000)
                        val r = p.soumettreSecurite(bad, CommandeCorps(1f, 1f, 1f, 1f),
                            RaisonSecurite.INTRUSION)
                        assertFalse("seed=$seed mauvaise session acceptee", r)
                    }
                    4 -> { // LIBERATION (bonne session)
                        if (owner != 0L) {
                            val r = p.libererSecurite(owner)
                            assertTrue("seed=$seed liberation bonne session", r)
                            owner = 0L; secCmd = null; norm = null
                        }
                    }
                    5 -> { // LIBERATION (mauvaise session) : refusee, proprietaire inchange
                        val bad = owner + 1L + rnd.nextInt(1000)
                        val r = p.libererSecurite(bad)
                        assertFalse("seed=$seed liberation mauvaise session acceptee", r)
                    }
                    6 -> { // TIMEOUT : avance l'horloge au-dela du timeout normal
                        horloge += 500L
                    }
                    7 -> { // STOP (arreter : meme teardown que arretUrgence, sans Log)
                        p.arreter()
                        owner = 0L; secCmd = null; norm = null; actif = false
                    }
                    8 -> { // REDEMARRAGE
                        forcerActif(p, true); actif = true
                    }
                    9 -> { // DECONNEXION / RECONNEXION
                        connecte = !connecte
                        pont.doitLever.set(!connecte)
                    }
                }

                // ---- TICK + verification contre le modele ----
                val avant = pont.emissions.size
                tick(p, horloge)
                when {
                    !actif -> assertEquals("seed=$seed tick inactif ne doit rien emettre",
                        avant, pont.emissions.size)
                    !connecte -> assertEquals("seed=$seed tick deconnecte ne doit rien emettre",
                        avant, pont.emissions.size)
                    else -> {
                        assertEquals("seed=$seed tick doit emettre 1 fois", avant + 1, pont.emissions.size)
                        val emise = pont.emissions.last()
                        if (owner != 0L) {
                            // sous propriete securite : sortie securite (fraiche) OU hover, JAMAIS normale
                            val attenduSec = secCmd?.let { bornerCorps(it) }
                            assertTrue(
                                "seed=$seed sortie invalide sous securite: $emise (attendu $attenduSec ou hover)",
                                emise == attenduSec || emise == HOVER)
                        } else {
                            assertEquals("seed=$seed chemin normal != modele",
                                prevoirNormal(norm, horloge), emise)
                        }
                    }
                }
            }

            // ---- invariant global : toute emission dans les bornes et finie ----
            for (e in pont.emissions) {
                assertTrue("seed=$seed pitch $e", e.pitch.isFinite() && e.pitch in -2f..2f)
                assertTrue("seed=$seed roll $e", e.roll.isFinite() && e.roll in -2f..2f)
                assertTrue("seed=$seed throttle $e", e.throttle.isFinite() && e.throttle in -2f..2f)
                assertTrue("seed=$seed yaw $e", e.yaw.isFinite() && e.yaw in -60f..60f)
            }
        }
    }
}
