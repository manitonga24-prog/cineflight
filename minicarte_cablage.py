# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "miniCarteVue" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) proprietes (avant le companion object ou pres de pont) : apres "private lateinit var pont: PontCockpit"
a1 = "    private lateinit var pont: PontCockpit"
if a1 in s:
    s = s.replace(a1, a1 + '''
    private var miniCarteVue: org.osmdroid.views.MapView? = null
    private var marqueurMini: org.osmdroid.views.overlay.Marker? = null
    private var miniVisible = false''', 1); ch+=1

# 2) init osmdroid apres setContentView
a2 = "        setContentView(R.layout.activity_main)"
if a2 in s:
    s = s.replace(a2, a2 + '''
        // mini-carte (osmdroid) : init, cachee au depart
        try {
            org.osmdroid.config.Configuration.getInstance().load(this,
                android.preference.PreferenceManager.getDefaultSharedPreferences(this))
            org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
            miniCarteVue = findViewById(R.id.miniCarte)
            miniCarteVue?.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            miniCarteVue?.setMultiTouchControls(true)
            miniCarteVue?.controller?.setZoom(16.0)
            val mk = org.osmdroid.views.overlay.Marker(miniCarteVue)
            mk.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
            miniCarteVue?.overlays?.add(mk)
            marqueurMini = mk
        } catch (_: Exception) {}''', 1); ch+=1

# 3) bouton carte : basculer la mini-carte au lieu d'ouvrir le plein ecran
a3 = '''        findViewById<Button>(R.id.btnCarte).setOnClickListener {
            // transmettre l'ancre de decollage si connue (chien fidele)
            try { MainActivity.ancreLatCarte = ancreLat; MainActivity.ancreLonCarte = ancreLon } catch (_: Exception) {}
            startActivity(android.content.Intent(this, CarteActivity::class.java))
        }'''
n3 = '''        findViewById<Button>(R.id.btnCarte).setOnClickListener { basculerMiniCarte() }
        findViewById<Button>(R.id.btnAgrandirCarte).setOnClickListener {
            try { MainActivity.ancreLatCarte = ancreLat; MainActivity.ancreLonCarte = ancreLon } catch (_: Exception) {}
            startActivity(android.content.Intent(this, CarteActivity::class.java))
        }'''
if a3 in s:
    s = s.replace(a3, n3, 1); ch+=1

# 4) fonctions basculerMiniCarte + majMiniCarte, avant rafraichirBoutonsMacros
a4 = "    private fun rafraichirBoutonsMacros() {"
fct = '''    private fun basculerMiniCarte() {
        miniVisible = !miniVisible
        val boite = findViewById<android.view.View>(R.id.boiteMiniCarte)
        boite.visibility = if (miniVisible) android.view.View.VISIBLE else android.view.View.GONE
        if (miniVisible) { miniCarteVue?.onResume(); majMiniCarte() } else { miniCarteVue?.onPause() }
    }
    private fun majMiniCarte() {
        if (!miniVisible) return
        val lat = derniereLatCarte; val lon = derniereLonCarte
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return
        val p = org.osmdroid.util.GeoPoint(lat, lon)
        marqueurMini?.position = p
        if (!dernierCapCarte.isNaN()) marqueurMini?.rotation = -dernierCapCarte
        miniCarteVue?.controller?.animateTo(p)
        miniCarteVue?.invalidate()
    }

    private fun rafraichirBoutonsMacros() {'''
if a4 in s:
    s = s.replace(a4, fct, 1); ch+=1

# 5) mettre a jour la mini-carte dans majCockpit (apres la maj des variables position)
a5 = '''        derniereGpsOkCarte = e.gpsValide
        // batterie'''
if a5 in s:
    s = s.replace(a5, '''        derniereGpsOkCarte = e.gpsValide
        try { majMiniCarte() } catch (_: Exception) {}
        // batterie''', 1); ch+=1

# 6) onResume : reprendre la mini-carte si visible
a6 = "        try { majValeursMouvement() } catch (_: Exception) {}\n    }"
if a6 in s:
    s = s.replace(a6, "        try { majValeursMouvement() } catch (_: Exception) {}\n        try { if (miniVisible) miniCarteVue?.onResume() } catch (_: Exception) {}\n    }", 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Cablage mini-carte :", ch, "/ 6")