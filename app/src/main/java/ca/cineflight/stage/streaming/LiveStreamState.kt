package ca.cineflight.stage.streaming

/**
 * LiveStreamState — etat courant, observable, du moteur de diffusion.
 *
 * PASSE 1 (noyau minimal) : etats simples sans sessionId ni metriques. La
 * semantique est provisoire (startStream.onSuccess -> Streaming). Les passes
 * suivantes ajouteront :
 *   - passe 2 : metriques reelles + Streaming seulement quand DJI confirme isStreaming=true ;
 *   - passe 3 : sessionId pour ignorer les callbacks perimes ;
 *   - passe 4 : reducteur, erreurs (SharedFlow) et timeouts.
 *
 * Cet enum d'etats ne porte AUCUN secret (pas d'URL RTMP, pas de cle).
 */
sealed interface LiveStreamState {

    /** Aucun direct en cours. Etat initial et de repos. */
    data object Idle : LiveStreamState

    /** Demarrage demande, en attente de confirmation du SDK. */
    data object Starting : LiveStreamState

    /** Diffusion active. */
    data object Streaming : LiveStreamState

    /** Arret demande, en attente de confirmation du SDK. */
    data object Stopping : LiveStreamState

    /**
     * Le direct n'a pas pu demarrer (ou s'est arrete sur erreur). [raison] est un
     * message deja rendu sur (aucun secret : ni URL RTMP, ni cle). Sert a AFFICHER
     * la cause reelle a l'operateur au lieu de retomber en silence sur "Arrete".
     */
    data class Erreur(val raison: String) : LiveStreamState
}
