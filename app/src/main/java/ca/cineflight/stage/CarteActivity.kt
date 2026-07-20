package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.util.BoundingBox
import org.osmdroid.tileprovider.cachemanager.CacheManager
import android.widget.EditText
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.widget.SeekBar
import android.view.View

class CarteActivity : AppCompatActivity() {

    private lateinit var carte: MapView
    private lateinit var marqueurDrone: Marker
    private var marqueurDecollage: Marker? = null
    private var modeRail = false
    private var railA: GeoPoint? = null
    private var railB: GeoPoint? = null
    private var marqueurA: Marker? = null
    private var marqueurB: Marker? = null
    private var ligneRail: Polyline? = null
    private lateinit var btnRailUtiliser: Button
    private var cercleHorsLigne: Polygon? = null
    private var rayonHorsLigneM = 1000.0   // rayon en metres (defaut 1 km)
    private var vueSatellite = false
    private lateinit var panneauHL: LinearLayout
    private lateinit var lblEstimation: TextView
    private lateinit var champNomHL: EditText
    private lateinit var infoTexte: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var suiviAuto = true

    private val rafraichir = object : Runnable {
        override fun run() {
            majPosition()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        val racine = FrameLayout(this)

        carte = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
        }
        racine.addView(carte, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        marqueurDrone = Marker(carte).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Drone"
        }
        carte.overlays.add(marqueurDrone)

        infoTexte = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 16, 24, 16)
            text = getString(R.string.ca_gps_recherche)
        }
        racine.addView(infoTexte, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // === RECHERCHE D'ADRESSE (geocodage) ===
        val barreRech = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dpx(8), dpx(6), dpx(8), dpx(6))
        }
        val champAdresse = EditText(this).apply {
            hint = getString(R.string.ca_chercher_hint)
            setTextColor(Color.WHITE); setHintTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
        }
        val btnChercher = Button(this).apply {
            text = getString(R.string.ca_chercher); isAllCaps = false
            setOnClickListener { chercherAdresse(champAdresse.text.toString().trim()) }
        }
        barreRech.addView(champAdresse, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        barreRech.addView(btnChercher, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rechParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        rechParams.topMargin = dpx(52)   // sous infoTexte
        racine.addView(barreRech, rechParams)

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        val btnCentrer = Button(this).apply {
            text = getString(R.string.map_centrer_drone)
            isAllCaps = false
            setOnClickListener { suiviAuto = true; centrerSurDrone() }
        }
        val btnFermer = Button(this).apply {
            text = getString(R.string.map_fermer)
            isAllCaps = false
            setOnClickListener { finish() }
        }
        barre.addView(btnCentrer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        barre.addView(btnFermer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val barreParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        barreParams.gravity = android.view.Gravity.BOTTOM
        racine.addView(barre, barreParams)

        carte.setOnTouchListener { _, _ -> suiviAuto = false; false }
        // tap carte : en mode rail, pose A puis B
        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (modeRail && p != null) { poserPointRail(p); return true }
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
        carte.overlays.add(eventsOverlay)
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
        })

        // === CARTE HORS LIGNE : bouton dans la barre ===
        val btnHorsLigne = Button(this).apply {
            text = getString(R.string.ca_hors_ligne); isAllCaps = false
            setOnClickListener { ouvrirPanneauHorsLigne() }
        }
        barre.addView(btnHorsLigne, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        val btnMesCartes = Button(this).apply {
            text = getString(R.string.ca_mes_cartes); isAllCaps = false
            setOnClickListener { ouvrirMesCartes() }
        }
        barre.addView(btnMesCartes, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        val btnRailDef = Button(this).apply {
            text = getString(R.string.ca_definir_rail); isAllCaps = false
            setOnClickListener { activerModeRail() }
        }
        barre.addView(btnRailDef, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        btnRailUtiliser = Button(this).apply {
            text = getString(R.string.ca_utiliser_rail); isAllCaps = false
            setBackgroundColor(0xFF1565C0.toInt()); setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { validerRail() }
        }
        barre.addView(btnRailUtiliser, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        // === ICONE FLOTTANT carte routiere / satellite (haut-droite) ===
        val btnVue = Button(this).apply {
            text = "\uD83D\uDEF0"   // satellite (vue actuelle = routier au demarrage)
            isAllCaps = false
            textSize = 18f
            setPadding(0, 0, 0, 0)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                vueSatellite = !vueSatellite
                if (vueSatellite) {
                    carte.setTileSource(sourceEsriSatellite())
                    text = "\uD83D\uDDFA"   // carte routiere
                } else {
                    carte.setTileSource(TileSourceFactory.MAPNIK)
                    text = "\uD83D\uDEF0"   // satellite
                }
                carte.invalidate()
            }
        }
        val vueParams = FrameLayout.LayoutParams(dpx(44), dpx(44))
        vueParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        vueParams.topMargin = dpx(110)   // sous la barre de recherche
        vueParams.rightMargin = dpx(8)
        racine.addView(btnVue, vueParams)

        // === PANNEAU HORS LIGNE (cache par defaut) ===
        panneauHL = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x990A0E12.toInt())
            setPadding(dpx(12), dpx(8), dpx(12), dpx(8))
            visibility = View.GONE
        }
        panneauHL.addView(TextView(this).apply {
            text = getString(R.string.ca_glissez)
            setTextColor(0xFFB0BEC5.toInt()); textSize = 11f
            setPadding(0, 0, 0, dpx(4))
        })
        lblEstimation = TextView(this).apply {
            setTextColor(0xFF4FC3F7.toInt()); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panneauHL.addView(lblEstimation)
        val seekRayon = SeekBar(this).apply {
            max = 15
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    rayonHorsLigneM = (500 + prog * 100).toDouble()
                    majCercleEtEstimation()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panneauHL.addView(seekRayon)
        champNomHL = EditText(this).apply {
            hint = getString(R.string.ca_nom_zone)
            setTextColor(Color.WHITE); setHintTextColor(0xFF78909C.toInt())
            setPadding(dpx(8), dpx(8), dpx(8), dpx(8))
        }
        panneauHL.addView(champNomHL)
        val ligneBtnHL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpx(12), 0, 0) }
        val btnTelecharger = Button(this).apply {
            text = getString(R.string.ca_telecharger); isAllCaps = false
            setBackgroundColor(0xFF00C853.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { lancerTelechargementHorsLigne() }
        }
        val btnAnnulerHL = Button(this).apply {
            text = getString(R.string.ca_annuler); isAllCaps = false
            setOnClickListener { fermerPanneauHorsLigne() }
        }
        ligneBtnHL.addView(btnTelecharger, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        ligneBtnHL.addView(btnAnnulerHL, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panneauHL.addView(ligneBtnHL)
        val panneauParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        panneauParams.gravity = android.view.Gravity.BOTTOM
        racine.addView(panneauHL, panneauParams)

        // === EN VOL : on degage la prepa (recherche, hors-ligne, mes cartes, definir rail).
        // On garde Centrer / Fermer / Utiliser rail + l'icone satellite. ===
        if (intent.getBooleanExtra("enVol", false)) {
            barreRech.visibility = View.GONE
            btnHorsLigne.visibility = View.GONE
            btnMesCartes.visibility = View.GONE
            btnRailDef.visibility = View.GONE
        }

        setContentView(racine)
        placerDecollage()
    }

    private fun activerModeRail() {
        modeRail = true
        suiviAuto = false
        railA = null; railB = null
        marqueurA?.let { carte.overlays.remove(it) }; marqueurA = null
        marqueurB?.let { carte.overlays.remove(it) }; marqueurB = null
        ligneRail?.let { carte.overlays.remove(it) }; ligneRail = null
        btnRailUtiliser.visibility = android.view.View.GONE
        infoTexte.text = getString(R.string.ca_rail_a_touch)
        carte.invalidate()
    }

    private fun poserPointRail(p: GeoPoint) {
        if (railA == null) {
            railA = p
            marqueurA = Marker(carte).apply {
                position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = getString(R.string.ma_rail_a)
            }
            carte.overlays.add(marqueurA)
            infoTexte.text = getString(R.string.ca_rail_b_touch)
        } else if (railB == null) {
            railB = p
            marqueurB = Marker(carte).apply {
                position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = getString(R.string.ma_rail_b)
            }
            carte.overlays.add(marqueurB)
            ligneRail = Polyline().apply {
                setPoints(listOf(railA, railB)); outlinePaint.color = 0xFF1565C0.toInt(); outlinePaint.strokeWidth = 8f
            }
            carte.overlays.add(ligneRail)
            btnRailUtiliser.visibility = android.view.View.VISIBLE
            infoTexte.text = getString(R.string.ca_rail_trace)
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
            infoTexte.text = getString(R.string.ca_rail_incomplet); return
        }
        MainActivity.railACarteLat = a.latitude; MainActivity.railACarteLon = a.longitude
        MainActivity.railBCarteLat = b.latitude; MainActivity.railBCarteLon = b.longitude
        MainActivity.railCarteDefini = true
        android.widget.Toast.makeText(this, getString(R.string.ca_rail_defini), android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ouvrirPanneauHorsLigne() {
        suiviAuto = false
        panneauHL.visibility = View.VISIBLE
        majCercleEtEstimation()
    }

    private fun fermerPanneauHorsLigne() {
        panneauHL.visibility = View.GONE
        cercleHorsLigne?.let { carte.overlays.remove(it) }
        cercleHorsLigne = null
        carte.invalidate()
    }

    /** Redessine le cercle centre sur la vue + met a jour l'estimation (tuiles/Mo/temps). */
    private fun majCercleEtEstimation() {
        val centre = carte.mapCenter as GeoPoint
        // cercle
        cercleHorsLigne?.let { carte.overlays.remove(it) }
        val pts = Polygon.pointsAsCircle(centre, rayonHorsLigneM)
        cercleHorsLigne = Polygon().apply {
            points = pts
            fillPaint.color = 0x224FC3F7
            outlinePaint.color = 0xFF4FC3F7.toInt()
            outlinePaint.strokeWidth = 5f
        }
        carte.overlays.add(cercleHorsLigne)
        carte.invalidate()
        // estimation : nb tuiles pour la bounding box du cercle, niveaux 14..18
        val bb = boundingBoxDuCercle(centre, rayonHorsLigneM)
        var totalTuiles = 0L
        for (z in 14..18) totalTuiles += tuilesPourBox(bb, z)
        val mo = totalTuiles * 14.0 / 1024.0   // ~14 Ko par tuile
        val minutes = Math.ceil(totalTuiles / 15.0 / 60.0).toInt()   // ~15 tuiles/s
        val rayonKm = rayonHorsLigneM / 1000.0
        lblEstimation.text = String.format(getString(R.string.ca_rayon_info),
            rayonKm, Math.round(mo), Math.max(1, minutes), totalTuiles)
    }

    private fun boundingBoxDuCercle(centre: GeoPoint, rayonM: Double): BoundingBox {
        val dLat = Math.toDegrees(rayonM / 6371000.0)
        val dLon = Math.toDegrees(rayonM / (6371000.0 * Math.cos(Math.toRadians(centre.latitude))))
        return BoundingBox(centre.latitude + dLat, centre.longitude + dLon,
            centre.latitude - dLat, centre.longitude - dLon)
    }

    private fun tuilesPourBox(bb: BoundingBox, zoom: Int): Long {
        val n = Math.pow(2.0, zoom.toDouble())
        fun xTile(lon: Double) = ((lon + 180.0) / 360.0 * n).toLong()
        fun yTile(lat: Double): Long {
            val r = Math.toRadians(lat)
            return ((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toLong()
        }
        val x1 = xTile(bb.lonWest); val x2 = xTile(bb.lonEast)
        val y1 = yTile(bb.latNorth); val y2 = yTile(bb.latSouth)
        return (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1)
    }

    /** Esri World Imagery (satellite) - autorise le telechargement, couvre le Canada. Format Z/Y/X. */
    private fun sourceEsriSatellite(): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
        return object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "EsriWorldImagery", 0, 19, 256, "",
            arrayOf("https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/"),
            "(c) Esri, Maxar, Earthstar Geographics"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return baseUrl + z + "/" + y + "/" + x
            }
        }
    }

    private fun lancerTelechargementHorsLigne() {
        val nom = champNomHL.text.toString().trim()
        if (nom.isEmpty()) {
            android.widget.Toast.makeText(this, getString(R.string.ca_nom_dabord), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val centre = carte.mapCenter as GeoPoint
        val bb = boundingBoxDuCercle(centre, rayonHorsLigneM)
        val zMin = 14
        val zMax = 18

        val progress = android.app.ProgressDialog(this).apply {
            setTitle(getString(R.string.ca_dl_titre))
            setMessage(getString(R.string.ca_dl_prep))
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
        }
        progress.show()

        val sourceAffichage = carte.tileProvider.tileSource
        carte.setTileSource(sourceEsriSatellite())
        val cm = CacheManager(carte)
        cm.downloadAreaAsync(this, bb, zMin, zMax, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                progress.dismiss()
                carte.setTileSource(sourceAffichage)
                sauvegarderCarteNommee(nom, centre, rayonHorsLigneM)
                android.widget.Toast.makeText(this@CarteActivity, getString(R.string.ca_dl_ok), android.widget.Toast.LENGTH_LONG).show()
                fermerPanneauHorsLigne()
            }
            override fun onTaskFailed(errors: Int) {
                progress.dismiss()
                carte.setTileSource(sourceAffichage)
                android.widget.Toast.makeText(this@CarteActivity, getString(R.string.ca_dl_partiel, errors), android.widget.Toast.LENGTH_LONG).show()
                sauvegarderCarteNommee(nom, centre, rayonHorsLigneM)
                fermerPanneauHorsLigne()
            }
            override fun updateProgress(progressVal: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                progress.progress = progressVal
                progress.setMessage(getString(R.string.ca_dl_niveau, currentZoomLevel, progressVal))
            }
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(total: Int) {
                progress.setMessage(getString(R.string.ca_dl_total, total))
            }
        })
    }

    private fun chercherAdresse(requete: String) {
        if (requete.isEmpty()) { android.widget.Toast.makeText(this, getString(R.string.ca_tapez), android.widget.Toast.LENGTH_SHORT).show(); return }
        infoTexte.text = getString(R.string.ca_recherche, requete)
        Thread {
            try {
                val q = URLEncoder.encode(requete, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "CineFlightSolo/1.0 (drone planning)")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val rep = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                // parse minimal : "lat":"45.5","lon":"-73.5"
                val mLat = Regex("\"lat\"\\s*:\\s*\"([-0-9.]+)\"").find(rep)
                val mLon = Regex("\"lon\"\\s*:\\s*\"([-0-9.]+)\"").find(rep)
                val lat = mLat?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = mLon?.groupValues?.get(1)?.toDoubleOrNull()
                runOnUiThread {
                    if (lat != null && lon != null) {
                        suiviAuto = false
                        carte.controller.setCenter(GeoPoint(lat, lon))
                        carte.controller.setZoom(15.0)
                        infoTexte.text = "Lieu trouve : $requete"
                    } else {
                        infoTexte.text = "Lieu introuvable : $requete"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { infoTexte.text = "Recherche impossible (reseau ?)" }
            }
        }.start()
    }

    private fun ouvrirMesCartes() {
        val prefs = getSharedPreferences("cartes_hors_ligne", android.content.Context.MODE_PRIVATE)
        val liste = (prefs.getString("liste", "") ?: "").split("\n").filter { it.isNotBlank() }
        if (liste.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucune carte sauvegardee. Telechargez une zone d'abord.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // noms a afficher
        val noms = liste.map { it.split("|").firstOrNull() ?: "?" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Mes cartes hors ligne")
            .setItems(noms) { _, which ->
                val parts = liste[which].split("|")
                if (parts.size >= 3) {
                    val lat = parts[1].toDoubleOrNull(); val lon = parts[2].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        suiviAuto = false
                        carte.controller.setCenter(GeoPoint(lat, lon))
                        carte.controller.setZoom(16.0)
                        infoTexte.text = "Carte : ${noms[which]}"
                    }
                }
            }
            .setNeutralButton("Supprimer une carte") { _, _ -> supprimerMesCartes(liste, noms) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun supprimerMesCartes(liste: List<String>, noms: Array<String>) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Supprimer quelle carte ?")
            .setItems(noms) { _, which ->
                val restant = liste.filterIndexed { i, _ -> i != which }
                getSharedPreferences("cartes_hors_ligne", android.content.Context.MODE_PRIVATE)
                    .edit().putString("liste", restant.joinToString("\n")).apply()
                android.widget.Toast.makeText(this, "Carte \"${noms[which]}\" retiree de la liste.", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun sauvegarderCarteNommee(nom: String, centre: GeoPoint, rayonM: Double) {
        try {
            val prefs = getSharedPreferences("cartes_hors_ligne", android.content.Context.MODE_PRIVATE)
            val existant = prefs.getString("liste", "") ?: ""
            val ligne = "$nom|${centre.latitude}|${centre.longitude}|${rayonM.toInt()}"
            val lignes = existant.split("\n").filter { it.isNotBlank() && !it.startsWith("$nom|") }
            val nouveau = (lignes + ligne).joinToString("\n")
            prefs.edit().putString("liste", nouveau).apply()
        } catch (_: Exception) {}
    }

    private fun placerDecollage() {
        val reco = MainActivity.parcoursReco
        if (reco.size >= 2) {
            val pts = reco.map { GeoPoint(it.first, it.second) }
            val ligneReco = org.osmdroid.views.overlay.Polyline().apply {
                setPoints(pts)
                outlinePaint.color = 0xFFFFEB3B.toInt()
                outlinePaint.strokeWidth = 10f
            }
            carte.overlays.add(ligneReco)
            suiviAuto = false
            try {
                val bb = org.osmdroid.util.BoundingBox.fromGeoPoints(pts)
                carte.post { carte.zoomToBoundingBox(bb.increaseByScale(1.4f), true) }
            } catch (_: Exception) {}
            infoTexte.text = "Trajectoire de reconnaissance"
            return
        }
        val lat = MainActivity.ancreLatCarte
        val lon = MainActivity.ancreLonCarte
        if (!lat.isNaN() && !lon.isNaN()) {
            val m = Marker(carte).apply {
                position = GeoPoint(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Decollage"
            }
            carte.overlays.add(m)
            marqueurDecollage = m
            carte.controller.setCenter(GeoPoint(lat, lon))
        } else {
            // pas de position drone : centrer sur le telephone (preparer une carte hors ligne)
            centrerSurTelephone()
        }
    }

    private fun centrerSurTelephone() {
        try {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val perm = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 909)
                carte.controller.setZoom(6.0)
                return
            }
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER)
            var loc: android.location.Location? = null
            for (p in providers) {
                try { val l = lm.getLastKnownLocation(p); if (l != null && (loc == null || l.time > loc!!.time)) loc = l } catch (_: Exception) {}
            }
            if (loc != null) {
                carte.controller.setCenter(GeoPoint(loc!!.latitude, loc!!.longitude))
                carte.controller.setZoom(15.0)
                infoTexte.text = "Position du telephone - naviguez vers votre zone"
            } else {
                carte.controller.setZoom(6.0)
                infoTexte.text = "Naviguez vers votre zone de tournage"
            }
        } catch (e: Exception) {
            carte.controller.setZoom(6.0)
            infoTexte.text = "Naviguez vers votre zone de tournage"
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 909 && res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            centrerSurTelephone()
        }
    }

    private fun majPosition() {
        val lat = MainActivity.derniereLatCarte
        val lon = MainActivity.derniereLonCarte
        val cap = MainActivity.dernierCapCarte
        val gpsOk = MainActivity.derniereGpsOkCarte

        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) {
            infoTexte.text = if (gpsOk) "Position indisponible" else getString(R.string.ca_gps_recherche)
            return
        }
        val p = GeoPoint(lat, lon)
        marqueurDrone.position = p
        if (!cap.isNaN()) marqueurDrone.rotation = -cap
        infoTexte.text = "Drone : %.5f, %.5f%s".format(lat, lon, if (gpsOk) "  GPS OK" else "  GPS faible")
        if (suiviAuto) carte.controller.animateTo(p)
        carte.invalidate()
    }

    private fun centrerSurDrone() {
        val lat = MainActivity.derniereLatCarte
        val lon = MainActivity.derniereLonCarte
        if (!lat.isNaN() && !lon.isNaN()) {
            carte.controller.animateTo(GeoPoint(lat, lon))
            carte.controller.setZoom(17.0)
        }
    }

    override fun onResume() {
        super.onResume()
        carte.onResume()
        handler.post(rafraichir)
    }

    override fun onPause() {
        super.onPause()
        carte.onPause()
        handler.removeCallbacks(rafraichir)
    }
}

