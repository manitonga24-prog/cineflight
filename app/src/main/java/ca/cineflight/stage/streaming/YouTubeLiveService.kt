package ca.cineflight.stage.streaming

import android.util.Log
import kotlinx.coroutines.flow.StateFlow

/**
 * YouTubeLiveService — orchestre un direct YouTube au-dessus du moteur.
 *
 * Role : recuperer la cle de diffusion (via [secretStore]), assembler l'URL RTMPS
 * COMPLETE le temps du demarrage, la passer a [engine], et ne JAMAIS la conserver.
 *
 * Frontieres (regles verbatim) :
 *   - le MOTEUR ne connait ni YouTube, ni Keystore, ni UI ;
 *   - ce SERVICE connait YouTube + le store, mais PAS le Keystore interne ni l'UI ;
 *   - apres avoir construit l'URL, on ne la garde dans AUCUN champ ni etat sauvegarde ;
 *   - on ne journalise JAMAIS la cle ni l'URL complete. Log sur : le serveur seul.
 *
 * Etat/metriques : simplement re-exposes depuis le moteur (source de verite unique).
 */
internal class YouTubeLiveService(
    private val engine: DjiLiveStreamEngine,
    private val secretStore: StreamSecretStore,
) {

    private companion object {
        const val TAG = "LIVE_STREAM"
        const val LABEL = "YouTube"
    }

    /** Etat de cycle de vie du direct (delegue au moteur). */
    val state: StateFlow<LiveStreamState> get() = engine.state

    /** Telemetrie du direct (delegue au moteur). */
    val metrics: StateFlow<LiveStreamMetrics> get() = engine.metrics

    /** Resultat d'une tentative de demarrage, pour un retour UI clair. */
    sealed interface Demarrage {
        data object Lance : Demarrage
        data object CleAbsente : Demarrage       // aucune cle stockee / illisible
        data class ServeurInvalide(val raison: String) : Demarrage
    }

    /** true si une cle de diffusion est stockee sur l'appareil. */
    fun cleEnregistree(): Boolean = secretStore.existe()

    /** Enregistre (chiffre) la cle de diffusion. */
    fun enregistrerCle(cle: String) = secretStore.enregistrer(cle.trim())

    /** Supprime la cle de diffusion stockee. */
    fun supprimerCle() = secretStore.effacer()

    /**
     * Demarre un direct YouTube vers [serveurRtmps] (ex. rtmps://a.rtmps.youtube.com/live2),
     * en utilisant la cle stockee. L'URL complete est construite ICI, transmise au moteur,
     * puis abandonnee (aucune retention).
     */
    fun demarrer(
        serveurRtmps: String,
        quality: StreamQualityChoisie = StreamQualityChoisie.DEFAUT,
    ): Demarrage {
        val serveur = serveurRtmps.trim().removeSuffix("/")
        if (serveur.isBlank() || !serveur.startsWith("rtmp")) {
            return Demarrage.ServeurInvalide("URL de serveur RTMPS invalide")
        }

        // Cle lue au dernier moment ; null si absente ou blob illisible (deja purge par le store).
        val cle = secretStore.lire()
        if (cle.isNullOrBlank()) {
            return Demarrage.CleAbsente
        }

        // URL complete = variable LOCALE uniquement. Jamais un champ, jamais logguee.
        val urlComplete = "$serveur/$cle"

        // SECURITE : on logge le SERVEUR + la qualite (aucun secret), jamais la cle ni l'URL complete.
        Log.i(TAG, "Demarrage $LABEL vers $serveur (cle presente=true) qualite=${quality.resolution} debit=${quality.bitrateBps ?: "AUTO"}")

        engine.demarrer(LiveStreamDestination.Rtmp(fullUrl = urlComplete, label = LABEL, quality = quality))
        // urlComplete et cle sortent de portee ici : rien n'est conserve.
        return Demarrage.Lance
    }

    /** Arrete le direct en cours. */
    fun arreter() {
        Log.i(TAG, "Arret $LABEL demande")
        engine.arreter()
    }
}
