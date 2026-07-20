# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
data = open(f, encoding="utf-8").read()
if "rail_sujet_dist_seek" in data:
    print("DEJA present"); raise SystemExit

lignes = data.split("\n")
# trouver la 1ere ligne "card.addView(box)" (fin de carteCamera)
idx = None
for i, l in enumerate(lignes):
    if l.strip() == "card.addView(box)":
        idx = i
        break
if idx is None:
    print("card.addView(box) NON TROUVE"); raise SystemExit

print(f"insertion avant ligne {idx+1}: '{lignes[idx]}'")

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
        box.addView(TextView(this).apply { text = "Appui long sur Rail A : le drone glisse de cette distance vers la personne filmee."; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0) })'''

# inserer le bloc juste avant la ligne idx (le card.addView(box) de carteCamera)
lignes.insert(idx, bloc)
open(f, "w", encoding="utf-8", newline="\n").write("\n".join(lignes))
print("curseur distance insere par ligne OK")