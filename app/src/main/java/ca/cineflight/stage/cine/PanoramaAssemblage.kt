package ca.cineflight.stage.cine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PanoramaAssemblage : envoie une serie de photos au serveur pour assemblage
 * 360 HAUTE QUALITE (Hugin cote serveur), suit le job, recupere l'image finale.
 *
 * TOUT se passe sur un thread de fond : appeler assembler() depuis un thread
 * dedie. Les callbacks reviennent sur CE thread ; l'appelant repose sur l'UI.
 */
object PanoramaAssemblage {

    private const val TAG = "CineFlightPano"

    /** Reduit une photo (cote max en px) et l'ecrit en JPEG dans dossierTmp. */
    private fun reduire(src: File, dossierTmp: File, maxCote: Int, qualite: Int): File? {
        return try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, opt)
            val w = opt.outWidth; val h = opt.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            val grand = maxOf(w, h)
            while (grand / (sample * 2) >= maxCote) sample *= 2
            val opt2 = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(src.absolutePath, opt2) ?: return null
            val gr = maxOf(bmp.width, bmp.height)
            if (gr > maxCote) {
                val ratio = maxCote.toFloat() / gr
                val nb = Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
                if (nb != bmp) bmp.recycle()
                bmp = nb
            }
            val out = File(dossierTmp, "r_" + src.name.substringBeforeLast('.') + ".jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, qualite, it) }
            bmp.recycle()
            out
        } catch (e: Exception) { null }
    }

    /**
     * Assemble en 360 via le serveur. A APPELER SUR UN THREAD DE FOND.
     * onProgres(message, pct 0..100) ; onFini(fichier image ou null).
     */
    fun assembler(
        context: Context,
        photos: List<File>,
        baseUrl: String,
        maxCote: Int = 3500,
        largeurMax: Int = 12000,
        onProgres: (String, Int) -> Unit,
        onFini: (File?) -> Unit
    ) {
        try {
            if (photos.size < 2) { onProgres("Pas assez de photos.", 0); onFini(null); return }
            val tmp = File(context.cacheDir, "pano_tmp_" + System.currentTimeMillis()).apply { mkdirs() }
            val reduites = ArrayList<File>()
            for ((i, p) in photos.withIndex()) {
                onProgres("Preparation des photos", (i * 30) / photos.size)
                reduire(p, tmp, maxCote, 90)?.let { reduites.add(it) }
            }
            if (reduites.size < 2) { onProgres("Reduction impossible.", 0); onFini(null); return }
            android.util.Log.i(TAG, "reduites ${reduites.size}/${photos.size} - envoi au serveur")
            onProgres("Envoi au serveur", 30)
            val jobId = uploader(reduites, baseUrl, largeurMax)
            android.util.Log.i(TAG, "job_id=$jobId")
            if (jobId == null) { onProgres("Envoi echoue.", 0); onFini(null); return }
            var etat = "en_cours"; var essais = 0
            while (etat == "en_cours" && essais < 240) {
                Thread.sleep(5000); essais++
                val j = interrogerJob(baseUrl, jobId)
                etat = j?.optString("etat", "en_cours") ?: "en_cours"
                if (essais % 3 == 0) android.util.Log.i(TAG, "poll #$essais etat=$etat")
                onProgres("Assemblage en cours", (60 + essais.coerceAtMost(34)))
            }
            if (etat != "termine") { onProgres("Assemblage echoue.", 0); onFini(null); return }
            onProgres("Recuperation du panorama", 96)
            val outImg = File(context.getExternalFilesDir(null), "CineFlight/panorama_" + System.currentTimeMillis() + ".jpg")
            outImg.parentFile?.mkdirs()
            val ok = telechargerImage(baseUrl, jobId, outImg)
            try { tmp.deleteRecursively() } catch (_: Exception) {}
            android.util.Log.i(TAG, "resultat: " + (if (ok) outImg.absolutePath else "echec"))
            if (ok) { onProgres("Panorama pret", 100); onFini(outImg) }
            else { onProgres("Telechargement echoue.", 0); onFini(null) }
        } catch (e: Exception) {
            onProgres("Erreur : " + (e.message ?: "?"), 0); onFini(null)
        }
    }

    private fun uploader(photos: List<File>, baseUrl: String, largeurMax: Int): String? {
        val boundary = "----CineFlightPano" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl/api/panorama?largeur_max=$largeurMax")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 300000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setChunkedStreamingMode(0)
            }
            DataOutputStream(conn.outputStream).use { out ->
                for (f in photos) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"${f.name}\"\r\n")
                    out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                    f.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n")
                }
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }
            if (conn.responseCode != 200) { android.util.Log.w(TAG, "upload HTTP ${conn.responseCode}"); return null }
            val txt = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            JSONObject(txt).optString("job_id", "").ifBlank { null }
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun interrogerJob(baseUrl: String, jobId: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl/api/panorama/$jobId").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun telechargerImage(baseUrl: String, jobId: String, sortie: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl/api/panorama/$jobId/image").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 120000
            }
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input -> FileOutputStream(sortie).use { input.copyTo(it) } }
            sortie.length() > 0
        } catch (e: Exception) { false } finally { conn?.disconnect() }
    }
}
