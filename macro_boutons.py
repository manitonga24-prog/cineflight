f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "rafraichirBoutonsMacros" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) ajouter la fonction + onResume juste avant lancerMacro
anc = "    private fun lancerMacro(tagId: Int) {"
fct = '''    private fun rafraichirBoutonsMacros() {
        val rangee = findViewById<LinearLayout>(R.id.rangeeMacros) ?: return
        rangee.removeAllViews()
        val d = resources.displayMetrics.density
        fun dpx(v: Int) = (v * d).toInt()
        for (tagId in macros.tagsAvecMacro()) {
            val m = macros.charger(tagId) ?: continue
            val label = if (m.nom.isNotEmpty()) m.nom else "T$tagId"
            val b = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpx(6), 0, dpx(6), 0)
                setInsetTop(0); setInsetBottom(0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF6A1B9A.toInt())
                val lp = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dpx(36))
                lp.marginEnd = dpx(4)
                layoutParams = lp
                setOnClickListener { lancerMacro(tagId) }
            }
            rangee.addView(b)
        }
    }

    override fun onResume() {
        super.onResume()
        try { rafraichirBoutonsMacros() } catch (_: Exception) {}
    }

'''
if anc in s:
    s = s.replace(anc, fct + anc, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 3 (boutons macros) :", ch)