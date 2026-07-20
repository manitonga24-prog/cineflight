# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "private val hyperlapse" in s:
    print("DEJA present"); raise SystemExit

anc = "private val cableCam = ca.cineflight.stage.control.CableCam()   // rail virtuel A->B"
ajout = anc + "\n    private val hyperlapse = ca.cineflight.stage.control.Hyperlapse()  // capture photo periodique pendant le rail"

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("champ hyperlapse OK")
else:
    print("ANCRE NON TROUVEE")