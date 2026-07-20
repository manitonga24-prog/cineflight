# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "modeRail" in s:
    print("DEJA present"); raise SystemExit

# 1) imports
s = s.replace(
    "import org.osmdroid.views.overlay.Marker",
    "import org.osmdroid.views.overlay.Marker\n" +
    "import org.osmdroid.views.overlay.MapEventsOverlay\n" +
    "import org.osmdroid.events.MapEventsReceiver\n" +
    "import org.osmdroid.views.overlay.Polyline",
    1)

# 2) champs d'etat du mode rail (apres marqueurDecollage)
s = s.replace(
    "    private var marqueurDecollage: Marker? = null",
    "    private var marqueurDecollage: Marker? = null\n" +
    "    private var modeRail = false\n" +
    "    private var railA: GeoPoint? = null\n" +
    "    private var railB: GeoPoint? = null\n" +
    "    private var marqueurA: Marker? = null\n" +
    "    private var marqueurB: Marker? = null\n" +
    "    private var ligneRail: Polyline? = null\n" +
    "    private lateinit var infoTexte2: TextView\n" +
    "    private lateinit var btnRailDef: Button\n" +
    "    private lateinit var btnRailUtiliser: Button",
    1)

# 3) ajouter les boutons rail dans la barre + overlay de tap, juste avant setContentView(racine)
anc = '''        carte.setOnTouchListener { _, _ -> suiviAuto = false; false }
        setContentView(racine)'''

neuf = '''        carte.setOnTouchListener { _, _ -> if (!modeRail) suiviAuto = false; false }

        // === MODE RAIL : poser A et B en touchant la carte ===
        btnRailDef = Button(this).apply {
            text = "Definir rail"; isAllCaps = false
            setOnClickListener { activerModeRail() }
        }
        btnRailUtiliser = Button(this).apply {
            text = "Utiliser ce rail"; isAllCaps = false
            visibility = android.view.View.GONE
            setBackgroundColor(0xFF00C853.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { validerRail() }
        }
        barre.addView(btnRailDef, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        barre.addView(btnRailUtiliser, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))

        // overlay qui capte les taps sur la carte (coordonnees GPS)
        val recepteur = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (modeRail && p != null) { poserPointRail(p); return true }
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        carte.overlays.add(0, MapEventsOverlay(recepteur))

        setContentView(racine)'''

s = s.replace(anc, neuf, 1)

# 4) ajouter les methodes du mode rail avant placerDecollage
ancM = "    private fun placerDecollage() {"
methodes = '''    private fun activerModeRail() {
        modeRail = true
        suiviAuto = false
        railA = null; railB = null
        marqueurA?.let { carte.overlays.remove(it) }; marqueurA = null
        marqueurB?.let { carte.overlays.remove(it) }; marqueurB = null
        ligneRail?.let { carte.overlays.remove(it) }; ligneRail = null
        btnRailUtiliser.visibility = android.view.View.GONE
        infoTexte.text = "Mode rail : touchez le point A (depart)"
        carte.invalidate()
    }

    private fun poserPointRail(p: GeoPoint) {
        if (railA == null) {
            railA = p
            marqueurA = Marker(carte).apply {
                position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Rail A"
            }
            carte.overlays.add(marqueurA)
            infoTexte.text = "Point A pose. Touchez le point B (arrivee)"
        } else if (railB == null) {
            railB = p
            marqueurB = Marker(carte).apply {
                position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Rail B"
            }
            carte.overlays.add(marqueurB)
            ligneRail = Polyline().apply {
                setPoints(listOf(railA, railB)); outlinePaint.color = 0xFF1565C0.toInt(); outlinePaint.strokeWidth = 8f
            }
            carte.overlays.add(ligneRail)
            btnRailUtiliser.visibility = android.view.View.VISIBLE
            infoTexte.text = "Rail trace. Touchez \\"Utiliser ce rail\\" ou retouchez pour recommencer."
        } else {
            // 3e tap : recommencer
            activerModeRail()
            poserPointRail(p)
            return
        }
        carte.invalidate()
    }

    private fun validerRail() {
        val a = railA; val b = railB
        if (a == null || b == null) {
            infoTexte.text = "Posez A et B avant d'utiliser le rail."; return
        }
        MainActivity.railACarteLat = a.latitude; MainActivity.railACarteLon = a.longitude
        MainActivity.railBCarteLat = b.latitude; MainActivity.railBCarteLon = b.longitude
        MainActivity.railCarteDefini = true
        android.widget.Toast.makeText(this, "Rail defini. Retournez au vol pour le lancer.", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    private fun placerDecollage() {'''

s = s.replace(ancM, methodes, 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("pose A/B sur carte ajoutee OK")