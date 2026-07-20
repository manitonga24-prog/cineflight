package ca.cineflight.stage.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PHASE 1 — Facade publique. SEUL point d'entree que le reste de l'app touchera
 * (plus tard, en OBSERVATION seulement). Portee ISOLEE avec SupervisorJob : un
 * plantage vocal n'annule jamais une portee de pilotage. Aucun evenement reel du
 * drone n'est branche en Phase 1 (seul l'ecran de test publie).
 */
class FlightVoiceSystem private constructor(appContext: Context) {

    val settings = FlightVoiceSettings()
    val logger = FlightVoiceLogger()
    val feedback = FlightVoiceFeedback(appContext, logger) { _, _ -> reveiller() }
    val engine = VoicePolicyEngine(settings, feedback, logger)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var demarre = false
    @Volatile private var horlogeMs: Long = 0L

    fun demarrer(langueInitiale: String) {
        if (demarre) return
        demarre = true
        settings.langue = langueInitiale
        feedback.definirLangue(langueInitiale)
        feedback.definirVitesse(settings.vitesse)
        feedback.definirVolume(settings.volumeRelatif)
        feedback.initialiser()
        scope.launch {
            while (isActive) {
                try {
                    horlogeMs += 200
                    engine.tick(horlogeMs, ttsOccupe = false)
                } catch (_: Throwable) {}
                delay(200)
            }
        }
    }

    private fun reveiller() {
        scope.launch { try { engine.tick(horlogeMs, ttsOccupe = false) } catch (_: Throwable) {} }
    }

    fun publishVoiceEventSafely(event: FlightVoiceEvent, maintenantMs: Long) {
        try {
            horlogeMs = maintenantMs
            engine.tryPublish(event)
        } catch (_: Throwable) {
        }
    }

    fun definirLangue(code: String) { settings.langue = code; feedback.definirLangue(code) }
    fun definirVitesse(v: Float) { settings.vitesse = v; feedback.definirVitesse(v) }
    fun definirVolume(v: Float) { settings.volumeRelatif = v; feedback.definirVolume(v) }

    fun arreterTout() { engine.viderFile(); feedback.arreterParole() }

    fun liberer() {
        try { scope.cancel() } catch (_: Throwable) {}
        feedback.liberer()
        demarre = false
    }

    companion object {
        @Volatile private var instance: FlightVoiceSystem? = null
        fun obtenir(context: Context): FlightVoiceSystem {
            return instance ?: synchronized(this) {
                instance ?: FlightVoiceSystem(context.applicationContext).also { instance = it }
            }
        }
    }
}
