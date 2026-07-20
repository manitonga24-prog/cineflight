# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "Sujet a suivre" in s:
    print("DEJA present"); raise SystemExit

# inserer la carte juste apres le sous-titre (avant la boucle des groupes)
anc = "        for ((nomGroupe, params) in Reglages.GROUPES) {"
bloc = '''        col.addView(carteSujetASuivre())
        for ((nomGroupe, params) in Reglages.GROUPES) {'''
ch = 0
if anc in s:
    s = s.replace(anc, bloc, 1); ch+=1

# ajouter la fonction carteSujetASuivre avant ligneParam
anc2 = "    private fun ligneParam(p: Reglages.Param): LinearLayout {"
fct = '''    private fun carteSujetASuivre(): androidx.cardview.widget.CardView {
        val ACCENT = 0xFF007AFF.toInt()
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = "Sujet a suivre (mode auto)"; textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = "Ce que le drone cherche tout seul. En touchant l'ecran, vous pouvez toujours designer n'importe quelle cible."
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        val choix = listOf("Personne", "Personne + Animal", "Personne + Vehicule", "Tout")
        val boutons = ArrayList<Button>()
        fun maj() {
            val sel = reglages.getSujetASuivre()
            for ((i, b) in boutons.withIndex()) {
                if (i == sel) { b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt()) }
                else { b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE) }
            }
        }
        for ((i, lbl) in choix.withIndex()) {
            val b = Button(this).apply {
                text = lbl; textSize = 13f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
                setOnClickListener { reglages.setSujetASuivre(i); maj() }
            }
            boutons.add(b); box.addView(b)
        }
        maj()
        card.addView(box)
        return card
    }

    private fun ligneParam(p: Reglages.Param): LinearLayout {'''
if anc2 in s:
    s = s.replace(anc2, fct, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 3b (selecteur sujet) :", ch, "/ 2")