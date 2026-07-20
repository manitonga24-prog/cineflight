# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "derniereLatCarte" in s:
    print("DEJA present"); raise SystemExit
anc = "        const val SIGNAL_FAIBLE = 30"
add = anc + '''
        // position du drone partagee avec l'ecran carte (mise a jour dans majCockpit)
        @JvmStatic var derniereLatCarte = Double.NaN
        @JvmStatic var derniereLonCarte = Double.NaN
        @JvmStatic var dernierCapCarte = Float.NaN
        @JvmStatic var derniereGpsOkCarte = false
        @JvmStatic var ancreLatCarte = Double.NaN
        @JvmStatic var ancreLonCarte = Double.NaN'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Variables position carte ajoutees :", "derniereLatCarte" in s)
else:
    print("ANCRE NON TROUVEE")