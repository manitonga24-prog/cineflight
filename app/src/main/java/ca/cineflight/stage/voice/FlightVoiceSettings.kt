package ca.cineflight.stage.voice

/**
 * PHASE 1 — Reglages. FEATURE FLAG maitre (active) par defaut DESACTIVE :
 * l'app se comporte EXACTEMENT comme avant tant que rien n'est active (critere A).
 * Meme confirmations coupees, l'utilisateur garde les alertes critiques.
 */
class FlightVoiceSettings {

    // FEATURE FLAG MAITRE (infrastructure). false = la couche vocale ne parle jamais.
    @Volatile var active: Boolean = false

    // FEATURE FLAG PHASE 2 : branchement observation DJI. false = seul l'ecran de test
    // publie ; les callbacks/etats reels du drone ne publient rien. Permet de couper
    // toute l'observation DJI sans retirer le moteur valide.
    @Volatile var observationDji: Boolean = false
    @Volatile var mode: VoiceMode = VoiceMode.NORMAL
    @Volatile var alertesCritiquesToujours: Boolean = true

    @Volatile var confirmationsCommandes: Boolean = true
    @Volatile var alertesSecurite: Boolean = true
    @Volatile var etatGpsRtk: Boolean = true
    @Volatile var etatSujet: Boolean = true
    @Volatile var batterie: Boolean = true
    @Volatile var missions: Boolean = true
    @Volatile var camera: Boolean = true
    @Volatile var clicker: Boolean = true
    @Volatile var annoncesRecuperation: Boolean = true
    @Volatile var lireValeursNumeriques: Boolean = true

    @Volatile var vitesse: Float = 1.0f
    @Volatile var volumeRelatif: Float = 1.0f
    @Volatile var langue: String = "fr"

    fun doitParler(event: FlightVoiceEvent): Boolean {
        return try {
            if (!active) return false
            if (event.severity.niveau < mode.seuil) {
                if (!(event.severity == VoiceSeverity.CRITICAL && alertesCritiquesToujours)) {
                    return false
                }
            }
            val categorieOk = when (event.source) {
                VoiceEventSource.DJI_COMMAND -> confirmationsCommandes
                VoiceEventSource.VIRTUAL_STICK -> confirmationsCommandes
                VoiceEventSource.DJI_FLIGHT_STATE -> alertesSecurite
                VoiceEventSource.CONNECTION -> alertesSecurite
                VoiceEventSource.BATTERY -> batterie
                VoiceEventSource.RTK_SUBJECT -> etatGpsRtk
                VoiceEventSource.VISION -> etatSujet
                VoiceEventSource.PREDICTION -> etatSujet
                VoiceEventSource.MISSION -> missions
                VoiceEventSource.CAMERA -> camera
                VoiceEventSource.CLICKER -> clicker
                VoiceEventSource.USER_INTERFACE -> true
                VoiceEventSource.TEST -> true
            }
            if (!categorieOk && !(event.severity == VoiceSeverity.CRITICAL && alertesCritiquesToujours)) {
                return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
