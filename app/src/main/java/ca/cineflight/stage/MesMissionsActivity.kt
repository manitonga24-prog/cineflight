package ca.cineflight.stage

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.cine.AnalyseurLieuServeur
import ca.cineflight.stage.cine.CineAuth
import ca.cineflight.stage.cine.MissionResumee
import ca.cineflight.stage.control.ExecuteurMissionWpml
import kotlinx.coroutines.launch
import java.io.File

/**
 * MesMissionsActivity — liste les missions NOMMEES preparees par l'utilisateur
 * sur CineFlight Explorer (web), et permet de les lancer sur le drone.
 *
 * Flux :
 *   1. CineAuth.token() -> AnalyseurLieu.listerMesMissions(token)
 *   2. affiche la liste (nom, lieu, style, duree)
 *   3. clic sur une mission -> telechargerMissionParId -> KMZ local
 *   4. confirmation pilote (dialog securite)
 *   5. ExecuteurMissionWpml.executer(kmz, ...) -> vol autonome
 *
 * SECURITE : confirmation explicite avant tout vol. Le KMZ est une mission
 * waypoint complete (deja validee cote serveur : securite + budget). Le
 * pilote garde le controle (RTH, arret d'urgence) pendant l'execution.
 */
class MesMissionsActivity : AppCompatActivity() {

    private val BLEU = Color.parseColor("#4FC3F7")
    private val VERT = Color.parseColor("#3A7D5A")
    private val BG = Color.parseColor("#0A1220")
    private val CARTE = Color.parseColor("#141A24")
    private val BORDURE = Color.parseColor("#2A3340")
    private val TEXTE = Color.parseColor("#E8EEF5")
    private val TEXTE_DOUX = Color.parseColor("#8A97A8")

    // client HTTP du serveur Explorer (implementation concrete d'AnalyseurLieu)
    private val analyseur = AnalyseurLieuServeur()

    private lateinit var conteneur: LinearLayout
    private lateinit var statut: TextView
    private lateinit var spinner: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // garde-fou : il faut etre connecte
        if (!CineAuth.estConnecte(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish(); return
        }

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val titre = TextView(this).apply {
            text = getString(R.string.mmi_titre)
            setTextColor(BLEU); textSize = 22f
            setPadding(0, 0, 0, dp(4))
        }
        val user = CineAuth.username(this) ?: ""
        val sousTitre = TextView(this).apply {
            text = getString(R.string.mmi_sous_titre, user)
            setTextColor(TEXTE_DOUX); textSize = 13f
            setPadding(0, 0, 0, dp(14))
        }

        statut = TextView(this).apply {
            text = getString(R.string.mmi_chargement)
            setTextColor(TEXTE_DOUX); textSize = 14f
        }
        spinner = ProgressBar(this).apply { visibility = View.VISIBLE }

        conteneur = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply { addView(conteneur) }

        racine.addView(titre)
        racine.addView(sousTitre)
        racine.addView(spinner)
        racine.addView(statut)
        racine.addView(scroll)
        setContentView(racine)

        charger()
    }

    private fun charger() {
        val token = CineAuth.token(this) ?: run {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        spinner.visibility = View.VISIBLE
        statut.text = getString(R.string.mmi_chargement_missions)
        lifecycleScope.launch {
            val liste = analyseur.listerMesMissions(token)
            spinner.visibility = View.GONE
            conteneur.removeAllViews()
            if (liste == null) {
                statut.text = getString(R.string.mmi_serveur_ko) +
                    getString(R.string.mmi_verif_internet)
                return@launch
            }
            if (liste.isEmpty()) {
                statut.text = getString(R.string.mmi_vide1) +
                    getString(R.string.mmi_vide2)
                return@launch
            }
            statut.text = getString(R.string.mmi_compte, liste.size)
            val triees = liste.map { it to epochDe(it.creeLe) }
                .sortedByDescending { it.second }
            var periodeCourante = -1
            for ((m, epoch) in triees) {
                val p = periodeDe(epoch)
                if (p != periodeCourante) {
                    periodeCourante = p
                    conteneur.addView(enTetePeriode(labelPeriode(p)))
                }
                conteneur.addView(carteMission(m))
            }
        }
    }

    private fun carteMission(m: MissionResumee): View {
        val carte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARTE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6))
            layoutParams = lp
        }
        val nom = TextView(this).apply {
            text = m.nom
            setTextColor(TEXTE); textSize = 17f
        }
        val quand = dateLisible(m.creeLe)
        val lieu = m.lieuLabel ?: "${"%.4f".format(m.lat)}, ${"%.4f".format(m.lon)}"
        val ligneLieuDate = TextView(this).apply {
            text = if (quand.isBlank()) lieu else "$lieu  ·  $quand"
            setTextColor(TEXTE); textSize = 13f
            setPadding(0, dp(4), 0, 0)
        }
        val ligneStyle = TextView(this).apply {
            text = "${traduireIntention(m.intention)}  ·  ${traduireDuree(m.duree)}"
            setTextColor(TEXTE_DOUX); textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        }
        val btn = Button(this).apply {
            text = getString(R.string.mmi_dl_lancer)
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(VERT)
            setOnClickListener { confirmerEtLancer(m) }
        }
        carte.addView(nom)
        carte.addView(ligneLieuDate)
        carte.addView(ligneStyle)
        carte.addView(btn)
        return carte
    }

    private fun confirmerEtLancer(m: MissionResumee) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mmi_dlg_titre))
            .setMessage(getString(R.string.mmi_dlg_msg, m.nom))
            .setPositiveButton(getString(R.string.mmi_dl_lancer)) { _, _ -> telechargerPuisLancer(m) }
            .setNegativeButton(getString(R.string.mmi_annuler), null)
            .show()
    }

    private fun telechargerPuisLancer(m: MissionResumee) {
        val token = CineAuth.token(this) ?: return
        val dlg = AlertDialog.Builder(this)
            .setMessage(getString(R.string.mmi_dl_encours))
            .setCancelable(false)
            .create()
        dlg.show()
        lifecycleScope.launch {
            val dest = File(cacheDir, "mission_${m.id}.kmz")
            val kmz = analyseur.telechargerMissionParId(m.id, token, dest)
            dlg.dismiss()
            if (kmz == null) {
                Toast.makeText(this@MesMissionsActivity,
                    getString(R.string.mmi_dl_echec, analyseur.derniereErreur ?: "?"),
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            lancerSurDrone(kmz, m.nom)
        }
    }

    private fun lancerSurDrone(kmz: File, nom: String) {
        val progres = AlertDialog.Builder(this)
            .setTitle(getString(R.string.mmi_mission_nom, nom))
            .setMessage(getString(R.string.mmi_envoi))
            .setCancelable(false)
            .create()
        progres.show()
        ExecuteurMissionWpml.executer(
            this,
            kmz = kmz,
            onProgres = { wp ->
                runOnUiThread { progres.setMessage(getString(R.string.mmi_envol_wp, wp)) }
            },
            onTermine = { ok, msg ->
                runOnUiThread {
                    progres.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle(if (ok) getString(R.string.mmi_terminee) else getString(R.string.mmi_interrompue))
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    // ── libelles lisibles ───────────────────────────────────────────
    private fun traduireIntention(i: String?): String = when (i) {
        "reveler_immensite" -> getString(R.string.mmi_int_spectaculaire)
        "montrer_la_vue"    -> getString(R.string.mmi_int_carte)
        "souvenir"          -> getString(R.string.mmi_int_souvenir)
        "contemplatif"      -> getString(R.string.mmi_int_contemplatif)
        "mettre_en_valeur"  -> getString(R.string.mmi_int_valeur)
        null, ""            -> getString(R.string.mmi_int_sequence)
        else                -> i
    }

    private fun traduireDuree(d: String): String = when (d) {
        "courte"  -> getString(R.string.mmi_duree_courte)
        "longue"  -> getString(R.string.mmi_duree_longue)
        else      -> getString(R.string.mmi_duree_moyenne)
    }

    // classification chronologique
    private fun epochDe(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            val base = iso.trim().substringBefore('.').substringBefore('+').removeSuffix("Z")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(base)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun dateLisible(iso: String): String {
        val e = epochDe(iso)
        if (e <= 0L) return ""
        return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.CANADA_FRENCH)
            .format(java.util.Date(e))
    }

    private fun periodeDe(epoch: Long): Int {
        if (epoch <= 0L) return 3
        val now = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance().apply { timeInMillis = epoch }
        val a = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
        if (a && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) return 0
        if (a && c.get(java.util.Calendar.WEEK_OF_YEAR) == now.get(java.util.Calendar.WEEK_OF_YEAR)) return 1
        if (a && c.get(java.util.Calendar.MONTH) == now.get(java.util.Calendar.MONTH)) return 2
        return 3
    }

    private fun labelPeriode(p: Int): String = when (p) {
        0 -> getString(R.string.mmi_today)
        1 -> getString(R.string.mmi_semaine)
        2 -> getString(R.string.mmi_mois)
        else -> getString(R.string.mmi_ancien)
    }

    private fun enTetePeriode(label: String): View = TextView(this).apply {
        text = label
        setTextColor(BLEU); textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), dp(18), 0, dp(6))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

