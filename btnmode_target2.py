# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# caracteres reels presents dans le fichier
croix = "\u2715"   # ✕
triangle = "\u25B6" # ▶
plein = "\u25CF"   # ●
vide = "\u25CB"    # ○

a1 = 'btnMode.text = "' + croix + ' STOP SUIVI"'
n1 = 'btnMode.text = "' + plein + ' TARGET"'
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

a2 = 'btnMode.text = "' + triangle + ' SUIVRE"'
n2 = 'btnMode.text = "' + vide + ' MANUEL"'
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Libelles TARGET/MANUEL :", ch, "/ 2")