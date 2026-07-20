f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\EditeurMacrosActivity.kt"
s = open(f, encoding="utf-8").read()
if "champNom" in s:
    print("DEJA present"); raise SystemExit
ch = 0

a1 = "    private var tagChoisi = 11"
if a1 in s:
    s = s.replace(a1, a1 + "\n    private var champNom: android.widget.EditText? = null", 1); ch+=1

a2 = '''            it.addView(titreCarte("Tag a programmer"))
            val spinTag = Spinner(this)'''
n2 = '''            it.addView(titreCarte("Nom de la macro (max 6)"))
            val cn = EditText(this).apply {
                hint = "ex. Scene1"; setText(""); setTextColor(TEXTE)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            }
            champNom = cn
            it.addView(cn)
            it.addView(titreCarte("Tag a programmer").apply { setPadding(0, dp(14), 0, dp(4)) })
            val spinTag = Spinner(this)'''
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

a3 = 'macros.sauver(Macros.Macro(tagChoisi, "Tag $tagChoisi", etapes.toList()))'
n3 = '''val nomSaisi = champNom?.text?.toString()?.trim().orEmpty().take(6)
            val nomFinal = if (nomSaisi.isNotEmpty()) nomSaisi else "Tag $tagChoisi"
            macros.sauver(Macros.Macro(tagChoisi, nomFinal, etapes.toList()))'''
if a3 in s:
    s = s.replace(a3, n3, 1); ch+=1

a4 = "    private fun chargerExistant() { etapes.clear(); macros.charger(tagChoisi)?.let { etapes.addAll(it.etapes) }; rafraichirListe() }"
n4 = '''    private fun chargerExistant() {
        etapes.clear()
        val m = macros.charger(tagChoisi)
        if (m != null) {
            etapes.addAll(m.etapes)
            champNom?.setText(if (m.nom.startsWith("Tag ")) "" else m.nom)
        } else {
            champNom?.setText("")
        }
        rafraichirListe()
    }'''
if a4 in s:
    s = s.replace(a4, n4, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 1 (champ nom max 6) :", ch, "/ 4")