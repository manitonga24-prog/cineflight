package ca.cineflight.stage.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ca.cineflight.stage.sentinelle.PontPiloteSecuriteAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests de l'ARBITRE PiloteDrone (etapes 1-3 du refactor securite).
 *
 * Couvre le contrat gele de l'API : arbitrage securite > normal, bornage de
 * garde, consigne perimee -> hover, propriete de session, preservation exacte
 * des valeurs Corps (aucune conversion Scene), reroutage de l'adaptateur
 * Sentinelle, et arret synchrone (boucle reelle terminee).
 *
 * La boucle 15 Hz n'est en general PAS demarree : on appelle directement la
 * methode privee tick() par reflexion apres avoir force "actif", ce qui rend
 * chaque cas deterministe. Deux tests demarrent la vraie boucle pour verifier
 * l'arret synchrone. AUCUNE modification du code de production n'est requise.
 *
 * android.util.Log n'est jamais declenche (seul arretUrgence() logge, non teste
 * ici ; arreter() a le meme teardown sans log).
 */
@RunWith(RobolectricTestRunner::class)
class PiloteDroneTest {

    // Context Android reel fourni par Robolectric (PiloteDrone appelle ctx.getString).
    private val context: Context = ApplicationProvider.getApplicationContext()

    // --- Faux pont DJI : enregistre les emissions, ne touche aucun vrai drone. ---
    private class FauxPont : PiloteDrone.PontDji {
        data class Vitesse(val pitch: Float, val roll: Float, val throttle: Float, val yaw: Float)
        // thread-safe : la boucle reelle (Dispatchers.Default) peut ecrire pendant
        // que le thread de test lit.
        val emissions = CopyOnWriteArrayList<Vitesse>()
        var vsActif: Boolean? = null
        var cap: Float = 0f                 // cap drone simule (pour prouver Corps != Scene)
        var nbOrienterNacelle: Int = 0
        // Derniere origine recue (nouvelle semantique ObstacleSafetyGate) : les tests
        // peuvent ainsi rester conscients de l'origine des commandes.
        @Volatile var dernierOrigin: CommandOrigin? = null
        override fun activerVirtualStick(actif: Boolean) { vsActif = actif }
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: CommandOrigin) {
            dernierOrigin = origin
            emissions.add(Vitesse(pitch, roll, throttle, yaw))
        }
        override fun decoller(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun atterrir(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun capDroneDeg(): Float = cap
        override fun batteriePourcent(): Int = 100
        override fun estConnecte(): Boolean = true
        override fun latitudeDrone(): Double = 0.0
        override fun longitudeDrone(): Double = 0.0
        override fun altitudeDrone(): Double = 0.0
        override fun gpsValide(): Boolean = true
        override fun orienterNacelle(pitchDeg: Float, yawDeg: Float, yawAbsolu: Boolean) { nbOrienterNacelle++ }
        override fun demarrerEnregistrement() {}
        override fun arreterEnregistrement() {}
        override fun enregistreEnCours(): Boolean = false
        override fun modeleDrone(): String = "TEST"
    }

    private val HOVER = FauxPont.Vitesse(0f, 0f, 0f, 0f)

    // --- Acces aux internals prives, UNIQUEMENT pour les tests (reflexion). ---
    private fun forcerActif(p: PiloteDrone, actif: Boolean) {
        val f = PiloteDrone::class.java.getDeclaredField("actif")
        f.isAccessible = true
        f.setBoolean(p, actif)
    }

    private fun tick(p: PiloteDrone, maintenant: Long) {
        val m = PiloteDrone::class.java.getDeclaredMethod("tick", java.lang.Long.TYPE)
        m.isAccessible = true
        m.invoke(p, maintenant)
    }

    private fun champBoucle(p: PiloteDrone): Any? {
        val f = PiloteDrone::class.java.getDeclaredField("boucle")
        f.isAccessible = true
        return f.get(p)
    }

    private fun cmdNormale(
        vx: Float = 0f, vy: Float = 0f, vz: Float = 0f, yawRate: Float = 0f,
        mode: String = "actif", recuA: Long = 0L
    ) = RecepteurBridge.CommandeBridge(
        t = 0.0, vx = vx, vy = vy, vz = vz, yawRate = yawRate, mode = mode, recuA = recuA
    )

    private fun derniere(p: FauxPont) = p.emissions.last()

    // =====================================================================
    //  PROPRIETE DE SESSION (prendre / soumettre / liberer)
    // =====================================================================

    @Test fun prendreControle_deuxieme_session_refusee() {
        val p = PiloteDrone(ctx = context, pont = FauxPont())
        assertTrue(p.prendreControleSecurite(1L))
        assertFalse(
            "une 2e session ne peut pas prendre un controle deja pris",
            p.prendreControleSecurite(2L)
        )
    }

    @Test fun soumettreSecurite_rejete_si_pas_proprietaire() {
        val p = PiloteDrone(ctx = context, pont = FauxPont())
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 1f, 0f), RaisonSecurite.MONTEE_ASSISTEE))
        assertFalse(
            "une session non proprietaire ne peut pas soumettre",
            p.soumettreSecurite(2L, CommandeCorps(0f, 0f, 1f, 0f), RaisonSecurite.MONTEE_ASSISTEE)
        )
    }

    @Test fun liberer_puis_reprise_possible() {
        val p = PiloteDrone(ctx = context, pont = FauxPont())
        assertTrue(p.prendreControleSecurite(1L))
        assertFalse("liberer avec une mauvaise session echoue", p.libererSecurite(2L))
        assertTrue(p.libererSecurite(1L))
        assertTrue(
            "apres liberation, une nouvelle session peut reprendre",
            p.prendreControleSecurite(3L)
        )
    }

    @Test fun ancienne_session_qui_libere_en_retard_ne_vole_pas_la_propriete() {
        val p = PiloteDrone(ctx = context, pont = FauxPont())
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.libererSecurite(1L))          // session 1 terminee
        assertTrue(p.prendreControleSecurite(2L))  // session 2 active
        // callback tardif de la session 1 : ne doit PAS liberer la session 2
        assertFalse(p.libererSecurite(1L))
        assertFalse("la session 2 reste proprietaire", p.prendreControleSecurite(3L))
    }

    // =====================================================================
    //  CHEMIN NORMAL (aucun proprietaire de securite)
    // =====================================================================

    @Test fun sans_commande_hover() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        forcerActif(p, true)
        tick(p, 1000L)
        assertEquals(HOVER, derniere(pont))
    }

    @Test fun commande_perimee_hover() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vz = 0.5f, recuA = 1000L))
        forcerActif(p, true)
        tick(p, 1500L)   // 500 ms > timeoutHoverMs (400) -> perimee -> hover
        assertEquals(HOVER, derniere(pont))
    }

    @Test fun commande_fraiche_emise_et_traduite() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vx = 0.1f, vy = 0.3f, vz = 0.5f, yawRate = 10f, recuA = 1000L))
        forcerActif(p, true)
        tick(p, 1100L)   // 100 ms < 400 -> fraiche
        val d = TraductionAxes.versDji(0.1f, 0.3f, 0.5f, 10f, 0f)
        assertEquals(FauxPont.Vitesse(d.pitch, d.roll, d.verticalThrottle, d.yaw), derniere(pont))
    }

    @Test fun un_seul_envoi_par_tick() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vz = 0.5f, recuA = 1000L))
        forcerActif(p, true)
        pont.emissions.clear()
        tick(p, 1100L)
        assertEquals("un tick = exactement un envoi SDK", 1, pont.emissions.size)
    }

    // =====================================================================
    //  ARBITRAGE SECURITE > NORMAL
    // =====================================================================

    @Test fun securite_prioritaire_sur_normale() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vz = 0.5f, recuA = 1000L))   // consigne normale fraiche
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 1.0f, 0f), RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(p, true)
        tick(p, 1100L)
        assertEquals(
            "la manoeuvre securite doit primer sur la commande normale",
            FauxPont.Vitesse(0f, 0f, 1.0f, 0f), derniere(pont)
        )
    }

    @Test fun securite_bornee_aux_limites_de_garde() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(5f, -5f, 5f, 200f), RaisonSecurite.BALAYAGE_360))
        forcerActif(p, true)
        tick(p, 1L)
        // bornage : +/-2 m/s (pitch/roll/throttle), +/-60 deg/s (yaw)
        assertEquals(FauxPont.Vitesse(2f, -2f, 2f, 60f), derniere(pont))
    }

    @Test fun prise_securite_efface_la_commande_normale() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vz = 0.5f, recuA = 1000L))
        assertTrue(p.prendreControleSecurite(1L))   // doit effacer la commande normale
        forcerActif(p, true)
        // proprietaire pris mais AUCUNE consigne securite -> hover (jamais l'ancienne normale)
        tick(p, 1100L)
        assertEquals(HOVER, derniere(pont))
    }

    @Test fun liberation_efface_la_commande_normale() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vz = 0.5f, recuA = 1000L))
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 1f, 0f), RaisonSecurite.MONTEE_ASSISTEE))
        assertTrue(p.libererSecurite(1L))   // efface normale + securite
        forcerActif(p, true)
        tick(p, 1100L)                       // normale effacee -> hover
        assertEquals(HOVER, derniere(pont))
    }

    @Test fun securite_perimee_donne_hover_sans_liberer() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 1f, 0f), RaisonSecurite.MONTEE_ASSISTEE))
        Thread.sleep(450L)   // laisse la consigne se perimer (TIMEOUT_SECURITE_NS = 400 ms)
        forcerActif(p, true)
        tick(p, 1L)
        assertEquals("consigne securite perimee -> hover", HOVER, derniere(pont))
        assertFalse(
            "une consigne perimee ne doit PAS liberer la propriete",
            p.prendreControleSecurite(2L)
        )
    }

    // =====================================================================
    //  PRESERVATION EXACTE DES VALEURS CORPS (aucune conversion Scene)
    // =====================================================================

    @Test fun securite_montee_valeurs_preservees() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 1.0f, 0f), RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(p, true)
        tick(p, 1L)
        assertEquals("montee Sentinelle : sortie SDK identique a l'entree",
            FauxPont.Vitesse(0f, 0f, 1.0f, 0f), derniere(pont))
    }

    @Test fun securite_balayage_valeurs_preservees() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        assertTrue(p.prendreControleSecurite(1L))
        assertTrue(p.soumettreSecurite(1L, CommandeCorps(0f, 0f, 0f, 20f), RaisonSecurite.BALAYAGE_360))
        forcerActif(p, true)
        tick(p, 1L)
        assertEquals("balayage 360 : sortie SDK identique a l'entree",
            FauxPont.Vitesse(0f, 0f, 0f, 20f), derniere(pont))
    }

    @Test fun securite_corps_ne_depend_pas_du_cap_donc_pas_de_versDji() {
        // Si le chemin Corps passait par versDji, la sortie dependrait du cap du
        // drone (rotation Scene->corps). On prouve l'inverse : meme consigne Corps,
        // deux caps differents -> MEME sortie. Donc aucune conversion Scene.
        val axes = CommandeCorps(0.5f, 0.3f, 0f, 0f)

        val pontA = FauxPont().apply { cap = 0f }
        val pA = PiloteDrone(ctx = context, pont = pontA)
        assertTrue(pA.prendreControleSecurite(1L))
        assertTrue(pA.soumettreSecurite(1L, axes, RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(pA, true); tick(pA, 1L)

        val pontB = FauxPont().apply { cap = 90f }
        val pB = PiloteDrone(ctx = context, pont = pontB)
        assertTrue(pB.prendreControleSecurite(1L))
        assertTrue(pB.soumettreSecurite(1L, axes, RaisonSecurite.MONTEE_ASSISTEE))
        forcerActif(pB, true); tick(pB, 1L)

        assertEquals("Corps : sortie independante du cap (aucun versDji)",
            derniere(pontA), derniere(pontB))
        assertEquals(FauxPont.Vitesse(0.5f, 0.3f, 0f, 0f), derniere(pontB))
    }

    // =====================================================================
    //  INTEGRATION : adaptateur Sentinelle -> arbitre (ecrivain unique)
    // =====================================================================

    @Test fun adapter_route_vers_soumettreSecurite_valeurs_intactes() {
        val pont = FauxPont(); val pilote = PiloteDrone(ctx = context, pont = pont)
        assertTrue(pilote.prendreControleSecurite(7L))
        val adapter = PontPiloteSecuriteAdapter(pilote, 7L)
        // Double -> Float, ordre et signes intacts, une seule soumission.
        adapter.envoyerVitesses(0.0, 0.0, 1.0, 20.0)
        forcerActif(pilote, true)
        pont.emissions.clear()
        tick(pilote, 1L)
        assertEquals("un seul envoi SDK via l'arbitre", 1, pont.emissions.size)
        assertEquals(FauxPont.Vitesse(0f, 0f, 1.0f, 20f), derniere(pont))
    }

    @Test fun adapter_session_incorrecte_est_ignoree() {
        val pont = FauxPont(); val pilote = PiloteDrone(ctx = context, pont = pont)
        assertTrue(pilote.prendreControleSecurite(7L))       // proprietaire = 7
        val adapterIntrus = PontPiloteSecuriteAdapter(pilote, 999L)
        adapterIntrus.envoyerVitesses(0.0, 0.0, 1.0, 0.0)    // 999 != 7 -> ignore
        forcerActif(pilote, true)
        tick(pilote, 1L)
        assertEquals("une session non proprietaire ne doit rien emettre", HOVER, derniere(pont))
    }

    @Test fun adapter_orienterNacelle_ne_touche_pas_le_pont() {
        val pont = FauxPont(); val pilote = PiloteDrone(ctx = context, pont = pont)
        val adapter = PontPiloteSecuriteAdapter(pilote, 1L)
        adapter.orienterNacelle(-30.0, 10.0, true)   // no-op volontaire
        assertEquals("la Sentinelle ne commande jamais la nacelle", 0, pont.nbOrienterNacelle)
    }

    // =====================================================================
    //  ARRET (teardown + arret synchrone reel)
    // =====================================================================

    @Test fun arreter_teardown_hover_sortie_vs_puis_silence() {
        // arreter() a le meme teardown que arretUrgence() (sans le Log) : hover
        // final, sortie du Virtual Stick, et plus aucune emission ensuite.
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        p.soumettre(cmdNormale(vz = 0.5f, recuA = 1000L))   // commande non nulle en attente
        forcerActif(p, true)
        p.arreter()
        assertEquals("hover final", HOVER, derniere(pont))
        assertEquals("sortie du Virtual Stick", false, pont.vsActif)
        pont.emissions.clear()
        tick(p, 1100L)   // la commande non nulle ne doit PAS repasser
        assertTrue("aucune commande non nulle apres l'arret", pont.emissions.isEmpty())
    }

    @Test fun arreterEtAttendre_termine_la_boucle_reelle() {
        val pont = FauxPont(); val p = PiloteDrone(ctx = context, pont = pont)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            p.demarrer(scope)          // lance la vraie boucle 15 Hz
            Thread.sleep(120L)         // laisse tourner quelques ticks
            runBlocking { p.arreterEtAttendre() }   // cancelAndJoin : attend la fin

            assertNull("le Job de boucle doit etre termine et efface", champBoucle(p))
            assertEquals("sortie du Virtual Stick", false, pont.vsActif)
            pont.emissions.clear()
            Thread.sleep(120L)         // si la boucle vivait encore, elle emettrait
            assertTrue("aucun tick apres arreterEtAttendre", pont.emissions.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
