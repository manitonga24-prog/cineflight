# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "rail_sujet_dist_seek" in s:
    print("DEJA present"); raise SystemExit

anc = '''        box.addView(TextView(this).apply { text = "24 = rendu cinema, 30 = standard, 60 = tres fluide / ralenti."; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(8), 0, 0) })
        card.addView(box)
        return card
    }
    private fun carteSujetASuivre(): androidx.cardview.widget.CardView {'''

neuf = '''        box.addView(TextView(this).apply { text = "24 = rendu cinema, 30 = standard, 60 = tres fluide / ralenti."; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(8), 0, 0) })
        // --- Distance du rail "vers le sujet" (appui long sur Rail A) ---
        val lblDist = TextView(this).apply {
            text = "Distance rail vers sujet : ${reglages.getRailSujetDist()} m"
            textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4))
        }
        box.addView(lblDist)
        val seekDist = SeekBar(this).apply {
            tag = "rail_sujet_dist_seek"
            max = 27   // 3..30 m -> progress 0..27
            progress = reglages.getRailSujetDist() - 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val d = prog + 3
                    lblDist.text = "Distance rail vers sujet : $d m"
                    if (fromUser) reglages.setRailSujetDist(d)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seekDist)
        box.addView(TextView(this).apply { text = "Quand vous faites un appui long sur Rail A, le drone glisse de cette distance vers la personne filmee."; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0) })
        card.addView(box)
        return card
    }
    private fun carteSujetASuivre(): androidx.cardview.widget.CardView {'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("curseur distance rail sujet ajoute OK")
else:
    print("ANCRE NON TROUVEE")