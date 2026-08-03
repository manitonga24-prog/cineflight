package ca.cineflight.stage.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DjiLiveStreamEngine — noyau commun de diffusion (RTSP + RTMP) au-dessus du SDK DJI.
 *
 * ===================== PASSES 1 + 2 (etat + statut/metriques) ====================
 *   - etat de cycle de vie observable via [state] (StateFlow) ;
 *   - telemetrie observable via [metrics] (StateFlow SEPARE : les metriques changent
 *     souvent sans provoquer de fausse transition d'etat) ;
 *   - [demarrer] / [arreter] ;
 *   - callbacks DJI (commande ET statut) RE-SERIALISES sur un scope a thread unique ;
 *   - refus d'un 2e demarrage tant que l'etat n'est pas Idle.
 *
 * Semantique DURCIE (passe 2) — l'acquittement de la commande ne suffit plus :
 *     startStream.onSuccess  -> reste Starting  (commande acceptee)
 *     status.isStreaming=true -> Streaming       (diffusion reellement active)
 *     stopStream.onSuccess   -> reste Stopping   (arret accepte)
 *     status.isStreaming=false-> Idle            (diffusion reellement arretee)
 * Cela evite d'afficher « EN DIRECT » avant que le manager ne publie un etat actif.
 *
 * Passes suivantes : sessionId anti-callback-perime (3) ; reducteur + erreurs
 * SharedFlow + TIMEOUTS + reconciliation isStreaming() + close() (4). En particulier,
 * le cas « arret demande mais status.isStreaming=false jamais publie » reste OUVERT
 * ici (on peut rester coince en Stopping) : sa resolution (timeout + reconciliation)
 * est explicitement prevue en passe 4.
 *
 * ------------------------------- THREADING --------------------------------------
 * Les callbacks DJI (Completion ET StatusListener) arrivent sur un thread ARBITRAIRE.
 * Regle stricte : un callback ne modifie JAMAIS l'etat directement ; il re-lance le
 * traitement sur [engineScope], serialise sur un dispatcher unique injecte :
 *   - prod : Dispatchers.Main.immediate ;
 *   - test : Dispatchers.Unconfined (execution synchrone, sans kotlinx-coroutines-test).
 *
 * ------------------------------- CYCLE DE VIE -----------------------------------
 * Le moteur ne detient AUCUNE reference a Activity/Fragment/View/Context. Le listener
 * de statut est attache AU PLUS UNE FOIS (le port est idempotent) ; son retrait
 * explicite via close() arrivera en passe 4.
 */
internal class DjiLiveStreamEngine(
    private val manager: LiveStreamManagerAdapter = LiveStreamManagerAdapter.Dji,
    private val statusPort: LiveStreamStatusPort = DjiLiveStreamStatusAdapter(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    private companion object {
        const val TAG = "LIVE_STREAM"
        // Au-dela de ce delai sans confirmation du SDK (isStreaming), un demarrage
        // ou un arret est considere comme echoue : on debloque l'UI (Erreur) au lieu
        // de rester coince en Starting/Stopping (bouton Demarrer grise a vie).
        const val TIMEOUT_MS = 15_000L
    }

    // Watchdog du demarrage/arret : annule des qu'une transition reelle survient.
    private var timeoutJob: Job? = null

    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(engineJob + dispatcher)

    private val _state = MutableStateFlow<LiveStreamState>(LiveStreamState.Idle)
    /** Etat de cycle de vie, observable par l'UI (a collecter de facon lifecycle-aware). */
    val state: StateFlow<LiveStreamState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(LiveStreamMetrics.Empty)
    /** Telemetrie du direct (fps, debit, resolution...). Vide hors diffusion. */
    val metrics: StateFlow<LiveStreamMetrics> = _metrics.asStateFlow()

    /**
     * Demande le demarrage d'un direct vers [destination].
     *
     * Refuse si l'etat n'est pas Idle (empeche deux directs simultanes).
     * SECURITE : on logge uniquement destination.label, jamais l'URL/cle RTMP.
     */
    fun demarrer(destination: LiveStreamDestination) {
        engineScope.launch { demarrerSerialise(destination) }
    }

    /** Demande l'arret du direct en cours. Sans effet si deja Idle. */
    fun arreter() {
        engineScope.launch { arreterSerialise() }
    }

    // ------------------------------------------------------------------
    // Traitement serialise (toujours sur engineScope / dispatcher unique)
    // ------------------------------------------------------------------

    private fun demarrerSerialise(destination: LiveStreamDestination) {
        // Idle ou Erreur = pret a (re)demarrer. Starting/Streaming/Stopping = occupe.
        val pret = _state.value is LiveStreamState.Idle || _state.value is LiveStreamState.Erreur
        if (!pret) {
            // Un direct est deja en cours (ou en transition) : on refuse en silence.
            Log.w(TAG, "Demarrage ignore : etat=${_state.value::class.simpleName} destination=${destination.label}")
            return
        }

        // Ecoute du statut : attachee au plus une fois (le port est idempotent).
        attacherStatut()

        // Reset des metriques au debut d'un nouveau start.
        _metrics.value = LiveStreamMetrics.Empty
        _state.value = LiveStreamState.Starting
        Log.i(TAG, "Demarrage destination=${destination.label} state=STARTING")
        // Watchdog : si isStreaming=true n'arrive pas a temps, on debloque en Erreur.
        armerTimeout("Le drone n'a pas confirme le demarrage a temps. Verifie la connexion, la cle et le reseau.")

        // CONFLIT DE FLUX UNIQUE : le liveStreamManager DJI est PARTAGE (RTSP + RTMP).
        // Si un flux tourne deja (RTSP en cours, ancien live YouTube non arrete, ou etat
        // fantome apres un plantage), startStream renverrait "live stream already started".
        // On arrete d'abord ce flux, PUIS on enchaine la vraie configuration + demarrage.
        if (manager.diffuseDeja()) {
            Log.w(TAG, "Flux DJI deja actif : arret prealable avant de demarrer ${destination.label}")
            manager.arreter(object : LiveStreamManagerAdapter.Completion {
                override fun onSuccess() {
                    engineScope.launch { configurerEtDemarrer(destination) }
                }
                override fun onFailure(raison: String) {
                    // On tente quand meme : le stop a peut-etre suffi cote SDK.
                    Log.w(TAG, "Arret prealable en echec ($raison) : on tente le demarrage quand meme")
                    engineScope.launch { configurerEtDemarrer(destination) }
                }
            })
            return
        }

        configurerEtDemarrer(destination)
    }

    /** Configure la destination puis lance startStream. Appelee directement, ou apres
     *  l'arret prealable d'un flux DJI deja actif (conflit de manager unique). */
    private fun configurerEtDemarrer(destination: LiveStreamDestination) {
        try {
            manager.configurer(destination)
        } catch (e: Throwable) {
            // Config impossible (SDK absent, drone non supporte...) : on remonte la raison.
            val raison = "Configuration impossible : ${e.message ?: e.javaClass.simpleName}"
            _state.value = LiveStreamState.Erreur(raison)
            _metrics.value = LiveStreamMetrics.Empty
            Log.e(TAG, "Configuration echouee destination=${destination.label} : ${e.message}")
            return
        }

        manager.demarrer(object : LiveStreamManagerAdapter.Completion {
            override fun onSuccess() {
                // Callback thread arbitraire -> re-serialisation avant toute mutation d'etat.
                engineScope.launch { onDemarrageReussi() }
            }
            override fun onFailure(raison: String) {
                engineScope.launch { onDemarrageEchoue(raison) }
            }
        })
    }

    private fun arreterSerialise() {
        val courant = _state.value
        if (courant is LiveStreamState.Idle || courant is LiveStreamState.Stopping) {
            // Rien a arreter / arret deja en cours : no-op sans exception.
            return
        }

        _state.value = LiveStreamState.Stopping
        Log.i(TAG, "Arret demande state=STOPPING")
        // Watchdog : si isStreaming=false n'arrive pas a temps, on force le retour a Idle.
        armerTimeout(null)  // null = timeout d'arret -> Idle (pas d'erreur affichee)
        // NB : on n'efface PAS les metriques ici ; elles restent visibles pendant l'arret.

        manager.arreter(object : LiveStreamManagerAdapter.Completion {
            override fun onSuccess() {
                engineScope.launch { onArretReussi() }
            }
            override fun onFailure(raison: String) {
                engineScope.launch { onArretEchoue(raison) }
            }
        })
    }

    // ------------------------------------------------------------------
    // Statut DJI (metriques + transitions basees sur isStreaming)
    // ------------------------------------------------------------------

    private fun attacherStatut() {
        statusPort.attach(
            onStatus = { snapshot -> engineScope.launch { onStatut(snapshot) } },
            onError = { raison -> engineScope.launch { onStatutErreur(raison) } },
        )
    }

    private fun onStatut(snapshot: DjiLiveStreamStatusSnapshot) {
        // 1) Telemetrie : toujours mise a jour (flux separe, n'affecte pas l'etat).
        _metrics.value = LiveStreamMetrics(
            fps = snapshot.fps,
            bitrateBps = snapshot.bitrate.toLong(),
            resolution = snapshot.resolution,
            packetLoss = snapshot.packetLoss,
            packetCacheLength = snapshot.packetCacheLength,
            rttMs = snapshot.rttMs,
        )

        // 2) Transitions de cycle de vie confirmees par la diffusion reelle.
        when {
            snapshot.isStreaming && _state.value is LiveStreamState.Starting -> {
                annulerTimeout()   // confirme a temps : plus de watchdog
                _state.value = LiveStreamState.Streaming
                Log.i(TAG, "Direct actif state=STREAMING")
            }
            !snapshot.isStreaming && _state.value is LiveStreamState.Stopping -> {
                annulerTimeout()
                _state.value = LiveStreamState.Idle
                _metrics.value = LiveStreamMetrics.Empty
                Log.i(TAG, "Direct arrete state=IDLE")
            }
        }
    }

    // ------------------------------------------------------------------
    // Watchdog demarrage/arret : evite de rester coince en Starting/Stopping
    // ------------------------------------------------------------------

    /**
     * Arme un delai [TIMEOUT_MS]. Si l'etat est TOUJOURS en transition a l'echeance,
     * on debloque : Erreur([raison]) pour un demarrage, Idle pour un arret ([raison]=null).
     * Un seul watchdog a la fois (le precedent est annule).
     */
    private fun armerTimeout(raison: String?) {
        annulerTimeout()
        timeoutJob = engineScope.launch {
            delay(TIMEOUT_MS)
            when (_state.value) {
                is LiveStreamState.Starting -> {
                    _state.value = LiveStreamState.Erreur(
                        raison ?: "Demarrage non confirme (delai depasse)."
                    )
                    _metrics.value = LiveStreamMetrics.Empty
                    Log.e(TAG, "Timeout demarrage : etat force en Erreur")
                }
                is LiveStreamState.Stopping -> {
                    _state.value = LiveStreamState.Idle
                    _metrics.value = LiveStreamMetrics.Empty
                    Log.w(TAG, "Timeout arret : etat force en Idle")
                }
                else -> { /* deja resolu entre-temps : rien a faire */ }
            }
        }
    }

    private fun annulerTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun onStatutErreur(raison: String) {
        // Une erreur de statut NE force PAS un changement d'etat (sans autre preuve).
        // Passe 4 : la transformer en evenement d'erreur ponctuel (SharedFlow).
        Log.e(TAG, "Erreur de statut : $raison")
    }

    // ------------------------------------------------------------------
    // Callbacks de COMMANDE (start/stop) — semantique durcie passe 2
    // ------------------------------------------------------------------

    private fun onDemarrageReussi() {
        // Commande acceptee : on RESTE Starting jusqu'a status.isStreaming=true.
        Log.i(TAG, "Commande de demarrage acceptee (attente isStreaming=true)")
    }

    private fun onDemarrageEchoue(raison: String) {
        annulerTimeout()   // echec immediat : le watchdog n'a plus de raison d'etre
        // On EXPOSE la raison DJI a l'ecran (au lieu de retomber en silence sur Idle).
        _state.value = LiveStreamState.Erreur(raison)
        _metrics.value = LiveStreamMetrics.Empty
        Log.e(TAG, "Demarrage echoue : $raison")
    }

    private fun onArretReussi() {
        // Commande d'arret acceptee : on RESTE Stopping jusqu'a status.isStreaming=false.
        Log.i(TAG, "Commande d'arret acceptee (attente isStreaming=false)")
    }

    private fun onArretEchoue(raison: String) {
        annulerTimeout()
        // Passe 2 : l'echec d'arret ramene a Idle (best-effort). Passe 4 : reconciliation
        // via isStreamingNow() + evenement d'erreur.
        _state.value = LiveStreamState.Idle
        _metrics.value = LiveStreamMetrics.Empty
        Log.e(TAG, "Arret echoue : $raison")
    }
}
