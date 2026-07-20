f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "executerTag" in s:
    print("DEJA cable"); raise SystemExit
ch = 0

# 1) ajouter onTag au constructeur de YoloSuivi (apres le bloc onSujet)
# on cherche la fermeture du lambda onSujet : "            }\n        }" suivi de la fin du YoloSuivi(...)
old1 = '''            yoloSuivi = YoloSuivi(yolo) { trouve, cx, cy, w, h ->
                runOnUiThread {
                    val boxes = if (trouve)
                        listOf(RecepteurBoxes.Box(cx - w / 2f, cy - h / 2f, w, h, 1f, true))
                    else emptyList()
                    overlayYolo.majBoxes(boxes)
                    txtSujet.text = if (trouve) "\\u25CF SUJET SUIVI" else "\\u25CF AUCUN SUJET"
                    txtSujet.setTextColor(if (trouve) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                    if (trouve && modeAuto) {
                        pilote.soumettre(calculerSuivi(cx, cy, w, h))
                    }
                }
            }'''
new1 = '''            yoloSuivi = YoloSuivi(yolo,
                onSujet = { trouve, cx, cy, w, h ->
                    runOnUiThread {
                        val boxes = if (trouve)
                            listOf(RecepteurBoxes.Box(cx - w / 2f, cy - h / 2f, w, h, 1f, true))
                        else emptyList()
                        overlayYolo.majBoxes(boxes)
                        txtSujet.text = if (trouve) "\\u25CF SUJET SUIVI" else "\\u25CF AUCUN SUJET"
                        txtSujet.setTextColor(if (trouve) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                        if (trouve && modeAuto) {
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))
                        }
                    }
                },
                onTag = { id -> runOnUiThread { executerTag(id) } }
            )'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1
else:
    print("ancre1 onSujet non trouvee (forme differente)")

# 2) fonction executerTag (mapping tags -> actions) avant la derniere accolade
fonc = '''
    // Mapping des tags ArUco vers les actions (modes + cadrages)
    private fun executerTag(id: Int) {
        val nom = when (id) {
            0 -> { findViewById<Button>(R.id.btnMouvStatique).performClick(); "PRESENTATION" }
            1 -> { findViewById<Button>(R.id.btnMouvTravel).performClick(); "MARCHE" }
            2 -> { findViewById<Button>(R.id.btnMouvOrbite).performClick(); "DANSE" }
            3 -> { findViewById<Button>(R.id.btnMouvRevel).performClick(); "IMMOBILIER" }
            4 -> { if (!modeAuto) basculerMode(true); "SPORT/SUIVI" }
            5 -> { findViewById<Button>(R.id.btnPlanEnsemble).performClick(); "PLAN LARGE" }
            6 -> { findViewById<Button>(R.id.btnPlanAmericain).performClick(); "AMERICAIN" }
            7 -> { findViewById<Button>(R.id.btnPlanGros).performClick(); "RAPPROCHE" }
            8 -> { findViewById<Button>(R.id.btnMouvOrbite).performClick(); "ORBITE" }
            9 -> { if (modeAuto) basculerMode(false); "PAUSE" }
            10 -> { pilote.arretUrgence(); modeAuto = false; majBoutonMode(); "STOP" }
            else -> "TAG $id"
        }
        txtCommandeVoc.text = "\\uD83C\\uDFF7 TAG $id \\u2192 $nom"
        txtCommandeVoc.visibility = android.view.View.VISIBLE
        txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
    }
'''
idx = s.rstrip().rfind("}")
s = s[:idx] + fonc + "\n}\n"
ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Branchement onTag :", ch, "/ 2")