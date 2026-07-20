package ca.cineflight.stage.control

import android.util.Log
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.settings.RtspSettings

/**
 * StreamRtsp — active le SERVEUR RTSP integre du SDK DJI v5.
 *
 * En mode RTSP, le SDK fait du TELEPHONE un serveur RTSP : le flux video du
 * drone devient accessible a l'adresse
 *     rtsp://<user>:<password>@<IP_DU_TELEPHONE>:8554/streaming/live/1
 * que yolo_pose.py (sur le PC) lit via --source.
 *
 * IMPORTANT (doc DJI) : en RTSP, le port DOIT etre 8554. La doc liste les
 * drones supportes (Mavic 3E, Matrice, Mini 4 Pro...) ; le Mini 3 N'Y figure
 * PAS. Ce composant tente quand meme le demarrage et REMONTE l'erreur exacte
 * via le callback, pour qu'on sache si le Mini 3 le supporte ou non.
 *
 * Usage (apres connexion du drone) :
 *   StreamRtsp.demarrer { ok, msg -> ... }   // affiche succes / raison d'echec
 *   StreamRtsp.arreter()
 */
object StreamRtsp {

    private const val TAG = "StreamRtsp"

    // identifiants du flux RTSP (a reutiliser cote yolo_pose.py)
    const val RTSP_USER = "cineflight"
    const val RTSP_PASS = "cineflight"
    const val RTSP_PORT = 8554   // impose par DJI pour RTSP

    @Volatile var enCours = false
        private set

    /**
     * Demarre le serveur RTSP. [onResultat] est rappele avec :
     *  - (true, "rtsp://.../streaming/live/1")  si le stream demarre
     *  - (false, raison)                        sinon (ex. Mini 3 non supporte)
     */
    fun demarrer(onResultat: (Boolean, String) -> Unit) {
        try {
            val mgr = MediaDataCenter.getInstance().liveStreamManager

            // configure le mode RTSP (user / password / port 8554)
            val rtsp = RtspSettings.Builder()
                .setUserName(RTSP_USER)
                .setPassWord(RTSP_PASS)
                .setPort(RTSP_PORT)
                .build()
            val settings = LiveStreamSettings.Builder()
                .setLiveStreamType(LiveStreamType.RTSP)
                .setRtspSettings(rtsp)
                .build()
            mgr.liveStreamSettings = settings

            // qualite : HD suffit pour YOLO (et reduit la latence vs 4K)
            try { mgr.liveStreamQuality = StreamQuality.HD } catch (_: Exception) {}

            mgr.startStream(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    enCours = true
                    // l'adresse contient l'IP du telephone (cote PC, on la connait deja)
                    val url = "rtsp://$RTSP_USER:$RTSP_PASS@<IP_TELEPHONE>:$RTSP_PORT/streaming/live/1"
                    Log.i(TAG, "RTSP demarre : $url")
                    onResultat(true, url)
                }
                override fun onFailure(error: IDJIError) {
                    enCours = false
                    val desc = error.description() ?: "erreur inconnue"
                    Log.e(TAG, "RTSP echec : $desc")
                    onResultat(false, desc)
                }
            })
        } catch (e: Throwable) {
            // p.ex. classe absente / drone non supporte -> on remonte le message
            enCours = false
            onResultat(false, "Exception : ${e.message}")
        }
    }

    fun arreter() {
        try {
            MediaDataCenter.getInstance().liveStreamManager
                .stopStream(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { enCours = false }
                    override fun onFailure(error: IDJIError) { enCours = false }
                })
        } catch (_: Throwable) {
            enCours = false
        }
    }
}

