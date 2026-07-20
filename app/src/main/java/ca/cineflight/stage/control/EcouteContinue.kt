package ca.cineflight.stage.control

import ca.cineflight.stage.R

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * EcouteContinue - ecoute en boucle, ne reagit qu'aux phrases avec le mot-cle.
 *
 * Le SpeechRecognizer Android s'arrete apres chaque phrase ; on le relance
 * automatiquement pour simuler une ecoute continue (mains-libres via DJI Mic 2).
 * Seules les phrases contenant "cineflight" (et variantes mal entendues)
 * declenchent une commande -> evite les declenchements accidentels.
 */
class EcouteContinue(
    private val context: Context,
    private val onCommande: (String) -> Unit,
    private val onTexte: (String) -> Unit
) {
    private var sr: SpeechRecognizer? = null
    private var actif = false
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    // variantes que Google peut entendre pour "cineflight"
    private val motsCles = listOf("cineflight", "cine flight", "ciné flight", "sci-fi light",
                                  "cine light", "scene flight", "cinéflight", "synflight", "sin flight", "sign flight", "sci flight")

    fun demarrer() {
        if (actif) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onTexte(context.getString(R.string.vc_indispo)); return
        }
        actif = true
        couperBips(true)
        onTexte(context.getString(R.string.vc_ecoute_active))
        lancerCycle()
    }

    fun arreter() {
        actif = false
        handler.removeCallbacksAndMessages(null)
        sr?.destroy(); sr = null
        couperBips(false)
        onTexte(context.getString(R.string.vc_ecoute_arretee))
    }

    @Suppress("DEPRECATION")
    private fun couperBips(couper: Boolean) {
        try {
            val flux = intArrayOf(
                android.media.AudioManager.STREAM_SYSTEM,
                android.media.AudioManager.STREAM_NOTIFICATION,
                android.media.AudioManager.STREAM_MUSIC
            )
            for (st in flux) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    audio.adjustStreamVolume(st,
                        if (couper) android.media.AudioManager.ADJUST_MUTE
                        else android.media.AudioManager.ADJUST_UNMUTE, 0)
                } else {
                    audio.setStreamMute(st, couper)
                }
            }
        } catch (e: Exception) { Log.w("EcouteContinue", "mute: $e") }
    }

    fun estActif() = actif

    private fun lancerCycle() {
        if (!actif) return
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, context.getString(R.string.vc_stt_locale))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, context.getString(R.string.vc_stt_locale))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { sr?.startListening(intent) } catch (e: Exception) { relancerBientot() }
    }

    private fun relancerBientot() {
        if (!actif) return
        handler.postDelayed({ lancerCycle() }, 400)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val liste = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            traiter(liste)
            relancerBientot()   // relance pour continuer a ecouter
        }
        override fun onError(error: Int) {
            // erreurs frequentes en boucle (NO_MATCH, timeout) : on relance simplement
            relancerBientot()
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(r: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(e: Int, p: Bundle?) {}
    }

    private fun traiter(liste: ArrayList<String>) {
        for (h in liste) {
            val t = h.lowercase(Locale.FRENCH)
            // le mot-cle doit etre present
            if (motsCles.none { it in t }) continue
            val action = interpreter(t)
            if (action != null) {
                onTexte(context.getString(R.string.vc_ok_fmt, libelleAction(action).uppercase()))
                onCommande(action)
                return
            }
        }
    }

    private fun interpreter(t: String): String? {
        return when {
            "stop" in t || "arrête" in t || "arret" in t || "urgence" in t || "emergency" in t || "abort" in t -> "stop"
            "suivi" in t || "suis" in t || "suivre" in t || "follow" in t -> "suivi"
            "pause" in t || "hold" in t || "wait" in t -> "pause"
            "orbite" in t || "tourne" in t || "orbit" in t || "circle" in t -> "orbite"
            "travel" in t || "tracking" in t || "dolly" in t -> "travelling"
            "révél" in t || "revel" in t || "reveal" in t -> "revelation"
            "approche" in t || "rapproche" in t || "approach" in t || "push" in t || "closer" in t -> "approche"
            "gros" in t || "close" in t || "closeup" in t -> "plan_gros"
            "américain" in t || "americain" in t || "medium" in t || "cowboy" in t -> "plan_americain"
            "détaill" in t || "detaill" in t || "pied" in t || "full shot" in t || "full body" in t -> "plan_pied"
            "ensemble" in t || "large" in t || "wide" in t || "establish" in t -> "plan_ensemble"
            "suivant" in t || "next" in t -> "plan_suivant"
            "statique" in t || "fixe" in t || "static" in t || "fixed" in t || "freeze" in t -> "statique"
            else -> null
        }
    }

    private fun libelleAction(a: String): String = when (a) {
        "suivi" -> context.getString(R.string.vc_act_suivi)
        "pause" -> context.getString(R.string.vc_act_pause)
        "orbite" -> context.getString(R.string.vc_act_orbite)
        "travelling" -> context.getString(R.string.vc_act_travelling)
        "revelation" -> context.getString(R.string.vc_act_revelation)
        "approche" -> context.getString(R.string.vc_act_approche)
        "plan_suivant" -> context.getString(R.string.vc_act_plan_suivant)
        "statique" -> context.getString(R.string.vc_act_statique)
        "stop" -> context.getString(R.string.vc_act_stop)
        "plan_gros" -> context.getString(R.string.vc_act_plan_gros)
        "plan_americain" -> context.getString(R.string.vc_act_plan_americain)
        "plan_pied" -> context.getString(R.string.vc_act_plan_pied)
        "plan_ensemble" -> context.getString(R.string.vc_act_plan_ensemble)
        else -> a
    }
}