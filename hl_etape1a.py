# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "cercleHorsLigne" in s:
    print("DEJA present"); raise SystemExit

# 1) imports
s = s.replace(
    "import org.osmdroid.views.overlay.Polyline",
    "import org.osmdroid.views.overlay.Polyline\n" +
    "import org.osmdroid.views.overlay.Polygon\n" +
    "import org.osmdroid.util.BoundingBox\n" +
    "import org.osmdroid.util.GeoConstants\n" +
    "import android.widget.EditText\n" +
    "import android.widget.SeekBar\n" +
    "import android.view.View",
    1)

# 2) champs hors ligne
s = s.replace(
    "    private lateinit var btnRailUtiliser: Button",
    "    private lateinit var btnRailUtiliser: Button\n" +
    "    private var cercleHorsLigne: Polygon? = null\n" +
    "    private var rayonHorsLigneM = 1000.0   // rayon en metres (defaut 1 km)\n" +
    "    private lateinit var panneauHL: LinearLayout\n" +
    "    private lateinit var lblEstimation: TextView\n" +
    "    private lateinit var champNomHL: EditText",
    1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("imports + champs hors ligne OK")