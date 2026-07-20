# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "distanceVersB" in s:
    print("DEJA present (affichage)"); raise SystemExit

vieux = '''                                    txtSujet.text = if (trouve) "\\u25CF RAIL + SUIVI" else "\\u25CF RAIL A\\u2192B"'''

neuf = '''                                    val distB = cableCam.distanceVersB(e.latitude, e.longitude)
                                    val distTxt = if (distB >= 0) " \\u00B7 ${distB.toInt()} m" else ""
                                    txtSujet.text = (if (trouve) "\\u25CF RAIL + SUIVI" else "\\u25CF RAIL A\\u2192B") + distTxt'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("affichage distance OK")
else:
    print("ANCRE NON TROUVEE")