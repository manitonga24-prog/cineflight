# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s = open(f, encoding="utf-8").read()

# Reperer le debut (creation de barre) et la fin (col.addView(barre))
debut = s.find("        val barre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL")
fin_marqueur = "        col.addView(barre)"
fin = s.find(fin_marqueur)
if debut == -1 or fin == -1:
    print("ANCRES NON TROUVEES"); raise SystemExit
fin += len(fin_marqueur)

nouveau = '''        // Titre sur sa propre ligne (ne sera plus ecrase)
        val titres = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(10)) }
        titres.addView(TextView(this).apply { text = "Tags"; textSize = 24f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        titres.addView(TextView(this).apply { text = "Montrez une carte au drone pour le piloter"; textSize = 13f; setTextColor(TEXTE_DOUX) })
        col.addView(titres)

        // Rangee de boutons defilante horizontalement (tient sur une ligne meme nombreux)
        val barre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(14)) }
        fun boutonMenu(libelle: String, plein: Boolean, action: () -> Unit) {
            barre.addView(Button(this).apply {
                text = libelle; textSize = 14f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
                if (plein) { setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT) }
                else { setTextColor(ACCENT); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt()) }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.rightMargin = dp(8); layoutParams = lp
                setOnClickListener { action() }
            })
        }
        boutonMenu("Mes macros", true) { startActivity(android.content.Intent(this@TagsActivity, EditeurMacrosActivity::class.java)) }
        boutonMenu("Guide", false) { startActivity(android.content.Intent(this@TagsActivity, GuideActivity::class.java)) }
        boutonMenu("Reglages", false) { startActivity(android.content.Intent(this@TagsActivity, ReglagesActivity::class.java)) }
        boutonMenu("Album", false) { startActivity(android.content.Intent(this@TagsActivity, PhototequeActivity::class.java)) }
        boutonMenu("Montage", false) { startActivity(android.content.Intent(this@TagsActivity, MontageActivity::class.java)) }
        boutonMenu("Mes montages", false) { startActivity(android.content.Intent(this@TagsActivity, MesMontagesActivity::class.java)) }
        val scrollBarre = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(barre) }
        col.addView(scrollBarre)'''

s = s[:debut] + nouveau + s[fin:]
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Barre menu reorganisee OK")