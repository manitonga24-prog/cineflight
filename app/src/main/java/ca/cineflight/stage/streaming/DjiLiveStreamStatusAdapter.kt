package ca.cineflight.stage.streaming

import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener

/**
 * DjiLiveStreamStatusAdapter — SONDE API (passe 2A).
 *
 * BUT UNIQUE : confirmer a la compilation, dans UN SEUL fichier, les signatures
 * du listener de statut DJI et des champs de LiveStreamStatus, AVANT d'integrer
 * les metriques et la transition isStreaming au moteur (passe 2B). Meme methode
 * que DjiRtmpSettingsFactory : l'incertitude API reste enfermee ici.
 *
 * Signatures attendues (doc DJI v5) — a confirmer par assembleDebug :
 *   manager.addLiveStreamStatusListener(listener)
 *   manager.removeLiveStreamStatusListener(listener)
 *   manager.isStreaming
 *   listener.onLiveStreamStatusUpdate(status) / listener.onError(error)
 *   status.isStreaming / .fps / .vbps / .resolution / .packetLoss
 *          / .packetCacheLen / .rtt
 *
 * NOTE : Kotlin traduit les getters Java (getFps()) en proprietes (fps). Si une
 * propriete ne compile pas avec le SDK reellement installe, remplacer UNIQUEMENT
 * la ligne concernee par l'appel explicite (ex. status.getFps()). Le SDK du PC
 * reste la source de verite finale.
 *
 * ERREUR : en passe 2A on remonte un simple message String (sur, deja rendu).
 * Le type riche StreamError arrivera en passe 4 ; on ne l'introduit pas ici pour
 * garder la sonde autonome et compilable seule.
 */
internal data class DjiLiveStreamStatusSnapshot(
    val isStreaming: Boolean,
    val fps: Int,
    val bitrate: Int,
    val resolution: String?,
    val packetLoss: Int,
    val packetCacheLength: Int,
    val rttMs: Int,
)

/**
 * Port d'observation du statut de diffusion, testable sans SDK.
 * Le moteur (passe 2B) fournira un faux port pour piloter les snapshots.
 */
internal interface LiveStreamStatusPort {

    /**
     * Attache l'ecoute du statut. Idempotent : un second appel sans [detach]
     * n'enregistre PAS un deuxieme listener.
     *
     * @param onStatus recoit un snapshot INDEPENDANT du SDK (copie immediate).
     * @param onError  recoit un message d'erreur sur (String), thread arbitraire.
     */
    fun attach(
        onStatus: (DjiLiveStreamStatusSnapshot) -> Unit,
        onError: (String) -> Unit,
    )

    /** Retire l'ecoute. Idempotent : sans effet si rien n'est attache. */
    fun detach()

    /** Etat de diffusion tel que rapporte par le SDK a l'instant present. */
    fun isStreamingNow(): Boolean
}

/**
 * Implementation reelle : enveloppe le liveStreamManager du SDK.
 *
 * On NE nomme PAS le type de l'interface du manager (il varie selon la version
 * du SDK) : on le recupere via MediaDataCenter et on laisse Kotlin l'inferer,
 * exactement comme StreamRtsp (`MediaDataCenter.getInstance().liveStreamManager`).
 * Enregistre AU PLUS UN listener a la fois (garde-fou contre les doublons).
 */
internal class DjiLiveStreamStatusAdapter : LiveStreamStatusPort {

    // Type infere par Kotlin (comme StreamRtsp), jamais annonce explicitement.
    private val manager get() = MediaDataCenter.getInstance().liveStreamManager

    private var listener: LiveStreamStatusListener? = null

    override fun attach(
        onStatus: (DjiLiveStreamStatusSnapshot) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (listener != null) return  // deja attache : pas de doublon

        val nouveau = object : LiveStreamStatusListener {
            override fun onLiveStreamStatusUpdate(status: LiveStreamStatus) {
                // Copie immediate et INDEPENDANTE : on ne conserve pas l'objet DJI.
                onStatus(
                    DjiLiveStreamStatusSnapshot(
                        isStreaming = status.isStreaming,
                        fps = status.fps,
                        bitrate = status.vbps,
                        resolution = status.resolution?.toString(),
                        packetLoss = status.packetLoss,
                        packetCacheLength = status.packetCacheLen,
                        rttMs = status.rtt,
                    )
                )
            }

            override fun onError(error: IDJIError) {
                onError(error.description() ?: "erreur de statut inconnue")
            }
        }

        listener = nouveau
        manager.addLiveStreamStatusListener(nouveau)
    }

    override fun detach() {
        val courant = listener ?: return
        manager.removeLiveStreamStatusListener(courant)
        listener = null
    }

    override fun isStreamingNow(): Boolean = manager.isStreaming
}
