package ca.cineflight.stage.voice

/**
 * PHASE 1 — Cerveau des regles vocales. Priorite, dedup, expiration, recuperation,
 * delai de repetition, file bornee. NON BLOQUANT. Temps passe par l'appelant
 * (deterministe, testable). fail-open partout.
 */
class VoicePolicyEngine(
    private val settings: FlightVoiceSettings,
    private val feedback: FlightVoiceFeedback,
    private val logger: FlightVoiceLogger,
    private val tailleFileMax: Int = 32
) {
    private val file = ArrayDeque<FlightVoiceEvent>()
    private val verrou = Any()
    private val dernierParleMs = HashMap<String, Long>()

    private val delaisRepetition: Map<String, Long> = mapOf(
        "gps_drone" to 30_000L,
        "batterie_faible" to 60_000L,
        "batterie_critique" to 15_000L,
        "sujet_perime" to 20_000L,
        "liaison_drone" to 10_000L
    )
    private val delaiRepetitionDefaut = 8_000L
    @Volatile private var compteur = 0L

    fun tryPublish(event: FlightVoiceEvent): Boolean {
        return try {
            logger.consigner(event, VoiceOutcome.RECEIVED)
            if (!settings.doitParler(event)) {
                logger.consigner(event, VoiceOutcome.SUPPRESSED_DISABLED)
                return false
            }
            synchronized(verrou) {
                if (event.recoveryKey != null) {
                    val iter = file.iterator()
                    while (iter.hasNext()) {
                        val e = iter.next()
                        if (e.deduplicationKey == event.recoveryKey) {
                            iter.remove()
                            logger.consigner(e, VoiceOutcome.CANCELLED_BY_RECOVERY)
                        }
                    }
                }
                if (event.deduplicationKey != null &&
                    file.any { it.deduplicationKey == event.deduplicationKey }) {
                    logger.consigner(event, VoiceOutcome.SUPPRESSED_DUPLICATE, "deja en file")
                    return false
                }
                if (file.size >= tailleFileMax) {
                    val plusBas = file.minByOrNull { it.severity.niveau }
                    if (plusBas != null && plusBas.severity.niveau < event.severity.niveau) {
                        file.remove(plusBas)
                        logger.consigner(plusBas, VoiceOutcome.QUEUE_OVERFLOW)
                    } else {
                        logger.consigner(event, VoiceOutcome.QUEUE_OVERFLOW, "file pleine")
                        return false
                    }
                }
                file.addLast(event)
                logger.consigner(event, VoiceOutcome.QUEUED)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun tick(maintenantMs: Long, ttsOccupe: Boolean) {
        try {
            val choisi: FlightVoiceEvent = synchronized<FlightVoiceEvent?>(verrou) {
                val iter = file.iterator()
                while (iter.hasNext()) {
                    val e = iter.next()
                    if (e.expiresAtMs != null && maintenantMs > e.expiresAtMs) {
                        iter.remove()
                        logger.consigner(e, VoiceOutcome.EXPIRED)
                    }
                }
                if (file.isEmpty()) return@synchronized null
                val cible = file.maxByOrNull { it.severity.niveau }!!
                if (ttsOccupe && cible.interruptPolicy != InterruptPolicy.FLUSH &&
                    cible.severity != VoiceSeverity.CRITICAL) {
                    return@synchronized null
                }
                val cle = cible.deduplicationKey
                if (cle != null && cible.repeatPolicy == RepeatPolicy.ON_INTERVAL) {
                    val dernier = dernierParleMs[cle]
                    val delai = delaisRepetition[cle] ?: delaiRepetitionDefaut
                    if (dernier != null && maintenantMs - dernier < delai) {
                        file.remove(cible)
                        logger.consigner(cible, VoiceOutcome.SUPPRESSED_DUPLICATE, "delai repetition")
                        return@synchronized null
                    }
                }
                file.remove(cible)
                cible
            } ?: return

            val texte = FlightVoiceCatalog.texte(choisi.messageKey, settings.langue, choisi.parameters)
            val flush = choisi.interruptPolicy == InterruptPolicy.FLUSH ||
                choisi.severity == VoiceSeverity.CRITICAL
            val ok = feedback.parler(texte, choisi.eventId, flush)
            if (ok) {
                choisi.deduplicationKey?.let { dernierParleMs[it] = maintenantMs }
                logger.consigner(choisi, VoiceOutcome.SPOKEN, texte)
            } else {
                logger.consigner(choisi, VoiceOutcome.TTS_FAILED)
            }
        } catch (_: Throwable) {
        }
    }

    fun viderFile() { try { synchronized(verrou) { file.clear() } } catch (_: Throwable) {} }
    fun tailleFile(): Int = try { synchronized(verrou) { file.size } } catch (_: Throwable) { 0 }
    fun prochainId(prefixe: String): String { compteur += 1; return prefixe + "-" + compteur }
}
