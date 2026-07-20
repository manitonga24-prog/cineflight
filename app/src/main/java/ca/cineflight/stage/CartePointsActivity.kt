package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Carte AUTONOME des lieux detectes par « Preparer le lieu ».
 * Ne touche pas a CarteActivity (rails). Recoit :
 *  - "points_json" : tableau JSON [{type,label,nom,lat,lon,score}, ...]
 *  - "centre_lat" / "centre_lon" : position de l'utilisateur
 *  - "sugg_lat" / "sugg_lon" / "sugg_nom" : site suggere (optionnel)
 */
class CartePointsActivity : AppCompatActivity() {

    private lateinit var carte: MapView
    private var vueSatellite = true   // on demarre en satellite

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        val racine = FrameLayout(this)
        carte = MapView(this).apply {
            setTileSource(sourceEsriSatellite())
            setMultiTouchControls(true)
            controller.setZoom(14.0)
        }
        racine.addView(carte, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val cLat = intent.getDoubleExtra("centre_lat", 0.0)
        val cLon = intent.getDoubleExtra("centre_lon", 0.0)
        if (cLat != 0.0 || cLon != 0.0) {
            carte.controller.setCenter(GeoPoint(cLat, cLon))
            // marqueur "vous etes ici"
            carte.overlays.add(Marker(carte).apply {
                position = GeoPoint(cLat, cLon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = getString(R.string.cp_votre_position)
            })
        }

        // points detectes
        val ptsJson = intent.getStringExtra("points_json")
        if (!ptsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(ptsJson)
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val lat = p.optDouble("lat", 0.0)
                    val lon = p.optDouble("lon", 0.0)
                    if (lat == 0.0 && lon == 0.0) continue
                    val nom = p.optString("nom", "")
                    val label = p.optString("label", "")
                    // distance a vol d'oiseau depuis la position de l'utilisateur
                    val titre = if (cLat != 0.0 || cLon != 0.0) {
                        val d = distanceM(cLat, cLon, lat, lon)
                        (if (nom.isNotBlank()) nom else label) + "  ·  $d m"
                    } else {
                        (if (nom.isNotBlank()) nom else label)
                    }
                    carte.overlays.add(Marker(carte).apply {
                        position = GeoPoint(lat, lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = titre
                        snippet = label
                    })
                }
            } catch (_: Exception) { }
        }

        // site suggere (deplacement voiture)
        val sLat = intent.getDoubleExtra("sugg_lat", 0.0)
        val sLon = intent.getDoubleExtra("sugg_lon", 0.0)
        if (sLat != 0.0 || sLon != 0.0) {
            carte.overlays.add(Marker(carte).apply {
                position = GeoPoint(sLat, sLon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "🚗 " + intent.getStringExtra("sugg_nom").orEmpty()
                snippet = getString(R.string.cp_vaut_deplacement)
            })
        }
        carte.invalidate()

        // barre du bas : vue (satellite/routiere) + recentrer + fermer
        val barre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnVue = Button(this).apply {
            text = getString(R.string.map_vue_routiere); isAllCaps = false   // on demarre en satellite
            setOnClickListener {
                vueSatellite = !vueSatellite
                if (vueSatellite) {
                    carte.setTileSource(sourceEsriSatellite())
                    text = getString(R.string.map_vue_routiere)
                } else {
                    carte.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                    text = getString(R.string.map_vue_satellite)
                }
                carte.invalidate()
            }
        }
        val btnCentrer = Button(this).apply {
            text = getString(R.string.map_recentrer); isAllCaps = false
            setOnClickListener {
                if (cLat != 0.0 || cLon != 0.0) carte.controller.animateTo(GeoPoint(cLat, cLon))
            }
        }
        val btnFermer = Button(this).apply {
            text = getString(R.string.map_fermer); isAllCaps = false
            setOnClickListener { finish() }
        }
        barre.addView(btnVue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        barre.addView(btnCentrer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barre.addView(btnFermer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val barreParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        barreParams.gravity = android.view.Gravity.BOTTOM
        racine.addView(barre, barreParams)

        setContentView(racine)
    }

    override fun onResume() { super.onResume(); carte.onResume() }
    override fun onPause() { super.onPause(); carte.onPause() }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return (2 * r * Math.asin(Math.sqrt(a))).toInt()
    }

    private fun sourceEsriSatellite(): OnlineTileSourceBase {
        return object : OnlineTileSourceBase(
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
}

