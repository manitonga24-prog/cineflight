# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "addMapListener" in s:
    print("DEJA present"); raise SystemExit

# 1) imports MapListener
s = s.replace(
    "import org.osmdroid.events.MapEventsReceiver",
    "import org.osmdroid.events.MapEventsReceiver\n" +
    "import org.osmdroid.events.MapListener\n" +
    "import org.osmdroid.events.ScrollEvent\n" +
    "import org.osmdroid.events.ZoomEvent",
    1)

# 2) ajouter le listener juste apres le setOnTouchListener
anc = "        carte.setOnTouchListener { _, _ -> suiviAuto = false; false }"
neuf = '''        carte.setOnTouchListener { _, _ -> suiviAuto = false; false }
        // quand le panneau hors ligne est ouvert, le cercle suit le centre de la carte
        carte.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                if (panneauHL.visibility == View.VISIBLE) majCercleEtEstimation()
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                if (panneauHL.visibility == View.VISIBLE) majCercleEtEstimation()
                return false
            }
        })'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("MapListener (cercle suit la carte) OK")
else:
    print("ANCRE NON TROUVEE")