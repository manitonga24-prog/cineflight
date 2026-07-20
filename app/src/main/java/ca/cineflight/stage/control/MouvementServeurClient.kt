package ca.cineflight.stage.control

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

data class DemandeMouvement(
    val ok: Boolean,
    val deviceId: String?,
    val kind: String?,
    val movement: String?,
    val source: String?,
    val safetyNote: String?,
    val serverRxTs: Double?,
    val rawJson: String
)

data class RtkSujet(
    val present: Boolean,
    val lat: Double?,
    val lon: Double?,
    val altM: Double?,
    val rtk: String?,
    val ageS: Double?,
    val hz: Double?,
    // AUDIT-RTK-VITESSE-CAP-2026-07 (etape 2) : champs vehicule sur route.
    // OPTIONNELS : un serveur ancien qui ne les renvoie pas -> null / false.
    // vitesseMps : ground_speed_mps GNSS (source PRINCIPALE de vitesse pour
    //   ParcoursRoute ; si null -> fallback derivation Android).
    // capDeg     : heading_deg GNSS (deg, 0=N). Sert a confirmer le SENS sur le
    //   rail ; ne remplace jamais la tangente de route.
    // capValide  : heading_valid. Si false -> on ignore le cap (tangente route).
    val vitesseMps: Double? = null,
    val capDeg: Double? = null,
    val capValide: Boolean = false,
    val rawJson: String
)

class MouvementServeurClient(
    private val baseUrl: String = "http://161.35.188.68:8095"
) {
    private val http = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var running = false
    private var lastServerRxTs: Double? = null

    fun startPolling(
        intervalMs: Long = 1000L,
        onNewRequest: (DemandeMouvement) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (running) return
        running = true

        fun tick() {
            if (!running) return

            executor.execute {
                try {
                    val req = Request.Builder()
                        .url("$baseUrl/api/mouvement/latest")
                        .get()
                        .build()

                    http.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()

                        if (!resp.isSuccessful) {
                            mainHandler.post { onError("HTTP ${resp.code}: $body") }
                        } else {
                            val demande = parseLatest(body)

                            if (demande != null && demande.serverRxTs != null) {
                                if (demande.serverRxTs != lastServerRxTs) {
                                    lastServerRxTs = demande.serverRxTs
                                    mainHandler.post { onNewRequest(demande) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post { onError(e.message ?: "Erreur mouvement serveur") }
                }

                mainHandler.postDelayed({ tick() }, intervalMs)
            }
        }

        tick()
    }

    fun fetchRtkSujet(
        onResult: (RtkSujet?) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        executor.execute {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/rtk/sujet")
                    .get()
                    .build()

                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()

                    if (!resp.isSuccessful) {
                        mainHandler.post {
                            onError("HTTP ${resp.code}: $body")
                            onResult(null)
                        }
                        return@execute
                    }

                    val rtk = parseRtkSujet(body)
                    mainHandler.post { onResult(rtk) }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onError(e.message ?: "Erreur RTK sujet")
                    onResult(null)
                }
            }
        }
    }

    fun sendAck(
        demande: DemandeMouvement,
        androidStatus: String = "RECEIVED_BY_ANDROID",
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        executor.execute {
            try {
                val payload = JSONObject().apply {
                    put("device_id", demande.deviceId ?: JSONObject.NULL)
                    put("kind", demande.kind ?: JSONObject.NULL)
                    put("movement", demande.movement ?: JSONObject.NULL)
                    put("source", demande.source ?: JSONObject.NULL)
                    put("server_rx_ts", demande.serverRxTs ?: JSONObject.NULL)
                    put("android_status", androidStatus)
                    put("safety_note", "ack_only_no_drone_execution")
                    put("android_rx_ts", System.currentTimeMillis() / 1000.0)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = payload.toString().toRequestBody(mediaType)

                val req = Request.Builder()
                    .url("$baseUrl/api/mouvement/ack")
                    .post(body)
                    .build()

                http.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    mainHandler.post {
                        onResult(resp.isSuccessful, "HTTP ${resp.code}: $txt")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(false, e.message ?: "Erreur ACK Android")
                }
            }
        }
    }

    fun stopPolling() {
        running = false
    }

    /** Ferme le client et son exécuteur lorsqu'une Activity est détruite. */
    fun fermer() {
        running = false
        executor.shutdownNow()
    }

    private fun parseLatest(json: String): DemandeMouvement? {
        val root = JSONObject(json)
        val ok = root.optBoolean("ok", false)

        if (!ok || !root.has("request")) return null

        val r = root.getJSONObject("request")

        return DemandeMouvement(
            ok = ok,
            deviceId = r.optString("device_id", null),
            kind = r.optString("kind", null),
            movement = r.optString("movement", null),
            source = r.optString("source", null),
            safetyNote = r.optString("safety_note", null),
            serverRxTs = if (r.has("server_rx_ts")) r.optDouble("server_rx_ts") else null,
            rawJson = json
        )
    }

    private fun parseRtkSujet(json: String): RtkSujet {
        val r = JSONObject(json)

        return RtkSujet(
            present = r.optBoolean("present", false),
            lat = if (r.has("lat")) r.optDouble("lat") else null,
            lon = if (r.has("lon")) r.optDouble("lon") else null,
            altM = if (r.has("alt_m")) r.optDouble("alt_m") else null,
            rtk = r.optString("rtk", null),
            ageS = if (r.has("age_s") && !r.isNull("age_s")) r.optDouble("age_s") else null,
            hz = if (r.has("hz") && !r.isNull("hz")) r.optDouble("hz") else null,
            // AUDIT-RTK-VITESSE-CAP-2026-07 : champs optionnels (serveur ancien -> absents).
            vitesseMps = if (r.has("ground_speed_mps") && !r.isNull("ground_speed_mps"))
                r.optDouble("ground_speed_mps") else null,
            capDeg = if (r.has("heading_deg") && !r.isNull("heading_deg"))
                r.optDouble("heading_deg") else null,
            capValide = r.optBoolean("heading_valid", false),
            rawJson = json
        )
    }
}

