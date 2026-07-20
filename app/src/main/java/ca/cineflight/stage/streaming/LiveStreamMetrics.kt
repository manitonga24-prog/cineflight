package ca.cineflight.stage.streaming

/**
 * LiveStreamMetrics — telemetrie EVOLUTIVE du direct (passe 2B).
 *
 * Volontairement separee de [LiveStreamState] : l'etat repond a « le direct est-il
 * arrete / en demarrage / actif / en arret ? », les metriques a « quelles sont ses
 * performances actuelles ? ». Les metriques changent souvent (fps 25 -> 26 -> 24)
 * sans provoquer de fausse transition de cycle de vie. Le moteur les expose dans un
 * StateFlow distinct ; l'UI peut combiner les deux flux.
 *
 * Tous les champs sont nullables : avant le premier statut DJI, on ne connait rien
 * (cf. [Empty]).
 */
data class LiveStreamMetrics(
    val fps: Int? = null,
    val bitrateBps: Long? = null,
    val resolution: String? = null,
    val packetLoss: Int? = null,
    val packetCacheLength: Int? = null,
    val rttMs: Int? = null,
) {
    companion object {
        /** Aucune metrique connue (etat initial, apres arret/echec/fermeture). */
        val Empty = LiveStreamMetrics()
    }
}
