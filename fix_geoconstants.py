# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
vieux = "import org.osmdroid.util.GeoConstants\n"
if vieux in s:
    s = s.replace(vieux, "", 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("import GeoConstants retire OK")
else:
    print("DEJA retire ou introuvable")