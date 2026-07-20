package ca.cineflight.stage

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MissionActivity — assistant de mission : ARTISTE -> CHANSON -> PASSE.
 *
 * Flux :
 *  1. Artiste (formation)  : /api/groupe/liste
 *  2. Chanson (plan)       : /api/tournage/liste
 *  3. Passe                : /api/mission/passes (calcule Passe 1, 2... pour
 *                            cet artiste + cette chanson, avec leurs prises)
 *  4. Lancer               : /api/mission/lancer_passe -> le serveur demarre le
 *                            bridge qui ENCHAINE automatiquement toutes les
 *                            prises de la passe. L'app passe en mode AUTO.
 *
 * SECURITE : simulation par defaut. Vol reel = confirmation + IP du telephone,
 * drone deja en vol stationnaire, telecommande RC en main (priorite absolue).
 */
class MissionActivity : AppCompatActivity() {

    private var baseUrl = "http://192.168.50.118:8095"

    private data class Item(val cle: String, val label: String)
    private val artistes = mutableListOf<Item>()      // formations
    private val chansons = mutableListOf<Item>()      // plans de tournage
    private data class Passe(val numero: Int, val nPrises: Int, val resume: String)
    private val passes = mutableListOf<Passe>()

    private var artisteChoisi: Item? = null
    private var chansonChoisie: Item? = null
    private var passeChoisie: Passe? = null

    private lateinit var inpServeur: EditText
    private lateinit var spArtiste: Spinner
    private lateinit var spChanson: Spinner
    private lateinit var spPasse: Spinner
    private lateinit var txtDetailPasse: TextView
    private lateinit var inpIp: EditText
    private lateinit var txtRecap: TextView
    private lateinit var txtResultat: TextView
    private lateinit var btnSimu: Button
    private lateinit var btnReel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        inpServeur = findViewById(R.id.inpServeur)
        spArtiste = findViewById(R.id.spFormation)   // reutilise l'id existant
        spChanson = findViewById(R.id.spChanson)
        spPasse = findViewById(R.id.spAngle)          // reutilise l'id existant
        txtDetailPasse = findViewById(R.id.txtChanson)
        inpIp = findViewById(R.id.inpIp)
        txtRecap = findViewById(R.id.txtRecap)
        txtResultat = findViewById(R.id.txtResultat)
        btnSimu = findViewById(R.id.btnSimu)
        btnReel = findViewById(R.id.btnReel)

        inpServeur.setText(baseUrl)
        findViewById<Button>(R.id.btnRecharger).setOnClickListener {
            baseUrl = inpServeur.text.toString().trim().trimEnd('/')
            chargerArtistesEtChansons()
        }
        btnSimu.setOnClickListener { lancer(false) }
        btnReel.setOnClickListener { confirmerEtLancerReel() }
        inpIp.addTextChangedSimple { majBoutons() }

        majRecap()
        chargerArtistesEtChansons()
    }

    private fun chargerArtistesEtChansons() {
        txtResultat.text = getString(R.string.mi_connexion)
        lifecycleScope.launch {
            try {
                val jForm = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/groupe/liste") }
                val jCh = withContext(Dispatchers.IO) { httpGet("$baseUrl/api/tournage/liste") }

                artistes.clear()
                JSONObject(jForm).getJSONArray("formations").let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        artistes.add(Item(o.optString("cle"),
                            getString(R.string.mi_artiste_label, o.optString("nom"), o.optInt("n_musiciens"))))
                    }
                }
                chansons.clear()
                JSONObject(jCh).getJSONArray("chansons").let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        chansons.add(Item(o.optString("nom"),
                            getString(R.string.mi_chanson_label, o.optString("titre"), o.optInt("n_prises"))))
                    }
                }

                remplirArtistesChansons()
                txtResultat.text = getString(R.string.mi_serveur_ok, artistes.size, chansons.size)
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.mi_serveur_err, e.message)
            }
        }
    }

    private fun remplirArtistesChansons() {
        val la = mutableListOf(getString(R.string.mi_choisir_artiste)); la.addAll(artistes.map { it.label })
        spArtiste.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, la)
        spArtiste.onSelect { pos ->
            artisteChoisi = if (pos >= 1) artistes[pos - 1] else null
            calculerPassesSiPret(); majRecap()
        }
        val lc = mutableListOf(getString(R.string.mi_choisir_chanson)); lc.addAll(chansons.map { it.label })
        spChanson.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lc)
        spChanson.onSelect { pos ->
            chansonChoisie = if (pos >= 1) chansons[pos - 1] else null
            calculerPassesSiPret(); majRecap()
        }
        // passe : vide tant qu'on n'a pas artiste+chanson
        spPasse.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            mutableListOf(getString(R.string.mi_choisir_both)))
    }

    private fun calculerPassesSiPret() {
        val a = artisteChoisi ?: return
        val c = chansonChoisie ?: return
        txtResultat.text = getString(R.string.mi_calcul)
        passes.clear(); passeChoisie = null
        txtDetailPasse.text = "—"
        lifecycleScope.launch {
            try {
                val body = JSONObject().apply {
                    put("cle_formation", a.cle); put("chanson", c.cle)
                }
                val rep = withContext(Dispatchers.IO) {
                    httpPostJson("$baseUrl/api/mission/passes", body.toString())
                }
                val o = JSONObject(rep)
                val arr = o.getJSONArray("passes")
                for (i in 0 until arr.length()) {
                    val po = arr.getJSONObject(i)
                    val pr = po.getJSONArray("prises")
                    val resume = StringBuilder()
                    for (k in 0 until pr.length()) {
                        val x = pr.getJSONObject(k)
                        resume.append(getString(R.string.mi_prise, x.optString("section"), x.optString("label")))
                    }
                    passes.add(Passe(po.optInt("numero"), po.optInt("n_prises"), resume.toString().trim()))
                }
                val labels = mutableListOf(getString(R.string.mi_choisir_passe))
                labels.addAll(passes.map { getString(R.string.mi_passe_label, it.numero, it.nPrises) })
                spPasse.adapter = ArrayAdapter(this@MissionActivity,
                    android.R.layout.simple_spinner_dropdown_item, labels)
                spPasse.onSelect { pos ->
                    passeChoisie = if (pos >= 1) passes[pos - 1] else null
                    txtDetailPasse.text = passeChoisie?.resume ?: "—"
                    majRecap()
                }
                txtResultat.text = o.optString("message",
                    getString(R.string.mi_passes_calc, passes.size))
            } catch (e: Exception) {
                txtResultat.text = getString(R.string.mi_calc_err, e.message)
            }
        }
    }

    private fun majRecap() {
        txtRecap.text = getString(R.string.mi_recap_artiste, artisteChoisi?.label ?: "—") +
                getString(R.string.mi_recap_chanson, chansonChoisie?.label ?: "—") +
                getString(R.string.mi_recap_passe, passeChoisie?.let { getString(R.string.mi_passe_label, it.numero, it.nPrises) } ?: "—")
        majBoutons()
    }

    private fun majBoutons() {
        val pret = artisteChoisi != null && chansonChoisie != null && passeChoisie != null
        btnSimu.isEnabled = pret
        btnReel.isEnabled = pret && inpIp.text.toString().trim().isNotEmpty()
    }

    private fun confirmerEtLancerReel() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mi_vol_reel_titre))
            .setMessage(getString(R.string.mi_dlg_p1) +
                getString(R.string.mi_dlg_p2) + txtRecap.text +
                getString(R.string.mi_dlg_p3, inpIp.text))
            .setNegativeButton(getString(R.string.mi_annuler), null)
            .setPositiveButton(getString(R.string.mi_lancer)) { _, _ -> lancer(true) }
            .show()
    }

    private fun lancer(reel: Boolean) {
        val a = artisteChoisi ?: return
        val c = chansonChoisie ?: return
        val p = passeChoisie ?: return
        val ip = inpIp.text.toString().trim()
        txtResultat.text = if (reel) getString(R.string.mi_lancement_reel) else getString(R.string.mi_simu_encours)
        val body = JSONObject().apply {
            put("cle_formation", a.cle); put("chanson", c.cle)
            put("numero_passe", p.numero); put("reel", reel)
            put("host_android", if (reel) ip else "")
        }
        lifecycleScope.launch {
            try {
                val rep = withContext(Dispatchers.IO) {
                    httpPostJson("$baseUrl/api/mission/lancer_passe", body.toString())
                }
                val o = JSONObject(rep)
                txtResultat.text = "✅ ${o.optString("mode")}\n" +
                    "Artiste : ${o.optString("formation")}\n" +
                    "Chanson : ${o.optString("chanson")}\n" +
                    "Passe ${o.optInt("passe")} · ${o.optInt("n_prises")} prises\n" +
                    o.optString("note")
                if (reel) Toast.makeText(this@MissionActivity,
                    "Passe l'app en mode AUTO 🤖", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                txtResultat.text = "Refusé / erreur : ${e.message}"
            }
        }
    }

    // ---- HTTP ----
    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 4000; readTimeout = 6000
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
    private fun httpPostJson(url: String, body: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 4000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val detail = try { JSONObject(txt).optString("detail", txt) } catch (e: Exception) { txt }
            throw RuntimeException("HTTP $code : $detail")
        }
        return txt
    }

    private fun Spinner.onSelect(cb: (Int) -> Unit) {
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = cb(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }
    private fun EditText.addTextChangedSimple(cb: () -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = cb()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
}

