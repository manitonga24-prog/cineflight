package ca.cineflight.stage.control

import android.content.Context
import android.util.Log
import ca.cineflight.stage.BuildConfig
import ca.cineflight.stage.cine.ClientRtkSujet
import ca.cineflight.stage.cine.ClientRtkSujetV4
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Journal terrain persistant V4.2 au format JSON Lines.
 *
 * Les fichiers restent dans le dossier externe prive de l'application:
 * Android/data/ca.cineflight.solo/files/rtk_sessions.
 * Ils sont accessibles par ADB et ne sont jamais envoyes automatiquement.
 */
class JournalSessionRtkV41(private val context: Context) {
    private val tag = "CF_RtkV42Journal"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CineFlight-RTK-Journal").apply { isDaemon = true }
    }
    private val ferme = AtomicBoolean(false)
    private val lignesEcrites = AtomicLong(0)
    private val erreurs = AtomicLong(0)

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var fichier: File? = null

    fun demarrer(profil: ProfilSujetMobile): File? {
        if (ferme.get()) return null
        val dossier = context.getExternalFilesDir("rtk_sessions")
            ?: File(context.filesDir, "rtk_sessions")
        if (!dossier.exists() && !dossier.mkdirs()) {
            Log.e(tag, "creation dossier impossible: ${dossier.absolutePath}")
            return null
        }
        nettoyerAnciensJournaux(dossier)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sortie = File(dossier, "CineFlight_RTK_V4_2_${stamp}.jsonl")
        fichier = sortie
        executor.execute {
            try {
                writer = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(sortie, true), Charsets.UTF_8),
                    64 * 1024
                )
                ecrireDirect(
                    JSONObject()
                        .put("kind", "session_start")
                        .put("timestamp_ms", System.currentTimeMillis())
                        .put("version", MoteurFusionRtkV4.VERSION)
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .put("profile", profil.code)
                        .put("simulation_only", false)
                        .put("control_policy", "FIX_FULL_FLOAT_VISION_ONLY")
                        .put("target_source_hz", 10)
                        .put("target_fusion_hz", 50)
                )
                Log.i(tag, "session=${sortie.absolutePath}")
            } catch (e: Exception) {
                erreurs.incrementAndGet()
                Log.e(tag, "ouverture journal impossible", e)
            }
        }
        return sortie
    }

    fun enregistrerPosition(
        position: ClientRtkSujet.PositionSujet,
        metriques: ClientRtkSujetV4.Metriques,
        rawType: String,
        profil: ProfilSujetMobile
    ) {
        val objet = JSONObject()
            .put("kind", "rtk")
            .put("timestamp_ms", System.currentTimeMillis())
            .put("monotonic_ns", android.os.SystemClock.elapsedRealtimeNanos())
            .put("profile", profil.code)
            .put("raw_type", rawType)
            .put("present", position.present)
            .put("valid", position.valid)
            .put("network_connected", position.networkConnected)
            .put("stream", position.streamStatus)
            .put("rtk", position.rtk.name)
            .putNullable("lat", position.lat.takeIf { it.isFinite() })
            .putNullable("lon", position.lon.takeIf { it.isFinite() })
            .putNullable("alt_m", position.altM)
            .putNullable("source_sequence", position.sourceSequence)
            .putNullable("transport_sequence", position.transportSequence)
            .putNullable("server_sequence", position.serverSequence)
            .putNullable("source_hz", position.measuredRateHz)
            .putNullable("age_ms", position.ageS?.times(1000.0))
            .putNullable("server_age_ms", position.serverAgeMs)
            .putNullable("downlink_ms", position.downlinkAgeMs)
            .putNullable("end_to_end_ms", position.endToEndAgeMs)
            .putNullable("hacc_m", position.haccM)
            .putNullable("vacc_m", position.vaccM)
            .putNullable("speed_mps", position.groundSpeedMps)
            .putNullable("heading_deg", position.capDeg)
            .put("heading_valid", position.headingValid)
            .putNullable("num_sv", position.numSv)
            .putNullable("rtcm_age_ms", position.rtcmAgeMs)
            .putNullable("reason", position.reason)
            .put("control_reliable", position.controleFiable)
            .put("display_reliable", position.fiable)
            .put("ws_reconnects", metriques.reconnects)
            .put("source_missing", metriques.missingSourceFrames)
            .put("local_coalesced", metriques.localCoalescedFrames)
            .put("local_stale_events", metriques.localStaleEvents)
            .put("local_disconnected_events", metriques.localDisconnectedEvents)
            .put("parse_errors", metriques.parseErrors)
        envoyer(objet)
    }

    fun enregistrerPrediction(prediction: MoteurFusionRtkV4.PredictionTempsReel) {
        val objet = JSONObject()
            .put("kind", "prediction")
            .put("timestamp_ms", System.currentTimeMillis())
            .put("monotonic_ns", prediction.tickTimestampNs)
            .put("version", prediction.version)
            .put("profile", prediction.profil.code)
            .putNullable("source_sequence", prediction.sourceSequence)
            .put("stream", prediction.streamStatus)
            .putNullable("source_hz", prediction.sourceRateHz)
            .putNullable("fusion_hz", prediction.fusionRateHz)
            .putNullable("age_ms", prediction.ageMs)
            .putNullable("server_age_ms", prediction.serverAgeMs)
            .putNullable("downlink_ms", prediction.downlinkAgeMs)
            .putNullable("end_to_end_ms", prediction.endToEndAgeMs)
            .put("display_ready", prediction.displayReady)
            .put("control_ready", prediction.controlReady)
            .put("reason", prediction.reason)
            .putNullable("lat", prediction.lat)
            .putNullable("lon", prediction.lon)
            .putNullable("heading_deg", prediction.headingDeg)
            .putNullable("speed_mps", prediction.speedMps)
            .putNullable("acceleration_mps2", prediction.accelerationMps2)
            .putNullable("turn_rate_deg_s", prediction.turnRateDegS)
            .put("horizon_s", prediction.horizonS)
            .putNullable("uncertainty_m", prediction.uncertaintyM)
        envoyer(objet)
    }

    fun enregistrerSysteme(type: String, detail: String? = null) {
        envoyer(
            JSONObject()
                .put("kind", "system")
                .put("timestamp_ms", System.currentTimeMillis())
                .put("event", type)
                .putNullable("detail", detail)
        )
    }

    fun chemin(): String? = fichier?.absolutePath
    fun lignesEcrites(): Long = lignesEcrites.get()
    fun erreurs(): Long = erreurs.get()

    fun fermer() {
        if (!ferme.compareAndSet(false, true)) return
        executor.execute {
            try {
                ecrireDirect(
                    JSONObject()
                        .put("kind", "session_end")
                        .put("timestamp_ms", System.currentTimeMillis())
                        .put("lines_written", lignesEcrites.get())
                        .put("write_errors", erreurs.get())
                )
                writer?.flush()
                writer?.close()
            } catch (e: Exception) {
                erreurs.incrementAndGet()
                Log.e(tag, "fermeture journal impossible", e)
            } finally {
                writer = null
            }
        }
        executor.shutdown()
    }

    private fun envoyer(objet: JSONObject) {
        if (ferme.get()) return
        executor.execute {
            try {
                ecrireDirect(objet)
            } catch (e: Exception) {
                erreurs.incrementAndGet()
                Log.e(tag, "ecriture journal impossible", e)
            }
        }
    }

    private fun ecrireDirect(objet: JSONObject) {
        val sortie = writer ?: return
        sortie.write(objet.toString())
        sortie.newLine()
        val n = lignesEcrites.incrementAndGet()
        if (n % 50L == 0L) sortie.flush()
    }

    private fun nettoyerAnciensJournaux(dossier: File) {
        try {
            val fichiers = dossier.listFiles { f ->
                f.isFile && f.name.startsWith("CineFlight_RTK_") && f.name.endsWith(".jsonl")
            }?.sortedByDescending { it.lastModified() }.orEmpty()
            fichiers.drop(MAX_SESSION_FILES - 1).forEach { it.delete() }
            var total = fichiers.take(MAX_SESSION_FILES - 1).sumOf { it.length() }
            if (total > MAX_TOTAL_BYTES) {
                fichiers.sortedBy { it.lastModified() }.forEach { f ->
                    if (total <= MAX_TOTAL_BYTES) return@forEach
                    val taille = f.length()
                    if (f.delete()) total -= taille
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "rotation journaux impossible", e)
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    companion object {
        const val MAX_SESSION_FILES = 20
        const val MAX_TOTAL_BYTES = 200L * 1024L * 1024L
    }
}
