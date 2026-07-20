f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\CommandeVocale.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) Langue : fr-FR (plus universel que fr-CA) + preferer hors-ligne off + plus de resultats
old1 = '''            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)'''
new1 = '''            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1

# 2) onResults : analyser TOUTES les hypotheses (pas juste la 1ere)
old2 = '''        override fun onResults(results: Bundle?) {
            val liste = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val brut = liste?.firstOrNull()?.lowercase(Locale.FRENCH) ?: ""
            Log.i("CommandeVocale", "entendu: $brut")
            val action = interpreter(brut)
            if (action != null) {
                onTexte("\\u2713 " + action.uppercase())
                onCommande(action)
            } else {
                onTexte("? \\"$brut\\" non reconnu")
            }
        }'''
new2 = '''        override fun onResults(results: Bundle?) {
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
                onTexte("\\u2713 " + action.uppercase())
                onCommande(action)
            } else {
                onTexte("? \\"$brut\\" non reconnu")
            }
        }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1

# 3) onError : messages clairs au lieu de codes
old3 = '''        override fun onError(error: Int) { onTexte("(erreur micro $error)") }'''
new3 = '''        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Pas compris, reessaie"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Parle plus fort/tot"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reseau requis"
                11 -> "Langue non installee (voir reglages)"
                else -> "Micro erreur $error"
            }
            onTexte(msg)
        }'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Ameliorations vocal :", ch, "/ 3")