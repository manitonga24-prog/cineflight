package ca.cineflight.stage

import ca.cineflight.stage.control.DiagnosticPredictionRtk
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Preview reseau RTK basee exclusivement sur le moteur V3.1.
 *
 * Cette classe reste simulation_only: meme lorsque control_ready=true, elle
 * n'envoie aucune commande au drone. Elle publie seulement un diagnostic ACK.
 */
object SoloRtkPrediction {

    private const val SERVER = "https://cineflight.ca"
    private const val DEVICE_ID = "samsung_SM_A546W"
    private const val TAG = "CineFlightACK"

    private val actif = AtomicBoolean(false)
    private val moteur = DiagnosticPredictionRtk()
    private val dernierAck = AtomicReference<org.json.JSONObject?>(null)

    @Volatile
    private var threadAcquisition: Thread? = null

    @Volatile
    private var threadAck: Thread? = null

    /**
     * V3.1: cadence a echeance fixe. La duree HTTP ne s'ajoute plus au delai
     * demande, et l'ACK est publie par un worker latest-only distinct.
     */
    fun startContinuous(iterations: Int = 75, intervalMs: Long = 200L) {
        if (!actif.compareAndSet(false, true)) return

        moteur.reinitialiser()
        dernierAck.set(null)
        demarrerWorkerAck()

        val periodeMs = intervalMs.coerceAtLeast(100L)
        val periodeNs = periodeMs * 1_000_000L
        val total = iterations.coerceAtLeast(1)

        threadAcquisition = Thread({
            var prochaineEcheanceNs = System.nanoTime()
            try {
                for (iteration in 1..total) {
                    if (!actif.get()) break

                    runOnce(iteration)
                    prochaineEcheanceNs += periodeNs

                    val maintenantNs = System.nanoTime()
                    var attenteNs = prochaineEcheanceNs - maintenantNs
                    if (attenteNs < -2L * periodeNs) {
                        // Une longue pause reseau ne doit pas provoquer une rafale.
                        prochaineEcheanceNs = maintenantNs
                        attenteNs = 0L
                    }
                    if (attenteNs > 0L) {
                        val millis = attenteNs / 1_000_000L
                        val nanos = (attenteNs % 1_000_000L).toInt()
                        Thread.sleep(millis, nanos)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                actif.set(false)
            }
        }, "CineFlight-RTK-V3.1").apply {
            isDaemon = true
            start()
        }
    }

    fun stopContinuous() {
        actif.set(false)
        threadAcquisition?.interrupt()
        threadAcquisition = null
    }

    private fun demarrerWorkerAck() {
        if (threadAck?.isAlive == true) return
        threadAck = Thread({
            try {
                while (actif.get() || dernierAck.get() != null) {
                    val payload = dernierAck.getAndSet(null)
                    if (payload == null) {
                        Thread.sleep(25L)
                        continue
                    }
                    try {
                        postAck(payload)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "ACK V3.1 non publie", e)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "CineFlight-RTK-ACK-V3.1").apply {
            isDaemon = true
            start()
        }
    }

    private fun readRtk(): org.json.JSONObject {
        val connection = java.net.URL("$SERVER/api/rtk/sujet")
            .openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val response = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            }
            if (code !in 200..299) throw java.io.IOException("RTK HTTP $code: $response")
            org.json.JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun runOnce(iteration: Int) {
        try {
            val source = readRtk()
            val present = source.optBoolean("present", false)
            val lat = source.optDouble("lat", Double.NaN)
            val lon = source.optDouble("lon", Double.NaN)
            val ageS = source.optDouble("age_s", Double.POSITIVE_INFINITY)
            val rtk = source.optString("rtk", "LOST")
            val rtkNorm = normaliserRtk(rtk)
            val fiable = present && (rtkNorm == "FIX" || rtkNorm == "FLOAT")
            val timestampMs = extraireTimestampMs(source, ageS)

            val etat = if (!present) {
                moteur.signalerIndisponible("POSITION_ABSENTE", "LOST")
            } else {
                moteur.mettreAJour(
                    lat = lat,
                    lon = lon,
                    ageS = ageS,
                    fiable = fiable,
                    qualiteRtk = rtk,
                    timestampMs = timestampMs
                )
            }

            val subjectAltM = source.optDouble("alt_m", Double.NaN)
            val gpsHeading = source.optDouble("hdg", Double.NaN)
            val predictedLat = etat.latPredite ?: Double.NaN
            val predictedLon = etat.lonPredite ?: Double.NaN
            val heading = etat.capDeg ?: gpsHeading
            val preview = calculerPreviewDrone(
                sujetLat = predictedLat,
                sujetLon = predictedLon,
                sujetAltM = subjectAltM,
                capSujetDeg = heading,
                distanceM = 4.0,
                hauteurRelativeM = 5.0
            )

            val payload = org.json.JSONObject().apply {
                put("source", "android")
                put("device_id", DEVICE_ID)
                put("engine", "RTK_PREDICTION_V3_1")
                put("ok", true)
                put("android_status", if (etat.pret) "prediction_display_ready" else "prediction_blocked")
                put("message", "Moteur V3.1 fail-closed; regression robuste; simulation seulement")
                put("safety_mode", "simulation_only")
                put("movement_allowed", false)
                put("preview_valid", etat.pret && preview != null)
                put("display_ready", etat.pret)
                put("control_ready", etat.controlePret)
                put("display_reason", etat.raison)
                put("control_reason", etat.raisonControle)
                put("state", etat.etatMouvement.code)
                put("model", etat.modele.code)
                put("model_probability_pct", etat.probabiliteModelePct ?: org.json.JSONObject.NULL)
                put("confidence_pct", etat.confiancePct ?: org.json.JSONObject.NULL)
                put("iteration", iteration)
                put("rtk", etat.qualiteRtk)
                putDoubleSafe(this, "age_s", etat.ageS)
                putDoubleSafe(this, "history_s", etat.dureeHistoriqueS)
                putDoubleSafe(this, "model_stable_s", etat.stabiliteModeleS)
                putDoubleSafe(this, "quality_stable_s", etat.stabiliteQualiteS)
                putDoubleSafe(this, "speed_mps", etat.vitesseMps)
                putDoubleSafe(this, "heading_deg", etat.capDeg)
                putDoubleSafe(this, "turn_deg_s", etat.virageDegS)
                putDoubleSafe(this, "residual_m", etat.residuModeleM)
                putDoubleSafe(this, "uncertainty_m", etat.incertitudeM)
                putDoubleSafe(this, "prediction_horizon_s", etat.horizonS)
                putDoubleSafe(this, "predicted_subject_lat", etat.latPredite)
                putDoubleSafe(this, "predicted_subject_lon", etat.lonPredite)
                putDoubleSafe(this, "subject_alt_m", subjectAltM)

                preview?.let {
                    putDoubleSafe(this, "drone_preview_lat", it.lat)
                    putDoubleSafe(this, "drone_preview_lon", it.lon)
                    putDoubleSafe(this, "drone_preview_alt_m", it.altM)
                    putDoubleSafe(this, "yaw_camera_deg", it.yawDeg)
                    putDoubleSafe(this, "gimbal_pitch_deg", it.gimbalDeg)
                    putDoubleSafe(this, "horizontal_distance_m", it.distanceM)
                    putDoubleSafe(this, "vertical_delta_m", it.hauteurRelativeM)
                }
            }

            dernierAck.set(payload)
            android.util.Log.i(TAG, etat.journal())
        } catch (e: Exception) {
            moteur.signalerIndisponible("ERREUR_RESEAU", "LOST")
            android.util.Log.e(TAG, "Erreur RTK V3.1", e)
        }
    }

    private data class PreviewDrone(
        val lat: Double,
        val lon: Double,
        val altM: Double,
        val yawDeg: Double,
        val gimbalDeg: Double,
        val distanceM: Double,
        val hauteurRelativeM: Double
    )

    private fun calculerPreviewDrone(
        sujetLat: Double,
        sujetLon: Double,
        sujetAltM: Double,
        capSujetDeg: Double,
        distanceM: Double,
        hauteurRelativeM: Double
    ): PreviewDrone? {
        if (!sujetLat.isFinite() || !sujetLon.isFinite() || !capSujetDeg.isFinite()) return null
        val bearingArriere = (capSujetDeg + 180.0) % 360.0
        val rad = Math.toRadians(bearingArriere)
        val metresParDegLat = 111_111.0
        val metresParDegLon = 111_111.0 * cos(Math.toRadians(sujetLat)).coerceAtLeast(1e-8)
        val droneLat = sujetLat + distanceM * cos(rad) / metresParDegLat
        val droneLon = sujetLon + distanceM * sin(rad) / metresParDegLon
        val droneAlt = if (sujetAltM.isFinite()) sujetAltM + hauteurRelativeM else Double.NaN
        val yaw = bearingDeg(droneLat, droneLon, sujetLat, sujetLon)
        val gimbal = -Math.toDegrees(atan2(hauteurRelativeM, distanceM))
        return PreviewDrone(droneLat, droneLon, droneAlt, yaw, gimbal, distanceM, hauteurRelativeM)
    }

    private fun postAck(payload: org.json.JSONObject) {
        val connection = java.net.URL("$SERVER/api/mouvement/ack")
            .openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("ACK HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    private fun extraireTimestampMs(json: org.json.JSONObject, ageS: Double): Long {
        val noms = arrayOf("timestamp_ms", "measurement_time_ms", "gnss_time_ms", "time_ms", "epoch_ms", "timestamp", "ts")
        for (nom in noms) {
            if (!json.has(nom) || json.isNull(nom)) continue
            val raw = json.optDouble(nom, Double.NaN)
            if (!raw.isFinite()) continue
            val value = raw.toLong()
            return if (value in 1..9_999_999_999L) value * 1000L else value
        }
        val age = if (ageS.isFinite()) ageS.coerceAtLeast(0.0) else 0.0
        return System.currentTimeMillis() - (age * 1000.0).toLong()
    }

    private fun normaliserRtk(value: String): String {
        val q = value.trim().uppercase(Locale.US).replace('-', '_').replace(' ', '_')
        if (q.contains("NO_FIX") || q.contains("LOST") || q.contains("INVALID") || q.contains("PERDU")) return "LOST"
        if (q.contains("FLOAT")) return "FLOAT"
        if (q == "FIX" || q == "FIXED" || q.contains("RTK_FIX") || q.contains("CARRIER_FIXED")) return "FIX"
        if (q.contains("GPS") || q.contains("GNSS") || q.contains("DGPS")) return "GPS"
        return q
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) return Double.NaN
        val dLonRad = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLonRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun putDoubleSafe(obj: org.json.JSONObject, key: String, value: Double?) {
        if (value == null || !value.isFinite()) obj.put(key, org.json.JSONObject.NULL) else obj.put(key, value)
    }
}
