package ca.cineflight.stage.sport.soccer

import org.json.JSONArray
import org.json.JSONObject

/**
 * SoccerRailProfileParser — lit et VALIDE un profil SOCCER_RAIL (JSON du Web) vers
 * un [SoccerRailProfile]. Style aligne sur le projet (org.json).
 *
 * DOCTRINE : fail-closed. Tout profil incomplet, mal type, hors bornes, ou de mode
 * inattendu est REJETE avec un message clair, plutot que produire un profil bancal.
 * Le resultat est explicite : [Resultat.Ok] ou [Resultat.Erreur].
 *
 * SECURITE : un profil valide n'AUTORISE rien par lui-meme ; il decrit seulement la
 * mission. Le respect du verdict de securite et des bornes est a la charge des
 * couches de commande ulterieures.
 */
object SoccerRailProfileParser {

    const val MODE_ATTENDU = "SOCCER_RAIL"

    sealed interface Resultat {
        data class Ok(val profile: SoccerRailProfile) : Resultat
        data class Erreur(val message: String) : Resultat
    }

    /** Parse depuis une chaine JSON. */
    fun parse(json: String): Resultat = try {
        parse(JSONObject(json))
    } catch (e: Exception) {
        Resultat.Erreur("JSON invalide : ${e.message}")
    }

    /** Parse depuis un JSONObject deja construit. */
    fun parse(root: JSONObject): Resultat {
        // 1) Mode.
        val mode = root.optString("mode", "")
        if (mode != MODE_ATTENDU) {
            return Resultat.Erreur("mode inattendu : \"$mode\" (attendu \"$MODE_ATTENDU\")")
        }

        // 2) Rail (start + end obligatoires, coordonnees finies).
        val start = lirePoint(root.optJSONObject("rail_start"))
            ?: return Resultat.Erreur("rail_start manquant ou invalide")
        val end = lirePoint(root.optJSONObject("rail_end"))
            ?: return Resultat.Erreur("rail_end manquant ou invalide")
        if (start.lat == end.lat && start.lon == end.lon) {
            return Resultat.Erreur("rail degenere : start == end")
        }

        // 3) Altitude et vitesse : presentes et strictement positives.
        val altitude = root.optDouble("altitude_agl_m", Double.NaN)
        if (!altitude.isFinite() || altitude <= 0.0) {
            return Resultat.Erreur("altitude_agl_m invalide : $altitude")
        }
        val maxSpeed = root.optDouble("max_speed_mps", Double.NaN)
        if (!maxSpeed.isFinite() || maxSpeed <= 0.0) {
            return Resultat.Erreur("max_speed_mps invalide : $maxSpeed")
        }

        // 4) Position de securite dans [0,1].
        val safe = root.optDouble("safe_position", Double.NaN)
        if (!safe.isFinite() || safe < 0.0 || safe > 1.0) {
            return Resultat.Erreur("safe_position hors [0,1] : $safe")
        }

        // 5) Verdict de securite obligatoire.
        val verdictObj = root.optJSONObject("safety_verdict")
            ?: return Resultat.Erreur("safety_verdict manquant")
        val verdict = SoccerRailProfile.SafetyVerdict(
            approved = verdictObj.optBoolean("approved", false),
            reason = verdictObj.optString("reason", ""),
        )

        // 6) Zones (optionnelles ; polygones de points valides).
        val spectatorZones = lirePolygones(root.optJSONArray("spectator_zones"))
        val takeoffZone = lirePolygone(root.opt("takeoff_zone"))
        val terrain = lirePolygone(root.opt("terrain"))   // contour du terrain (>=3 sommets)

        return Resultat.Ok(
            SoccerRailProfile(
                mode = mode,
                rail = DroneRail(start = start, end = end),
                altitudeAglM = altitude,
                maxSpeedMps = maxSpeed,
                safePosition = safe.toFloat(),
                spectatorZones = spectatorZones,
                takeoffZone = takeoffZone,
                terrain = terrain,
                safetyVerdict = verdict,
            )
        )
    }

    // --- helpers ---

    private fun lirePoint(o: JSONObject?): RailPoint? {
        if (o == null) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return RailPoint(lat = lat, lon = lon)
    }

    private fun lirePolygone(any: Any?): List<RailPoint> {
        // takeoff_zone peut etre {} (vide) ou un tableau de points.
        val arr = any as? JSONArray ?: return emptyList()
        val pts = ArrayList<RailPoint>(arr.length())
        for (i in 0 until arr.length()) {
            lirePoint(arr.optJSONObject(i))?.let { pts.add(it) }
        }
        return pts
    }

    private fun lirePolygones(arr: JSONArray?): List<List<RailPoint>> {
        if (arr == null) return emptyList()
        val zones = ArrayList<List<RailPoint>>(arr.length())
        for (i in 0 until arr.length()) {
            val poly = lirePolygone(arr.optJSONArray(i))
            if (poly.isNotEmpty()) zones.add(poly)
        }
        return zones
    }
}
