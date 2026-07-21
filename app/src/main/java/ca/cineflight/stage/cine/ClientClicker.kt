package ca.cineflight.stage.cine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ClientClicker — RELAIS TELECOMMANDE (presenter) cote Android.
 *
 * Le sujet tient un clicker ; le Pi transforme chaque appui en evenement UNIQUE
 * (event_id croissant) poste au serveur. Ce client :
 *   1. lit le dernier evenement NON acquitte  (GET /api/clicker/event),
 *   2. le dedoublonne par event_id (double filet avec le serveur),
 *   3. laisse l'appelant l'EXECUTER (VALIDATION_SEULE : afficher, ne pas bouger le drone),
 *   4. envoie l'accuse                         (POST /api/clicker/ack).
 *
 * Calque sur ClientTraces (HttpURLConnection + Dispatchers.IO, meme BASE_URL,
 * aucune dependance ajoutee). VALIDATION_SEULE : ce client transporte l'INTENTION ; c'est
 * le NoyauSecurite, en aval, qui reste souverain sur tout mouvement reel.
 */
object ClientClicker {

    private const val BASE_URL = "https://cineflight.ca"

    /** Commandes possibles (miroir de la liste blanche serveur). */
    enum class Commande {
        RAPPROCHE, RAPPROCHE_PRONONCE, ELOIGNE, REVEAL_ARRIERE,
        PAUSE, REPRENDRE, CHANGER_COTE, ORBITE, HOLD, INCONNUE;

        companion object {
            fun depuis(txt: String?): Commande =
                entries.firstOrNull { it.name == (txt ?: "").trim().uppercase() } ?: INCONNUE
        }
    }

    /** Un evenement clicker recu du serveur. */
    data class EvenementClicker(
        val eventId: Long,
        val commande: Commande,
        val commandeBrute: String,
        val timestamp: Double?,
        val source: String?
    )

    /** Plus grand event_id deja traite cote Android (dedoublonnage local). */
    @Volatile
    private var dernierTraite: Long = 0L

    /**
     * Recupere le dernier evenement NON acquitte ET NON encore traite localement.
     * Retourne null si rien de nouveau (pas d'evenement, deja acquitte, ou deja
     * traite ici). Ne leve jamais : renvoie null en cas d'erreur reseau.
     */
    suspend fun prochainEvenement(): EvenementClicker? = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/api/clicker/event")
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
            if (!o.optBoolean("present", false)) return@withContext null
            val id = o.optLong("event_id", 0L)
            if (id <= dernierTraite) return@withContext null   // deja traite ici
            val brute = o.optString("commande", "")
            EvenementClicker(
                eventId = id,
                commande = Commande.depuis(brute),
                commandeBrute = brute,
                timestamp = if (o.isNull("timestamp")) null else o.optDouble("timestamp"),
                source = if (o.isNull("source")) null else o.optString("source")
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Accuse reception d'un evenement (POST /api/clicker/ack) et le marque traite
     * localement pour ne jamais le rejouer. A appeler APRES l'avoir execute.
     * Retourne true si l'ack a ete pris en compte cote reseau (best-effort).
     */
    suspend fun acquitter(eventId: Long, status: String = "accepted"): Boolean =
        withContext(Dispatchers.IO) {
            // marquer traite localement AVANT le reseau : meme si l'ack echoue, on
            // ne rejouera pas (le serveur re-servira, mais le filtre local bloque).
            if (eventId > dernierTraite) dernierTraite = eventId
            val url = URL("$BASE_URL/api/clicker/ack")
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                    .put("event_id", eventId)
                    .put("status", status)
                    .toString()
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) return@withContext false
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(texte).optBoolean("ok", false)
            } catch (e: Exception) {
                false
            } finally {
                conn?.disconnect()
            }
        }

    /** Reinitialise le dedoublonnage local (ex. changement de sujet/session). */
    fun reset() {
        dernierTraite = 0L
    }
}
