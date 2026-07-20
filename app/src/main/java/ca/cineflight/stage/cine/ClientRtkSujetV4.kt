package ca.cineflight.stage.cine

import android.util.Log
import ca.cineflight.stage.BuildConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Consommateur WebSocket RTK V4.2.
 *
 * Invariants:
 * - connexion WSS persistante avec Bearer token;
 * - aucune mesure source dupliquee ou desordonnee n'est emise;
 * - canal Android latest-only: une interface lente ne rejoue jamais un retard;
 * - watchdog local: STALE a 500 ms, DISCONNECTED a 3 s;
 * - reconnexion bornee a 3 secondes;
 * - aucune commande drone n'est produite ici.
 */
class ClientRtkSujetV4(
    private val wsUrl: String = BuildConfig.CINEFLIGHT_RTK_V4_WS_URL,
    private val consumerToken: String = BuildConfig.CINEFLIGHT_RTK_V4_CONSUMER_TOKEN,
    private val deviceId: String = BuildConfig.CINEFLIGHT_RTK_V4_DEVICE_ID
) {
    data class Metriques(
        val connected: Boolean = false,
        val messagesReceived: Long = 0,
        val rtkFramesAccepted: Long = 0,
        val duplicateSourceFrames: Long = 0,
        val outOfOrderSourceFrames: Long = 0,
        val missingSourceFrames: Long = 0,
        val localCoalescedFrames: Long = 0,
        val localStaleEvents: Long = 0,
        val localDisconnectedEvents: Long = 0,
        val reconnects: Long = 0,
        val parseErrors: Long = 0,
        val lastMessageElapsedMs: Long? = null,
        val lastSourceSequence: Long? = null,
        val lastError: String? = null
    )

    data class Evenement(
        val position: ClientRtkSujet.PositionSujet,
        val metriques: Metriques,
        val rawType: String
    )

    private val tag = "CF_RtkV4WS"
    private val actif = AtomicBoolean(false)
    private val websocket = AtomicReference<WebSocket?>(null)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CineFlight-RTK-V4-Reconnect").apply { isDaemon = true }
    }
    private var reconnexionFuture: ScheduledFuture<*>? = null
    private var fraicheurFuture: ScheduledFuture<*>? = null
    private val generation = AtomicLong(0)
    private val reconnexionPlanifieeGeneration = AtomicLong(-1)

    private val messagesReceived = AtomicLong(0)
    private val rtkFramesAccepted = AtomicLong(0)
    private val duplicateSourceFrames = AtomicLong(0)
    private val outOfOrderSourceFrames = AtomicLong(0)
    private val missingSourceFrames = AtomicLong(0)
    private val localCoalescedFrames = AtomicLong(0)
    private val localStaleEvents = AtomicLong(0)
    private val localDisconnectedEvents = AtomicLong(0)
    private val reconnects = AtomicLong(0)
    private val parseErrors = AtomicLong(0)

    @Volatile private var connected = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastMessageElapsedMs: Long? = null
    @Volatile private var lastSourceSequence: Long? = null
    @Volatile private var lastPosition: ClientRtkSujet.PositionSujet? = null
    @Volatile private var backoffMs = 500L
    @Volatile private var dernierEtatFraicheurLocal = "NO_DATA"

    /**
     * Un seul evenement en attente. Si l'UI prend du retard, l'ancienne position
     * est remplacee par la plus recente au lieu d'etre rejouee plus tard.
     */
    private val canalEvenements = Channel<Evenement>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { localCoalescedFrames.incrementAndGet() }
    )
    val evenements: Flow<Evenement> = canalEvenements.receiveAsFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun demarrer() {
        if (!actif.compareAndSet(false, true)) return
        require(wsUrl.startsWith("wss://")) { "Le flux RTK V4 doit utiliser wss://" }
        require(consumerToken.matches(Regex("^[A-Fa-f0-9]{64}$"))) {
            "Token consommateur RTK V4 absent ou invalide"
        }
        demarrerWatchdogFraicheur()
        ouvrirConnexion(immediate = true)
    }

    fun arreter() {
        actif.set(false)
        generation.incrementAndGet()
        reconnexionFuture?.cancel(false)
        reconnexionFuture = null
        fraicheurFuture?.cancel(false)
        fraicheurFuture = null
        connected = false
        websocket.getAndSet(null)?.close(1000, "arret application")
    }

    fun fermerDefinitivement() {
        arreter()
        canalEvenements.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scheduler.shutdownNow()
    }

    fun metriques(): Metriques = Metriques(
        connected = connected,
        messagesReceived = messagesReceived.get(),
        rtkFramesAccepted = rtkFramesAccepted.get(),
        duplicateSourceFrames = duplicateSourceFrames.get(),
        outOfOrderSourceFrames = outOfOrderSourceFrames.get(),
        missingSourceFrames = missingSourceFrames.get(),
        localCoalescedFrames = localCoalescedFrames.get(),
        localStaleEvents = localStaleEvents.get(),
        localDisconnectedEvents = localDisconnectedEvents.get(),
        reconnects = reconnects.get(),
        parseErrors = parseErrors.get(),
        lastMessageElapsedMs = lastMessageElapsedMs,
        lastSourceSequence = lastSourceSequence,
        lastError = lastError
    )

    private fun ouvrirConnexion(immediate: Boolean) {
        if (!actif.get()) return
        val delai = if (immediate) 0L else backoffMs
        reconnexionFuture?.cancel(false)
        reconnexionFuture = scheduler.schedule({
            if (!actif.get()) return@schedule
            val maGeneration = generation.incrementAndGet()
            reconnexionPlanifieeGeneration.set(-1)
            val request = Request.Builder()
                .url("$wsUrl/$deviceId")
                .header("Authorization", "Bearer $consumerToken")
                .header("User-Agent", "CineFlight-Android-V4.2")
                .build()
            websocket.set(client.newWebSocket(request, Listener(maGeneration)))
        }, delai, TimeUnit.MILLISECONDS)
    }

    private fun planifierReconnexion(message: String, maGeneration: Long) {
        if (!actif.get() || maGeneration != generation.get()) return
        val dejaPlanifiee = reconnexionPlanifieeGeneration.get()
        if (dejaPlanifiee == maGeneration) return
        if (!reconnexionPlanifieeGeneration.compareAndSet(-1, maGeneration)) {
            if (reconnexionPlanifieeGeneration.get() == maGeneration) return
            reconnexionPlanifieeGeneration.set(maGeneration)
        }
        connected = false
        lastError = message.take(240)
        reconnects.incrementAndGet()
        emettreIndisponible("WS_DISCONNECTED", lastError)
        websocket.set(null)
        backoffMs = min(3_000L, max(500L, (backoffMs * 1.7).toLong()))
        ouvrirConnexion(immediate = false)
    }

    private fun demarrerWatchdogFraicheur() {
        fraicheurFuture?.cancel(false)
        fraicheurFuture = scheduler.scheduleAtFixedRate({
            if (!actif.get()) return@scheduleAtFixedRate
            val dernier = lastMessageElapsedMs ?: return@scheduleAtFixedRate
            val ageMs = (android.os.SystemClock.elapsedRealtime() - dernier).coerceAtLeast(0L)
            val nouvelEtat = when {
                ageMs >= LOCAL_DISCONNECTED_MS -> "DISCONNECTED"
                ageMs >= LOCAL_STALE_MS -> "STALE"
                else -> "LIVE"
            }
            if (nouvelEtat == dernierEtatFraicheurLocal) return@scheduleAtFixedRate
            dernierEtatFraicheurLocal = nouvelEtat
            when (nouvelEtat) {
                "STALE" -> {
                    localStaleEvents.incrementAndGet()
                    emettreStatutLocal("STALE", ageMs, "LOCAL_WATCHDOG_STALE")
                }
                "DISCONNECTED" -> {
                    localDisconnectedEvents.incrementAndGet()
                    emettreStatutLocal("DISCONNECTED", ageMs, "LOCAL_WATCHDOG_DISCONNECTED")
                }
                // Le prochain paquet reel publie lui-meme l'etat LIVE.
            }
        }, 100L, 100L, TimeUnit.MILLISECONDS)
    }

    private inner class Listener(private val maGeneration: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!actif.get() || maGeneration != generation.get()) {
                webSocket.close(1000, "connexion remplacee")
                return
            }
            connected = true
            lastError = null
            backoffMs = 500L
            lastMessageElapsedMs = android.os.SystemClock.elapsedRealtime()
            dernierEtatFraicheurLocal = "LIVE"
            Log.i(tag, "connected url=$wsUrl device=$deviceId latest_only=true")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!actif.get() || maGeneration != generation.get()) return
            messagesReceived.incrementAndGet()
            lastMessageElapsedMs = android.os.SystemClock.elapsedRealtime()
            dernierEtatFraicheurLocal = "LIVE"
            try {
                traiterMessage(JSONObject(text))
            } catch (e: Exception) {
                parseErrors.incrementAndGet()
                lastError = "PARSE:${e.javaClass.simpleName}:${e.message}".take(240)
                Log.e(tag, "message V4 invalide", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            planifierReconnexion("CLOSED:$code:$reason", maGeneration)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            planifierReconnexion(
                "FAIL:${code ?: "-"}:${t.javaClass.simpleName}:${t.message}",
                maGeneration
            )
        }
    }

    private fun traiterMessage(root: JSONObject) {
        when (val type = root.optString("type", "unknown")) {
            "rtk" -> traiterRtk(root, type)
            "stream_status" -> traiterStatut(root, type)
            "error" -> {
                lastError = "SERVER:${root.optString("code", "UNKNOWN")}".take(240)
                emettreIndisponible("SERVER_ERROR", lastError)
            }
            else -> {
                parseErrors.incrementAndGet()
                lastError = "TYPE_INCONNU:$type"
            }
        }
    }

    private fun traiterRtk(root: JSONObject, type: String) {
        val payload = root.optJSONObject("payload") ?: error("payload absent")
        val sourceSequence = payload.optLongNullable("source_sequence")
        if (sourceSequence != null) {
            val precedent = lastSourceSequence
            if (precedent != null) {
                when {
                    sourceSequence == precedent -> {
                        duplicateSourceFrames.incrementAndGet()
                        return
                    }
                    sourceSequence < precedent -> {
                        outOfOrderSourceFrames.incrementAndGet()
                        return
                    }
                    sourceSequence > precedent + 1L ->
                        missingSourceFrames.addAndGet(sourceSequence - precedent - 1L)
                }
            }
            lastSourceSequence = sourceSequence
        }

        val streamStatus = root.optString("stream_status", "NO_DATA")
        val serverAgeMs = root.optDoubleNullable("age_ms")
        val serverRxNs = root.optLongNullable("server_rx_ns")
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val downlinkAgeMs = serverRxNs?.let {
            ((nowNs - it) / 1_000_000.0).takeIf { age -> age in 0.0..10_000.0 }
        }
        val effectiveAgeMs = when {
            serverAgeMs != null && downlinkAgeMs != null -> serverAgeMs + downlinkAgeMs
            serverAgeMs != null -> serverAgeMs
            downlinkAgeMs != null -> downlinkAgeMs
            else -> null
        }
        val sourceTimestampNs = payload.optLongNullable("source_timestamp_ns")
        val endToEndAgeMs = sourceTimestampNs?.let {
            ((nowNs - it) / 1_000_000.0).takeIf { age -> age in 0.0..30_000.0 }
        }
        val valid = payload.optBoolean("valid", false)
        val rtk = ClientRtkSujet.parseStatut(payload.optString("rtk", "LOST"))
        val lat = payload.optDoubleNullable("lat") ?: Double.NaN
        val lon = payload.optDoubleNullable("lon") ?: Double.NaN
        val headingValid = payload.optBoolean("heading_valid", false)

        val position = ClientRtkSujet.PositionSujet(
            present = true,
            valid = valid,
            lat = lat,
            lon = lon,
            altM = payload.optDoubleNullable("alt_msl_m"),
            rtk = rtk,
            ageS = effectiveAgeMs?.div(1000.0),
            capDeg = if (headingValid) payload.optDoubleNullable("heading_deg") else null,
            streamStatus = streamStatus,
            sourceSequence = sourceSequence,
            transportSequence = payload.optLongNullable("sequence"),
            serverSequence = root.optLongNullable("server_sequence"),
            sourceTimestampNs = sourceTimestampNs,
            serverRxNs = serverRxNs,
            measuredRateHz = root.optDoubleNullable("measured_rate_hz"),
            haccM = payload.optDoubleNullable("hacc_m"),
            vaccM = payload.optDoubleNullable("vacc_m"),
            groundSpeedMps = payload.optDoubleNullable("ground_speed_mps"),
            headingValid = headingValid,
            numSv = payload.optIntNullable("num_sv"),
            rtcmAgeMs = payload.optLongNullable("rtcm_age_ms"),
            subjectProfile = payload.optStringNullable("subject_profile"),
            reason = payload.optStringNullable("reason"),
            networkConnected = connected,
            serverAgeMs = serverAgeMs,
            downlinkAgeMs = downlinkAgeMs,
            endToEndAgeMs = endToEndAgeMs
        )
        lastPosition = position
        rtkFramesAccepted.incrementAndGet()
        emettre(Evenement(position, metriques(), type))
    }

    private fun traiterStatut(root: JSONObject, type: String) {
        val status = root.optString("status", "NO_DATA")
        val ageMs = root.optDoubleNullable("age_ms")
        val base = lastPosition
        val position = if (base == null) {
            ClientRtkSujet.PositionSujet(
                present = false,
                valid = false,
                streamStatus = status,
                ageS = ageMs?.div(1000.0),
                serverAgeMs = ageMs,
                measuredRateHz = root.optDoubleNullable("measured_rate_hz"),
                networkConnected = connected,
                reason = status
            )
        } else {
            base.copy(
                valid = base.valid && status == "LIVE",
                streamStatus = status,
                ageS = ageMs?.div(1000.0) ?: base.ageS,
                serverAgeMs = ageMs ?: base.serverAgeMs,
                measuredRateHz = root.optDoubleNullable("measured_rate_hz") ?: base.measuredRateHz,
                networkConnected = connected,
                reason = if (status == "LIVE") base.reason else status
            )
        }
        lastPosition = position
        emettre(Evenement(position, metriques(), type))
    }

    private fun emettreStatutLocal(status: String, ageMs: Long, reason: String) {
        val base = lastPosition
        val position = if (base == null) {
            ClientRtkSujet.PositionSujet(
                present = false,
                valid = false,
                streamStatus = status,
                ageS = ageMs / 1000.0,
                networkConnected = connected,
                reason = reason
            )
        } else {
            base.copy(
                valid = false,
                streamStatus = status,
                ageS = ageMs / 1000.0,
                networkConnected = connected,
                reason = reason
            )
        }
        lastPosition = position
        emettre(Evenement(position, metriques(), "local_freshness"))
    }

    private fun emettreIndisponible(reason: String, detail: String?) {
        val base = lastPosition
        val position = if (base == null) {
            ClientRtkSujet.PositionSujet(
                present = false,
                valid = false,
                streamStatus = "DISCONNECTED",
                networkConnected = false,
                reason = listOfNotNull(reason, detail).joinToString(":")
            )
        } else {
            base.copy(
                valid = false,
                streamStatus = "DISCONNECTED",
                networkConnected = false,
                reason = listOfNotNull(reason, detail).joinToString(":")
            )
        }
        lastPosition = position
        emettre(Evenement(position, metriques(), "local_status"))
    }

    private fun emettre(evenement: Evenement) {
        canalEvenements.trySend(evenement)
    }

    private fun JSONObject.optLongNullable(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun JSONObject.optIntNullable(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.optDoubleNullable(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

    private fun JSONObject.optStringNullable(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val LOCAL_STALE_MS = 500L
        const val LOCAL_DISCONNECTED_MS = 3_000L
    }
}
