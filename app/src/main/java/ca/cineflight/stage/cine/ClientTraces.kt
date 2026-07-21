package ca.cineflight.stage.cine

import ca.cineflight.stage.control.GeoBarriere
import ca.cineflight.stage.control.MoniteurCorridor
import ca.cineflight.stage.control.ParcoursRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ClientTraces — REPERTOIRE DES TRACES cote Android.
 *
 * Lit le catalogue de traces NOMMES enregistres depuis preview3d (titre +
 * description + geometrie). "Tracer != voler" : l'app rappelle un trace quand
 * elle veut, puis l'alimente a :
 *   - ParcoursRoute      (type "corridor_route", vehicule sur route), ou
 *   - MoniteurCorridor   (type "corridor_suivi", sujet mobile a bandes).
 *
 * Calque sur ClientTrajectoireSuivi (HttpURLConnection + Dispatchers.IO, meme
 * BASE_URL, aucune dependance ajoutee). Source unique = ce que preview3d a valide.
 */
object ClientTraces {

    private const val BASE_URL = "https://cineflight.ca"

    /** Vue LEGERE d'un trace (liste). */
    data class ResumeTrace(
        val id: String,
        val titre: String,
        val description: String,
        val type: String,               // "corridor_route" | "corridor_suivi"
        val dateCreation: Double?,
        val nbPoints: Int,
        val lieuLat: Double?,
        val lieuLon: Double?,
        val deviceCible: String?
    )

    /** Trace COMPLET charge (geometrie + seuils + reglages). */
    data class TraceComplet(
        val id: String,
        val titre: String,
        val description: String,
        val type: String,
        val points: List<GeoBarriere.Point>,   // geometrie brute (WGS84)
        val seuilNormalM: Double = 10.0,
        val seuilToleranceM: Double = 25.0,
        val seuilPrudenceM: Double = 40.0,
        val decalageM: Double? = null,          // corridor_route : decalage lateral
        val cote: String? = null,
        val camera: String? = null,             // corridor_route
        val distM: Double? = null,              // corridor_suivi : distance de suivi
        val deviceCible: String? = null
    ) {
        /** Rail pret pour le profil vehicule sur route (corridor_route). */
        fun versParcoursRoute(): ParcoursRoute? =
            if (type == "corridor_route" && points.size >= 2)
                ParcoursRoute(points) else null

        /** Points prets pour le MoniteurCorridor (corridor_suivi). */
        fun versPointsMoniteur(): List<MoniteurCorridor.Point> =
            points.map { MoniteurCorridor.Point(it.lat, it.lon) }
    }

    /**
     * Liste tous les traces (leger). Retourne liste vide en cas d'erreur reseau.
     */
    suspend fun lister(): List<ResumeTrace> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/traces")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return@withContext emptyList()
            val texte = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(texte)
            val arr = o.optJSONArray("traces") ?: JSONArray()
            val res = ArrayList<ResumeTrace>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val lieu = t.optJSONObject("lieu")
                res.add(
                    ResumeTrace(
                        id = t.optString("id", ""),
                        titre = t.optString("titre", ""),
                        description = t.optString("description", ""),
                        type = t.optString("type", ""),
                        dateCreation = if (t.isNull("date_creation")) null else t.optDouble("date_creation"),
                        nbPoints = t.optInt("nb_points", 0),
                        lieuLat = lieu?.let { if (it.isNull("lat")) null else it.optDouble("lat") },
                        lieuLon = lieu?.let { if (it.isNull("lon")) null else it.optDouble("lon") },
                        deviceCible = if (t.isNull("device_cible")) null else t.optString("device_cible")
                    )
                )
            }
            res
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Charge un trace complet par id. Retourne null si introuvable / erreur.
     */
    suspend fun charger(id: String): TraceComplet? = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(id, "UTF-8")
        val url = URL("$BASE_URL/api/traces/$enc")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return@withContext null
            val texte = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(texte)
            val geo = o.optJSONObject("geometrie") ?: return@withContext null
            val ptsJson = geo.optJSONArray("points") ?: JSONArray()
            val pts = ArrayList<GeoBarriere.Point>(ptsJson.length())
            for (i in 0 until ptsJson.length()) {
                val p = ptsJson.optJSONArray(i) ?: continue
                if (p.length() >= 2) pts.add(GeoBarriere.Point(p.optDouble(0), p.optDouble(1)))
            }
            if (pts.size < 2) return@withContext null
            val seuils = o.optJSONObject("seuils_m") ?: JSONObject()
            val reg = o.optJSONObject("reglages") ?: JSONObject()
            TraceComplet(
                id = o.optString("id", id),
                titre = o.optString("titre", ""),
                description = o.optString("description", ""),
                type = o.optString("type", ""),
                points = pts,
                seuilNormalM = seuils.optDouble("normal", 10.0),
                seuilToleranceM = seuils.optDouble("tolerance", 25.0),
                seuilPrudenceM = seuils.optDouble("prudence", 40.0),
                decalageM = if (reg.isNull("decalage_m")) null else reg.optDouble("decalage_m"),
                cote = if (reg.isNull("cote")) null else reg.optString("cote"),
                camera = if (reg.isNull("camera")) null else reg.optString("camera"),
                distM = if (reg.isNull("dist_m")) null else reg.optDouble("dist_m"),
                deviceCible = if (o.isNull("device_cible")) null else o.optString("device_cible")
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
