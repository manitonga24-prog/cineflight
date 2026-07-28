package ca.cineflight.stage.cine

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ClientModele3D — envoie un jeu de photos au serveur pour RECONSTRUCTION 3D (2026-07-27).
 *
 * ⚠ DIFFÉRENCE MAJEURE AVEC LE PANORAMA : un panorama s'assemble en quelques minutes ; une
 * reconstruction photogrammétrique demande des DIZAINES DE MINUTES à des HEURES de calcul.
 * Ce client ne fait donc qu'ENVOYER et rendre l'URL de suivi. Le client regardera la page
 * plus tard — il n'attend pas devant son téléphone, et l'app ne bloque pas dessus.
 *
 * Les photos sont envoyées en PLEINE RÉSOLUTION, contrairement au panorama (réduit à
 * 3500 px). La photogrammétrie vit de détail fin : réduire les images, c'est perdre les
 * points d'appui qui font tenir le modèle.
 *
 * Bloquant : appeler depuis un fil de fond. Ne jette jamais.
 */
object ClientModele3D {

    private const val TAG = "ClientModele3D"

    /**
     * ⚠ CLOUDFLARE REFUSE TOUTE REQUÊTE DE PLUS DE 100 Mo sur les plans gratuits.
     *
     * Constaté le 2026-07-28 : un envoi de 249 Mo a été coupé AVANT d'atteindre le serveur
     * — rien dans les journaux nginx, rien dans ceux du service, et une erreur SSL sans
     * explication côté client. Un jeu de 96 photos pleine résolution approche le
     * gigaoctet : sans découpage, la capture 3D échouerait SYSTÉMATIQUEMENT, après le vol,
     * la batterie et le déplacement.
     *
     * 70 Mo par lot : la marge couvre l'enrobage multipart et une photo plus lourde que
     * prévu.
     */
    private const val LOT_MAX_OCTETS = 70L * 1000L * 1000L

    /** Regroupe les photos en lots dont le poids reste sous la limite. */
    private fun decouper(photos: List<File>): List<List<File>> {
        val lots = ArrayList<List<File>>()
        var courant = ArrayList<File>()
        var poids = 0L
        for (f in photos) {
            val t = f.length()
            // Une photo seule plus lourde que la limite part quand même seule : mieux vaut
            // un refus explicite du serveur qu'un lot dont on sait déjà qu'il échouera.
            if (courant.isNotEmpty() && poids + t > LOT_MAX_OCTETS) {
                lots.add(courant); courant = ArrayList(); poids = 0L
            }
            courant.add(f); poids += t
        }
        if (courant.isNotEmpty()) lots.add(courant)
        return lots
    }

    /**
     * @param onProgres (lot courant, nombre de lots) — l'envoi dure plusieurs minutes.
     * @return l'URL de la page de suivi/visualisation du modèle, ou null.
     */
    fun envoyer(
        baseUrl: String, titre: String, photos: List<File>,
        onProgres: (Int, Int) -> Unit = { _, _ -> },
    ): String? {
        if (photos.size < 12) {
            android.util.Log.w(TAG, "seulement ${photos.size} photos : reconstruction sans espoir")
            return null
        }
        val base = baseUrl.trimEnd('/')
        val lots = decouper(photos)
        android.util.Log.i(TAG, "${photos.size} photos en ${lots.size} lot(s)")
        var id: String? = null
        for ((i, lot) in lots.withIndex()) {
            onProgres(i + 1, lots.size)
            val url = if (id == null) "$base/api/modele3d" else "$base/api/modele3d/$id/ajouter"
            val rendu = envoyerLot(url, if (id == null) titre else null, lot) ?: run {
                android.util.Log.w(TAG, "échec au lot ${i + 1}/${lots.size}")
                return null
            }
            if (id == null) id = rendu.optString("modele_id", "").ifBlank { null }
            if (id == null) { android.util.Log.w(TAG, "identifiant absent"); return null }
        }
        // C'est SEULEMENT ici que le jeu est déclaré complet : tant que `finir` n'a pas
        // été appelé, l'atelier ne le voit pas et ne peut pas reconstruire un jeu partiel.
        val fin = envoyerLot("$base/api/modele3d/$id/finir", null, emptyList()) ?: run {
            android.util.Log.w(TAG, "clôture refusée"); return null
        }
        android.util.Log.i(TAG, "envoi terminé : ${fin.optInt("photos", -1)} photos")
        return "$base/modele3d/$id"
    }

    /** Une requête multipart. `titre` seulement au premier lot. Rend la réponse JSON. */
    private fun envoyerLot(url: String, titre: String?, lot: List<File>): JSONObject? {
        val boundary = "----CineFlight3D" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 900_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setChunkedStreamingMode(0)      // pas de mise en tampon : la mémoire du
                                                // téléphone ne tiendrait pas le lot entier
            }
            DataOutputStream(conn.outputStream).use { out ->
                if (titre != null) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"titre\"\r\n\r\n")
                    out.write(titre.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                for (f in lot) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"${f.name}\"\r\n")
                    out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                    f.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n")
                }
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }
            if (conn.responseCode != 200) {
                android.util.Log.w(TAG, "HTTP ${conn.responseCode} sur $url")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "envoi impossible : ${e.message}"); null
        } finally { conn?.disconnect() }
    }
}
