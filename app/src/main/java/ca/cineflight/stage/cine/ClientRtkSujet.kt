package ca.cineflight.stage.cine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modele commun de position RTK sujet.
 *
 * V4 utilise [ClientRtkSujetV4] en WebSocket. La methode HTTP historique reste
 * disponible uniquement comme outil de repli/diagnostic; elle n'est plus la
 * source temps reel principale.
 */
object ClientRtkSujet {

    private const val LEGACY_BASE_URL = "http://161.35.188.68:8095"

    enum class StatutRtk { FIX, FLOAT, GPS, LOST }

    data class PositionSujet(
        val present: Boolean,
        val valid: Boolean = false,
        val lat: Double = Double.NaN,
        val lon: Double = Double.NaN,
        val altM: Double? = null,
        val rtk: StatutRtk = StatutRtk.LOST,
        val ageS: Double? = null,
        val capDeg: Double? = null,
        val trace: List<DoubleArray> = emptyList(),
        val streamStatus: String = "NO_DATA",
        val sourceSequence: Long? = null,
        val transportSequence: Long? = null,
        val serverSequence: Long? = null,
        val sourceTimestampNs: Long? = null,
        val serverRxNs: Long? = null,
        val measuredRateHz: Double? = null,
        val haccM: Double? = null,
        val vaccM: Double? = null,
        val groundSpeedMps: Double? = null,
        val headingValid: Boolean = false,
        val numSv: Int? = null,
        val rtcmAgeMs: Long? = null,
        val subjectProfile: String? = null,
        val reason: String? = null,
        val networkConnected: Boolean = false,
        /** Age de la mesure lorsqu'elle quitte le serveur. */
        val serverAgeMs: Double? = null,
        /** Temps mesure entre la reception serveur et Android. */
        val downlinkAgeMs: Double? = null,
        /** Age total estime de la mesure au telephone. */
        val endToEndAgeMs: Double? = null
    ) {
        /** Position assez fraiche pour l'affichage et les calculs non critiques. */
        val fiable: Boolean
            get() = present && valid && networkConnected && streamStatus == "LIVE" &&
                rtk != StatutRtk.LOST && lat.isFinite() && lon.isFinite() &&
                (ageS ?: Double.POSITIVE_INFINITY) <= 0.50

        /** Barriere stricte pour toute future logique de controle. */
        val controleFiable: Boolean
            get() = fiable && rtk == StatutRtk.FIX &&
                (ageS ?: Double.POSITIVE_INFINITY) <= 0.30 &&
                (measuredRateHz ?: 0.0) >= 5.0 &&
                haccM != null && haccM <= 1.0 &&
                rtcmAgeMs != null && rtcmAgeMs <= 5_000L &&
                sourceSequence != null
    }

    fun parseStatut(s: String?): StatutRtk = when (s?.uppercase()) {
        "FIX" -> StatutRtk.FIX
        "FLOAT" -> StatutRtk.FLOAT
        "GPS" -> StatutRtk.GPS
        else -> StatutRtk.LOST
    }

    /** Lecture HTTP historique. Ne pas utiliser pour le suivi V4 a 10 Hz. */
    suspend fun lirePositionSujet(): PositionSujet = withContext(Dispatchers.IO) {
        val url = URL("$LEGACY_BASE_URL/api/rtk/sujet")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return@withContext PositionSujet(present = false)
            val texte = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(texte)
            if (!o.optBoolean("present", false)) return@withContext PositionSujet(present = false)

            val traceJson: JSONArray = o.optJSONArray("trace") ?: JSONArray()
            val trace = ArrayList<DoubleArray>(traceJson.length())
            for (i in 0 until traceJson.length()) {
                val p = traceJson.optJSONArray(i) ?: continue
                if (p.length() >= 2) trace.add(doubleArrayOf(p.optDouble(0), p.optDouble(1)))
            }
            val rtk = parseStatut(o.optString("rtk", "LOST"))
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            PositionSujet(
                present = true,
                valid = lat.isFinite() && lon.isFinite() && rtk != StatutRtk.LOST,
                lat = lat,
                lon = lon,
                altM = if (o.isNull("alt_m")) null else o.optDouble("alt_m"),
                rtk = rtk,
                ageS = if (o.isNull("age_s")) null else o.optDouble("age_s"),
                capDeg = if (o.isNull("hdg")) null else o.optDouble("hdg"),
                trace = trace,
                streamStatus = if (rtk == StatutRtk.LOST) "DISCONNECTED" else "LIVE",
                networkConnected = true,
                reason = "LEGACY_HTTP"
            )
        } catch (_: Exception) {
            PositionSujet(present = false, reason = "LEGACY_HTTP_ERROR")
        } finally {
            conn?.disconnect()
        }
    }
}
