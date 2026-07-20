package ca.cineflight.stage.control

import ca.cineflight.stage.R

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * CommandeVocale - reconnaissance vocale ponctuelle (bouton micro).
 *
 * Vocabulaire LIMITE (spec CineFlight Solo). Le pilote appuie sur le micro,
 * prononce une commande, le systeme la reconnait et la transmet via onCommande.
 * Le mot-cle "cineflight" est optionnel (le bouton fait deja office de declencheur).
 *
 * Commandes reconnues : suivi, pause, orbite, travelling, revelation,
 * approche, plan suivant.
 */
class CommandeVocale(
    private val context: Context,
    // onCommande(action) : action normalisee ("suivi","pause","orbite",...)
    private val onCommande: (String) -> Unit,
    // onTexte(texteReconnu) : pour afficher a l'ecran ce qui a ete entendu
    private val onTexte: (String) -> Unit
) {
    private var sr: SpeechRecognizer? = null

    fun ecouter() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onTexte(context.getString(R.string.vc_indispo))
            return
        }
        // (re)creer a chaque ecoute (plus stable)
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, context.getString(R.string.vc_stt_locale))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, context.getString(R.string.vc_stt_locale))
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
        onTexte(context.getString(R.string.vc_ecoute))
        sr?.startListening(intent)
    }

    fun arreter() {
        sr?.destroy()
        sr = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val liste = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            Log.i("CommandeVocale", "hypotheses: $liste")
            // tester chaque hypothese, garder la 1ere qui matche une commande
            var action: String? = null
            var brut = ""
            for (h in liste) {
                val t = h.lowercase(Locale.FRENCH)
                if (brut.isEmpty()) brut = t
                val a = interpreter(t)
                if (a != null) { action = a; break }
            }
            if (action != null) {
                onTexte(context.getString(R.string.vc_ok_fmt, libelleAction(action).uppercase()))
                onCommande(action)
            } else {
                onTexte(context.getString(R.string.vc_non_reconnu_fmt, brut))
            }
        }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.vc_err_no_match)
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.vc_err_timeout)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.vc_err_network)
                11 -> context.getString(R.string.vc_err_langue)
                else -> context.getString(R.string.vc_err_micro_fmt, error)
            }
            onTexte(msg)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // mappe le texte entendu vers une action normalisee (tolerant aux variantes)
    private fun interpreter(t: String): String? {
        return when {
            "suivi" in t || "suis" in t || "suivre" in t || "follow" in t -> "suivi"
            "pause" in t || "stop" in t || "arr" in t || "hold" in t || "wait" in t -> "pause"
            "orbite" in t || "tourne" in t || "orbit" in t || "circle" in t -> "orbite"
            "travel" in t || "tracking" in t || "dolly" in t -> "travelling"
            "revel" in t || "révél" in t || "reveal" in t -> "revelation"
            "approche" in t || "rapproche" in t || "approach" in t || "push" in t || "closer" in t -> "approche"
            ("plan" in t && "suiv" in t) || "suivant" in t || "next" in t -> "plan_suivant"
            "statique" in t || "statik" in t || "fixe" in t || "static" in t || "fixed" in t || "freeze" in t -> "statique"
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