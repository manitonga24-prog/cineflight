package ca.cineflight.stage.streaming

import android.util.Log
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.settings.RtspSettings
import dji.v5.manager.interfaces.ILiveStreamManager

/**
 * LiveStreamManagerAdapter — fine couche d'abstraction au-dessus du SDK DJI.
 *
 * POURQUOI : [DjiLiveStreamEngine] doit etre testable sur la JVM (aucune classe
 * DJI n'existe dans un test unitaire pur). L'adaptateur isole TOUT contact avec
 * le SDK derriere une interface minuscule ; les tests fournissent un faux
 * controlable, la prod fournit [Dji].
 *
 * L'adaptateur ne detient AUCUN etat metier (ni StateFlow, ni sessionId) : ce
 * n'est qu'un pont vers ILiveStreamManager. Toute la logique d'etat vit dans le moteur.
 */
internal interface LiveStreamManagerAdapter {

    /** Callback binaire de fin de commande (succes / echec avec raison sure). */
    interface Completion {
        fun onSuccess()
        /** [raison] est un message deja rendu sur (pas d'URL, pas de cle). */
        fun onFailure(raison: String)
    }

    /**
     * Configure le manager pour la [destination] donnee (type + reglages).
     * Ne demarre pas le flux. Peut lever si le SDK est indisponible.
     */
    fun configurer(destination: LiveStreamDestination)

    /** Demande le demarrage du flux ; [completion] rappele par le SDK. */
    fun demarrer(completion: Completion)

    /** Demande l'arret du flux ; [completion] rappele par le SDK. */
    fun arreter(completion: Completion)

    /**
     * Implementation reelle : enveloppe MediaDataCenter.liveStreamManager.
     *
     * Reprend a l'identique les signatures confirmees par StreamRtsp :
     *   - mgr.liveStreamSettings = settings        (PROPRIETE, pas setLiveStreamSettings)
     *   - mgr.liveStreamQuality  = StreamQuality.HD
     *   - mgr.startStream(object : CommonCallbacks.CompletionCallback { ... })  (objet, pas lambda)
     * et, pour le RTMP, delegue la construction des reglages a DjiRtmpSettingsFactory
     * (la seule zone d'incertitude API, deja confirmee a la compilation).
     */
    object Dji : LiveStreamManagerAdapter {

        private const val TAG = "LIVE_STREAM"

        override fun configurer(destination: LiveStreamDestination) {
            val mgr = MediaDataCenter.getInstance().liveStreamManager

            val settings: LiveStreamSettings = when (destination) {
                is LiveStreamDestination.Rtsp -> {
                    val rtsp = RtspSettings.Builder()
                        .setUserName(destination.username)
                        .setPassWord(destination.password)   // double capitale : signature DJI reelle
                        .setPort(destination.port)
                        .build()
                    LiveStreamSettings.Builder()
                        .setLiveStreamType(LiveStreamType.RTSP)
                        .setRtspSettings(rtsp)
                        .build()
                }
                is LiveStreamDestination.Rtmp ->
                    // SECURITE : la factory ne logge jamais l'URL (elle contient la cle).
                    DjiRtmpSettingsFactory.construire(destination.fullUrl)
            }

            mgr.liveStreamSettings = settings

            // Qualite demandee (resolution + debit). Best-effort : chaque reglage est
            // isole pour qu'un refus du drone n'empeche pas les autres ni le demarrage.
            appliquerQualite(mgr, destination.quality)

            // SECURITE : on logge le LABEL + la qualite (aucun secret), jamais l'URL/cle.
            Log.i(TAG, "Configure destination=${destination.label} qualite=${destination.quality.resolution} debit=${destination.quality.bitrateBps ?: "AUTO"}")
        }

        /**
         * Traduit la qualite METIER en reglages SDK DJI (API typee, noms officiels) :
         *   - resolution -> StreamQuality (1080p = FULL_HD, 720p = HD) ;
         *   - debit null  -> LiveVideoBitrateMode.AUTO (le SDK adapte au reseau) ;
         *   - debit fixe  -> LiveVideoBitrateMode.MANUAL + setLiveVideoBitrate(bit/s).
         * Chaque appel est best-effort (le drone/firmware peut refuser un reglage),
         * mais les symboles sont TYPES : une mauvaise version casserait la compilation
         * plutot que d'etre masquee silencieusement.
         */
        private fun appliquerQualite(mgr: ILiveStreamManager, quality: StreamQualityChoisie) {
            val djiQuality = when (quality.resolution) {
                StreamQualityChoisie.Resolution.P1080 -> StreamQuality.FULL_HD
                StreamQualityChoisie.Resolution.P720 -> StreamQuality.HD
            }
            try { mgr.setLiveStreamQuality(djiQuality) } catch (_: Exception) {}

            val bitrate = quality.bitrateBps
            try {
                if (bitrate != null) {
                    mgr.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
                    mgr.setLiveVideoBitrate(bitrate)
                } else {
                    mgr.setLiveVideoBitrateMode(LiveVideoBitrateMode.AUTO)
                }
            } catch (_: Exception) {}
        }

        override fun demarrer(completion: Completion) {
            val mgr = MediaDataCenter.getInstance().liveStreamManager
            mgr.startStream(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = completion.onSuccess()
                override fun onFailure(error: IDJIError) =
                    completion.onFailure(error.description() ?: "erreur inconnue")
            })
        }

        override fun arreter(completion: Completion) {
            val mgr = MediaDataCenter.getInstance().liveStreamManager
            mgr.stopStream(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = completion.onSuccess()
                override fun onFailure(error: IDJIError) =
                    completion.onFailure(error.description() ?: "erreur inconnue")
            })
        }
    }
}
