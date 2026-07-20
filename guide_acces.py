# 1) Manifest
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
m = open(fm, encoding="utf-8").read()
if "GuideActivity" not in m:
    m = m.replace('</application>', '    <activity android:name=".GuideActivity" android:exported="false" />\n    </application>', 1)
    open(fm, "w", encoding="utf-8", newline="\n").write(m)
    print("Manifest: GuideActivity declaree")
else:
    print("Manifest: deja present")

# 2) bouton Guide dans l'ecran TAGS (a cote du bouton Mes macros)
ft = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
t = open(ft, encoding="utf-8").read()
if "GuideActivity" not in t:
    anc = '''        barre.addView(Button(this).apply {
            text = "Mes macros"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, EditeurMacrosActivity::class.java)) }
        })'''
    add = anc + '''
        barre.addView(Button(this).apply {
            text = "Guide"; setTextColor(ACCENT); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.leftMargin = dp(8); layoutParams = lp
            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, GuideActivity::class.java)) }
        })'''
    t = t.replace(anc, add, 1)
    open(ft, "w", encoding="utf-8", newline="\n").write(t)
    print("TagsActivity: bouton Guide ajoute :", "GuideActivity" in t)
else:
    print("TagsActivity: deja present")