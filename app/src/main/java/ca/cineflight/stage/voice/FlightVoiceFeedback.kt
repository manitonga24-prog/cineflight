package ca.cineflight.stage.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * PHASE 1 — Moteur vocal Android (SEUL composant qui touche TextToSpeech).
 * Instance DEDIEE (distincte du TTS du clicker). Machine d'etat asynchrone,
 * non bloquante. fail-open : erreur TTS -> abandon annonce, jamais d'exception.
 */
class FlightVoiceFeedback(
    appContext: Context,
    private val logger: FlightVoiceLogger,
    private val onParleFini: (utteranceId: String, erreur: Boolean) -> Unit = { _, _ -> }
) {
    enum class Etat { NON_INITIALISE, EN_INIT, PRET, ECHEC }

    @Volatile var etat: Etat = Etat.NON_INITIALISE
        private set

    private val ctx = appContext.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var langueOk = false
    @Volatile private var vitesse: Float = 1.0f
    @Volatile private var volume: Float = 1.0f
    @Volatile private var langueCode: String = "fr"

    @Volatile var simulerIndisponible: Boolean = false

    fun initialiser() {
        if (etat == Etat.EN_INIT || etat == Etat.PRET) return
        etat = Etat.EN_INIT
        try {
            tts = TextToSpeech(ctx) { statut ->
                try {
                    if (statut == TextToSpeech.SUCCESS) {
                        appliquerLangue(langueCode)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                try { onParleFini(utteranceId ?: "", false) } catch (_: Throwable) {}
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                try { onParleFini(utteranceId ?: "", true) } catch (_: Throwable) {}
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                try { onParleFini(utteranceId ?: "", true) } catch (_: Throwable) {}
                            }
                        })
                        etat = Etat.PRET
                        Log.i("VOICE", "FlightVoiceFeedback pret (langue=" + langueCode + " ok=" + langueOk + ")")
                    } else {
                        etat = Etat.ECHEC
                        Log.w("VOICE", "TTS init echec statut=" + statut)
                    }
                } catch (t: Throwable) {
                    etat = Etat.ECHEC
                    Log.w("VOICE", "TTS init exception: " + t.message)
                }
            }
        } catch (t: Throwable) {
            etat = Etat.ECHEC
            Log.w("VOICE", "Creation TextToSpeech impossible: " + t.message)
        }
    }

    fun definirLangue(code: String) {
        langueCode = code
        if (etat == Etat.PRET) appliquerLangue(code)
    }

    private fun appliquerLangue(code: String) {
        try {
            val loc = if (code == "fr") Locale.FRENCH else Locale.ENGLISH
            val res = tts?.setLanguage(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
            langueOk = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            if (!langueOk) Log.w("VOICE", "Langue " + code + " indisponible (res=" + res + ")")
        } catch (t: Throwable) {
            langueOk = false
            Log.w("VOICE", "setLanguage exception: " + t.message)
        }
    }

    fun definirVitesse(v: Float) {
        vitesse = v.coerceIn(0.5f, 2.0f)
        try { tts?.setSpeechRate(vitesse) } catch (_: Throwable) {}
    }

    fun definirVolume(v: Float) { volume = v.coerceIn(0f, 1f) }

    fun estPret(): Boolean = etat == Etat.PRET && langueOk && !simulerIndisponible

    fun parler(texte: String, utteranceId: String, flush: Boolean): Boolean {
        if (!estPret()) return false
        return try {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = tts?.speak(texte, mode, params, utteranceId) ?: TextToSpeech.ERROR
            r == TextToSpeech.SUCCESS
        } catch (t: Throwable) {
            Log.w("VOICE", "speak exception: " + t.message)
            false
        }
    }

    fun arreterParole() { try { tts?.stop() } catch (_: Throwable) {} }

    fun liberer() {
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        etat = Etat.NON_INITIALISE
    }
}
