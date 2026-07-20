# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()

# 1) champs : remplacer le bloc avec doublons par 2 champs propres
old = """    // refs pour affichage conditionnel des reglages
    private var blocDuree: View? = null
    private var blocPosition: View? = null
    private var descMode: TextView? = null
    private var champNom: EditText? = null
    private var descMode: TextView? = null
    private var champNom: EditText? = null
"""
new = """    private var descMode: TextView? = null
    private var champNom: EditText? = null
"""
if old in s:
    s = s.replace(old, new, 1)
    print("champs nettoyes OK")
else:
    print("ANCRE champs NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)