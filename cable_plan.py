f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "cibleHPlan" in s:
    print("DEJA cable"); raise SystemExit
ch = 0

# 1) variable cibleHPlan apres la declaration yoloSuivi
a1 = "private var yoloSuivi: YoloSuivi? = null"
if a1 in s:
    s = s.replace(a1, a1 + "\n    private var cibleHPlan = 0.55f   // cadrage courant (americain par defaut)", 1); ch+=1

# 2) calculerSuivi : remplacer la cible fixe
a2 = "val cibleH = 0.55f"
if a2 in s:
    s = s.replace(a2, "val cibleH = cibleHPlan", 1); ch+=1

# 3) indicateur sujet dans le callback (apres overlayYolo.majBoxes(boxes))
a3 = "overlayYolo.majBoxes(boxes)"
add3 = '''overlayYolo.majBoxes(boxes)
                    txtSujet.text = if (trouve) "\\u25CF SUJET SUIVI" else "\\u25CF AUCUN SUJET"
                    txtSujet.setTextColor(if (trouve) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())'''
if a3 in s:
    s = s.replace(a3, add3, 1); ch+=1

# 4) declaration txtSujet (lateinit) + cablage des boutons
# 4a declaration apres overlay: lateinit
a4 = "private lateinit var overlayYolo: OverlayYolo"
if a4 in s:
    s = s.replace(a4, a4 + "\n    private lateinit var txtSujet: android.widget.TextView", 1); ch+=1

# 4b init + boutons : juste apres overlayYolo = findViewById(R.id.overlayYolo)
a5 = "overlayYolo = findViewById(R.id.overlayYolo)"
add5 = '''overlayYolo = findViewById(R.id.overlayYolo)
        txtSujet = findViewById(R.id.txtSujet)
        run {
            val bGros = findViewById<Button>(R.id.btnPlanGros)
            val bAmer = findViewById<Button>(R.id.btnPlanAmericain)
            val bPied = findViewById<Button>(R.id.btnPlanPied)
            val bEns  = findViewById<Button>(R.id.btnPlanEnsemble)
            val tous = listOf(bGros, bAmer, bPied, bEns)
            fun choisir(sel: Button, cible: Float) {
                cibleHPlan = cible
                tous.forEach { it.setBackgroundColor(0xFF37474F.toInt()) }
                sel.setBackgroundColor(0xFF2E7D32.toInt())
            }
            bGros.setOnClickListener { choisir(bGros, 0.75f) }
            bAmer.setOnClickListener { choisir(bAmer, 0.55f) }
            bPied.setOnClickListener { choisir(bPied, 0.40f) }
            bEns.setOnClickListener  { choisir(bEns, 0.25f) }
        }'''
if a5 in s:
    s = s.replace(a5, add5, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Cablage PLAN :", ch, "/ 5")
print("cibleHPlan present :", "cibleHPlan" in s)