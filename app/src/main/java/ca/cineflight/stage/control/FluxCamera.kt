package ca.cineflight.stage.control

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

// --- Imports DJI Mobile SDK v5 (flux caméra) ---
// putCameraStreamSurface affiche le flux décodé directement sur une Surface.
// Disponible via MediaDataCenter -> ICameraStreamManager (MSDK 5.x).
// ⚠ ADAPTATION SDK : selon ta version exacte (5.10.0), l'enum d'index caméra
// peut s'appeler ComponentIndexType.LEFT_OR_MAIN (le plus courant) ou
// CameraIndex.CAMERA_INDEX_0. Android Studio proposera le bon (Alt+Entrée).
import dji.v5.manager.datacenter.MediaDataCenter
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * FluxCamera — affiche le flux vidéo du Mini 3 sur une SurfaceView.
 *
 * RÔLE :
 *   - relie la SurfaceView de l'UI au flux caméra décodé du MSDK v5,
 *   - le décodage/affichage est géré par le SDK (putCameraStreamSurface),
 *   - aucun traitement d'image ici : c'est juste l'aperçu pilote à l'écran.
 *
 * ISOLATION : comme PontDjiReel, ce fichier est le SEUL (avec PontDjiReel) à
 * toucher le SDK. MainActivity ne connaît que demarrer()/arreter(). Ainsi on
 * peut compiler/tester le reste sans le flux.
 *
 * ARCHI A (rappel) : pour faire tourner YOLO côté QUESTSERVER, c'est un AUTRE
 * listener (addReceiveStreamListener, flux H.264/H.265 brut poussé au serveur)
 * — indépendant de l'aperçu écran ci-dessous. On l'ajoutera séparément sans
 * toucher cette classe.
 *
 * ⚠ MINI 3 / RC-N1 : le flux n'arrive QUE si l'appareil est connecté et le SDK
 * enregistré. En MODE_SIMULE (pas de SDK), n'instancie pas cette classe.
 *
 * ⚠ DÉCODEUR EN PAUSE : le SDK met le décodeur en pause s'il n'y a plus de
 * Surface/listener référencé (économie batterie). Tant que la SurfaceView est
 * visible et reliée, le flux reste actif.
 */
class FluxCamera(
    private val surfaceView: SurfaceView,
    // Index de la caméra. LEFT_OR_MAIN = caméra principale (cas Mini 3).
    private val indexCamera: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
) {
    private var relie = false

    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            relier(holder)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // re-relier avec les bonnes dimensions (le SDK adapte le scaling)
            relier(holder)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            delier()
        }
    }

    /** À appeler dans onCreate (après setContentView). */
    fun demarrer() {
        surfaceView.holder.addCallback(callback)
        // si la surface est déjà prête, relier tout de suite
        surfaceView.holder.surface?.let {
            if (it.isValid) relier(surfaceView.holder)
        }
    }

    /** À appeler dans onDestroy. */
    fun arreter() {
        surfaceView.holder.removeCallback(callback)
        delier()
    }

    private fun relier(holder: SurfaceHolder) {
        if (relie) return
        try {
            val mgr: ICameraStreamManager =
                MediaDataCenter.getInstance().cameraStreamManager
            // Affiche le flux décodé sur la Surface. Le SDK gère le scaling.
            // ⚠ ADAPTATION SDK : la signature courante est
            //   putCameraStreamSurface(index, surface, width, height, scaleType)
            // Le scaleType (CENTER_INSIDE / CENTER_CROP / FIX_XY) dépend de ta
            // version ; CENTER_INSIDE garde le ratio sans rogner.
            mgr.putCameraStreamSurface(
                indexCamera,
                holder.surface,
                surfaceView.width.coerceAtLeast(1),
                surfaceView.height.coerceAtLeast(1),
                ICameraStreamManager.ScaleType.CENTER_INSIDE
            )
            relie = true
            Log.i("FluxCamera", "Flux relié sur SurfaceView ($indexCamera)")
        } catch (e: Exception) {
            Log.e("FluxCamera", "Échec liaison flux caméra : $e")
        }
    }

    private fun delier() {
        if (!relie) return
        try {
            MediaDataCenter.getInstance().cameraStreamManager
                .removeCameraStreamSurface(surfaceView.holder.surface)
        } catch (e: Exception) {
            Log.e("FluxCamera", "Échec retrait surface : $e")
        }
        relie = false
    }
}

