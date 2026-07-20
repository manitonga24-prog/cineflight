# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\..\CarteActivity.kt".replace("control\\..\\","")
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "} else {\n            // pas de position drone" in s:
    print("DEJA present"); raise SystemExit

vieux = '''            carte.overlays.add(m)
            marqueurDecollage = m
        }
    }
    private fun centrerSurTelephone() {'''

neuf = '''            carte.overlays.add(m)
            marqueurDecollage = m
            carte.controller.setCenter(GeoPoint(lat, lon))
        } else {
            // pas de position drone : centrer sur le telephone (preparer une carte hors ligne)
            centrerSurTelephone()
        }
    }
    private fun centrerSurTelephone() {'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("appel centrerSurTelephone dans placerDecollage OK")
else:
    print("ANCRE NON TROUVEE")