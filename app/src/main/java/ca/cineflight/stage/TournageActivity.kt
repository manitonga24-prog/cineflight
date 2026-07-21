package ca.cineflight.stage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * TournageActivity — l'ecran "realisateur".
 *
 * Apres la reconnaissance (sujet T localise), l'utilisateur exprime SON INTENTION
 * et le TEMPS de tournage. Le serveur compose la sequence video (le realisateur
 * choisit et dimensionne les plans selon le sujet + l'environnement) et renvoie
 * un zip de KMZ (un par plan). L'ecran affiche la sequence et enregistre le zip.
 *
 *   T (extras) -> intention + temps -> POST /api/generer_tournage -> zip KMZ
 *
 * Recoit en extras : sujet_lat, sujet_lon, sujet_alt, et optionnellement
 * hauteur_sujet_m, type_sujet, azimut_principal_deg.
 */
class TournageActivity : AppCompatActivity() {

    private val BASE_URL = "https://cineflight.ca"

    private var sujetLat = 0.0
    private var sujetLon = 0.0
    private var sujetAlt = 0.0
    private var hauteurSujet = 0.0
    private var typeSujet = "ponctuel"
    private var azimutPrincipal = 0.0

    private var intentionChoisie = "valoriser"
    private var tempsChoisi = "standard"

    private lateinit var statut: TextView
    private lateinit var blocConditions: LinearLayout
    private lateinit var resultat: TextView
    private lateinit var btnCreer: Button
    private lateinit var progres: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sujetLat = intent.getDoubleExtra("sujet_lat", 0.0)
        sujetLon = intent.getDoubleExtra("sujet_lon", 0.0)
        sujetAlt = intent.getDoubleExtra("sujet_alt", 0.0)
        hauteurSujet = intent.getDoubleExtra("hauteur_sujet_m", 0.0)
        typeSujet = intent.getStringExtra("type_sujet") ?: "ponctuel"
        azimutPrincipal = intent.getDoubleExtra("azimut_principal_deg", 0.0)

        val racine = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        racine.addView(col)

        // --- en-tete : sujet localise ---
        col.addView(TextView(this).apply {
            text = getString(R.string.tr_sujet_localise)
            textSize = 22f
            setTextColor(0xFF1C1C1E.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.tr_latlon).format(sujetLat, sujetLon)
            textSize = 13f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, 0, 0, dp(12))
        })

        // --- bloc conditions de vol (meteo) : securite d'abord, rempli en arriere-plan ---
        blocConditions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF2F2F7.toInt())
        }
        blocConditions.addView(TextView(this).apply {
            text = getString(R.string.tr_conditions_load)
            textSize = 13f
            setTextColor(0xFF8E8E93.toInt())
        })
        col.addView(blocConditions)
        col.addView(espace(dp(18)))
        chargerConditions()

        // --- bloc 1 : intention ---
        col.addView(titre(getString(R.string.tr_q_intention)))
        val grpIntention = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val intentions = listOf(
            "valoriser" to getString(R.string.tr_int_valoriser),
            "environnement" to getString(R.string.tr_int_environnement),
            "spectaculaire" to getString(R.string.tr_int_spectaculaire)
        )
        intentions.forEachIndexed { i, (cle, label) ->
            grpIntention.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.tr_int_prefix, label)
                textSize = 16f
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isChecked = (i == 0)
                setOnClickListener { intentionChoisie = cle }
            })
        }
        col.addView(grpIntention)
        col.addView(espace(dp(18)))

        // --- bloc 2 : temps ---
        col.addView(titre(getString(R.string.tr_q_temps)))
        val grpTemps = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val temps = listOf(
            "rapide" to getString(R.string.tr_temps_rapide),
            "standard" to getString(R.string.tr_temps_standard),
            "complet" to getString(R.string.tr_temps_complet),
            "premium" to getString(R.string.tr_temps_premium)
        )
        temps.forEachIndexed { i, (cle, label) ->
            grpTemps.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 16f
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isChecked = (cle == "standard")   // standard par defaut
                setOnClickListener { tempsChoisi = cle }
            })
        }
        col.addView(grpTemps)
        col.addView(espace(dp(24)))

        // --- bouton creer ---
        btnCreer = Button(this).apply {
            text = getString(R.string.tr_creer)
            textSize = 17f
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { lancerCreation() }
        }
        col.addView(btnCreer)

        progres = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        col.addView(progres)

        statut = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(statut)

        resultat = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF1C1C1E.toInt())
            setPadding(0, dp(16), 0, 0)
            setTextIsSelectable(true)
        }
        col.addView(resultat)

        setContentView(racine)
        title = getString(R.string.tr_creer)
    }

    /** Recupere et affiche les conditions de vol (meteo) en arriere-plan. */
    private fun chargerConditions() {
        thread {
            val c = ca.cineflight.stage.control.ConditionsVol.recuperer(sujetLat, sujetLon, MainActivity.codeMeteoDrone, MainActivity.tempMinDrone, MainActivity.tempMaxDrone)
            runOnUiThread { afficherConditions(c) }
        }
    }

    private fun afficherConditions(c: ca.cineflight.stage.control.ConditionsVol.Conditions) {
        val CV = ca.cineflight.stage.control.ConditionsVol
        blocConditions.removeAllViews()

        // bandeau verdict global, colore
        blocConditions.addView(TextView(this).apply {
            text = "${CV.pastille(c.global)}  ${c.verdict}"
            textSize = 15f
            setTextColor(CV.couleur(c.global))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        // un facteur par ligne
        for (f in listOf(c.vent, c.pluie, c.lumiere, c.temperature)) {
            blocConditions.addView(TextView(this).apply {
                text = "${CV.pastille(f.feu)}  ${f.texte}"
                textSize = 14f
                setTextColor(0xFF1C1C1E.toInt())
                setPadding(0, dp(2), 0, dp(2))
            })
        }

        // rappel honnete : indication, pas garantie
        blocConditions.addView(TextView(this).apply {
            text = getString(R.string.tr_indication)
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun lancerCreation() {
        btnCreer.isEnabled = false
        progres.visibility = View.VISIBLE
        statut.text = getString(R.string.tr_composition)
        resultat.text = ""

        thread {
            try {
                val body = JSONObject().apply {
                    put("sujet_lat", sujetLat)
                    put("sujet_lon", sujetLon)
                    if (sujetAlt != 0.0) put("sujet_alt_m", sujetAlt)
                    if (hauteurSujet != 0.0) put("hauteur_sujet_m", hauteurSujet)
                    put("type_sujet", typeSujet)
                    put("intention", intentionChoisie)
                    put("temps", tempsChoisi)
                    put("azimut_principal_deg", azimutPrincipal)
                    put("drone", MainActivity.codeMissionDrone)
                    put("profil", MainActivity.codeMissionDrone)
                }
                val (code, octets, entetes) = postTelecharger(
                    "$BASE_URL/api/generer_tournage", body.toString())

                if (code != 200) {
                    val msg = String(octets).take(400)
                    runOnUiThread { afficherEchec(msg) }
                    return@thread
                }

                // enregistrer le zip dans Downloads/CineFlight
                val dossier = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CineFlight")
                if (!dossier.exists()) dossier.mkdirs()
                val nomZip = "tournage_${System.currentTimeMillis()}.zip"
                val fichier = File(dossier, nomZip)
                FileOutputStream(fichier).use { it.write(octets) }

                val nbPlans = entetes["X-CineFlight-Plans"] ?: "?"
                val dureeMin = entetes["X-CineFlight-Duree-Min"] ?: "?"

                runOnUiThread {
                    afficherSucces(fichier.absolutePath, nbPlans, dureeMin, octets.size)
                }
            } catch (e: Exception) {
                runOnUiThread { afficherEchec(e.message ?: getString(R.string.tr_erreur_inconnue)) }
            }
        }
    }

    private fun afficherSucces(chemin: String, nbPlans: String, dureeMin: String, taille: Int) {
        progres.visibility = View.GONE
        btnCreer.isEnabled = true
        statut.text = getString(R.string.tr_pret)
        val sb = StringBuilder()
        sb.append(getString(R.string.tr_res_titre))
        sb.append(getString(R.string.tr_res_plans, nbPlans))
        sb.append(getString(R.string.tr_res_duree, dureeMin))
        sb.append(getString(R.string.tr_res_fichier, taille / 1024))
        sb.append(getString(R.string.tr_res_chemin, chemin))
        sb.append(getString(R.string.tr_res_zip))
        resultat.text = sb.toString()

        // L'utilisateur vient de voir une mission generee : bon moment pour demander
        // (une seule fois) le consentement a l'envoi des donnees d'apprentissage.
        ca.cineflight.stage.control.DialogueConsentement.demanderSiNecessaire(this)
    }

    private fun afficherEchec(msg: String) {
        progres.visibility = View.GONE
        btnCreer.isEnabled = true
        statut.text = getString(R.string.tr_echec)
        resultat.text = msg
    }

    // --- reseau : POST JSON -> telecharge le corps binaire (zip) ---
    private fun postTelecharger(urlStr: String, json: String): Triple<Int, ByteArray, Map<String, String>> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 120000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val flux = if (code == 200) conn.inputStream else conn.errorStream
        val octets = flux?.readBytes() ?: ByteArray(0)
        val entetes = mapOf(
            "X-CineFlight-Plans" to (conn.getHeaderField("X-CineFlight-Plans") ?: ""),
            "X-CineFlight-Duree-Min" to (conn.getHeaderField("X-CineFlight-Duree-Min") ?: "")
        )
        conn.disconnect()
        return Triple(code, octets, entetes)
    }

    private fun titre(txt: String) = TextView(this).apply {
        text = txt
        textSize = 17f
        setTextColor(0xFF1C1C1E.toInt())
        setPadding(0, 0, 0, dp(6))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun espace(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

