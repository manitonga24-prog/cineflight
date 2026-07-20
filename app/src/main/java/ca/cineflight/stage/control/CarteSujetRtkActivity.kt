package ca.cineflight.stage.control
import ca.cineflight.stage.R

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

class CarteSujetRtkActivity : AppCompatActivity() {

    private val client = MouvementServeurClient()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var map: MapView
    private lateinit var info: TextView

    private var markerSujet: Marker? = null
    private var cerclePrecision: Polygon? = null

    private val boucle = object : Runnable {
        override fun run() {
            lireSujetRtk()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        val racine = FrameLayout(this)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(19.0)
            controller.setCenter(GeoPoint(45.5633358, -73.6628376))
        }

        info = TextView(this).apply {
            text = getString(R.string.csr_attente)
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(18, 12, 18, 12)
        }

        val infoParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }

        racine.addView(map)
        racine.addView(info, infoParams)

        setContentView(racine)
    }

    override fun onStart() {
        super.onStart()
        map.onResume()
        handler.post(boucle)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        map.onPause()
    }

    private fun lireSujetRtk() {
        client.fetchRtkSujet(
            onResult = { rtk ->
                if (rtk == null || !rtk.present || rtk.lat == null || rtk.lon == null) {
                    info.text = getString(R.string.csr_absent)
                    return@fetchRtkSujet
                }

                afficherSujet(rtk)
            },
            onError = { err ->
                info.text = getString(R.string.csr_erreur, err)
            }
        )
    }

    private fun afficherSujet(rtk: RtkSujet) {
        val lat = rtk.lat ?: return
        val lon = rtk.lon ?: return
        val point = GeoPoint(lat, lon, rtk.altM ?: 0.0)

        val statut = rtk.rtk ?: "?"
        val age = rtk.ageS ?: -1.0
        val hz = rtk.hz ?: 0.0

        val rayonM = when (statut.uppercase(Locale.US)) {
            "FIX" -> 0.25
            "FLOAT" -> 2.0
            "GPS" -> 5.0
            else -> 10.0
        }

        if (markerSujet == null) {
            markerSujet = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = getString(R.string.csr_titre)
                map.overlays.add(this)
            }
        }

        markerSujet?.apply {
            position = point
            title = getString(R.string.csr_titre)
            snippet = "RTK=$statut age=${"%.2f".format(age)}s"
        }

        cerclePrecision?.let {
            map.overlays.remove(it)
        }

        cerclePrecision = Polygon(map).apply {
            points = Polygon.pointsAsCircle(point, rayonM)

            fillColor = when (statut.uppercase(Locale.US)) {
                "FIX" -> Color.argb(70, 0, 220, 80)
                "FLOAT" -> Color.argb(70, 255, 190, 0)
                else -> Color.argb(70, 255, 60, 60)
            }

            strokeColor = when (statut.uppercase(Locale.US)) {
                "FIX" -> Color.rgb(0, 220, 80)
                "FLOAT" -> Color.rgb(255, 190, 0)
                else -> Color.rgb(255, 60, 60)
            }

            strokeWidth = 3f
            map.overlays.add(this)
        }

        map.controller.animateTo(point)
        map.invalidate()

        info.text =
            getString(R.string.csr_detail_titre) +
            "RTK : $statut   age : ${"%.2f".format(age)} s   hz : ${"%.1f".format(hz)}\n" +
            "Lat : ${"%.8f".format(lat)}\n" +
            "Lon : ${"%.8f".format(lon)}\n" +
            "Alt : ${"%.1f".format(rtk.altM ?: 0.0)} m\n" +
            "Rayon affiche : ${"%.2f".format(rayonM)} m"
    }
}

