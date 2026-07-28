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
     * Lien de la VISIONNEUSE 360/VR du dernier panorama assemblé (2026-07-26), ou null.
     * Le même lien fonctionne en casque (Quest, immersion WebXR), sur téléphone
     * (gyroscope) et sur ordinateur (souris) — rien à installer côté client.
     */
    @Volatile var dernierLienVr: String? = null
        private set

    /** Identifiant serveur du dernier panorama assemblé — sert à composer une VISITE
     *  (plusieurs panoramas reliés) sans recopier aucune image. */
    @Volatile var dernierJobId: String? = null
        private set

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
        /**
         * Cap et inclinaison de CHAQUE cliché, dans l'ordre de `photos`. L'app les connaît
         * exactement (`PanoramaGrille`) ; sans eux, le serveur doit les redécouvrir à partir
         * des pixels — ce qui échoue sur le ciel (aucune texture) et, surtout, fait
         * atterrir deux panoramas d'une même paire stéréo dans des repères DIFFÉRENTS.
         * Mesuré au vol du 2026-07-27 : 2,6° d'écart vertical entre les deux yeux.
         * null = ancien comportement, strictement inchangé.
         */
        angles: List<Pair<Float, Float>>? = null,
        onProgres: (String, Int) -> Unit,
        onFini: (File?) -> Unit
    ) {
        try {
            // ⚠ REMIS À ZÉRO À CHAQUE APPEL. `dernierJobId` est lu par l'appelant APRÈS
            // chaque lot ; s'il gardait la valeur du lot précédent, un lot en échec
            // ferait croire à une réussite et le MÊME panorama serait compté deux fois.
            dernierJobId = null
            dernierLienVr = null
            if (photos.size < 2) { onProgres("Pas assez de photos.", 0); onFini(null); return }
            val tmp = File(context.cacheDir, "pano_tmp_" + System.currentTimeMillis()).apply { mkdirs() }
            val reduites = ArrayList<File>()
            val indicesConserves = HashSet<Int>()
            for ((i, p) in photos.withIndex()) {
                onProgres("Preparation des photos", (i * 30) / photos.size)
                reduire(p, tmp, maxCote, 90)?.let { reduites.add(it); indicesConserves.add(i) }
            }
            if (reduites.size < 2) { onProgres("Reduction impossible.", 0); onFini(null); return }
            android.util.Log.i(TAG, "reduites ${reduites.size}/${photos.size} - envoi au serveur")
            onProgres("Envoi au serveur", 30)
            // ⚠ Les angles suivent les photos RÉELLEMENT envoyées : si la réduction en a
            // écarté une, l'angle correspondant doit sauter aussi, sinon tout le reste est
            // décalé d'un cran et l'assemblage guidé serait pire que l'automatique.
            val anglesEnvoyes = if (angles == null || angles.size != photos.size) {
                if (angles != null) android.util.Log.w(TAG,
                    "angles ignorés : ${angles.size} pour ${photos.size} photos")
                null
            } else photos.indices.filter { indicesConserves.contains(it) }.map { angles[it] }
            val jobId = uploader(reduites, baseUrl, largeurMax, anglesEnvoyes)
            android.util.Log.i(TAG, "job_id=$jobId")
            if (jobId == null) { onProgres("Envoi echoue.", 0); onFini(null); return }
            var etat = "en_cours"; var essais = 0
            // `attente_atelier` sort de la boucle comme un état FINAL : inutile
            // d'interroger vingt minutes un serveur qui n'assemblera pas.
            while (etat == "en_cours" && essais < 240) {
                Thread.sleep(5000); essais++
                val j = interrogerJob(baseUrl, jobId)
                etat = j?.optString("etat", "en_cours") ?: "en_cours"
                if (essais % 3 == 0) android.util.Log.i(TAG, "poll #$essais etat=$etat")
                onProgres("Assemblage en cours", (60 + essais.coerceAtMost(34)))
            }
            // ⚠ « attente_atelier » N'EST PAS UN ÉCHEC (2026-07-28).
            //
            // Depuis que l'assemblage est délégué au PC — `cpfind` occupait 1,26 Go et
            // quinze minutes de processeur sur un droplet d'un seul cœur —, le serveur
            // REÇOIT les photos et s'arrête là. Il ne rendra donc jamais `termine`.
            // L'ancien code concluait « Assemblage échoué » alors que tout allait bien :
            // aucun lien pour l'utilisateur, et la tâche restant en file, il aurait tout
            // renvoyé une seconde fois.
            //
            // Ce qui compte est acquis dès cet instant : les photos sont SUR LE SERVEUR.
            // Le lien est donc valide tout de suite — la page affichera le panorama dès
            // que l'atelier l'aura déposé.
            if (etat == "attente_atelier") {
                dernierJobId = jobId
                dernierLienVr = "$baseUrl/vr/$jobId"
                android.util.Log.i(TAG, "delegue a l'atelier : $dernierLienVr")
                try { tmp.deleteRecursively() } catch (_: Exception) {}
                onProgres("Photos reçues — assemblage à l'atelier", 100)
                onFini(null)      // pas d'image LOCALE : elle n'existe pas encore
                return
            }
            if (etat != "termine") { onProgres("Assemblage echoue.", 0); onFini(null); return }
            onProgres("Recuperation du panorama", 96)
            val outImg = File(context.getExternalFilesDir(null), "CineFlight/panorama_" + System.currentTimeMillis() + ".jpg")
            outImg.parentFile?.mkdirs()
            val ok = telechargerImage(baseUrl, jobId, outImg)
            try { tmp.deleteRecursively() } catch (_: Exception) {}
            android.util.Log.i(TAG, "resultat: " + (if (ok) outImg.absolutePath else "echec"))
            if (ok) {
                // LIEN VR (2026-07-26) : le panorama reste sur le serveur ; cette URL
                // l'ouvre en 360 — immersion en casque (Quest), gyroscope sur téléphone,
                // souris sur PC. UN SEUL lien à partager, rien à installer pour le client.
                dernierJobId = jobId
                dernierLienVr = "$baseUrl/vr/$jobId"
                android.util.Log.i(TAG, "lien VR : $dernierLienVr")
                onProgres("Panorama pret", 100); onFini(outImg)
            }
            else { onProgres("Telechargement echoue.", 0); onFini(null) }
        } catch (e: Exception) {
            onProgres("Erreur : " + (e.message ?: "?"), 0); onFini(null)
        }
    }

    private fun uploader(
        photos: List<File>, baseUrl: String, largeurMax: Int,
        angles: List<Pair<Float, Float>>? = null,
    ): String? {
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
                if (angles != null) {
                    // Champ de formulaire AVANT les fichiers : le serveur le lit sans avoir
                    // à tamponner les images, qui pèsent plusieurs dizaines de mégaoctets.
                    val json = angles.joinToString(",", "[", "]") {
                        "[%.3f,%.3f]".format(java.util.Locale.US, it.first, it.second)
                    }
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"angles\"\r\n\r\n")
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                    android.util.Log.i(TAG, "angles transmis : ${angles.size} clichés")
                }
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
