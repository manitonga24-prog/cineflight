package ca.cineflight.stage.sport.soccer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SoccerRailClient — charge le profil SOCCER_RAIL depuis le serveur CineFlight Web
 * (GET /api/soccer_rail/profil), dessine dans preview3d. Le JSON est parse par
 * [SoccerRailProfileParser] (deja teste). Meme style HTTP que le reste du projet
 * (HttpURLConnection, base 161.35.188.68:8095, appel sur Dispatchers.IO).
 *
 * Fail-safe : au moindre probleme (reseau, absent, JSON invalide), retourne null.
 * L'appelant garde alors son rail d'essai / n'active pas le mouvement.
 */
object SoccerRailClient {

    private const val BASE_URL = "http://161.35.188.68:8095"

    /** Resultat du chargement, pour distinguer les cas cote UI. */
    sealed interface Resultat {
        data class Ok(val profile: SoccerRailProfile) : Resultat
        data object Absent : Resultat            // present=false (aucun profil enregistre)
        data class Erreur(val message: String) : Resultat
    }

    /** Entree de la liste des rails (vue legere pour un menu de choix). */
    data class RailEntree(val id: String, val nom: String, val longueurM: Int)

    /**
     * Liste tous les rails enregistres sur le serveur (id + nom + longueur).
     * Retourne une liste vide en cas d'erreur ou d'absence (fail-safe).
     */
    suspend fun lister(): List<RailEntree> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/soccer_rail/liste")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return@withContext emptyList()
            val brut = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(brut).optJSONArray("rails") ?: return@withContext emptyList()
            val out = ArrayList<RailEntree>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue   // ignore les anciens rails sans id
                out.add(RailEntree(
                    id = id,
                    nom = o.optString("nom", "(sans nom)"),
                    longueurM = o.optInt("longueur_m", 0),
                ))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Charge un rail precis par son id. */
    suspend fun chargerParId(id: String): Resultat = withContext(Dispatchers.IO) {
        val brut = try {
            val url = URL("$BASE_URL/api/soccer_rail/profil/$id")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 404) return@withContext Resultat.Absent
            if (conn.responseCode != 200) return@withContext Resultat.Erreur("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return@withContext Resultat.Erreur(e.message ?: e.javaClass.simpleName)
        }
        when (val r = SoccerRailProfileParser.parse(brut)) {
            is SoccerRailProfileParser.Resultat.Ok -> Resultat.Ok(r.profile)
            is SoccerRailProfileParser.Resultat.Erreur -> Resultat.Erreur(r.message)
        }
    }

    /**
     * Recupere le dernier profil rail enregistre. A appeler depuis une coroutine.
     */
    suspend fun charger(): Resultat = withContext(Dispatchers.IO) {
        val brut = try {
            val url = URL("$BASE_URL/api/soccer_rail/profil")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                return@withContext Resultat.Erreur("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return@withContext Resultat.Erreur(e.message ?: e.javaClass.simpleName)
        }

        // Le serveur renvoie {"present":false} si rien n'est enregistre.
        try {
            val obj = JSONObject(brut)
            if (!obj.optBoolean("present", true)) {
                return@withContext Resultat.Absent
            }
        } catch (_: Exception) {
            return@withContext Resultat.Erreur("JSON illisible")
        }

        // Parse le profil via le parseur teste (il ignore les champs en trop : present/nom/ts).
        when (val r = SoccerRailProfileParser.parse(brut)) {
            is SoccerRailProfileParser.Resultat.Ok -> Resultat.Ok(r.profile)
            is SoccerRailProfileParser.Resultat.Erreur -> Resultat.Erreur(r.message)
        }
    }
}
