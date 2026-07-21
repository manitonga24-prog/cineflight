package ca.cineflight.stage.cine

import ca.cineflight.stage.control.MoniteurCorridor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ClientTrajectoireSuivi — récupère la trajectoire PRÉVUE du sujet depuis le
 * serveur (celle validée dans preview3d à la génération du travelling).
 *
 * L'app Android alimente ainsi son MoniteurCorridor avec la MÊME trajectoire que
 * celle affichée/validée dans preview3d (source unique). Calqué sur ClientRtkSujet
 * (HttpURLConnection + Dispatchers.IO, même BASE_URL, aucune dépendance ajoutée).
 */
object ClientTrajectoireSuivi {

    private const val BASE_URL = "https://cineflight.ca"

    /** Trajectoire prévue + seuils + paramètres, ou present=false si aucune. */
    data class TrajectoireSuivi(
        val present: Boolean,
        val points: List<MoniteurCorridor.Point> = emptyList(),
        val seuilNormalM: Double = 10.0,
        val seuilToleranceM: Double = 25.0,
        val seuilPrudenceM: Double = 40.0,
        val vitesseSujetKmh: Double? = null,
        val distanceDroneSujetM: Double? = null,
        val cote: String? = null,
        val hauteurAglM: Double? = null,
        val missionId: String? = null
    )

    /**
     * Lit la trajectoire prévue courante. Retourne present=false si aucune
     * trajectoire enregistrée ou en cas d'erreur réseau (le MoniteurCorridor
     * traitera l'absence de trajectoire comme "corridor non défini").
     */
    suspend fun lireTrajectoire(): TrajectoireSuivi =
        withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL/api/corridor_suivi/trajectoire")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) return@withContext TrajectoireSuivi(present = false)
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(texte)
                if (!o.optBoolean("present", false)) {
                    return@withContext TrajectoireSuivi(present = false)
                }
                val trajJson: JSONArray = o.optJSONArray("trajectoire") ?: JSONArray()
                val pts = ArrayList<MoniteurCorridor.Point>(trajJson.length())
                for (i in 0 until trajJson.length()) {
                    val p = trajJson.optJSONArray(i) ?: continue
                    if (p.length() >= 2) {
                        pts.add(MoniteurCorridor.Point(p.optDouble(0), p.optDouble(1)))
                    }
                }
                val seuils = o.optJSONObject("seuils_m") ?: JSONObject()
                TrajectoireSuivi(
                    present = pts.size >= 2,
                    points = pts,
                    seuilNormalM = seuils.optDouble("normal", 10.0),
                    seuilToleranceM = seuils.optDouble("tolerance", 25.0),
                    seuilPrudenceM = seuils.optDouble("prudence", 40.0),
                    vitesseSujetKmh = if (o.isNull("vitesse_sujet_kmh")) null else o.optDouble("vitesse_sujet_kmh"),
                    distanceDroneSujetM = if (o.isNull("distance_drone_sujet_m")) null else o.optDouble("distance_drone_sujet_m"),
                    cote = if (o.isNull("cote")) null else o.optString("cote"),
                    hauteurAglM = if (o.isNull("hauteur_agl_m")) null else o.optDouble("hauteur_agl_m"),
                    missionId = if (o.isNull("mission_id")) null else o.optString("mission_id")
                )
            } catch (e: Exception) {
                TrajectoireSuivi(present = false)
            } finally {
                conn?.disconnect()
            }
        }
}

