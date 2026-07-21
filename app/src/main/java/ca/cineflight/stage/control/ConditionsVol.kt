package ca.cineflight.stage.control

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ConditionsVol - recupere le verdict de conditions de vol (meteo) depuis le
 * serveur, pour affichage AVANT la generation de mission.
 *
 * Securite d'abord : le serveur renvoie un verdict (vert/jaune/rouge) deja
 * calcule selon le drone (vent, pluie, lumiere). L'app ne fait qu'afficher.
 * Best-effort : si l'appel echoue, on renvoie un etat "indisponible".
 *
 * TEMPERATURE : calculee COTE APP. Le serveur renvoie la temperature brute
 * (meteo_json.temperature_c) ; l'app la compare a la plage de fonctionnement
 * FABRICANT du drone reel (tempMin..tempMax, cf. CapacitesDrone.plageTemp) et
 * en deduit un facteur qui peut aggraver le verdict global.
 */
object ConditionsVol {

    private const val BASE_URL = "https://cineflight.ca"

    data class Facteur(val feu: String, val texte: String, val detail: String = "")
    data class Conditions(
        val disponible: Boolean,
        val global: String,
        val verdict: String,
        val vent: Facteur,
        val pluie: Facteur,
        val lumiere: Facteur,
        val temperature: Facteur
    )

    /** Appel reseau bloquant. tempMin/tempMax : plage FABRICANT du drone, en degres C. */
    fun recuperer(lat: Double, lon: Double, drone: String = "mini3",
                  tempMin: Int = -10, tempMax: Int = 40): Conditions {
        return try {
            val body = JSONObject().apply {
                put("lat", lat); put("lon", lon); put("drone", drone)
            }
            val conn = (URL("$BASE_URL/api/conditions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8000; readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code !in 200..299) return indisponible()
            parser(JSONObject(txt), tempMin, tempMax)
        } catch (e: Exception) {
            indisponible()
        }
    }

    private fun parser(j: JSONObject, tempMin: Int, tempMax: Int): Conditions {
        val f = j.optJSONObject("facteurs") ?: JSONObject()
        fun fac(nom: String): Facteur {
            val o = f.optJSONObject(nom) ?: return Facteur("indisponible", "$nom : non disponible")
            return Facteur(
                feu = o.optString("feu", "indisponible"),
                texte = o.optString("texte", ""),
                detail = o.optString("detail", "")
            )
        }
        val vent = fac("vent"); val pluie = fac("pluie"); val lumiere = fac("lumiere")
        val temperature = facteurTemp(j, tempMin, tempMax)

        var glob = j.optString("global", "indisponible")
        var verdict = j.optString("verdict", "Conditions non disponibles. Verifiez sur place.")
        if (temperature.feu == "rouge" && glob != "rouge") {
            glob = "rouge"
            verdict = "Vol deconseille : temperature hors de la plage du drone."
        } else if (temperature.feu == "jaune" && glob == "vert") {
            glob = "jaune"
            verdict = "Vol possible avec prudence (temperature proche des limites)."
        }
        return Conditions(
            disponible = j.optBoolean("disponible", false),
            global = glob,
            verdict = verdict,
            vent = vent, pluie = pluie, lumiere = lumiere,
            temperature = temperature
        )
    }

    /** Facteur temperature : compare la temperature brute a la plage FABRICANT du drone. */
    private fun facteurTemp(j: JSONObject, tmin: Int, tmax: Int): Facteur {
        val tc: Double? = try {
            val mj = JSONObject(j.optString("meteo_json", "{}"))
            if (mj.isNull("temperature_c")) null else mj.optDouble("temperature_c")
        } catch (_: Exception) { null }
        if (tc == null) return Facteur("indisponible", "Temperature : non disponible")
        val t = Math.round(tc).toInt()
        val marge = 5
        val feu = when {
            tc < tmin || tc > tmax -> "rouge"
            tc < tmin + marge || tc > tmax - marge -> "jaune"
            else -> "vert"
        }
        val txt = when {
            feu == "vert" -> "Temperature : $t°C (OK)"
            feu == "jaune" && tc < tmin + marge -> "Temperature : $t°C - froid, autonomie reduite"
            feu == "jaune" -> "Temperature : $t°C - chaud, risque de surchauffe"
            tc < tmin -> "Temperature : $t°C - sous la limite du drone (min $tmin°C)"
            else -> "Temperature : $t°C - au-dessus de la limite du drone (max $tmax°C)"
        }
        return Facteur(feu, txt, "plage drone $tmin a $tmax°C")
    }

    private fun indisponible() = Conditions(
        disponible = false, global = "indisponible",
        verdict = "Conditions meteo non disponibles. Verifiez sur place.",
        vent = Facteur("indisponible", "Vent : non disponible"),
        pluie = Facteur("indisponible", "Precipitations : non disponible"),
        lumiere = Facteur("indisponible", "Lumiere : non disponible"),
        temperature = Facteur("indisponible", "Temperature : non disponible")
    )

    fun couleur(feu: String): Int = when (feu) {
        "vert" -> 0xFF34C759.toInt()
        "jaune" -> 0xFFFF9500.toInt()
        "rouge" -> 0xFFFF3B30.toInt()
        else -> 0xFF8E8E93.toInt()
    }

    fun pastille(feu: String): String = when (feu) {
        "vert" -> "🟢"; "jaune" -> "🟡"; "rouge" -> "🔴"; else -> "⚪"
    }
}
