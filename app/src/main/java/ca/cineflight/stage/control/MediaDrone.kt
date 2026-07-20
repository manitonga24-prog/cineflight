package ca.cineflight.stage.control

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileFilter
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * MediaDrone - acces a la phototheque du drone (carte SD) via MSDK v5.
 *
 * IMPORTANT : en mode media, la camera NE PEUT PLUS filmer / le flux live s'arrete.
 * A utiliser APRES le tournage, dans un ecran dedie. Toujours appeler quitter()
 * en sortant pour rendre la camera au mode normal.
 */
class MediaDrone {

    private val mgr get() = MediaDataCenter.getInstance().mediaManager

    /** Entre dans le module media (coupe le live). callback(true) si OK. */
    /** Formate la carte SD du drone (EFFACE TOUT). onFini(true) si succes.
     *  Cle confirmee par decouverte : CameraKey.KeyFormatStorage, location SDCARD. */
    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        // Le format est une operation CAMERA : il faut quitter le mode media d'abord
        // (en mode media la camera ne peut ni filmer ni formater -> erreur null).
        try { mgr.disable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { Log.i(TAG, "quitte le mode media avant de formater") }
            override fun onFailure(error: IDJIError) { Log.w(TAG, "disable avant format: ${error.description()}") }
        }) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ formaterMaintenant(onFini) }, 1500)
    }

    private fun formaterMaintenant(onFini: (Boolean, String) -> Unit) {
        try {
            val cle = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyFormatStorage)
            KeyManager.getInstance().performAction(cle, CameraStorageLocation.SDCARD,
                object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        Log.i(TAG, "carte SD formatee")
                        onFini(true, "Carte SD formatee.")
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "format echec: ${error.description()}")
                        onFini(false, "Echec du formatage : ${error.description()}")
                    }
                })
        } catch (e: Throwable) {
            Log.e(TAG, "format ex: ${e.message}")
            onFini(false, "Erreur : ${e.message}")
        }
    }

    fun activer(onFait: (Boolean) -> Unit) {
        try {
            mgr.enable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "media active, attente 3s que le manager soit pret...")
                    // Le SDK media (Mini 3) a besoin de ~3s apres enable avant d'accepter liste/action.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "media active (pret apres delai)"); onFait(true)
                    }, 3000)
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "enable echec: ${error.description()}"); onFait(false)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "activer ex: ${e.message}"); onFait(false) }
    }

    /** Sort du module media (rend la camera au mode normal). */
    fun quitter() {
        try {
            mgr.disable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { Log.i(TAG, "media quitte") }
                override fun onFailure(error: IDJIError) { Log.e(TAG, "disable: ${error.description()}") }
            })
        } catch (e: Exception) { Log.e(TAG, "quitter ex: ${e.message}") }
    }

    /** Liste les photos de la carte SD du drone. onListe(liste) sur succes.
     *  Le MediaManager met un instant a etre pret apres enable() : on reessaie
     *  jusqu'a 3 fois avec un court delai (corrige "execution could not be executed"). */
    fun listerPhotos(onListe: (List<MediaFile>) -> Unit) {
        listerPhotosAvecEssais(3, onListe)
    }

    private fun listerPhotosAvecEssais(essaisRestants: Int, onListe: (List<MediaFile>) -> Unit) {
        try {
            // Source = carte SD
            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()
            mgr.setMediaFileDataSource(source)

            // Sur Mini 3 : le pull echoue si le MediaManager n'est pas IDLE. On attend IDLE.
            val etat = try { mgr.mediaFileListState } catch (e: Exception) { null }
            Log.i(TAG, "etat MediaManager: $etat")
            if (etat != MediaFileListState.IDLE && etat != MediaFileListState.UP_TO_DATE) {
                if (essaisRestants > 1) {
                    Log.w(TAG, "manager pas pret ($etat), nouvel essai dans 1000ms... restants=${essaisRestants - 1}")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        listerPhotosAvecEssais(essaisRestants - 1, onListe)
                    }, 1000)
                    return
                }
            }

            // nettoie un pull precedent reste coince
            try { mgr.stopPullMediaFileListFromCamera() } catch (_: Exception) {}

            // param AVEC filtre PHOTO (necessaire sur Mini 3/4)
            val param = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .count(-1)
                .filter(MediaFileFilter.PHOTO)
                .build()
            mgr.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val data = try { mgr.mediaFileListData.data ?: emptyList() } catch (e: Exception) { emptyList() }
                    val photos = data.filter { val n = it.fileName.lowercase(); n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".dng") }
                    Log.i(TAG, "photos trouvees: ${photos.size}")
                    onListe(photos)
                }
                override fun onFailure(error: IDJIError) {
                    if (essaisRestants > 1) {
                        Log.w(TAG, "liste echec (${error.description()}), nouvel essai dans 1000ms... restants=${essaisRestants - 1}")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            listerPhotosAvecEssais(essaisRestants - 1, onListe)
                        }, 1000)
                    } else {
                        Log.e(TAG, "liste echec definitif: ${error.description()}"); onListe(emptyList())
                    }
                }
            })
        } catch (e: Exception) { Log.e(TAG, "lister ex: ${e.message}"); onListe(emptyList()) }
    }

    /**
     * Telecharge une photo pleine resolution vers le stockage du telephone.
     * onProgres(0..100), onFini(fichier ou null).
     */
    fun telecharger(ctx: Context, mf: MediaFile, onProgres: (Int) -> Unit, onFini: (File?) -> Unit) {
        try {
            val dossier = File(ctx.getExternalFilesDir(null), "CineFlight")
            if (!dossier.exists()) dossier.mkdirs()
            val sortie = File(dossier, mf.fileName)
            if (sortie.exists()) sortie.delete()
            val fos = FileOutputStream(sortie)
            val total = mf.fileSize.coerceAtLeast(1L)
            var recu = 0L
            mf.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                override fun onStart() { onProgres(0) }
                override fun onProgress(t: Long, c: Long) {
                    recu += c
                    val pct = ((recu * 100) / total).toInt().coerceIn(0, 100)
                    onProgres(pct)
                }
                override fun onRealtimeDataUpdate(data: ByteArray, offset: Long) {
                    try { fos.write(data) } catch (e: Exception) { Log.e(TAG, "write: ${e.message}") }
                }
                override fun onFinish() {
                    try { fos.flush(); fos.close() } catch (_: Exception) {}
                    Log.i(TAG, "telecharge: ${sortie.absolutePath}")
                    onProgres(100); onFini(sortie)
                }
                override fun onFailure(error: IDJIError) {
                    try { fos.close() } catch (_: Exception) {}
                    Log.e(TAG, "dl echec: ${error.description()}"); onFini(null)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "telecharger ex: ${e.message}"); onFini(null) }
    }

    /** Recupere la miniature (thumbnail) d'une photo sous forme de Bitmap. onMini(bitmap ou null). */
    fun miniature(mf: MediaFile, onMini: (Bitmap?) -> Unit) {
        try {
            mf.pullThumbnailFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<Bitmap> {
                override fun onSuccess(bmp: Bitmap?) { onMini(bmp) }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "mini echec: ${error.description()}"); onMini(null)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "miniature ex: ${e.message}"); onMini(null) }
    }

    companion object { private const val TAG = "MediaDrone" }
}
