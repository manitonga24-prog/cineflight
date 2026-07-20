# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

old = 'val v = cableCam.calculer(e.latitude, e.longitude, e.altAgl.toDouble(), e.capDeg)'
new = 'val v = cableCam.calculer(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)'
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("appel calculer corrige (altitudeAgl) OK")
elif new in s:
    print("DEJA corrige")
else:
    print("ANCRE NON TROUVEE")