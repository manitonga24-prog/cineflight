# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) appliquer les classes juste apres le demarrage du suivi
a1 = "                        yoloSuivi?.demarrer()"
if a1 in s and "setClassesSuivies" not in s:
    n1 = a1 + "\n                        yoloSuivi?.setClassesSuivies(reglages.classesPourSujet())"
    s = s.replace(a1, n1, 1); ch+=1

# 2) dans onResume : reappliquer (si le reglage a change)
a2 = "        try { rafraichirBoutonsMacros() } catch (_: Exception) {}"
if a2 in s and "classesPourSujet" not in s.split("onResume")[1] if "onResume" in s else False:
    pass
# insertion robuste : ajouter apres la ligne rafraichirBoutonsMacros dans onResume
if a2 in s and s.count("setClassesSuivies") < 2:
    n2 = a2 + "\n        try { yoloSuivi?.setClassesSuivies(reglages.classesPourSujet()) } catch (_: Exception) {}"
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 3a (appliquer classes) :", ch, "/ 2")