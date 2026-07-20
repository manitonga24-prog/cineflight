package ca.cineflight.stage

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PlacementActivity — placer les musiciens d'une formation au VIVE TRACKER.
 *
 * Principe : on pose le tracker physique a l'endroit d'un musicien, on touche
 * "Capturer", et la position (x,z) scene du musicien est enregistree.
 *
 * Necessite un CALIBRAGE tracker->scene prealable (2 points de reference). Si
 * le serveur dit "non calibre", l'ecran propose d'abord de calibrer.
 *
 * Routes serveur (toutes existantes) :
 *   GET  /api/groupe/liste
 *   GET  /api/groupe/charger/{cle}
 *   GET  /api/repetition/tracker_etat         (le tracker repond-il ?)
 *   GET  /api/repetition/lire_tracker         (position brute, pour calibrer)
 *   GET  /api/repetition/lire_tracker_scene   (position en coords scene)
 *   POST /api/repetition/calibrer             (2 points -> transformation)
 *   POST /api/groupe/sauver                   (enregistre la formation modifiee)
 */
class PlacementActivity : AppCompatActivity() {

    private var baseUrl = "http://192.168.50.118:8095"

    private data class Item(val cle: String, val label: String)
    private val formations = mutableListOf<Item>()
    private var formationCle: String? = null
    private var formationJson: JSONObject? = null     // formation complete chargee
    private var musiciens = JSONArray()
    private var musicienIndex = -1                     // index dans musiciens

    // calibrage : 2 points (tracker brut + scene voulue)
    private var calibPret = false
    private var ptA_tracker: DoubleArray? = null
    private var ptB_tracker: DoubleArray? = null

    private lateinit var inpServeur: EditText
    private lateinit var spFormation: Spinner
    private lateinit var spMusicien: Spinner
    private lateinit var txtEtatTracker: TextView
    private lateinit var txtCalib: TextView
    private lateinit var txtPosition: TextView
    private lateinit var txtResultat: TextView
    private lateinit var btnCapturer: Button
    private lateinit var btnSauver: Button

    // champs calibrage
    private lateinit var inpAx: EditText
    private lateinit var inpAz: EditText
    private lateinit var inpBx: EditText
    private lateinit var inpBz: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_placement)

        inpServeur = findViewById(R.id.inpServeur)
        spFormation = findViewById(R.id.spFormation)
        spMusicien = findViewById(R.id.spMusicien)
        txtEtatTracker = findViewById(R.id.txtEtatTracker)
        txtCalib = findViewById(R.id.txtCalib)
        txtPosition = findViewById(R.id.txtPosition)
        txtResultat = findViewById(R.id.txtResultat)
        btnCapturer = findViewById(R.id.btnCapturer)
        btnSauver = findViewById(R.id.btnSauver)
        inpAx = findViewById(R.id.inpAx); inpAz = findViewById(R.id.inpAz)
        inpBx = findViewById(R.id.inpBx); inpBz = findViewById(R.id.inpBz)

        inpServeur.setText(baseUrl)
        findViewById<Button>(R.id.btnRecharger).setOnClickListener {
            baseUrl = inpServeur.text.toString().trim().trimEnd('/')
            chargerFormations(); verifierTracker()
        }
        findViewById<Button>(R.id.btnVerifTracker).setOnClickListener { verifierTracker() }
        findViewById<Button>(R.id.btnCalibA).setOnClickListener { capturerCalib("A") }
        findViewById<Button>(R.id.btnCalibB).setOnClickListener { capturerCalib("B") }
        findViewById<Button>(R.id.btnCalibrer).setOnClickListener { envoyerCalibrage() }
        btnCapturer.setOnClickListener { capturerPositionMusicien() }
        btnSauver.setOnClickListener { sauverFormation() }

        chargerFormations()
        verifierTracker()
    }

    // ---- formations ----
    private fun chargerFormations() {
        lifecycleScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/groupe/liste") }
                formations.clear()
                JSONObject(j).getJSONArray("formations").let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        formations.add(Item(o.optString("cle"),
                            getString(R.string.pl_formation_label, o.optString("nom"), o.optInt("n_musiciens"))))
                    }
                }
                val labels = mutableListOf(getString(R.string.pl_choisir_formation))
                labels.addAll(formations.map { it.label })
                spFormation.adapter = ArrayAdapter(this@PlacementActivity,
                    android.R.layout.simple_spinner_dropdown_item, labels)
                spFormation.onSelect { pos ->
                    if (pos >= 1) chargerFormation(formations[pos - 1].cle)
                }
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.pl_serveur_err, e.message)
            }
        }
    }

    private fun chargerFormation(cle: String) {
        formationCle = cle
        lifecycleScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/groupe/charger/$cle") }
                formationJson = JSONObject(j)
                musiciens = formationJson!!.optJSONArray("musiciens") ?: JSONArray()
                val labels = mutableListOf(getString(R.string.pl_choisir_musicien))
                for (i in 0 until musiciens.length()) {
                    val m = musiciens.getJSONObject(i)
                    labels.add("${m.optString("nom")} (${m.optString("role")})")
                }
                spMusicien.adapter = ArrayAdapter(this@PlacementActivity,
                    android.R.layout.simple_spinner_dropdown_item, labels)
                spMusicien.onSelect { pos ->
                    musicienIndex = pos - 1
                    majPosition()
                }
                txtResultat.text = getString(R.string.pl_formation_chargee, musiciens.length())
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.pl_chargement_err, e.message)
            }
        }
    }

    private fun majPosition() {
        if (musicienIndex < 0 || musicienIndex >= musiciens.length()) {
            txtPosition.text = "—"; majBoutons(); return
        }
        val m = musiciens.getJSONObject(musicienIndex)
        txtPosition.text = "${m.optString("nom")} : x=${round1(m.optDouble("x"))}  z=${round1(m.optDouble("z"))}"
        majBoutons()
    }

    // ---- tracker ----
    private fun verifierTracker() {
        txtEtatTracker.text = getString(R.string.pl_sonde)
        lifecycleScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/repetition/tracker_etat") }
                val o = JSONObject(j)
                val ok = o.optBoolean("connecte", false)
                txtEtatTracker.text = (if (ok) "✅ " else "⚠ ") + o.optString("raison")
                // etat calibrage
                val jd = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/repetition/disponible") }
                calibPret = JSONObject(jd).optJSONObject("calib")?.optBoolean("pret", false) ?: false
                txtCalib.text = if (calibPret) getString(R.string.pl_calibrage_fait)
                                else getString(R.string.pl_non_calibre_msg)
                majBoutons()
            } catch (e: Exception) {
                txtEtatTracker.text = getString(R.string.pl_tracker_err, e.message)
            }
        }
    }

    private fun capturerCalib(point: String) {
        lifecycleScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/repetition/lire_tracker") }
                val o = JSONObject(j)
                if (!o.optBoolean("valide", false)) {
                    txtResultat.text = getString(R.string.pl_tracker_invalide); return@launch
                }
                val pt = doubleArrayOf(o.optDouble("x"), o.optDouble("y"), o.optDouble("z"))
                if (point == "A") { ptA_tracker = pt; txtResultat.text = getString(R.string.pl_point_a) }
                else { ptB_tracker = pt; txtResultat.text = getString(R.string.pl_point_b) }
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.pl_capture_err, e.message)
            }
        }
    }

    private fun envoyerCalibrage() {
        val a = ptA_tracker; val b = ptB_tracker
        if (a == null || b == null) { txtResultat.text = getString(R.string.pl_capture_ab); return }
        val ax = inpAx.text.toString().toDoubleOrNull() ?: 0.0
        val az = inpAz.text.toString().toDoubleOrNull() ?: 0.0
        val bx = inpBx.text.toString().toDoubleOrNull() ?: 2.0
        val bz = inpBz.text.toString().toDoubleOrNull() ?: 0.0
        val body = JSONObject().apply {
            put("a_tracker", JSONArray(listOf(a[0], a[1], a[2])))
            put("a_scene", JSONArray(listOf(ax, az)))
            put("b_tracker", JSONArray(listOf(b[0], b[1], b[2])))
            put("b_scene", JSONArray(listOf(bx, bz)))
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { httpPostJson("$baseUrl/api/repetition/calibrer", body.toString()) }
                calibPret = true
                txtCalib.text = getString(R.string.pl_calibrage_fait)
                txtResultat.text = getString(R.string.pl_calibrage_ok)
                majBoutons()
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.pl_calibrage_err, e.message)
            }
        }
    }

    // ---- capture position d'un musicien ----
    private fun capturerPositionMusicien() {
        if (musicienIndex < 0) { txtResultat.text = getString(R.string.pl_choisis_musicien); return }
        txtResultat.text = getString(R.string.pl_lecture)
        lifecycleScope.launch {
            try {
                val j = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/repetition/lire_tracker_scene") }
                val o = JSONObject(j)
                if (!o.optBoolean("valide", false)) {
                    txtResultat.text = getString(R.string.pl_tracker_invalide2); return@launch
                }
                val x = o.optDouble("x"); val z = o.optDouble("z")
                val m = musiciens.getJSONObject(musicienIndex)
                m.put("x", x); m.put("z", z)
                majPosition()
                txtResultat.text = getString(R.string.pl_place, m.optString("nom"), round1(x), round1(z))
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.pl_erreur, e.message)
            }
        }
    }

    // ---- sauvegarde ----
    private fun sauverFormation() {
        val f = formationJson ?: return
        // remet la liste musiciens (modifiee en place) dans la formation
        f.put("musiciens", musiciens)
        val body = JSONObject().apply {
            put("nom", f.optString("nom"))
            put("chanson", f.optString("chanson", ""))
            put("formation", f)
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { httpPostJson("$baseUrl/api/groupe/sauver", body.toString()) }
                txtResultat.text = getString(R.string.pl_sauvee)
                Toast.makeText(this@PlacementActivity, getString(R.string.pl_sauvegarde_toast), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.pl_sauvegarde_err, e.message)
            }
        }
    }

    private fun majBoutons() {
        btnCapturer.isEnabled = calibPret && musicienIndex >= 0
        btnSauver.isEnabled = formationJson != null
    }

    private fun round1(v: Double) = (Math.round(v * 10) / 10.0)

    // ---- HTTP ----
    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 4000; readTimeout = 8000
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
    private fun httpPostJson(url: String, body: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 4000; readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val detail = try { JSONObject(txt).optString("detail", txt) } catch (e: Exception) { txt }
            throw RuntimeException(getString(R.string.pl_http, code, detail))
        }
        return txt
    }

    private fun Spinner.onSelect(cb: (Int) -> Unit) {
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = cb(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }
}

