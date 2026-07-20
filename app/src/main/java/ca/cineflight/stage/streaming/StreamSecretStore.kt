package ca.cineflight.stage.streaming

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * StreamSecretStore — rangement local et chiffre d'un secret de diffusion (cle YouTube).
 *
 * Abstraction volontairement minuscule : le reste de l'app (service, UI) ne connait ni
 * le Keystore ni le format de stockage. Un faux store rendra le service testable.
 */
internal interface StreamSecretStore {

    /** Chiffre puis persiste [secret]. Ecrase un secret precedent. */
    fun enregistrer(secret: String)

    /**
     * Renvoie le secret en clair, ou null si aucun secret n'est stocke OU si le blob
     * est illisible (dans ce dernier cas, le blob corrompu est SUPPRIME au passage).
     * Ne renvoie JAMAIS une valeur partielle.
     */
    fun lire(): String?

    /** true s'il existe un secret stocke (sans le dechiffrer). */
    fun existe(): Boolean

    /** Supprime le secret stocke. Sans effet s'il n'y en a pas. */
    fun effacer()
}

/**
 * Implementation YouTube : blob (IV+ciphertext) encode en Base64 dans un
 * SharedPreferences PRIVE dedie ([PREFS_NAME]), chiffre par [YouTubeSecretCrypto].
 *
 * SECURITE (regles verbatim) :
 *   - le fichier [PREFS_NAME].xml doit etre EXCLU des sauvegardes (voir data_extraction_rules
 *     et full_backup_content, references au manifest) ;
 *   - sur echec de dechiffrement : supprimer le blob illisible, renvoyer null, ne pas
 *     demarrer de flux (c'est l'UI qui affichera « Cle YouTube a saisir de nouveau ») ;
 *   - ne JAMAIS journaliser la cle, le ciphertext ou l'IV. Les logs ici sont neutres.
 */
internal class YouTubeStreamSecretStore(
    context: Context,
) : StreamSecretStore {

    private companion object {
        const val PREFS_NAME = "youtube_live_secrets"   // -> youtube_live_secrets.xml (exclu du backup)
        const val CLE_BLOB = "yt_stream_key_blob"
        const val TAG = "LIVE_STREAM"
    }

    // applicationContext : pas de fuite d'Activity.
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun enregistrer(secret: String) {
        val blob = YouTubeSecretCrypto.chiffrer(secret)
        val base64 = Base64.encodeToString(blob, Base64.NO_WRAP)
        prefs.edit().putString(CLE_BLOB, base64).apply()
        Log.i(TAG, "Cle de diffusion enregistree (chiffree) sur l'appareil")  // neutre, pas de secret
    }

    override fun lire(): String? {
        val base64 = prefs.getString(CLE_BLOB, null) ?: return null

        return try {
            val blob = Base64.decode(base64, Base64.NO_WRAP)
            YouTubeSecretCrypto.dechiffrer(blob)
        } catch (e: Exception) {
            // Blob illisible (cle Keystore perdue, corruption...) : on le supprime et on
            // renvoie null. AUCUNE valeur partielle. L'UI redemandera la saisie.
            Log.w(TAG, "Cle de diffusion illisible : suppression du blob corrompu")
            effacer()
            null
        }
    }

    override fun existe(): Boolean = prefs.contains(CLE_BLOB)

    override fun effacer() {
        prefs.edit().remove(CLE_BLOB).apply()
    }
}
