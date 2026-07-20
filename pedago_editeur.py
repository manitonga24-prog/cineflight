f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\EditeurMacrosActivity.kt"
s = open(f, encoding="utf-8").read()
if "aidePedago" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# Ajouter un bloc d'aide pedagogique juste apres le titre
old = '''        col.addView(TextView(this).apply {
            text = "EDITEUR DE MACROS"
            textSize = 20f
            setPadding(0, 0, 0, 20)
        })'''
new = '''        col.addView(TextView(this).apply {
            text = "EDITEUR DE MACROS"
            textSize = 20f
            setPadding(0, 0, 0, 8)
        })

        // --- aide pedagogique ---
        val aidePedago = TextView(this).apply {
            text = "Une MACRO est une suite d'actions automatiques declenchee par un tag (11 a 30).\\n\\n" +
                   "Comment faire :\\n" +
                   "1. Choisissez un tag libre (11-30).\\n" +
                   "2. Ajoutez des etapes une par une : une ACTION + une ATTENTE (secondes) avant l'etape suivante.\\n" +
                   "3. Sauvegardez. Montrez ce tag au drone -> il enchaine toute la sequence.\\n\\n" +
                   "Exemple - tag \\\"entree en scene\\\" :\\n" +
                   "  - Plan large (attente 3s)\\n" +
                   "  - Approche (attente 4s)\\n" +
                   "  - Gros plan (attente 2s)\\n" +
                   "  - Orbite (attente 5s)\\n\\n" +
                   "L'ATTENTE = combien de temps le drone garde l'action avant de passer a la suivante.\\n" +
                   "SECURITE : le tag STOP (ou la commande Stop) interrompt TOUJOURS une macro en cours."
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF0F4F3.toInt())
        }
        col.addView(aidePedago)
        col.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
        })'''
if old in s:
    s = s.replace(old, new, 1); ch+=1
else:
    print("ancre titre non trouvee")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Aide pedago editeur :", ch, "/ 1")