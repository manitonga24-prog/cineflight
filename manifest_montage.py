# -*- coding: utf-8 -*-
ch = 0

# 1) Manifest : declarer TestMontageActivity
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(fm, encoding="utf-8").read()
if "TestMontageActivity" not in s:
    anc = '        <activity android:name=".PhototequeActivity" android:exported="false" android:screenOrientation="landscape" />'
    add = anc + '\n        <activity android:name=".TestMontageActivity" android:exported="false" android:screenOrientation="landscape" />'
    if anc in s:
        s = s.replace(anc, add, 1)
        open(fm, "w", encoding="utf-8", newline="\n").write(s)
        ch += 1
        print("Manifest : TestMontageActivity OK")
    else:
        print("ANCRE Manifest NON TROUVEE")
else:
    print("Manifest deja fait")

# 2) TagsActivity : bouton temporaire "Montage"
ft = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s2 = open(ft, encoding="utf-8").read()
if "TestMontageActivity::class.java" not in s2:
    anc2 = "            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, PhototequeActivity::class.java)) }\n        })\n        col.addView(barre)"
    add2 = """            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, PhototequeActivity::class.java)) }
        })
        barre.addView(Button(this).apply {
            text = "Montage"; setTextColor(ACCENT); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.leftMargin = dp(8); layoutParams = lp
            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, TestMontageActivity::class.java)) }
        })
        col.addView(barre)"""
    if anc2 in s2:
        s2 = s2.replace(anc2, add2, 1)
        open(ft, "w", encoding="utf-8", newline="\n").write(s2)
        ch += 1
        print("TagsActivity : bouton Montage OK")
    else:
        print("ANCRE TagsActivity NON TROUVEE")
else:
    print("TagsActivity deja fait")

print("Total :", ch, "/ 2")