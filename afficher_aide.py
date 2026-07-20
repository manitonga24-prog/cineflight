f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "p.aide" in s:
    print("DEJA present"); raise SystemExit

# inserer le texte d'aide juste avant le "return bloc" de ligneParam
old = '''        bloc.addView(bornes)
        return bloc'''
new = '''        bloc.addView(bornes)
        // explication pedagogique de l'effet du reglage
        if (p.aide.isNotEmpty()) {
            bloc.addView(TextView(this).apply {
                text = p.aide; textSize = 12f; setTextColor(TEXTE_DOUX)
                setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(4), 0, dp(2))
            })
        }
        // separateur leger entre parametres
        bloc.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(8) }
            setBackgroundColor(0xFFEFEFEF.toInt())
        })
        return bloc'''
if old in s:
    s = s.replace(old, new, 1)
    print("Aide affichee sous chaque curseur")
else:
    print("ANCRE NON TROUVEE")
open(f, "w", encoding="utf-8", newline="\n").write(s)