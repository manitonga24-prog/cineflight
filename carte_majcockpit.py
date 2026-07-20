# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "derniereLatCarte = e.latitude" in s:
    print("DEJA present"); raise SystemExit
anc = '''    private fun majCockpit(e: EtatCockpit) {
        // batterie'''
add = '''    private fun majCockpit(e: EtatCockpit) {
        // position partagee avec l'ecran carte
        derniereLatCarte = e.latitude
        derniereLonCarte = e.longitude
        dernierCapCarte = e.capDeg
        derniereGpsOkCarte = e.gpsValide
        // batterie'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("majCockpit met a jour la position :", "derniereLatCarte = e.latitude" in s)
else:
    print("ANCRE NON TROUVEE")