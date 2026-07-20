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
    private const val BASE_URL = "http://161.35.188.68:8095"

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

