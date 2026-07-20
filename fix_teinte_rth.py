# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

vieux = '''                                findViewById<Button>(R.id.btnRailGo)?.let { teinte(it, 0xFF263238.toInt()); it.text = "Go" }'''

neuf = '''                                findViewById<Button>(R.id.btnRailGo)?.let {
                                    it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
                                    it.text = "Go"
                                }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("fix teinte RTH OK")
else:
    print("ANCRE NON TROUVEE")