# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()

# fond bien plus transparent (0x99 au lieu de 0xCC)
s = s.replace("setBackgroundColor(0xCC0A0E12.toInt())", "setBackgroundColor(0x990A0E12.toInt())", 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("panneau plus transparent OK")