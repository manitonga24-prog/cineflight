f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "lancerMacro" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) import + declarations (moteur de macro)
a1 = "import ca.cineflight.stage.control.EcouteContinue"
if a1 in s:
    s = s.replace(a1, a1 + "\nimport ca.cineflight.stage.control.Macros", 1); ch+=1

a2 = "private var ecoute: EcouteContinue? = null"
if a2 in s:
    s = s.replace(a2, a2 + '''
    private val macros by lazy { Macros(this) }
    private var jobMacro: kotlinx.coroutines.Job? = null''', 1); ch+=1

# 2) fonction lancerMacro + stopMacro (avant la derniere accolade)
fonc = '''
    // Lance la macro associee a un tag (11..30). Interruptible par STOP.
    private fun lancerMacro(tagId: Int) {
        val macro = macros.charger(tagId) ?: return
        // une seule macro a la fois : annule la precedente
        jobMacro?.cancel()
        jobMacro = lifecycleScope.launch {
            txtCommandeVoc.text = "\\u25B6 MACRO: " + macro.nom
            txtCommandeVoc.visibility = android.view.View.VISIBLE
            for (etape in macro.etapes) {
                if (!isActive) break          // STOP a annule la macro
                // executer l'action de l'etape (reutilise le mapping existant)
                executerCommandeVocale(etape.action)
                // attendre la duree demandee (en verifiant l'annulation)
                val ms = (etape.attenteS * 1000).toLong()
                var reste = ms
                while (reste > 0 && isActive) {
                    val pas = minOf(100L, reste)
                    kotlinx.coroutines.delay(pas)
                    reste -= pas
                }
            }
            txtCommandeVoc.text = "\\u2713 MACRO TERMINEE"
            txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2000)
        }
    }

    // Interrompt toute macro en cours (appele par STOP)
    private fun stopMacro() {
        jobMacro?.cancel()
        jobMacro = null
    }
'''
idx = s.rstrip().rfind("}")
s = s[:idx] + fonc + "\n}\n"
ch+=1

# 3) STOP doit interrompre la macro : ajouter stopMacro() dans le cas "stop"
a3 = '"stop" -> { pilote.arretUrgence(); modeAuto = false; majBoutonMode() }'
if a3 in s:
    s = s.replace(a3, '"stop" -> { stopMacro(); pilote.arretUrgence(); modeAuto = false; majBoutonMode() }', 1); ch+=1

# 4) la detection de tag : si tag 11..30, lancer la macro au lieu d'une action fixe
a4 = '''            else -> "TAG $id"
        }'''
new4 = '''            in 11..30 -> { lancerMacro(id); "MACRO $id" }
            else -> "TAG $id"
        }'''
if a4 in s:
    s = s.replace(a4, new4, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Moteur macro :", ch, "/ 5")