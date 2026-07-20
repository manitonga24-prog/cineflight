# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
croix9 = "\u25C9"  # ◉
carre = "\u25A3"   # ▣
old = '            else -> "' + carre + ' statique"'
new = '            5 -> "' + croix9 + ' spotlight  vous pilotez, la camera suit"\n            else -> "' + carre + ' statique"'
if croix9 + " spotlight" in s:
    print("DEJA present")
elif old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Texte valeurs spotlight ajoute : OK")
else:
    print("ANCRE NON TROUVEE")