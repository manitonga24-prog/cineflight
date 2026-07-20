# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\PhototequeActivity.kt"
s = open(f, encoding="utf-8").read()
if "decouvrirFormat" in s:
    print("DEJA present"); raise SystemExit

anc = '''                if (!ok) { statut.text = "Impossible d'acceder a la camera. Drone connecte ?"; return@runOnUiThread }
                statut.text = "Lecture de la carte SD..."'''

neuf = '''                if (!ok) { statut.text = "Impossible d'acceder a la camera. Drone connecte ?"; return@runOnUiThread }
                media.decouvrirFormat()   // DECOUVERTE temporaire : log les cles de formatage
                statut.text = "Lecture de la carte SD..."'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("appel decouvrirFormat ajoute OK")
else:
    print("ANCRE NON TROUVEE")