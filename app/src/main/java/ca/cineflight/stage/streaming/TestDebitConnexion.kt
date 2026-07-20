package ca.cineflight.stage.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * TestDebitConnexion — mesure le DEBIT MONTANT (upload) reel de la connexion actuelle
 * et evalue si elle peut soutenir la qualite de diffusion demandee.
 *
 * POURQUOI l'upload : en live streaming, c'est le sens MONTANT (telephone -> YouTube)
 * qui est le goulot. Le download importe peu. On mesure donc un envoi reel.
 *
 * METHODE : on POST un bloc de donnees vers un endpoint qui accepte l'upload et on
 * chronometre. On utilise httpbin.org/post (echo, accepte n'importe quel corps).
 * Best-effort : en cas d'echec reseau, on renvoie un resultat Indetermine plutot
 * que de bloquer l'utilisateur.
 *
 * AUCUN secret n'est transmis (donnees de test aleatoires), rien n'est journalise de sensible.
 */
object TestDebitConnexion {

    private const val TAG = "LIVE_STREAM"

    /** Endpoint d'upload de test (echo). Aucune donnee utilisateur, que du remplissage. */
    private const val URL_UPLOAD = "https://httpbin.org/post"

    /** Taille du bloc envoye pour la mesure (1.5 Mo : assez pour une mesure stable). */
    private const val TAILLE_TEST_OCTETS = 1_500_000

    /** Marge de securite : on exige 20% de debit en plus que le strict besoin. */
    private const val MARGE = 1.2

    /**
     * Debits montants MINIMAUX recommandes (bit/s) pour un stream stable, par resolution.
     * Valeurs alignees sur les recommandations YouTube Live (H.264).
     */
    private const val MIN_720P_BPS = 3_000_000    // ~3 Mbps
    private const val MIN_1080P_BPS = 5_000_000   // ~5 Mbps

    /** Resultat d'un test de connexion, pret a afficher. */
    sealed interface Resultat {
        /** Mesure reussie : [debitMesureBps] mesure, [besoinBps] requis, [suffisant] verdict. */
        data class Mesure(
            val debitMesureBps: Long,
            val besoinBps: Int,
            val suffisant: Boolean,
        ) : Resultat

        /** Mesure impossible (reseau indisponible, endpoint injoignable...). */
        data class Indetermine(val raison: String) : Resultat
    }

    /**
     * Calcule le debit requis (bit/s) pour la qualite choisie :
     *  - si un debit manuel est fixe, c'est lui le besoin ;
     *  - sinon (Auto), on prend le minimum recommande selon la resolution.
     */
    fun besoinBps(quality: StreamQualityChoisie): Int {
        quality.bitrateBps?.let { return it }
        return when (quality.resolution) {
            StreamQualityChoisie.Resolution.P720 -> MIN_720P_BPS
            StreamQualityChoisie.Resolution.P1080 -> MIN_1080P_BPS
        }
    }

    /**
     * Mesure le debit montant reel et rend un verdict par rapport a [quality].
     * A appeler depuis une coroutine ; l'I/O reseau est faite sur Dispatchers.IO.
     */
    suspend fun mesurer(quality: StreamQualityChoisie): Resultat = withContext(Dispatchers.IO) {
        val besoin = besoinBps(quality)
        try {
            val donnees = ByteArray(TAILLE_TEST_OCTETS) { (it and 0xFF).toByte() }

            val debut = System.nanoTime()
            val conn = (URL(URL_UPLOAD).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 15_000
                setFixedLengthStreamingMode(donnees.size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            conn.outputStream.use { it.write(donnees); it.flush() }
            val code = conn.responseCode
            conn.inputStream.use { it.readBytes() }   // vide la reponse
            conn.disconnect()
            val secondes = (System.nanoTime() - debut) / 1_000_000_000.0

            if (code !in 200..299 || secondes <= 0.0) {
                return@withContext Resultat.Indetermine("Reponse serveur $code")
            }

            val debitBps = (donnees.size.toLong() * 8 / secondes).toLong()
            val suffisant = debitBps >= besoin * MARGE
            Log.i(TAG, "Test debit montant=${debitBps / 1000} kb/s besoin=${besoin / 1000} kb/s suffisant=$suffisant")
            Resultat.Mesure(debitMesureBps = debitBps, besoinBps = besoin, suffisant = suffisant)
        } catch (e: Exception) {
            Resultat.Indetermine(e.message ?: "erreur reseau")
        }
    }
}
