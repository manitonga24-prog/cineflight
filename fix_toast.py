# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

# remplacer toast("...") par Toast.makeText dans le bloc Cable-Cam
remplacements = [
    ('toast("GPS non fiable : impossible de memoriser le point A")',
     'android.widget.Toast.makeText(this, "GPS non fiable : impossible de memoriser le point A", android.widget.Toast.LENGTH_SHORT).show()'),
    ('toast("Point A memorise. Pilotez jusqu\\'au point B.")',
     'android.widget.Toast.makeText(this, "Point A memorise. Pilotez jusqu\\'au point B.", android.widget.Toast.LENGTH_SHORT).show()'),
    ('toast("GPS non fiable : impossible de memoriser le point B")',
     'android.widget.Toast.makeText(this, "GPS non fiable : impossible de memoriser le point B", android.widget.Toast.LENGTH_SHORT).show()'),
    ('toast("Point B memorise. Appuyez sur Go pour glisser A vers B.")',
     'android.widget.Toast.makeText(this, "Point B memorise. Appuyez sur Go pour glisser A vers B.", android.widget.Toast.LENGTH_SHORT).show()'),
    ('toast("Rail arrete.")',
     'android.widget.Toast.makeText(this, "Rail arrete.", android.widget.Toast.LENGTH_SHORT).show()'),
    ('toast("Memorisez d\\'abord Rail A et Rail B.")',
     'android.widget.Toast.makeText(this, "Memorisez d\\'abord Rail A et Rail B.", android.widget.Toast.LENGTH_SHORT).show()'),
    ('toast("Rail lance : le drone glisse vers B.")',
     'android.widget.Toast.makeText(this, "Rail lance : le drone glisse vers B.", android.widget.Toast.LENGTH_SHORT).show()'),
]
n = 0
for vieux, neuf in remplacements:
    if vieux in s:
        s = s.replace(vieux, neuf, 1); n += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("toasts remplaces :", n, "/ 7")
print("reste de toast( :", s.count("toast("))