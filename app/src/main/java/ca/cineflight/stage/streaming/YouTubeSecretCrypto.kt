package ca.cineflight.stage.streaming

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * YouTubeSecretCrypto — chiffrement local de la cle de diffusion YouTube.
 *
 * Utilise DIRECTEMENT l'Android Keystore (pas EncryptedSharedPreferences, deprecie) :
 * une cle symetrique AES-256 non-exportable est generee dans le materiel securise du
 * telephone (AndroidKeyStore). La cle en clair ne quitte JAMAIS le Keystore ; on ne
 * manipule que du chiffre.
 *
 * Format du blob produit : [ IV (12 octets) | ciphertext+tag GCM ]. L'IV est genere
 * aleatoirement par le Cipher a chaque chiffrement (randomizedEncryptionRequired) et
 * stocke en clair devant le ciphertext — c'est sans danger et necessaire au dechiffrement.
 *
 * SECURITE :
 *   - la cle Keystore est non-exportable, 256 bits, AES/GCM/NoPadding ;
 *   - pas d'authentification biometrique en V1 (setUserAuthenticationRequired(false)) ;
 *   - en cas d'echec de dechiffrement, on LEVE (jamais de valeur partielle) : l'appelant
 *     (le store) supprime alors le blob illisible et redemande la saisie de la cle.
 *   - cette classe ne journalise RIEN (ni cle, ni IV, ni ciphertext).
 */
internal object YouTubeSecretCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "cineflight_youtube_stream_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12          // 96 bits : taille recommandee pour GCM
    private const val GCM_TAG_BITS = 128      // tag d'authentification GCM

    /**
     * Chiffre [clair] et renvoie le blob [ IV | ciphertext ].
     * @throws si le Keystore est indisponible (cas exceptionnel).
     */
    fun chiffrer(clair: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtenirOuCreerCle())

        val iv = cipher.iv                                   // IV genere par le provider
        require(iv.size == IV_LENGTH) { "IV GCM inattendu" }
        val ciphertext = cipher.doFinal(clair.toByteArray(Charsets.UTF_8))

        return iv + ciphertext                               // concatenation IV || ciphertext
    }

    /**
     * Dechiffre un blob produit par [chiffrer].
     * @throws si le blob est corrompu / la cle a change / le tag GCM est invalide.
     *         Ne renvoie JAMAIS une valeur partielle.
     */
    fun dechiffrer(blob: ByteArray): String {
        require(blob.size > IV_LENGTH) { "Blob trop court" }

        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtenirCleExistante(), GCMParameterSpec(GCM_TAG_BITS, iv))

        val clair = cipher.doFinal(ciphertext)               // leve si tag GCM invalide
        return String(clair, Charsets.UTF_8)
    }

    /** Supprime la cle Keystore (ex. reinitialisation complete). Best-effort. */
    fun supprimerCle() {
        try {
            keystore().deleteEntry(KEY_ALIAS)
        } catch (_: Exception) { /* rien a faire */ }
    }

    // ------------------------------------------------------------------

    private fun keystore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Recupere la cle existante ; leve si absente (dechiffrement sans cle = impossible). */
    private fun obtenirCleExistante(): SecretKey {
        val entree = keystore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Cle de chiffrement absente")
        return entree.secretKey
    }

    /** Recupere la cle, ou la genere si elle n'existe pas encore. */
    private fun obtenirOuCreerCle(): SecretKey {
        val ks = keystore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)           // IV aleatoire impose par le systeme
            .setUserAuthenticationRequired(false)            // pas de biometrie en V1
            .build()

        generateur.init(spec)
        return generateur.generateKey()
    }
}
