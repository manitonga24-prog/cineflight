# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s = open(f, encoding="utf-8").read()
if "PhototequeActivity::class.java" in s:
    print("DEJA present"); raise SystemExit

old = '''            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, ReglagesActivity::class.java)) }
        })
        col.addView(barre)'''
new = '''            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, ReglagesActivity::class.java)) }
        })
        barre.addView(Button(this).apply {
            text = "Album"; setTextColor(ACCENT); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.leftMargin = dp(8); layoutParams = lp
            setOnClickListener { startActivity(android.content.Intent(this@TagsActivity, PhototequeActivity::class.java)) }
        })
        col.addView(barre)'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("bouton Album ajoute OK")
else:
    print("ANCRE NON TROUVEE")