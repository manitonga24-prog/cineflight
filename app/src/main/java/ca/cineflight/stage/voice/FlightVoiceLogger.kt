package ca.cineflight.stage.voice

import android.util.Log

/**
 * PHASE 1 — Journal (critere E). Consigne la destinee de chaque evenement.
 * fail-open : une erreur de journalisation ne remonte JAMAIS.
 */
class FlightVoiceLogger(private val tailleMax: Int = 300) {

    data class Entree(
        val horodatageMs: Long,
        val eventId: String,
        val messageKey: String,
        val source: VoiceEventSource,
        val severity: VoiceSeverity,
        val outcome: VoiceOutcome,
        val detail: String?
    )

    private val anneau = ArrayDeque<Entree>()
    private val verrou = Any()

    fun consigner(event: FlightVoiceEvent, outcome: VoiceOutcome, detail: String? = null) {
        try {
            val e = Entree(
                horodatageMs = event.createdAtMs,
                eventId = event.eventId,
                messageKey = event.messageKey,
                source = event.source,
                severity = event.severity,
                outcome = outcome,
                detail = detail
            )
            synchronized(verrou) {
                anneau.addLast(e)
                while (anneau.size > tailleMax) anneau.removeFirst()
            }
            Log.i("VOICE", outcome.name + " " + event.messageKey +
                " [" + event.source + "/" + event.severity + "]" +
                (if (detail != null) " - " + detail else ""))
        } catch (_: Throwable) {
        }
    }

    fun dernieres(n: Int = 50): List<Entree> = try {
        synchronized(verrou) { anneau.toList().takeLast(n) }
    } catch (_: Throwable) { emptyList() }

    fun vider() { try { synchronized(verrou) { anneau.clear() } } catch (_: Throwable) {} }
}
