package ca.cineflight.stage.streaming

import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings

/**
 * DjiRtmpSettingsFactory — MICRO-PASSE de confirmation d'API (V1 live YouTube).
 *
 * BUT UNIQUE : verifier a la compilation les noms exacts du Builder RTMP de TA version
 * du SDK DJI, AVANT tout refactor du streaming. L'incertitude API reste enfermee ICI :
 * si un nom differe, on ne corrige QUE ce fichier, le reste du projet n'en depend pas.
 *
 * Points confirmes par la doc DJI v5 (api-reference-v5) :
 *   - classe RtmpSettings dans dji.v5.manager.datacenter.livestream.settings
 *   - LiveStreamSettings porte le type RTMP + les RtmpSettings
 * Point a confirmer a la compilation : le nom exact de la methode d'URL du Builder
 *   (probablement setUrl(String) ; a ajuster si l'IDE signale une autre signature).
 *
 * SECURITE : cette factory ne journalise JAMAIS l'URL (elle contient la cle de diffusion).
 * L'appelant construit urlComplete = serveur + "/" + cleYouTube et ne doit ni la logger
 * ni la conserver au-dela du demarrage du stream.
 */
internal object DjiRtmpSettingsFactory {

    /**
     * Construit les reglages DJI pour une diffusion RTMP vers [urlComplete]
     * (serveur RTMPS YouTube + cle de diffusion, deja assemblee par l'appelant).
     *
     * @throws IllegalArgumentException si l'URL est vide/blanche.
     */
    fun construire(urlComplete: String): LiveStreamSettings {
        require(urlComplete.isNotBlank()) { "L'URL RTMP ne doit pas etre vide" }

        // --- ZONE D'INCERTITUDE API (a confirmer a la compilation) ---
        // Si l'IDE signale que setUrl n'existe pas, remplacer par la methode reelle
        // du Builder RtmpSettings de ta version du SDK (ex. setURL). C'est la SEULE
        // ligne susceptible de changer.
        val rtmpSettings = RtmpSettings.Builder()
            .setUrl(urlComplete)
            .build()
        // --- FIN ZONE D'INCERTITUDE ---

        return LiveStreamSettings.Builder()
            .setLiveStreamType(LiveStreamType.RTMP)
            .setRtmpSettings(rtmpSettings)
            .build()
    }
}
