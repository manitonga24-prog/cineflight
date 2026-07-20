# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

anc = '    private var mouvementActuel = 0   // 0=statik 1=orbite 2=travel 3=revel'
add = anc + '\n    private val cableCam = ca.cineflight.stage.control.CableCam()   // rail virtuel A->B'
if 'val cableCam' in s:
    print("DEJA present")
elif anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("champ cableCam ajoute OK")
else:
    print("ANCRE NON TROUVEE")