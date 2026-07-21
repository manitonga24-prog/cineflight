package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * DefinitionSujetActivity — écran UNIFIÉ de définition d'un sujet de tournage.
 *
 * L'utilisateur choisit le TYPE de sujet, puis trace sa géométrie sur la carte :
 *   - BÂTIMENT : polygone FERMÉ (footprint) + hauteur        [ACTIF]
 *   - PONT     : polyligne (axe) + largeur + hauteur tablier  [structure prête]
 *   - BERGE    : polyligne (axe)                              [structure prête]
 *   - BARRAGE  : polyligne (crête) + hauteurs                 [structure prête]
 *
 * Pour cette validation, seul le BÂTIMENT est branché sur son endpoint serveur
 * (/api/generer_batiment, déployé et testé). Les autres types affichent un
 * message "endpoint à venir" jusqu'à leur déploiement (même pattern).
 *
 * Le KMZ généré est téléchargé localement puis renvoyé à l'appelant
 * (RESULT_OK + chemin) ; MainActivity le charge dans LecteurMissionKmz et
 * affiche la trajectoire. Le vol est lancé par le pilote, pas automatiquement.
 */
class DefinitionSujetActivity : AppCompatActivity() {

    private lateinit var carte: MapView
    // Contexte de lumiere choisi dans l'assistant Lumieres du jour (null si entree directe).
    private var lumNom: String? = null
    private var lumDebut: String = ""
    private var lumFin: String = ""
    private var lumPic: String = ""
    private val sommets = mutableListOf<GeoPoint>()    // sommets tracés
    private var pointDecollage: GeoPoint? = null
    private var marqueurDecollage: Marker? = null
    private var modeDecollage = false
    private var btnDecollage: Button? = null
    private var btnVerifDecollage: Button? = null
    private var degagementDecollage: TextView? = null
    @Volatile private var verifDecollageEnCours = false
    private var overlayTrace: Polygon? = null
    private var overlayLigne: Polyline? = null
    private val marqueurs = mutableListOf<Marker>()

    private lateinit var spinnerType: Spinner
    private lateinit var info: TextView

    private val baseUrl = "https://cineflight.ca"

    // types : libellé visible -> est-ce un polygone fermé (true) ou polyligne (false)
    // 1re entrée = invitation vide : AUCUN type par défaut (l'usager doit choisir).
    private val typesFerme by lazy { listOf(
        getString(R.string.def_choisir_type) to false,
        getString(R.string.def_type_batiment) to true,
        getString(R.string.def_type_pont) to false,
        getString(R.string.def_type_berge) to false,
        getString(R.string.def_type_barrage) to false,
    ) }

    private fun typeChoisi(): Boolean = spinnerType.selectedItemPosition > 0
    private fun typeFerme(): Boolean = typesFerme[spinnerType.selectedItemPosition].second
    private fun typeLibelle(): String = typesFerme[spinnerType.selectedItemPosition].first

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        // Contexte de lumiere transmis par l'assistant Lumieres du jour (peut etre null).
        lumNom = intent.getStringExtra("momentNom")
        lumDebut = intent.getStringExtra("momentDebut") ?: ""
        lumFin = intent.getStringExtra("momentFin") ?: ""
        lumPic = intent.getStringExtra("momentPic") ?: ""

        val racine = FrameLayout(this)

        carte = MapView(this).apply {
            setTileSource(sourceEsriSatellite())
            setMultiTouchControls(true)
            controller.setZoom(17.0)
        }
        racine.addView(carte, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // --- barre de recherche en HAUT : centrer la carte sur un lieu connu ---
        val barreRecherche = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x66000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val champRecherche = EditText(this).apply {
            hint = getString(R.string.def_recherche_hint)
            setTextColor(Color.WHITE); setHintTextColor(0xFFBBBBBB.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnChercher = Button(this).apply {
            text = getString(R.string.def_aller); isAllCaps = false
            setOnClickListener {
                val q = champRecherche.text.toString().trim()
                if (q.isNotEmpty()) rechercherLieu(q)
            }
        }
        barreRecherche.addView(champRecherche)
        barreRecherche.addView(btnChercher)
        racine.addView(barreRecherche, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP))

        // centre : position fournie ou défaut Montréal
        val cLat = intent.getDoubleExtra("centre_lat", 45.5019)
        val cLon = intent.getDoubleExtra("centre_lon", -73.5674)
        carte.controller.setCenter(GeoPoint(cLat, cLon))

        // capture des taps : ajoute un sommet
        val recepteur = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) { if (modeDecollage) { poserDecollage(p); modeDecollage = false } else ajouterSommet(p); return true }
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        carte.overlays.add(0, MapEventsOverlay(recepteur))

        // --- panneau de contrôle en bas ---
        val panneau = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // TOUT sur une seule ligne horizontale -> maximum de place pour la carte.
        val ligneBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        spinnerType = Spinner(this).apply {
            adapter = ArrayAdapter(this@DefinitionSujetActivity,
                android.R.layout.simple_spinner_dropdown_item,
                typesFerme.map { it.first })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val btnAnnuler = Button(this).apply {
            text = getString(R.string.def_annuler); isAllCaps = false
            setOnClickListener { retirerDernierSommet() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnEffacer = Button(this).apply {
            text = getString(R.string.def_effacer); isAllCaps = false
            setOnClickListener { toutEffacer() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnGenerer = Button(this).apply {
            text = getString(R.string.def_generer); isAllCaps = false
            setOnClickListener { genererReconnaissance() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        ligneBtns.addView(spinnerType)
        ligneBtns.addView(btnAnnuler)
        ligneBtns.addView(btnEffacer)
        val btnDecollage = Button(this).apply {
            text = getString(R.string.def_decollage); isAllCaps = false; textSize = 12f
            setOnClickListener {
                modeDecollage = true
                Toast.makeText(this@DefinitionSujetActivity, getString(R.string.def_touchez), Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        btnDecollage?.let { ligneBtns.addView(it) }
        btnVerifDecollage = Button(this).apply {
            text = getString(R.string.def_degagement); isAllCaps = false; textSize = 12f
            setOnClickListener { verifierDegagementDecollage() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        btnVerifDecollage?.let { ligneBtns.addView(it) }
        ligneBtns.addView(btnGenerer)
        panneau.addView(ligneBtns)

        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f
            text = getString(R.string.def_info)
        }
        panneau.addView(info)

        degagementDecollage = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, dp(6), 0, 0)
            text = ""
        }
        panneau.addView(degagementDecollage)

        racine.addView(panneau, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM))

        // Bandeau contexte lumiere (visible seulement si on vient de l'assistant Lumieres du jour)
        if (lumNom != null) {
            val plage = if (lumDebut.isNotEmpty() && lumFin.isNotEmpty())
                getString(R.string.def_lum_plage, lumDebut, lumFin) + (if (lumPic.isNotEmpty()) getString(R.string.def_lum_pic_plage, lumPic) else "") else ""
            val bandeau = TextView(this).apply {
                text = getString(R.string.def_lum_banner, lumNom, plage)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC15485F.toInt())
                textSize = 14f
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            racine.addView(bandeau, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP))
        }
        setContentView(racine)
    }

    private fun poserDecollage(p: GeoPoint) {
        pointDecollage = p
        marqueurDecollage?.let { carte.overlays.remove(it) }
        val m = Marker(carte).apply {
            position = p
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.def_marqueur_decollage)
            try {
                icon = resources.getDrawable(org.osmdroid.library.R.drawable.marker_default, null).mutate().apply { setTint(0xFF00C853.toInt()) }
            } catch (_: Exception) {}
        }
        marqueurDecollage = m
        carte.overlays.add(m)
        carte.invalidate()
        info.text = getString(R.string.def_decollage_place)
        btnDecollage?.apply { text = getString(R.string.def_decollage); setBackgroundColor(0xFF37474F.toInt()) }
    }

    /** Vérifie le dégagement (obstacles connus de la carte) AUTOUR du point de
     *  décollage déjà placé sur la carte. C'est un filtre de sécurité AVANT le vol :
     *  il ne remplace pas l'inspection visuelle sur place ni le repérage 3D.
     *  @param auto true = déclenché automatiquement par Générer (silencieux si pas
     *              de décollage placé) ; false = bouton manuel (avertit l'usager). */
    private fun verifierDegagementDecollage(auto: Boolean = false) {
        val dep = pointDecollage
        if (dep == null) {
            if (!auto) {
                Toast.makeText(this, getString(R.string.def_placez), Toast.LENGTH_LONG).show()
            }
            return
        }
        if (verifDecollageEnCours) return
        verifDecollageEnCours = true
        btnVerifDecollage?.apply { isEnabled = false; text = "..." }
        val prefixe = if (auto) getString(R.string.def_diag_titre) else ""
        degagementDecollage?.text = getString(R.string.def_diag_interro)

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val url = URL("$baseUrl/api/verifier_point?lat=${dep.latitude}&lon=${dep.longitude}&rayon=40")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20000
                        readTimeout = 90000
                        setRequestProperty("Accept", "application/json")
                    }
                    if (conn.responseCode != 200) "ERR:${conn.responseCode}"
                    else conn.inputStream.bufferedReader().use { it.readText() }
                } catch (e: java.net.SocketTimeoutException) {
                    "TIMEOUT"
                } catch (e: Exception) {
                    "EXC:${e.message ?: e.javaClass.simpleName}"
                }
            }

            val texte = StringBuilder(prefixe)
            when {
                res == null || res.startsWith("ERR:") ->
                    texte.append(getString(R.string.def_diag_serveur, res?.removePrefix("ERR:") ?: "?") +
                        getString(R.string.def_diag_quand_meme))
                res == "TIMEOUT" ->
                    texte.append(getString(R.string.def_diag_delai) +
                        getString(R.string.def_diag_quand_meme))
                res.startsWith("EXC:") ->
                    texte.append(getString(R.string.def_diag_exc, res.removePrefix("EXC:")) +
                        getString(R.string.def_diag_quand_meme))
                else -> try {
                    val o = JSONObject(res)
                    val etat = o.optString("etat", "carte_indisponible")
                    val obsArr = o.optJSONArray("obstacles_connus")
                    val chkArr = o.optJSONArray("checklist_pilote")
                    val nomLieu = o.optString("nom_lieu", "")
                    if (nomLieu.isNotBlank() && nomLieu != "null") texte.append(getString(R.string.def_diag_lieu, nomLieu))
                    when (etat) {
                        "carte_ok" -> texte.append(getString(R.string.def_diag_ok))
                        "obstacles" -> {
                            val n = obsArr?.length() ?: 0
                            // On n'affiche que le PLUS PROCHE (le plus pertinent pour la sécurité)
                            // au lieu de lister tous les obstacles : plus lisible.
                            var typeProche = "obstacle"
                            var distProche = Double.MAX_VALUE
                            if (obsArr != null) for (i in 0 until obsArr.length()) {
                                val ob = obsArr.getJSONObject(i)
                                val d = ob.optDouble("distance_m", Double.MAX_VALUE)
                                if (d < distProche) { distProche = d; typeProche = ob.optString("type", "obstacle") }
                            }
                            texte.append(getString(R.string.def_diag_obstacles, n))
                            if (distProche != Double.MAX_VALUE)
                                texte.append(getString(R.string.def_diag_proche, typeProche, distProche.toInt()))
                        }
                        else -> texte.append(getString(R.string.def_diag_carte) +
                            getString(R.string.def_diag_feu_vert))
                    }
                    if (chkArr != null && chkArr.length() > 0) {
                        texte.append(getString(R.string.def_diag_confirmer))
                        for (i in 0 until chkArr.length()) texte.append(getString(R.string.def_diag_check, chkArr.getString(i)))
                    }
                    texte.append(getString(R.string.def_diag_note1) +
                        getString(R.string.def_diag_note2))
                    texte.append("\n\n${o.optString("disclaimer", "")}")
                } catch (e: Exception) {
                    texte.append(getString(R.string.def_diag_illisible))
                }
            }

            degagementDecollage?.text = texte.toString()
            verifDecollageEnCours = false
            btnVerifDecollage?.apply { isEnabled = true; text = getString(R.string.def_degagement) }
        }
    }

    private fun estimerEtRetourner(chemin: String, recap: String) {
        val data = android.content.Intent().apply {
            putExtra("kmz_path", chemin); putExtra("recap", recap)
        }
        setResult(RESULT_OK, data)
        val dep = pointDecollage
        if (dep == null) { Toast.makeText(this, messageRecap(recap), Toast.LENGTH_LONG).show(); finish(); return }
        val poses = try {
            ca.cineflight.stage.control.LecteurMissionKmz.pointsKmz(java.io.File(chemin))
                .map { ca.cineflight.stage.control.MissionCompleteBuilder.WaypointMission(it.first, it.second, 60.0, "reconnaissance") }
        } catch (e: Exception) { emptyList() }
        if (poses.size < 2) { Toast.makeText(this, messageRecap(recap), Toast.LENGTH_LONG).show(); finish(); return }
        val m = ca.cineflight.stage.control.MissionCompleteBuilder.construire(this, dep.latitude, dep.longitude, poses)
        val resume = ca.cineflight.stage.control.MissionCompleteBuilder.resume(this, m)
        val titre = if (m.realisable) getString(R.string.def_est_ok) else getString(R.string.def_est_loin)
        android.app.AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(getString(R.string.def_est_titre) + resume)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun ajouterSommet(p: GeoPoint) {
        sommets.add(p)
        val m = Marker(carte).apply {
            position = p
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Point ${sommets.size}"
        }
        marqueurs.add(m)
        carte.overlays.add(m)
        redessiner()
    }

    private fun retirerDernierSommet() {
        if (sommets.isEmpty()) return
        sommets.removeAt(sommets.size - 1)
        carte.overlays.remove(marqueurs.removeAt(marqueurs.size - 1))
        redessiner()
    }

    private fun toutEffacer() {
        sommets.clear()
        marqueurs.forEach { carte.overlays.remove(it) }
        marqueurs.clear()
        redessiner()
    }

    private fun redessiner() {
        overlayTrace?.let { carte.overlays.remove(it) }
        overlayLigne?.let { carte.overlays.remove(it) }
        overlayTrace = null; overlayLigne = null

        if (sommets.size >= 2) {
            if (typeFerme()) {
                overlayTrace = Polygon(carte).apply {
                    points = sommets.toList()
                    fillPaint.color = 0x2200FF88
                    outlinePaint.color = 0xFF00FF88.toInt()
                    outlinePaint.strokeWidth = 4f
                }
                carte.overlays.add(overlayTrace)
            } else {
                overlayLigne = Polyline(carte).apply {
                    setPoints(sommets.toList())
                    outlinePaint.color = 0xFF00CCFF.toInt()
                    outlinePaint.strokeWidth = 6f
                }
                carte.overlays.add(overlayLigne)
            }
        }
        info.text = getString(R.string.def_points, typeLibelle(), sommets.size)
        carte.invalidate()
    }

    private fun genererReconnaissance() {
        if (!typeChoisi()) {
            Toast.makeText(this, getString(R.string.def_choisir_dabord), Toast.LENGTH_LONG).show()
            return
        }
        val ferme = typeFerme()
        val minPts = if (ferme) 3 else 2
        if (sommets.size < minPts) {
            Toast.makeText(this, getString(R.string.def_tracez_min, minPts), Toast.LENGTH_SHORT).show()
            return
        }

        // Vérification AUTOMATIQUE du dégagement du décollage (Option 3) :
        // non bloquante — la mission est générée quoi qu'il arrive. Le diagnostic
        // s'affiche à côté. Le pilote reste responsable de la décision finale.
        verifierDegagementDecollage(auto = true)

        // Seul le BÂTIMENT est branché pour cette validation.
        // La hauteur est MESURÉE par le serveur (LiDAR) — aucune saisie utilisateur.
        val lib = typeLibelle()
        when (spinnerType.selectedItemPosition) {
            1 -> genererBatiment()
            2 -> genererPont()
            3 -> genererLineaire("LINE")
            4 -> genererLineaire("WALL")
            else -> Toast.makeText(this,
                getString(R.string.def_type_avenir, lib),
                Toast.LENGTH_LONG).show()
        }
    }

    /** Extrait un message lisible (nb de prises de vue) du recap JSON serveur. */
    private fun messageRecap(recap: String): String {
        return try {
            val o = JSONObject(recap)
            val n = o.optInt("nb_poses", -1)
            val type = o.optString("type", "")
            val prefixeLum = if (lumNom != null) "\uD83C\uDF05 " + lumNom + (if (lumPic.isNotEmpty()) getString(R.string.def_lum_pic) + lumPic + ")" else "") + "  -  " else ""
            val extra = StringBuilder()
            if (o.has("hauteur_mesuree_m") && !o.isNull("hauteur_mesuree_m"))
                extra.append(getString(R.string.def_hauteur, o.optDouble("hauteur_mesuree_m")))
            if (o.has("hauteur_tablier_m") && !o.isNull("hauteur_tablier_m"))
                extra.append(getString(R.string.def_tablier, o.optDouble("hauteur_tablier_m")))
            if (o.optBoolean("mode_securite_conservateur", false))
                extra.append(getString(R.string.def_mode_securite))
            if (n >= 0) prefixeLum + getString(R.string.def_reco_n, n, extra)
            else prefixeLum + getString(R.string.def_reco_extra, extra)
        } catch (e: Exception) {
            getString(R.string.def_reco_generee)
        }
    }

    private fun genererBatiment() {
        info.text = getString(R.string.def_mesure_batiment)
        lifecycleScope.launch {
            val resultat = withContext(Dispatchers.IO) {
                try {
                    val footprint = JSONArray()
                    for (s in sommets) {
                        footprint.put(JSONArray().put(s.latitude).put(s.longitude))
                    }
                    val corps = JSONObject().apply {
                        put("footprint", footprint)
                        put("main_facade_index", 0)
                        // PAS de hauteur_m : le serveur la mesure au LiDAR
                    }
                    val url = URL("$baseUrl/api/generer_batiment")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 15000
                        readTimeout = 90000
                    }
                    conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                    if (conn.responseCode != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: "code ${conn.responseCode}"
                        return@withContext getString(R.string.def_erreur, err)
                    }
                    val recap = conn.getHeaderField("X-Cine-Recap") ?: "{}"
                    val dest = File(cacheDir, "batiment_reco.kmz")
                    conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                    "OK:${dest.absolutePath}:$recap"
                } catch (e: Exception) {
                    getString(R.string.def_erreur, e.message)
                }
            }

            if (resultat.startsWith("OK:")) {
                val parts = resultat.removePrefix("OK:").split(":", limit = 2)
                val chemin = parts[0]
                val recap = if (parts.size > 1) parts[1] else "{}"
                info.text = messageRecap(recap)
                // renvoyer le KMZ à l'appelant (MainActivity le chargera dans LecteurMissionKmz)
                estimerEtRetourner(chemin, recap)
            } else {
                info.text = resultat
                Toast.makeText(this@DefinitionSujetActivity, resultat, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun genererPont() {
        info.text = getString(R.string.def_mesure_pont)
        lifecycleScope.launch {
            val resultat = withContext(Dispatchers.IO) {
                try {
                    val axe = JSONArray()
                    for (s in sommets) {
                        axe.put(JSONArray().put(s.latitude).put(s.longitude))
                    }
                    val corps = JSONObject().apply {
                        put("axe", axe)   // ligne centrale ; largeur/hauteur gérées serveur
                    }
                    val url = URL("$baseUrl/api/generer_pont")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 15000
                        readTimeout = 90000
                    }
                    conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                    if (conn.responseCode != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: "code ${conn.responseCode}"
                        return@withContext getString(R.string.def_erreur, err)
                    }
                    val recap = conn.getHeaderField("X-Cine-Recap") ?: "{}"
                    val dest = File(cacheDir, "pont_reco.kmz")
                    conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                    "OK:${dest.absolutePath}:$recap"
                } catch (e: Exception) {
                    getString(R.string.def_erreur, e.message)
                }
            }

            if (resultat.startsWith("OK:")) {
                val parts = resultat.removePrefix("OK:").split(":", limit = 2)
                val chemin = parts[0]
                val recap = if (parts.size > 1) parts[1] else "{}"
                estimerEtRetourner(chemin, recap)
            } else {
                info.text = resultat
                Toast.makeText(this@DefinitionSujetActivity, resultat, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun genererLineaire(typeLin: String) {
        info.text = getString(R.string.def_mesure_lineaire)
        lifecycleScope.launch {
            val resultat = withContext(Dispatchers.IO) {
                try {
                    val axe = JSONArray()
                    for (s in sommets) {
                        axe.put(JSONArray().put(s.latitude).put(s.longitude))
                    }
                    val corps = JSONObject().apply {
                        put("axe", axe)
                        put("type", typeLin)   // "LINE" ou "WALL" — hauteur mesurée serveur
                    }
                    val url = URL("$baseUrl/api/generer_lineaire")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 15000
                        readTimeout = 90000
                    }
                    conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                    if (conn.responseCode != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: "code ${conn.responseCode}"
                        return@withContext getString(R.string.def_erreur, err)
                    }
                    val recap = conn.getHeaderField("X-Cine-Recap") ?: "{}"
                    val dest = File(cacheDir, "lineaire_reco.kmz")
                    conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                    "OK:${dest.absolutePath}:$recap"
                } catch (e: Exception) {
                    getString(R.string.def_erreur, e.message)
                }
            }

            if (resultat.startsWith("OK:")) {
                val parts = resultat.removePrefix("OK:").split(":", limit = 2)
                val chemin = parts[0]
                val recap = if (parts.size > 1) parts[1] else "{}"
                estimerEtRetourner(chemin, recap)
            } else {
                info.text = resultat
                Toast.makeText(this@DefinitionSujetActivity, resultat, Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun rechercherLieu(q: String) {
        info.text = getString(R.string.def_recherche_encours, q)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val enc = java.net.URLEncoder.encode(q, "UTF-8")
                    val url = URL("$baseUrl/api/geocode?q=$enc")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000
                    }
                    if (conn.responseCode != 200) return@withContext null
                    val txt = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    // /api/geocode renvoie {"resultats": [ {lat,lon,nom,label}, ... ]}
                    val obj = JSONObject(txt)
                    val arr = obj.optJSONArray("resultats") ?: JSONArray()
                    if (arr.length() == 0) return@withContext null
                    val r0 = arr.getJSONObject(0)
                    Triple(r0.getDouble("lat"), r0.getDouble("lon"),
                           r0.optString("nom", r0.optString("label", q)))
                } catch (e: Exception) { null }
            }
            if (res != null) {
                val (lat, lon, label) = res
                carte.controller.animateTo(GeoPoint(lat, lon))
                carte.controller.setZoom(17.0)
                info.text = getString(R.string.def_centre, label)
            } else {
                info.text = getString(R.string.def_lieu_introuvable)
                Toast.makeText(this@DefinitionSujetActivity,
                    getString(R.string.def_introuvable_q, q), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); carte.onResume() }
    override fun onPause() { super.onPause(); carte.onPause() }

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

