fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
ch = 0

# 1) Remplacer le clic micro : au lieu de vocal?.ecouter(), lancer la popup Google
old = '''        findViewById<Button>(R.id.btnMicro).setOnClickListener {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            } else {
                vocal?.ecouter()
            }
        }'''
new = '''        findViewById<Button>(R.id.btnMicro).setOnClickListener {
            lancerPopupVocale()
        }'''
if old in k:
    k = k.replace(old, new, 1); ch+=1

# 2) Ajouter lancerPopupVocale + onActivityResult (avant la derniere accolade)
fonc = '''
    private val REQ_VOCAL = 4242
    private fun lancerPopupVocale() {
        try {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Commande CineFlight")
            }
            startActivityForResult(intent, REQ_VOCAL)
        } catch (e: Exception) {
            txtCommandeVoc.text = "Pas de module vocal Google"
            txtCommandeVoc.visibility = android.view.View.VISIBLE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOCAL && resultCode == RESULT_OK) {
            val res = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val brut = res?.joinToString(" ")?.lowercase() ?: ""
            android.util.Log.i("Vocal", "popup entendu: $brut")
            val action = interpreterVocal(brut)
            if (action != null) {
                txtCommandeVoc.text = "\\u2713 " + action.uppercase()
                executerCommandeVocale(action)
            } else {
                txtCommandeVoc.text = "? non reconnu"
            }
            txtCommandeVoc.visibility = android.view.View.VISIBLE
            txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
        }
    }

    private fun interpreterVocal(t: String): String? {
        return when {
            "suivi" in t || "suis" in t || "suivre" in t -> "suivi"
            "pause" in t || "stop" in t || "arr\\u00eate" in t || "arret" in t -> "pause"
            "orbite" in t || "tourne" in t -> "orbite"
            "travel" in t -> "travelling"
            "r\\u00e9v\\u00e9l" in t || "revel" in t -> "revelation"
            "approche" in t || "rapproche" in t -> "approche"
            "suivant" in t -> "plan_suivant"
            "statique" in t || "fixe" in t -> "statique"
            else -> null
        }
    }
'''
idx = k.rstrip().rfind("}")
k = k[:idx] + fonc + "\n}\n"
ch+=1

open(fk, "w", encoding="utf-8", newline="\n").write(k)
print("Popup vocale Google :", ch, "/ 2")