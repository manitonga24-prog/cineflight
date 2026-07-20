# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "reglages.getRailSujetDist()" in s:
    print("DEJA present"); raise SystemExit

vieux = '''                val distM = 8.0'''
neuf = '''                val distM = reglages.getRailSujetDist().toDouble()'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    # mettre a jour le toast pour afficher la distance reelle
    s = s.replace(
        'android.widget.Toast.makeText(this, "Rail vers sujet pret (8 m). Touchez Go.", android.widget.Toast.LENGTH_LONG).show()',
        'android.widget.Toast.makeText(this, "Rail vers sujet pret (${distM.toInt()} m). Touchez Go.", android.widget.Toast.LENGTH_LONG).show()',
        1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("distance reglable branchee OK")
else:
    print("ANCRE NON TROUVEE")