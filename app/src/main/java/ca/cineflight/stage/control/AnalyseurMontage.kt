package ca.cineflight.stage.control

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.tencent.yolo11ncnn.YOLO11Ncnn

/**
 * AnalyseurMontage - cerveau du montage intelligent.
 * Pour chaque clip : echantillonne 1 frame/seconde, passe a YOLO (detectBitmap),
 * score chaque seconde selon presence + cadrage du sujet (classes selon Reglages).
 * Produit une courbe de scores par clip, exploitee ensuite par les strategies de selection.
 */
class AnalyseurMontage(
    private val ctx: Context,
    private val yolo: YOLO11Ncnn,
    private val classesSuivies: Set<Int>
) {

    /** Score d'une seconde de video : index = seconde, valeur = score 0..1. */
    data class ClipScore(
        val uri: Uri,
        val dureeMs: Long,
        val scoresParSeconde: FloatArray
    )

    /**
     * Analyse tous les clips. onProgres(global 0..100). onFini(liste des scores par clip).
     * A appeler depuis un thread de fond (l'analyse est lourde).
     */
    fun analyser(
        clips: List<Uri>,
        onProgres: (Int) -> Unit,
        onFini: (List<ClipScore>) -> Unit
    ) {
        val resultats = ArrayList<ClipScore>()
        // total de secondes a analyser (pour la progression globale)
        val durees = clips.map { dureeVideoMs(it) }
        val totalSec = durees.sumOf { (it / 1000L).toInt().coerceAtLeast(1) }.coerceAtLeast(1)
        var faites = 0

        for ((idx, uri) in clips.withIndex()) {
            val dureeMs = durees[idx]
            val nbSec = (dureeMs / 1000L).toInt().coerceAtLeast(1)
            val scores = FloatArray(nbSec)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                for (sec in 0 until nbSec) {
                    val tUs = sec * 1_000_000L
                    val frame = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    scores[sec] = if (frame != null) scorerFrame(frame) else 0f
                    frame?.recycle()
                    faites++
                    if (faites % 3 == 0 || faites == totalSec) {
                        onProgres(((faites * 100) / totalSec).coerceIn(0, 100))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyse clip ex: ${e.message}")
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
            resultats.add(ClipScore(uri, dureeMs, scores))
            Log.i(TAG, "clip $idx scores=${scores.joinToString(",") { "%.2f".format(it) }}")
        }
        onProgres(100)
        onFini(resultats)
    }

    /**
     * Score d'une frame 0..1 : presence du sujet pertinent + cadrage (centrage + taille).
     * Reutilise le format FloatArray de YOLO : res[0]=n, puis 6 valeurs par detection
     * [classe, conf, x, y, w, h] en pixels.
     */
    private fun scorerFrame(frame: Bitmap): Float {
        val bmp = if (frame.config == Bitmap.Config.ARGB_8888) frame
                  else frame.copy(Bitmap.Config.ARGB_8888, false)
        val res = try { yolo.detectBitmap(bmp) } catch (e: Exception) { Log.e(TAG, "detectBitmap: ${e.message}"); null }
            ?: return 0f
        if (res.isEmpty()) return 0f
        val n = res[0].toInt()
        if (n <= 0) return 0f
        val w = bmp.width.toFloat().coerceAtLeast(1f)
        val h = bmp.height.toFloat().coerceAtLeast(1f)

        var meilleur = 0f
        for (i in 0 until n) {
            val o = 1 + i * 6
            if (o + 5 >= res.size) break
            val cl = res[o].toInt()
            if (cl !in classesSuivies) continue
            val conf = res[o + 1].coerceIn(0f, 1f)
            val x = res[o + 2]; val y = res[o + 3]
            val ww = res[o + 4]; val hh = res[o + 5]
            val cx = (x + ww / 2f) / w
            val cy = (y + hh / 2f) / h
            val tailleRel = (ww / w) * (hh / h)   // surface relative 0..1

            // bonus centrage : 1 au centre, decroit vers les bords
            val dCentre = Math.hypot((cx - 0.5).toDouble(), (cy - 0.5).toDouble()).toFloat()
            val bonusCentre = (1f - dCentre * 1.6f).coerceIn(0f, 1f)

            // bonus taille : ideal ~8%..50% de surface ; penalise minuscule et enorme
            val bonusTaille = when {
                tailleRel < 0.01f -> 0.1f          // minuscule
                tailleRel < 0.06f -> 0.5f
                tailleRel <= 0.55f -> 1.0f         // bonne taille
                tailleRel <= 0.8f -> 0.6f
                else -> 0.3f                        // remplit tout (coupe probable)
            }

            val score = conf * (0.4f + 0.35f * bonusCentre + 0.25f * bonusTaille)
            if (score > meilleur) meilleur = score
        }
        return meilleur.coerceIn(0f, 1f)
    }

    private fun dureeVideoMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L }
        finally { try { r.release() } catch (_: Exception) {} }
    }

    companion object { private const val TAG = "AnalyseurMontage" }
}
