package ca.cineflight.stage.cine

import ca.cineflight.stage.control.GeoBarriere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ClientGeoBarriere — récupère la géo-barrière (polygone de vol autorisé) depuis
 * le serveur (tracée et validée dans preview3d).
 *
 * L'app Android construit ainsi sa GeoBarriere avec le MÊME polygone que celui
 * validé dans preview3d (source unique). Calqué sur ClientRtkSujet /
 * ClientTrajectoireSuivi (HttpURLConnection + Dispatchers.IO, même BASE_URL).
 */
object ClientGeoBarriere {

    private const val BASE_URL = "http://161.35.188.68:8095"

    /** Géo-barrière + paramètres, ou present=false si aucune. */
    data class ZoneVol(
        val present: Boolean,
        val polygone: List<GeoBarriere.Point> = emptyList(),
        val margeM: Double = 5.0,
        val altMinM: Double? = null,
        val altMaxM: Double? = null,
        val distMinM: Double? = null,
        val distMaxM: Double? = null,
        // l'analyse d'obstacles de preview3d a-t-elle été passée ? L'app peut
        // refuser d'activer le suivi si la zone n'a pas été validée.
        val valideSecurite: Boolean = false
    ) {
        /** GeoBarriere prête à l'emploi (vide si polygone < 3 sommets). */
        fun barriere(): GeoBarriere = GeoBarriere(polygone)
    }

    /**
     * Lit la géo-barrière courante. present=false si aucune ou erreur réseau
     * (doctrine "non défini = non sûr" : sans zone, le suivi ne doit pas s'activer).
     */
    suspend fun lireZone(): ZoneVol =
        withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL/api/geobarriere")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) return@withContext ZoneVol(present = false)
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(texte)
                if (!o.optBoolean("present", false)) return@withContext ZoneVol(present = false)
                val polyJson: JSONArray = o.optJSONArray("polygone") ?: JSONArray()
                val pts = ArrayList<GeoBarriere.Point>(polyJson.length())
                for (i in 0 until polyJson.length()) {
                    val p = polyJson.optJSONArray(i) ?: continue
                    if (p.length() >= 2) {
                        pts.add(GeoBarriere.Point(p.optDouble(0), p.optDouble(1)))
                    }
                }
                ZoneVol(
                    present = pts.size >= 3,
                    polygone = pts,
                    margeM = o.optDouble("marge_m", 5.0),
                    altMinM = if (o.isNull("alt_min_m")) null else o.optDouble("alt_min_m"),
                    altMaxM = if (o.isNull("alt_max_m")) null else o.optDouble("alt_max_m"),
                    distMinM = if (o.isNull("dist_min_m")) null else o.optDouble("dist_min_m"),
                    distMaxM = if (o.isNull("dist_max_m")) null else o.optDouble("dist_max_m"),
                    valideSecurite = o.optBoolean("valide_securite", false)
                )
            } catch (e: Exception) {
                ZoneVol(present = false)
            } finally {
                conn?.disconnect()
            }
        }
}

