# -*- coding: utf-8 -*-
ch = 0

# 1) Manifest
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(fm, encoding="utf-8").read()
if '".MesMontagesActivity"' in s:
    print("Manifest deja fait")
else:
    anc = '        <activity android:name=".MontageActivity" android:exported="false" android:screenOrientation="landscape" />'
    add = anc + '\n        <activity android:name=".MesMontagesActivity" android:exported="false" android:screenOrientation="landscape" />'
    if anc in s:
        s = s.replace(anc, add, 1)
        open(fm, "w", encoding="utf-8", newline="\n").write(s)
        ch += 1
        print("Manifest : MesMontagesActivity OK")
    else:
        print("ANCRE Manifest NON TROUVEE")

# 2) TagsActivity : bouton "Mes montages" apres le bouton Montage
ft = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s2 = open(ft, encoding="utf-8").read()
if "MesMontagesActivity::class.java" in s2:
    print("TagsActivity deja fait")
else:
    anc2 = "            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, MontageActivity::class.java)) }\n        })\n        col.addView(barre)"
    add2 = """            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, MontageActivity::class.java)) }
        })
        barre.addView(Button(this).apply {
            text = "Mes montages"; setTextColor(ACCENT); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.leftMargin = dp(8); layoutParams = lp
            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, MesMontagesActivity::class.java)) }
        })
        col.addView(barre)"""
    if anc2 in s2:
        s2 = s2.replace(anc2, add2, 1)
        open(ft, "w", encoding="utf-8", newline="\n").write(s2)
        ch += 1
        print("TagsActivity : bouton Mes montages OK")
    else:
        print("ANCRE TagsActivity NON TROUVEE")

print("Total :", ch)