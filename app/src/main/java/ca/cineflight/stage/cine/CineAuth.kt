package ca.cineflight.stage.cine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * CineAuth — authentification de l'utilisateur aupres du serveur Explorer.
 *
 * Flux :
 *   1. login(ctx, username, password) -> POST /api/auth/login
 *      -> recoit un jeton JWT, le stocke dans SharedPreferences.
 *   2. token(ctx) -> lit le jeton stocke (ou null).
 *   3. estConnecte(ctx) -> true si un jeton est present.
 *   4. deconnexion(ctx) -> efface le jeton.
 *
 * Le jeton (valable 30 jours cote serveur) est ensuite passe aux appels
 * authentifies : AnalyseurLieu.listerMesMissions(token) /
 * telechargerMissionParId(id, token).
 *
 * SECURITE : le jeton donne acces aux missions de l'utilisateur. Il est
 * stocke dans les prefs privees de l'app (non accessibles aux autres apps).
 */
object CineAuth {

    private const val PREFS = "cineflight_auth"
    private const val CLE_TOKEN = "jwt_token"
    private const val CLE_USER = "username"
    // HTTPS (chiffre) : protege le JWT et la cle de diffusion en transit.
    // Le reverse-proxy de cineflight.ca route /api/ vers uvicorn (verifie : 401 sur /api/stream_key).
    private const val BASE_URL = "https://cineflight.ca"

    // ── Stockage local ──────────────────────────────────────────────
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String? =
        prefs(ctx).getString(CLE_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun username(ctx: Context): String? =
        prefs(ctx).getString(CLE_USER, null)

    fun estConnecte(ctx: Context): Boolean = token(ctx) != null

    fun deconnexion(ctx: Context) {
        prefs(ctx).edit().remove(CLE_TOKEN).remove(CLE_USER).apply()
    }

    private fun enregistrer(ctx: Context, token: String, username: String) {
        prefs(ctx).edit()
            .putString(CLE_TOKEN, token)
            .putString(CLE_USER, username)
            .apply()
    }

    // ── Login ───────────────────────────────────────────────────────
    /** Tente de se connecter. Renvoie ResultatLogin (succes + message). */
    data class ResultatLogin(val succes: Boolean, val message: String)

    suspend fun login(ctx: Context, username: String, password: String): ResultatLogin =
        withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL/api/auth/login")
            var conn: HttpURLConnection? = null
            try {
                val corps = JSONObject().apply {
                    put("username", username.trim())
                    put("password", password)
                }
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                val code = conn.responseCode
                if (code == 200) {
                    val texte = conn.inputStream.bufferedReader().use { it.readText() }
                    val o = JSONObject(texte)
                    val token = o.optString("token", "")
                    val user = o.optString("username", username.trim())
                    if (token.isBlank()) {
                        return@withContext ResultatLogin(false, "Reponse sans jeton")
                    }
                    enregistrer(ctx, token, user)
                    ResultatLogin(true, "Connecte")
                } else if (code == 401) {
                    ResultatLogin(false, "Identifiant ou mot de passe invalide")
                } else {
                    ResultatLogin(false, "Le serveur a repondu $code")
                }
            } catch (e: Exception) {
                ResultatLogin(false, "Connexion au serveur impossible : "
                    + (e.message ?: e.javaClass.simpleName))
            } finally {
                conn?.disconnect()
            }
        }

    // ── Cle de diffusion YouTube (partagee via le compte, serveur CineFlight) ──
    /**
     * Recupere la CLE DE DIFFUSION enregistree sur le compte via /api/stream_key.
     * Permet de saisir la cle UNE FOIS sur le web (Parametres) et de la retrouver
     * automatiquement dans l'app, sans copier-coller sur le telephone.
     *
     * SECURITE : la cle est un secret ; ne JAMAIS la journaliser. Transitee sur le
     * canal authentifie (Bearer JWT), comme les missions. Renvoie null si absente,
     * non connecte, ou reseau indisponible.
     *
     * Reponse serveur attendue (JSON) : { "stream_key": "xxxx-xxxx-...", "server_url": "rtmps://a.rtmps.youtube.com/live2" }
     * (server_url facultatif ; l'app garde son defaut si absent.)
     */
    /**
     * @param cle       cle de diffusion YouTube (secret ; peut etre vide si seule la regie est configuree).
     * @param serveurUrl URL serveur YouTube (defaut rtmps si absent).
     * @param regieUrl  URL COMPLETE de la regie/distributeur (destination alternative ; vide si non configuree).
     */
    data class CleStream(val cle: String, val serveurUrl: String?, val regieUrl: String? = null)

    suspend fun recupererCleStream(ctx: Context): CleStream? =
        withContext(Dispatchers.IO) {
            val t = token(ctx) ?: return@withContext null
            val url = URL("$BASE_URL/api/stream_key")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) return@withContext null
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(texte)
                val cle = o.optString("stream_key", "").trim()
                val regie = o.optString("regie_url", "").trim().takeIf { it.isNotBlank() }
                // Rien d'exploitable si ni cle YouTube ni URL regie.
                if (cle.isBlank() && regie == null) return@withContext null
                val serveur = o.optString("server_url", "").trim().takeIf { it.isNotBlank() }
                CleStream(cle, serveur, regie)
            } catch (_: Exception) {
                null   // reseau/serveur indisponible : l'app retombe sur la saisie manuelle
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * (Optionnel) Enregistre la cle depuis l'app vers le compte (/api/stream_key, POST).
     * Utile si tu veux aussi pouvoir POUSSER une cle depuis le telephone. Renvoie true
     * si le serveur a accepte. Le stockage principal reste le web (Parametres).
     */
    suspend fun envoyerCleStream(ctx: Context, cle: String): Boolean =
        withContext(Dispatchers.IO) {
            val t = token(ctx) ?: return@withContext false
            val url = URL("$BASE_URL/api/stream_key")
            var conn: HttpURLConnection? = null
            try {
                val corps = JSONObject().apply { put("stream_key", cle.trim()) }
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                conn.responseCode in 200..299
            } catch (_: Exception) {
                false
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * Termine le direct YouTube cote serveur (POST /api/youtube/stop_live).
     * Le serveur passe le broadcast en "complete" ET efface le watch_url, pour que la
     * page publique /live/{user} repasse sur l'ecran d'attente au lieu d'afficher une
     * video morte (« Video non disponible »). A appeler quand l'operateur arrete le direct.
     * Non bloquant : si non connecte / reseau absent / pas de direct, renvoie false sans lever.
     */
    suspend fun arreterLiveYoutube(ctx: Context): Boolean =
        withContext(Dispatchers.IO) {
            val t = token(ctx) ?: return@withContext false
            val url = URL("$BASE_URL/api/youtube/stop_live")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                }
                // Corps vide : stop_live n'attend aucun parametre.
                conn.outputStream.use { it.write("{}".toByteArray()) }
                conn.responseCode in 200..299
            } catch (_: Exception) {
                false
            } finally {
                conn?.disconnect()
            }
        }

    /** Verifie que le jeton stocke est encore valide (via /api/auth/me).
     *  Utile au lancement : si false, demander une reconnexion. */
    suspend fun verifierToken(ctx: Context): Boolean =
        withContext(Dispatchers.IO) {
            val t = token(ctx) ?: return@withContext false
            val url = URL("$BASE_URL/api/auth/me")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $t")
                }
                conn.responseCode == 200
            } catch (e: Exception) {
                // pas de reseau : on ne supprime PAS le jeton (mode terrain),
                // on laisse l'app tenter ; les appels echoueront proprement.
                true
            } finally {
                conn?.disconnect()
            }
        }
}

