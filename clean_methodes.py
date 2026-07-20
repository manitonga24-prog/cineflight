# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()

bloc = """    /** Petit titre de section. */
    private fun titreSection(t: String): TextView = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(8), 0, dp(6))
    }
    /** Carte d'explication (titre + texte) avec fond. */
    private fun carte(titre: String, texte: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARTE); setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(14); layoutParams = lp
        }
        box.addView(TextView(this).apply {
            text = titre; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = texte; textSize = 12f; setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        return box
    }
"""
nb = s.count(bloc)
print("occurrences du bloc :", nb)
if nb >= 2:
    # supprimer la DERNIERE occurrence
    idx = s.rfind(bloc)
    s = s[:idx] + s[idx+len(bloc):]
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("doublon titreSection+carte supprime OK")
else:
    print("PAS DE DOUBLON (ou bloc non identique) - rien supprime")