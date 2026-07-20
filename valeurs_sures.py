# 1) ajouter la config sure dans Reglages.kt
fr = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Reglages.kt"
s = open(fr, encoding="utf-8").read()
if "appliquerValeursSures" not in s:
    anc = "    fun reset(p: Param) = prefs.edit().remove(p.cle).apply()"
    methode = anc + '''

    // Config "valeurs sures" : prudente mais toujours cinematique (pas le minimum brut)
    fun appliquerValeursSures() {
        set(ORBITE_HAUTEUR, 4f); set(ORBITE_RAYON, 8f); set(ORBITE_VITESSE, 0.4f)
        set(TRAVEL_DISTANCE, 8f); set(TRAVEL_HAUTEUR, 4f); set(TRAVEL_REACTIV, 0.7f)
        set(REVEL_RECUL, 0.3f); set(REVEL_MONTEE, 0.3f)
        set(APP_VITESSE, 0.4f); set(APP_DISTANCE, 4f)
        set(SUIVI_DOUCEUR, 0.8f)
    }'''
    s = s.replace(anc, methode, 1)
    open(fr, "w", encoding="utf-8", newline="\n").write(s)
    print("Reglages: appliquerValeursSures ajoutee")
else:
    print("Reglages: deja present")

# 2) ajouter le bouton dans ReglagesActivity (avant le bouton reinitialiser)
fa = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
a = open(fa, encoding="utf-8").read()
if "appliquerValeursSures" not in a:
    anc = '''        col.addView(Button(this).apply {
            text = "Reinitialiser tout"'''
    add = '''        col.addView(Button(this).apply {
            text = "Mettre toutes les valeurs SURES"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF34C759.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)); lp.topMargin = dp(8); layoutParams = lp
            setOnClickListener {
                reglages.appliquerValeursSures()
                Toast.makeText(this@ReglagesActivity, "Valeurs sures appliquees", Toast.LENGTH_SHORT).show()
                recreate()
            }
        })
        col.addView(TextView(this).apply {
            text = "Config prudente : vitesses moderees, distances et hauteurs confortables. Les plans restent beaux, avec de bonnes marges de securite."
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(dp(4), dp(6), dp(4), dp(4))
        })
        col.addView(Button(this).apply {
            text = "Reinitialiser tout"'''
    a = a.replace(anc, add, 1)
    open(fa, "w", encoding="utf-8", newline="\n").write(a)
    print("ReglagesActivity: bouton SURES ajoute :", "appliquerValeursSures" in a)
else:
    print("ReglagesActivity: deja present")