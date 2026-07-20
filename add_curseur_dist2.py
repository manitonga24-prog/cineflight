# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "rail_sujet_dist_seek" in s:
    print("DEJA present"); raise SystemExit

# ancre courte : la 1ere occurrence de "card.addView(box)\n        return card\n    }" suivie de carteSujetASuivre
import re
anc = "        card.addView(box)\n        return card\n    }\n    private fun carteSujetASuivre("

bloc = '''        // --- Distance du rail "vers le sujet" (appui long sur Rail A) ---
        val lblDist = TextView(this).apply {
            text = "Distance rail vers sujet : ${reglages.getRailSujetDist()} m"
            textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4))
        }
        box.addView(lblDist)
        val seekDist = SeekBar(this).apply {
            tag = "rail_sujet_dist_seek"
            max = 27
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
        box.addView(TextView(this).apply { text = "Appui long sur Rail A : le drone glisse de cette distance vers la personne filmee."; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0) })
        card.addView(box)
        return card
    }
    private fun carteSujetASuivre('''

if anc in s:
    s = s.replace(anc, bloc, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("curseur distance ajoute OK")
else:
    print("ANCRE COURTE NON TROUVEE")