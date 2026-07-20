# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "railACarteLat" in s:
    print("DEJA present"); raise SystemExit

anc = "        @JvmStatic var ancreLonCarte = Double.NaN"
ajout = anc + '''
        // Rail Cable-Cam defini sur la carte : A et B (lat/lon). railCarteDefini = true quand pose validee.
        @JvmStatic var railACarteLat = Double.NaN
        @JvmStatic var railACarteLon = Double.NaN
        @JvmStatic var railBCarteLat = Double.NaN
        @JvmStatic var railBCarteLon = Double.NaN
        @JvmStatic var railCarteDefini = false'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("champs statiques rail carte ajoutes OK")
else:
    print("ANCRE NON TROUVEE")