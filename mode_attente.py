f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "cibleVerrouillee" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) declaration du verrou cible
a1 = "private var modeAuto = false"
if a1 in s:
    s = s.replace(a1, a1 + '''
    private var cibleVerrouillee = false   // le drone ne suit QUE si une cible est verrouillee''', 1); ch+=1

# 2) dans onSujet : suivre seulement si cible verrouillee, sinon ATTENTE (hover)
a2 = '''                        if (trouve && modeAuto) {
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))'''
new2 = '''                        if (trouve && modeAuto && cibleVerrouillee) {
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))
                        } else if (modeAuto && !cibleVerrouillee) {
                            // ATTENTE : hover stable, aucun deplacement tant que pas de cible
                            pilote.soumettre(RecepteurBridge.CommandeBridge(
                                System.currentTimeMillis(), 0f, 0f, 0f, 0f, "actif", false, 0f, 0f))'''
if a2 in s:
    s = s.replace(a2, new2, 1); ch+=1
else:
    print("ancre onSujet non trouvee - forme differente")

# 3) fonction verrouiller/deverrouiller la cible
fonc = '''
    // Verrouille la personne actuellement detectee comme cible a suivre.
    private fun verrouillerCible() {
        cibleVerrouillee = true
        if (!modeAuto) basculerMode(true)
        txtCommandeVoc.text = "\\uD83D\\uDD12 CIBLE VERROUILLEE"
        txtCommandeVoc.visibility = android.view.View.VISIBLE
        txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
    }

    private fun deverrouillerCible() {
        cibleVerrouillee = false
    }
'''
idx = s.rstrip().rfind("}")
s = s[:idx] + fonc + "\n}\n"
ch+=1

# 4) STOP doit aussi deverrouiller la cible (retour en ATTENTE)
a4 = '"stop" -> { stopMacro(); pilote.arretUrgence(); modeAuto = false; majBoutonMode() }'
if a4 in s:
    s = s.replace(a4, '"stop" -> { stopMacro(); deverrouillerCible(); pilote.arretUrgence(); modeAuto = false; majBoutonMode() }', 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Mode ATTENTE + verrou :", ch, "/ 4")