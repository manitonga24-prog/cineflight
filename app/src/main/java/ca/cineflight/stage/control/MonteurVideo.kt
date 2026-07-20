package ca.cineflight.stage.control

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * MonteurVideo - assemblage intelligent de clips video via Media3 Transformer.
 * Decoupe un segment de chaque clip (duree reglable + position) puis enchaine -> MP4.
 * Les clips viennent de la galerie du telephone (rapatries via DJI Fly).
 */
class MonteurVideo(private val ctx: Context) {

    enum class Position { DEBUT, MILIEU }

    /**
     * Assemble un montage rythme : garde un segment de chaque clip et les enchaine.
     * @param clips liste d'URI de videos (galerie du telephone)
     * @param dureeParClipSec duree gardee de chaque clip, en secondes (ex: 4)
     * @param position ou prendre le segment dans chaque clip (DEBUT ou MILIEU)
     * @param onProgres pourcentage 0..100 (approximatif)
     * @param onFini fichier MP4 exporte, ou null si echec
     */
    fun assembler(
        clips: List<Uri>,
        dureeParClipSec: Int = 4,
        position: Position = Position.DEBUT,
        nom: String = "",
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {
        if (clips.isEmpty()) { onFini(null); return }
        try {
            val dossier = File(ctx.getExternalFilesDir(null), "CineFlight/Montages")
            if (!dossier.exists()) dossier.mkdirs()
            val sortie = File(dossier, nomFichier(nom))

            val dureeMs = dureeParClipSec * 1000L

            // construire la sequence : chaque clip rogne a la duree voulue
            val items = clips.map { uri ->
                val totalMs = dureeVideoMs(uri)
                // calcul du segment a garder
                val debutMs: Long
                val finMs: Long
                if (totalMs <= 0L || totalMs <= dureeMs) {
                    // clip plus court que la duree voulue : on garde tout
                    debutMs = 0L
                    finMs = if (totalMs > 0L) totalMs else dureeMs
                } else if (position == Position.MILIEU) {
                    debutMs = (totalMs - dureeMs) / 2
                    finMs = debutMs + dureeMs
                } else {
                    debutMs = 0L
                    finMs = dureeMs
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(debutMs)
                            .setEndPositionMs(finMs)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem).build()
            }

            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(sequence).build()

            val transformer = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        Log.i(TAG, "export OK: ${sortie.absolutePath}")
                        onProgres(100); onFini(sortie)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        Log.e(TAG, "export echec: ${e.message}")
                        onFini(null)
                    }
                })
                .build()

            onProgres(0)
            transformer.start(composition, sortie.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "assembler ex: ${e.message}"); onFini(null)
        }
    }

    /**
     * Assemble une liste de segments precis (clip + debut + fin) choisis par l'analyse.
     * @param segments segments a enchainer dans l'ordre donne
     */
    fun assemblerSegments(
        segments: List<SelecteurSegments.Segment>,
        nom: String = "",
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {
        if (segments.isEmpty()) { onFini(null); return }
        try {
            val dossier = File(ctx.getExternalFilesDir(null), "CineFlight/Montages")
            if (!dossier.exists()) dossier.mkdirs()
            val sortie = File(dossier, nomFichier(nom))

            val items = segments.map { seg ->
                val mediaItem = MediaItem.Builder()
                    .setUri(seg.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(seg.debutMs)
                            .setEndPositionMs(seg.finMs)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem).build()
            }

            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(sequence).build()
            val transformer = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        Log.i(TAG, "export segments OK: ${sortie.absolutePath}")
                        onProgres(100); onFini(sortie)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        Log.e(TAG, "export segments echec: ${e.message}")
                        onFini(null)
                    }
                })
                .build()
            onProgres(0)
            transformer.start(composition, sortie.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "assemblerSegments ex: ${e.message}"); onFini(null)
        }
    }

    /** Construit un nom de fichier sur (nettoye), ou un nom horodate par defaut. */
    private fun nomFichier(nom: String): String {
        val propre = nom.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(" ", "_")
        return if (propre.isNotEmpty()) "$propre.mp4" else "montage_${System.currentTimeMillis()}.mp4"
    }

    /** Lit la duree d'une video en ms (0 si echec). */
    private fun dureeVideoMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "duree ex: ${e.message}"); 0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Copie le montage dans la galerie publique (Movies/CineFlight) pour qu'il
     * apparaisse dans l'app Galerie. Renvoie l'Uri public (ou null si echec).
     */
    fun publierDansGalerie(fichier: File): Uri? {
        return try {
            val resolver = ctx.contentResolver
            val nom = fichier.name
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, nom)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineFlight")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= 29)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                fichier.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "publie dans galerie: $uri")
            uri
        } catch (e: Exception) { Log.e(TAG, "publier ex: ${e.message}"); null }
    }

    companion object { private const val TAG = "MonteurVideo" }
}
