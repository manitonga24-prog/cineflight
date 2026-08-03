package ca.cineflight.stage.streaming

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DjiLiveStreamEngineTest — invariants du moteur (passes 1 + 2B).
 *
 * Dispatcher = Dispatchers.Unconfined : chaque coroutine s'execute IMMEDIATEMENT
 * sur le thread appelant. Avec des faux SYNCHRONES (commande + statut declenches
 * a la demande), le moteur est deterministe sans kotlinx-coroutines-test.
 *
 * Semantique DURCIE passe 2 :
 *   - startStream.onSuccess seul -> reste Starting ;
 *   - status.isStreaming=true    -> Streaming ;
 *   - stopStream.onSuccess seul  -> reste Stopping ;
 *   - status.isStreaming=false   -> Idle.
 */
class DjiLiveStreamEngineTest {

    /** Faux manager : memorise la Completion, ne la declenche que sur ordre du test. */
    private class FauxManager : LiveStreamManagerAdapter {
        var destinationConfiguree: LiveStreamDestination? = null
        var nbDemarrages = 0
        var nbArrets = 0

        /**
         * Simule la sonde « un flux DJI tourne deja ? » (manager unique partage RTSP+RTMP).
         * FALSE par defaut = cas nominal : aucun flux prealable, le moteur configure et
         * demarre directement. Un test peut le passer a true pour exercer le chemin
         * « arret prealable puis demarrage ».
         */
        var diffuseDejaValeur = false

        private var completionDemarrage: LiveStreamManagerAdapter.Completion? = null
        private var completionArret: LiveStreamManagerAdapter.Completion? = null

        override fun configurer(destination: LiveStreamDestination) {
            destinationConfiguree = destination
        }
        override fun diffuseDeja(): Boolean = diffuseDejaValeur
        override fun demarrer(completion: LiveStreamManagerAdapter.Completion) {
            nbDemarrages++; completionDemarrage = completion
        }
        override fun arreter(completion: LiveStreamManagerAdapter.Completion) {
            nbArrets++; completionArret = completion
        }

        fun accepterDemarrage() = completionDemarrage!!.onSuccess()
        fun echouerDemarrage(raison: String = "echec test") = completionDemarrage!!.onFailure(raison)
        fun accepterArret() = completionArret!!.onSuccess()
        fun echouerArret(raison: String = "echec arret") = completionArret!!.onFailure(raison)
    }

    /** Faux port de statut : le test pousse des snapshots/erreurs a volonte. */
    private class FauxStatusPort : LiveStreamStatusPort {
        var nbAttach = 0
        var nbDetach = 0
        private var onStatus: ((DjiLiveStreamStatusSnapshot) -> Unit)? = null
        private var onError: ((String) -> Unit)? = null

        override fun attach(
            onStatus: (DjiLiveStreamStatusSnapshot) -> Unit,
            onError: (String) -> Unit,
        ) {
            // Idempotent comme l'impl reelle : un 2e attach sans detach est ignore.
            if (this.onStatus != null) return
            nbAttach++
            this.onStatus = onStatus
            this.onError = onError
        }
        override fun detach() {
            if (onStatus == null) return
            nbDetach++; onStatus = null; onError = null
        }
        override fun isStreamingNow(): Boolean = false

        fun pousserStatut(
            isStreaming: Boolean,
            fps: Int = 30, bitrate: Int = 2500, resolution: String? = "1280x720",
            packetLoss: Int = 0, packetCacheLength: Int = 0, rttMs: Int = 40,
        ) = onStatus!!(
            DjiLiveStreamStatusSnapshot(isStreaming, fps, bitrate, resolution, packetLoss, packetCacheLength, rttMs)
        )

        fun pousserErreur(raison: String) = onError!!(raison)
    }

    private class Fixture {
        val manager = FauxManager()
        val port = FauxStatusPort()
        val engine = DjiLiveStreamEngine(
            manager = manager,
            statusPort = port,
            dispatcher = Dispatchers.Unconfined,
        )
    }

    private val rtmp = LiveStreamDestination.Rtmp(fullUrl = "rtmps://serveur/CLE_SECRETE", label = "YouTube")

    // --- Cycle de vie de base ---

    // start success SEUL reste Starting ; status true produit Streaming
    @Test
    fun demarrage_reste_starting_jusqua_isStreaming_true() {
        val f = Fixture()
        assertEquals(LiveStreamState.Idle, f.engine.state.value)

        f.engine.demarrer(rtmp)
        assertEquals(LiveStreamState.Starting, f.engine.state.value)

        f.manager.accepterDemarrage()          // commande acceptee, mais...
        assertEquals(LiveStreamState.Starting, f.engine.state.value)  // ...toujours Starting

        f.port.pousserStatut(isStreaming = true)
        assertEquals(LiveStreamState.Streaming, f.engine.state.value)
    }

    // stop success SEUL reste Stopping ; status false produit Idle
    @Test
    fun arret_reste_stopping_jusqua_isStreaming_false() {
        val f = Fixture()
        f.engine.demarrer(rtmp)
        f.port.pousserStatut(isStreaming = true)
        assertEquals(LiveStreamState.Streaming, f.engine.state.value)

        f.engine.arreter()
        assertEquals(LiveStreamState.Stopping, f.engine.state.value)

        f.manager.accepterArret()              // commande acceptee, mais...
        assertEquals(LiveStreamState.Stopping, f.engine.state.value)  // ...toujours Stopping

        f.port.pousserStatut(isStreaming = false)
        assertEquals(LiveStreamState.Idle, f.engine.state.value)
    }

    // 2e start refuse tant que non-Idle
    @Test
    fun deuxieme_demarrage_refuse() {
        val f = Fixture()
        f.engine.demarrer(rtmp)                                       // -> Starting
        f.engine.demarrer(LiveStreamDestination.Rtmp("rtmps://x/K2", "Facebook"))
        assertEquals(1, f.manager.nbDemarrages)
        assertEquals("YouTube", f.manager.destinationConfiguree?.label)

        f.port.pousserStatut(isStreaming = true)                     // -> Streaming
        f.engine.demarrer(rtmp)                                       // encore refuse
        assertEquals(1, f.manager.nbDemarrages)
    }

    // stop depuis Idle : no-op
    @Test
    fun arret_depuis_idle_sans_effet() {
        val f = Fixture()
        f.engine.arreter()
        assertEquals(LiveStreamState.Idle, f.engine.state.value)
        assertEquals(0, f.manager.nbArrets)
    }

    // --- Listener ---

    // listener attache une seule fois (meme sur plusieurs demarrages)
    @Test
    fun listener_attache_une_seule_fois() {
        val f = Fixture()
        f.engine.demarrer(rtmp)
        f.port.pousserStatut(isStreaming = true)
        f.engine.arreter()
        f.port.pousserStatut(isStreaming = false)
        f.engine.demarrer(rtmp)                 // 2e cycle
        assertEquals(1, f.port.nbAttach)        // toujours un seul attach
    }

    // --- Metriques ---

    // metriques copiees dans le flux dedie ; etat inchange par un simple tick
    @Test
    fun metriques_copiees_sans_changer_etat() {
        val f = Fixture()
        f.engine.demarrer(rtmp)
        f.port.pousserStatut(isStreaming = true, fps = 30, bitrate = 4000, resolution = "1920x1080")
        assertEquals(LiveStreamState.Streaming, f.engine.state.value)
        assertEquals(30, f.engine.metrics.value.fps)
        assertEquals(4000L, f.engine.metrics.value.bitrateBps)
        assertEquals("1920x1080", f.engine.metrics.value.resolution)

        // Tick suivant : le debit change, l'etat reste Streaming (pas de fausse transition).
        f.port.pousserStatut(isStreaming = true, fps = 28, bitrate = 3800)
        assertEquals(LiveStreamState.Streaming, f.engine.state.value)
        assertEquals(28, f.engine.metrics.value.fps)
        assertEquals(3800L, f.engine.metrics.value.bitrateBps)
    }

    // metriques remises a zero apres arret complet
    @Test
    fun metriques_effacees_apres_arret_complet() {
        val f = Fixture()
        f.engine.demarrer(rtmp)
        f.port.pousserStatut(isStreaming = true, fps = 30)
        assertEquals(30, f.engine.metrics.value.fps)

        f.engine.arreter()
        f.port.pousserStatut(isStreaming = false)
        assertEquals(LiveStreamState.Idle, f.engine.state.value)
        assertEquals(LiveStreamMetrics.Empty, f.engine.metrics.value)
        assertNull(f.engine.metrics.value.fps)
    }

    // --- Erreurs ---

    // une erreur du listener ne force PAS Idle
    @Test
    fun erreur_statut_ne_force_pas_idle() {
        val f = Fixture()
        f.engine.demarrer(rtmp)
        f.port.pousserStatut(isStreaming = true)
        assertEquals(LiveStreamState.Streaming, f.engine.state.value)

        f.port.pousserErreur("hoquet reseau")
        assertEquals(LiveStreamState.Streaming, f.engine.state.value)  // toujours en direct
    }

    /**
     * Echec de la commande de demarrage -> etat Erreur PORTANT LA RAISON, et redemarrage
     * possible.
     *
     * CONTRAT (choix delibere du moteur) : on n'efface PAS la cause en retombant en
     * silence sur Idle — la raison remontee par DJI doit rester affichable a l'operateur.
     * Erreur fait partie des etats « prets a redemarrer » (au meme titre qu'Idle), donc
     * l'operateur peut relancer sans quitter l'ecran.
     */
    @Test
    fun echec_demarrage_expose_la_raison_et_reste_redemarrable() {
        val f = Fixture()
        f.engine.demarrer(rtmp)
        assertEquals(LiveStreamState.Starting, f.engine.state.value)

        f.manager.echouerDemarrage("drone non supporte")

        val etat = f.engine.state.value
        assertTrue("l'etat doit etre Erreur, pas Idle", etat is LiveStreamState.Erreur)
        assertEquals("la raison DJI doit etre conservee",
            "drone non supporte", (etat as LiveStreamState.Erreur).raison)
        assertEquals("les metriques sont videes", LiveStreamMetrics.Empty, f.engine.metrics.value)

        f.engine.demarrer(rtmp)                 // redemarrage possible depuis Erreur
        assertTrue(f.engine.state.value is LiveStreamState.Starting)
        assertEquals(2, f.manager.nbDemarrages)
    }

    /**
     * La sonde « un flux DJI tourne deja ? » commande un ARRET PREALABLE avant de
     * configurer et demarrer — le liveStreamManager est unique et partage (RTSP + RTMP),
     * sans quoi startStream renverrait « live stream already started ».
     */
    @Test
    fun flux_deja_actif_declenche_un_arret_prealable_avant_le_demarrage() {
        val f = Fixture()
        f.manager.diffuseDejaValeur = true

        f.engine.demarrer(rtmp)
        assertEquals("un arret prealable doit etre demande", 1, f.manager.nbArrets)
        assertEquals("le demarrage n'a pas encore ete commande", 0, f.manager.nbDemarrages)

        f.manager.accepterArret()               // le SDK confirme l'arret prealable
        assertEquals("le demarrage suit l'arret", 1, f.manager.nbDemarrages)
        assertEquals(LiveStreamState.Starting, f.engine.state.value)
    }
}
