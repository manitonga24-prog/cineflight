package ca.cineflight.stage.streaming

/**
 * LiveStreamDestination — decrit OU pousser le flux video du drone.
 *
 * Le SDK DJI sait diffuser vers plusieurs types de destination (RTSP, RTMP...).
 * Ce sealed type modelise les deux cas dont CineFlight a besoin, sans que le
 * moteur ([DjiLiveStreamEngine]) connaisse YouTube, Facebook, Keystore ou l'UI.
 *
 * SECURITE : pour le RTMP, [Rtmp.fullUrl] contient la CLE DE DIFFUSION YouTube.
 * Cette valeur ne doit JAMAIS etre journalisee. Pour tout affichage/log, utiliser
 * uniquement [label], qui est volontairement sans secret.
 */
sealed interface LiveStreamDestination {

    /** Libelle SANS secret, utilisable dans les logs et l'UI. */
    val label: String

    /**
     * Qualite video demandee pour ce direct (resolution + debit).
     * Valeur METIER, independante du SDK DJI : le moteur reste testable sur JVM.
     * L'adaptateur DJI traduit ceci en StreamQuality + LiveVideoBitrateMode/bitrate.
     */
    val quality: StreamQualityChoisie
        get() = StreamQualityChoisie.DEFAUT

    /**
     * Serveur RTSP integre du SDK (le telephone devient serveur RTSP).
     * Reproduit exactement les reglages historiques de StreamRtsp.
     */
    data class Rtsp(
        val port: Int,
        val username: String,
        val password: String,
    ) : LiveStreamDestination {
        // Ni user ni password dans le label (le mot de passe est un secret).
        override val label: String get() = "RTSP:$port"
    }

    /**
     * Diffusion RTMP/RTMPS (YouTube Live, Facebook Live...).
     *
     * @param fullUrl URL complete = serveur + "/" + cle de diffusion. SECRET.
     *                Assemblee par l'appelant (ex. YouTubeLiveService), passee au
     *                moteur uniquement le temps du demarrage, jamais conservee ni loggee.
     * @param label   Libelle sur pour logs/UI (ex. "YouTube", "Facebook").
     */
    data class Rtmp(
        val fullUrl: String,
        override val label: String,
        override val quality: StreamQualityChoisie = StreamQualityChoisie.DEFAUT,
    ) : LiveStreamDestination
}

/**
 * StreamQualityChoisie — choix de qualite exprime cote UI/metier, sans dependance SDK.
 *
 * [resolution] : resolution cible (l'adaptateur DJI la traduit en StreamQuality).
 * [bitrateBps] : debit video en bit/s. null = mode AUTO (le SDK gere le debit).
 *                Une valeur non nulle = mode MANUEL a ce debit.
 */
data class StreamQualityChoisie(
    val resolution: Resolution,
    val bitrateBps: Int?,
) {
    enum class Resolution { P720, P1080 }

    companion object {
        /** Defaut sur : 1080p, debit automatique (comportement recommande DJI). */
        val DEFAUT = StreamQualityChoisie(resolution = Resolution.P1080, bitrateBps = null)
    }
}
