package ca.cineflight.stage

import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.control.LecteurMissionKmz
import ca.cineflight.stage.control.PiloteDrone
import ca.cineflight.stage.control.PontDjiSimuleCockpit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Carte PLEIN ECRAN de la mission, sur fond satellite Esri.
 *  - trace les waypoints du KMZ (ligne) ;
 *  - lance la SIMULATION (pont simule dedie, le vrai drone ne bouge jamais) ;
 *  - anime un marqueur "drone" qui parcourt la trajectoire en temps reel.
 *
 * Recoit par intent : "kmz_path" (chemin du fichier KMZ a voler).
 */
class CarteMissionActivity : AppCompatActivity() {

    private lateinit var carte: MapView
    private var vueSatellite = true
    private var marqueurDrone: Marker? = null
    private lateinit var txtEtat: TextView
    private lateinit var txtGps: TextView
    private lateinit var txtChrono: TextView

    private var pontSim: PontDjiSimuleCockpit? = null
    private var piloteSim: PiloteDrone? = null
    private var lecteur: LecteurMissionKmz? = null
    private var partagerRapportRef: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        val racine = FrameLayout(this)
        carte = MapView(this).apply {
            setTileSource(sourceEsriSatellite())
            setMultiTouchControls(true)
            controller.setZoom(17.0)
        }
        racine.addView(carte, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // une seule ligne en haut : etat mission a GAUCHE, position GPS a DROITE
        val barreHaut = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC0D1B2A.toInt())
            setPadding(24, 20, 24, 20)
        }
        txtEtat = TextView(this).apply {
            text = getString(R.string.cm_chargement)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        txtGps = TextView(this).apply {
            text = ""
            setTextColor(0xFF80CBC4.toInt())               // turquoise (distinct de l'etat)
            textSize = 13f
            gravity = android.view.Gravity.END
        }
        txtChrono = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFFFFD54F.toInt())               // jaune (distinct)
            textSize = 14f
            gravity = android.view.Gravity.END
        }
        barreHaut.addView(txtEtat, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        barreHaut.addView(txtGps, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barreHaut.addView(txtChrono, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f))
        val haut = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        haut.gravity = android.view.Gravity.TOP
        racine.addView(barreHaut, haut)

        // charge les waypoints du KMZ
        val kmzPath = intent.getStringExtra("kmz_path")
        val fichier = if (kmzPath != null) java.io.File(kmzPath) else null
        val points = if (fichier != null && fichier.exists())
            LecteurMissionKmz.pointsKmz(fichier) else emptyList()
        // DIAGNOSTIC : qu'est-ce que l'app charge vraiment ?
        android.util.Log.i("CineFlightMission",
            "KMZ charge: path=$kmzPath existe=${fichier?.exists()} taille=${fichier?.length()}o nb_points=${points.size}")

        if (points.size < 2) {
            txtEtat.text = getString(R.string.cm_illisible)
        } else {
            // 1) trace de la trajectoire (ligne verte). PAS tous les waypoints
            //    (les orbites en ont des dizaines -> carte illisible). On marque
            //    seulement le DEPART (= point de decollage, debut du trace).
            val ligne = Polyline().apply {
                setPoints(points.map { GeoPoint(it.first, it.second) })
                outlinePaint.color = 0xFF00E676.toInt()
                outlinePaint.strokeWidth = 8f
            }
            carte.overlays.add(ligne)
            // marqueur DEPART (= decollage, et retour en fin de mission)
            carte.overlays.add(Marker(carte).apply {
                position = GeoPoint(points.first().first, points.first().second)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = getString(R.string.cm_depart_retour)
            })
            // 2) marqueur drone (depart = 1er point) — rendu clignotant plus bas
            val md = Marker(carte).apply {
                position = GeoPoint(points.first().first, points.first().second)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Drone"
                icon = pointDroneClignotant(0xFFFFEB3B.toInt())   // jaune vif
            }
            carte.overlays.add(md)
            marqueurDrone = md
            // cadre sur la mission. CAS PARTICULIER : un PLAN DE HAUT vertical ou
            // en rotation sur place a TOUS ses points au meme endroit (aucune
            // etendue horizontale). Une BoundingBox de taille nulle fait degager
            // osmdroid jusqu'au monde entier -> on centre + zoom fixe a la place.
            val latsM = points.map { it.first }
            val lonsM = points.map { it.second }
            val latSpan = latsM.maxOrNull()!! - latsM.minOrNull()!!
            val lonSpan = lonsM.maxOrNull()!! - lonsM.minOrNull()!!
            val centreMission = GeoPoint(latsM.average(), lonsM.average())
            val surPlace = latSpan < 0.0004 && lonSpan < 0.0004   // ~ < 40 m d'etendue
            carte.post {
                try {
                    if (surPlace) {
                        carte.controller.setZoom(18.5)
                        carte.controller.setCenter(centreMission)
                    } else {
                        val box = org.osmdroid.util.BoundingBox.fromGeoPoints(
                            points.map { GeoPoint(it.first, it.second) })
                        carte.zoomToBoundingBox(box.increaseByScale(1.4f), false)
                    }
                } catch (_: Exception) {
                    carte.controller.setZoom(18.0); carte.controller.setCenter(centreMission)
                }
            }
            if (surPlace) {
                android.widget.Toast.makeText(this,
                    getString(R.string.cm_plan_haut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            carte.invalidate()

            // 3) lance la simulation (pont simule dedie : le vrai drone ne bouge pas)
            if (fichier != null) lancerSimulation(fichier, points)
        }

        // barre du bas : vue + arreter + fermer
        val barre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnVue = Button(this).apply {
            text = getString(R.string.map_vue_routiere); isAllCaps = false
            setOnClickListener {
                vueSatellite = !vueSatellite
                if (vueSatellite) { carte.setTileSource(sourceEsriSatellite()); text = getString(R.string.map_vue_routiere) }
                else { carte.setTileSource(TileSourceFactory.MAPNIK); text = getString(R.string.map_vue_satellite) }
                carte.invalidate()
            }
        }
        val btnStop = Button(this).apply {
            text = getString(R.string.cm_arreter); isAllCaps = false
            setOnClickListener { lecteur?.arreter(); piloteSim?.arreter() }
        }
        val btnRapport = Button(this).apply {
            text = getString(R.string.cm_rapport_btn); isAllCaps = false
            setOnClickListener { partagerRapportRef?.invoke() }
        }
        val btnFermer = Button(this).apply {
            text = getString(R.string.map_fermer); isAllCaps = false
            setOnClickListener { lecteur?.arreter(); piloteSim?.arreter(); finish() }
        }
        barre.addView(btnVue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        barre.addView(btnStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barre.addView(btnRapport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barre.addView(btnFermer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        bp.gravity = android.view.Gravity.BOTTOM
        racine.addView(barre, bp)

        setContentView(racine)
    }

    private fun lancerSimulation(fichier: java.io.File, points: List<Pair<Double, Double>>) {
        val depart = points.first()
        val ps = PontDjiSimuleCockpit(depart.first, depart.second)
        // plafond de garde a 8 m/s = vraie vitesse max de mission (transit).
        // Le cockpit manuel garde son plafond prudent par defaut (2 m/s).
        val pp = PiloteDrone(this, ps, vMaxGardeMps = 8.0f)
        pontSim = ps; piloteSim = pp
        pp.demarrer(lifecycleScope)
        pp.decoller { }
        // indice du waypoint courant (alimente par onProgression, lu par la boucle)
        var wpCourant = 0
        var wpTotal = points.size
        // Mesures par waypoint (pour le rapport detaille) :
        //  - erreurMin   : distance horizontale minimale atteinte (m)
        //  - altAtteinte : altitude du drone au moment de la plus proche approche (m)
        val erreurMin = DoubleArray(points.size) { Double.MAX_VALUE }
        val altAtteinte = DoubleArray(points.size) { Double.NaN }
        var rapportAffiche = false
        var distanceParcourueM = 0.0
        val tMissionDebut = System.currentTimeMillis()

        fun genererRapport(): java.io.File? {
            val wps = lecteur?.lesWaypoints() ?: emptyList()
            val n = points.size
            // --- CSV detaille (une ligne par waypoint) ---
            // Separateur ';' + point decimal (locale US) : ouvrable directement dans
            // Excel en region francophone (ou la virgule est le separateur decimal).
            val L = java.util.Locale.US
            fun f(x: Double, d: Int) = if (x.isNaN()) "" else "%.${d}f".format(L, x)
            val csv = StringBuilder()
            csv.append("no;type;lat;lon;alt_cible_m;alt_atteinte_m;err_alt_m;vitesse_mps;err_horiz_m;statut\n")
            for (k in 0 until n) {
                val type = wps.getOrNull(k)?.type ?: ""
                val altCible = wps.getOrNull(k)?.altAgl ?: Double.NaN
                val vit = (wps.getOrNull(k)?.vitesse ?: 0f).toDouble()
                val altAtt = altAtteinte[k]
                val errAlt = if (!altCible.isNaN() && !altAtt.isNaN()) kotlin.math.abs(altCible - altAtt) else Double.NaN
                val errH = erreurMin[k].let { if (it == Double.MAX_VALUE) Double.NaN else it }
                val statut = when {
                    errH.isNaN() -> "NON_MESURE"
                    errH <= 3.0 -> "OK"
                    errH <= 10.0 -> "ECART"
                    else -> "RATE"
                }
                csv.append("$k;$type;${f(points[k].first,7)};${f(points[k].second,7)};")
                csv.append("${f(altCible,1)};${f(altAtt,1)};${f(errAlt,1)};${f(vit,1)};${f(errH,1)};$statut\n")
            }
            // --- synthese ---
            val valides = (0 until n).filter { erreurMin[it] != Double.MAX_VALUE }
            val errs = valides.map { erreurMin[it] }
            val emoy = if (errs.isNotEmpty()) errs.average() else 0.0
            val emax = errs.maxOrNull() ?: 0.0
            val emed = if (errs.isNotEmpty()) errs.sorted()[errs.size / 2] else 0.0
            val nOk = errs.count { it <= 3.0 }
            val pct = if (errs.isNotEmpty()) 100.0 * nOk / errs.size else 0.0
            var pireIdx = -1; var pireVal = 0.0
            for (k in 0 until n) if (erreurMin[k] != Double.MAX_VALUE && erreurMin[k] > pireVal) { pireVal = erreurMin[k]; pireIdx = k }
            // erreurs verticales
            val errsAlt = (0 until n).mapNotNull { k ->
                val ac = wps.getOrNull(k)?.altAgl; val aa = altAtteinte[k]
                if (ac != null && !ac.isNaN() && !aa.isNaN()) kotlin.math.abs(ac - aa) else null
            }
            val altMoy = if (errsAlt.isNotEmpty()) errsAlt.average() else 0.0
            val altMax = errsAlt.maxOrNull() ?: 0.0
            // par type
            val parType = LinkedHashMap<String, MutableList<Double>>()
            for (k in valides) {
                val t = wps.getOrNull(k)?.type ?: "?"
                parType.getOrPut(t) { mutableListOf() }.add(erreurMin[k])
            }
            val dureeS = ((System.currentTimeMillis() - tMissionDebut) / 1000L)
            val txt = StringBuilder()
            txt.append(getString(R.string.cm_rep_titre))
            txt.append("=====================================\n")
            txt.append(getString(R.string.cm_rep_date, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CANADA_FRENCH).format(java.util.Date())))
            txt.append(getString(R.string.cm_rep_wp, n))
            txt.append(getString(R.string.cm_rep_duree, dureeS / 60, dureeS % 60))
            txt.append(getString(R.string.cm_rep_distance, "%.0f".format(distanceParcourueM)))
            txt.append(getString(R.string.cm_rep_horiz))
            txt.append(getString(R.string.cm_rep_3m, nOk, errs.size, "%.0f".format(pct)))
            txt.append(getString(R.string.cm_rep_moyenne, "%.1f".format(emoy), "%.1f".format(emed), "%.1f".format(emax)))
            txt.append(if (pireIdx >= 0) getString(R.string.cm_rep_pire, pireIdx, wps.getOrNull(pireIdx)?.type ?: "?") else "\n")
            txt.append(getString(R.string.cm_rep_vert))
            txt.append(getString(R.string.cm_rep_alt, "%.1f".format(altMoy), "%.1f".format(altMax)))
            txt.append(getString(R.string.cm_rep_partype))
            for ((t, lst) in parType) {
                txt.append(getString(R.string.cm_rep_type_line, t.padEnd(14), lst.size, "%.1f".format(lst.average()), "%.1f".format(lst.maxOrNull() ?: 0.0)))
            }
            txt.append(getString(R.string.cm_rep_verdict, (if (pct >= 90) getString(R.string.cm_rep_valide) else getString(R.string.cm_rep_verifier)), "%.0f".format(pct)))
            txt.append(getString(R.string.cm_rep_note1))
            txt.append(getString(R.string.cm_rep_note2))
            txt.append(getString(R.string.cm_rep_note3))
            // --- ecriture fichiers ---
            return try {
                val dossier = getExternalFilesDir(null) ?: filesDir
                val fCsv = java.io.File(dossier, "rapport_mission.csv")
                val fTxt = java.io.File(dossier, "rapport_mission.txt")
                fCsv.writeText(csv.toString())
                fTxt.writeText(txt.toString())
                android.util.Log.i("CineFlightMission", "Rapport ecrit: ${fTxt.absolutePath}")
                fTxt
            } catch (e: Exception) {
                android.util.Log.e("CineFlightMission", "ecriture rapport", e); null
            }
        }

        fun partagerRapport() {
            val dossier = getExternalFilesDir(null) ?: filesDir
            val fCsv = java.io.File(dossier, "rapport_mission.csv")
            val fTxt = java.io.File(dossier, "rapport_mission.txt")
            if (!fTxt.exists()) genererRapport()
            try {
                val uris = ArrayList<android.net.Uri>()
                for (f in listOf(fTxt, fCsv)) if (f.exists())
                    uris.add(androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", f))
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/*"
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.cm_rapport_sujet))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // ClipData : permet au sharesheet de lire les metadonnees (apercu)
                    if (uris.isNotEmpty()) {
                        val cd = android.content.ClipData.newUri(contentResolver, "rapport", uris[0])
                        for (i in 1 until uris.size) cd.addItem(android.content.ClipData.Item(uris[i]))
                        clipData = cd
                    }
                }
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.cm_partager_rapport)))
            } catch (e: Exception) {
                runOnUiThread { txtEtat.text = getString(R.string.cm_rapport_path, fTxt.absolutePath) }
            }
        }
        partagerRapportRef = { partagerRapport() }

        fun afficherRapport() {
            if (rapportAffiche) return
            rapportAffiche = true
            val valides = erreurMin.filter { it < Double.MAX_VALUE }
            val nOk = valides.count { it <= 3.0 }
            var pireIdx = -1; var pireVal = 0.0
            for (k in erreurMin.indices) {
                if (erreurMin[k] < Double.MAX_VALUE && erreurMin[k] > pireVal) {
                    pireVal = erreurMin[k]; pireIdx = k
                }
            }
            genererRapport()   // ecrit CSV + texte
            runOnUiThread {
                txtEtat.text = "Termine · ${nOk}/${valides.size} <3m · pire wp$pireIdx ${"%.0f".format(pireVal)}m · 📄 Rapport pret"
            }
        }
        val lect = LecteurMissionKmz(pp, ps,
            onProgression = { i, n, _ -> wpCourant = i; wpTotal = n },
            onTermine = { afficherRapport() })   // RAPPORT GARANTI a la fin
        lecteur = lect
        val n = lect.charger(fichier)
        if (n >= 2) {
            // chrono : demarre quand la mission part vraiment (apres le decollage)
            var chronoDebutMs = 0L
            lifecycleScope.launch {
                delay(2000)
                chronoDebutMs = System.currentTimeMillis()
                lect.lancer(lifecycleScope)
            }
            // boucle d'animation du marqueur drone : suit la position simulee + clignote
            // + trace EN ROUGE le chemin reellement parcouru
            val traceReelle = Polyline().apply {
                outlinePaint.color = 0xFFFF1744.toInt()   // rouge
                outlinePaint.strokeWidth = 5f
            }
            carte.overlays.add(traceReelle)
            fun distM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
                val mLat = 111_320.0
                val mLon = 111_320.0 * kotlin.math.cos(Math.toRadians(aLat))
                return kotlin.math.hypot((bLat - aLat) * mLat, (bLon - aLon) * mLon)
            }
            lifecycleScope.launch(Dispatchers.Main) {
                var clignote = false
                var tick = 0
                var derLat = Double.NaN; var derLon = Double.NaN
                while (isActive) {
                    val la = ps.latitudeDrone(); val lo = ps.longitudeDrone()
                    if (!la.isNaN() && !lo.isNaN()) {
                        marqueurDrone?.position = GeoPoint(la, lo)
                        if (!ps.capDroneDeg().isNaN()) marqueurDrone?.rotation = -ps.capDroneDeg()
                        traceReelle.addPoint(GeoPoint(la, lo))
                        // distance reellement parcourue (cumul)
                        if (!derLat.isNaN()) distanceParcourueM += distM(derLat, derLon, la, lo)
                        derLat = la; derLon = lo
                        // met a jour l'erreur min + l'altitude atteinte pour chaque waypoint
                        val altCourante = ps.altitudeDrone()
                        for (k in points.indices) {
                            val d = distM(la, lo, points[k].first, points[k].second)
                            if (d < erreurMin[k]) {
                                erreurMin[k] = d
                                altAtteinte[k] = altCourante   // altitude au plus proche du wp
                            }
                        }
                        // clignotement (1 frame sur 4 ~ 400ms)
                        tick++
                        if (tick % 4 == 0) {
                            clignote = !clignote
                            marqueurDrone?.icon = if (clignote)
                                pointDroneClignotant(0xFFFFEB3B.toInt(), 18)
                            else
                                pointDroneClignotant(0xFFFF6D00.toInt(), 11)
                        }
                        // position GPS du drone -> bandeau de DROITE (en continu)
                        txtGps.text = "GPS ${"%.5f".format(la)}, ${"%.5f".format(lo)} · ${ps.altitudeDrone().toInt()}m"
                        // chrono : temps ecoule depuis le depart de la mission
                        if (chronoDebutMs > 0L && lecteur?.actif == true) {
                            val s = ((System.currentTimeMillis() - chronoDebutMs) / 1000L)
                            txtChrono.text = "%02d:%02d".format(s / 60, s % 60)
                        }
                        // etat mission -> bandeau de GAUCHE (en direct pendant le vol)
                        if (lecteur?.actif == true) {
                            val passes = (0 until wpCourant.coerceIn(0, erreurMin.size))
                                .map { erreurMin[it] }.filter { it < Double.MAX_VALUE }
                            val pire = passes.maxOrNull() ?: 0.0
                            txtEtat.text = "wp $wpCourant/$wpTotal · pire ${"%.1f".format(pire)}m"
                        } else if (lecteur != null && wpCourant > 0 && !rapportAffiche) {
                            // FILET DE SECURITE : mission plus active -> on affiche le
                            // rapport ici aussi (au cas ou onTermine n'aboutirait pas).
                            afficherRapport()
                        }
                        carte.invalidate()
                    }
                    delay(100)
                }
            }
        } else {
            txtEtat.text = "Mission vide ($n waypoints)."
        }
    }

    override fun onResume() { super.onResume(); carte.onResume() }
    override fun onPause() { super.onPause(); carte.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        lecteur?.arreter(); piloteSim?.arreter()
    }

    /** Cree une icone ronde (point) pour le drone, couleur + rayon en px donnes. */
    private fun pointDroneClignotant(couleur: Int, rayonDp: Int = 14): android.graphics.drawable.Drawable {
        val d = (resources.displayMetrics.density)
        val r = (rayonDp * d)
        val taille = (r * 2 + 6).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(taille, taille, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val centre = taille / 2f
        // halo
        val halo = android.graphics.Paint().apply {
            color = couleur; alpha = 80; isAntiAlias = true
        }
        canvas.drawCircle(centre, centre, r, halo)
        // coeur plein
        val plein = android.graphics.Paint().apply {
            color = couleur; isAntiAlias = true
        }
        canvas.drawCircle(centre, centre, r * 0.6f, plein)
        // contour blanc
        val contour = android.graphics.Paint().apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f; isAntiAlias = true
        }
        canvas.drawCircle(centre, centre, r * 0.6f, contour)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
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

