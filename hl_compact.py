# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()

# 1) fond moins opaque + padding reduit
s = s.replace(
    '''        panneauHL = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE101418.toInt())
            setPadding(dpx(20), dpx(20), dpx(20), dpx(20))
            visibility = View.GONE
        }''',
    '''        panneauHL = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC0A0E12.toInt())
            setPadding(dpx(12), dpx(8), dpx(12), dpx(8))
            visibility = View.GONE
        }''', 1)

# 2) retirer le gros titre (garder juste l'essentiel) et raccourcir le sous-texte
s = s.replace(
    '''        panneauHL.addView(TextView(this).apply {
            text = "Telecharger la carte de cette zone"
            setTextColor(Color.WHITE); textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        panneauHL.addView(TextView(this).apply {
            text = "Glissez la carte pour centrer la zone, puis choisissez le rayon."
            setTextColor(0xFFB0BEC5.toInt()); textSize = 12f
            setPadding(0, dpx(4), 0, dpx(12))
        })''',
    '''        panneauHL.addView(TextView(this).apply {
            text = "Glissez la carte pour centrer - choisissez le rayon"
            setTextColor(0xFFB0BEC5.toInt()); textSize = 11f
            setPadding(0, 0, 0, dpx(4))
        })''', 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("panneau compact + semi-transparent OK")