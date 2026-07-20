package ca.cineflight.stage.voice

/**
 * PHASE 1 — Types de base de l'assistant vocal de vol.
 * PRINCIPE DE SECURITE : la couche vocale est un consommateur FACULTATIF des etats.
 * Elle ne devient JAMAIS une dependance du controle de vol. fail-open, non bloquant,
 * isole (aucun import de PiloteDrone/PontDji/clicker ici).
 */

enum class VoiceSeverity(val niveau: Int) {
    INFO(1), STATE(2), COMMAND(3), SAFETY(4), CRITICAL(5)
}

enum class VoiceEventSource {
    TEST, USER_INTERFACE, CLICKER, DJI_COMMAND, DJI_FLIGHT_STATE, VIRTUAL_STICK,
    RTK_SUBJECT, VISION, PREDICTION, BATTERY, CONNECTION, MISSION, CAMERA
}

enum class InterruptPolicy { NONE, FLUSH }

enum class RepeatPolicy { ONCE, ON_STATE_CHANGE, ON_INTERVAL }

enum class VoiceMode(val seuil: Int) {
    MINIMAL(VoiceSeverity.CRITICAL.niveau),
    NORMAL(VoiceSeverity.COMMAND.niveau),
    DETAILLE(VoiceSeverity.INFO.niveau)
}

data class FlightVoiceEvent(
    val eventId: String,
    val messageKey: String,
    val source: VoiceEventSource,
    val severity: VoiceSeverity,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null,
    val deduplicationKey: String? = null,
    val recoveryKey: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val interruptPolicy: InterruptPolicy = InterruptPolicy.NONE,
    val repeatPolicy: RepeatPolicy = RepeatPolicy.ON_STATE_CHANGE
)

enum class VoiceOutcome {
    RECEIVED, QUEUED, SPOKEN, SUPPRESSED_DUPLICATE, SUPPRESSED_DISABLED,
    SUPPRESSED_MODE, EXPIRED, INTERRUPTED, CANCELLED_BY_RECOVERY, QUEUE_OVERFLOW, TTS_FAILED
}
