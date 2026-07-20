# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "carteCamera" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) afficher la carte camera apres la carte sujet
a1 = "        col.addView(carteSujetASuivre())"
if a1 in s:
    s = s.replace(a1, a1 + "\n        col.addView(carteCamera())", 1); ch+=1

# 2) la fonction carteCamera, avant carteSujetASuivre
anc2 = "    private fun carteSujetASuivre(): androidx.cardview.widget.CardView {"
fct = '''    private fun carteCamera(): androidx.cardview.widget.CardView {
        val ACCENT = 0xFF007AFF.toInt()
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = "Camera (a regler avant le vol)"; textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = "Resolution et fluidite de la video. A choisir avant de decoller."
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        // Resolution
        box.addView(TextView(this).apply { text = "Resolution"; textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, dp(4)) })
        val resLbl = listOf("FHD 1080p", "2.7K", "4K")
        val resBtns = ArrayList<Button>()
        fun majRes() { for ((i,b) in resBtns.withIndex()) { if (i==reglages.getResolution()){b.setBackgroundColor(ACCENT);b.setTextColor(0xFFFFFFFF.toInt())} else {b.setBackgroundColor(0xFFE5E5EA.toInt());b.setTextColor(TEXTE)} } }
        val ligneRes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i,lbl) in resLbl.withIndex()) {
            val b = Button(this).apply { text = lbl; textSize = 12f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2),0,dp(2),0) }
                setOnClickListener { reglages.setResolution(i); majRes() } }
            resBtns.add(b); ligneRes.addView(b)
        }
        majRes(); box.addView(ligneRes)
        // FPS
        box.addView(TextView(this).apply { text = "Images par seconde (fps)"; textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(4)) })
        val fpsVals = listOf(24, 30, 60)
        val fpsBtns = ArrayList<Button>()
        fun majFps() { for ((i,b) in fpsBtns.withIndex()) { if (fpsVals[i]==reglages.getFps()){b.setBackgroundColor(ACCENT);b.setTextColor(0xFFFFFFFF.toInt())} else {b.setBackgroundColor(0xFFE5E5EA.toInt());b.setTextColor(TEXTE)} } }
        val ligneFps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i,v) in fpsVals.withIndex()) {
            val b = Button(this).apply { text = "$v"; textSize = 12f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2),0,dp(2),0) }
                setOnClickListener { reglages.setFps(v); majFps() } }
            fpsBtns.add(b); ligneFps.addView(b)
        }
        majFps(); box.addView(ligneFps)
        box.addView(TextView(this).apply { text = "24 = rendu cinema, 30 = standard, 60 = tres fluide / ralenti."; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(8), 0, 0) })
        card.addView(box)
        return card
    }

    private fun carteSujetASuivre(): androidx.cardview.widget.CardView {'''
if anc2 in s:
    s = s.replace(anc2, fct, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Carte camera :", ch, "/ 2")