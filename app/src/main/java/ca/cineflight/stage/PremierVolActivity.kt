package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ca.cineflight.stage.control.PontDjiReel
import ca.cineflight.stage.control.ObstacleGateWiring

/**
 * PremierVolActivity - demonstration "Mon premier vol" SANS RTK, pour mettre un
 * debutant en confiance avant de laisser CineFlight piloter son drone.
 *
 * >>> DOCTRINE <<<
 * Le plus rassurant possible. CineFlight decolle automatiquement, tient la
 * position (GPS embarque, aucun RTK requis), pivote lentement + fait un petit
 * mouvement vertical doux, puis atterrit. AUCUN deplacement horizontal (pitch et
 * roll toujours a zero) : le drone ne peut rien percuter. Gros bouton STOP a tout
 * moment (hover immediat + main rendue a la telecommande). Plafond d'altitude dur.
 *
 * TOUS les textes visibles passent par strings.xml (i18n). Aucune valeur cinema
 * n'est envoyee directement au SDK sans borne : yaw <= 15 deg/s, montee <= 0.4 m/s.
 */
class PremierVolActivity : AppCompatActivity() {

    private val pont = PontDjiReel(ObstacleGateWiring.Off)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var voyant: TextView
    private lateinit var voyantPilote: TextView
    private lateinit var btnCommencer: Button
    private lateinit var btnStop: Button
    private lateinit var btnSim: Button
    // DIAGNOSTIC PERCEPTION (passif, test au sol/sim). Instance dediee, lecture seule.
    private val perceptionDiag = ca.cineflight.stage.sentinelle.LecteurPerception()
    @Volatile private var diagActif = false
    private var diagTick: Runnable? = null

    // ---- bornes de securite (douces) ----
    private val YAW_DPS = 15f           // rotation lente (deg/s)
    private val MONTEE_MS = 0.4f        // vitesse verticale douce (m/s)
    private val ALT_PLAFOND_M = 8.0     // ne JAMAIS monter au-dela (m AGL)
    private val BATT_MIN = 30           // batterie minimale pour lancer (%)

    private val SIM_LAT = 45.5
    private val SIM_LON = -73.56
    @Volatile private var enDemo = false
    @Volatile private var arrete = false
    private var t0 = 0L
    private var rafraichissementsRestants = 16   // 16 x 500 ms = 8 s de suivi d'etat

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.pv_titre)
            setTextColor(Color.WHITE); textSize = 20f
            setPadding(0, 0, 0, dp(12))
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.pv_intro)
            setTextColor(0xFFB0BEC5.toInt()); textSize = 14f
            setPadding(0, 0, 0, dp(16))
        })

        voyant = TextView(this).apply {
            text = getString(R.string.pv_verif)
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(18))
            setBackgroundColor(0xFF1C2530.toInt())
        }
        col.addView(voyant)
        voyantPilote = TextView(this).apply {
            text = getString(R.string.pv_pilote_manette)
            setTextColor(0xFF90A4AE.toInt()); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        col.addView(voyantPilote)
        col.addView(espace(dp(18)))

        btnSim = Button(this).apply {
            text = getString(R.string.pv_sim_activer); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
            setOnClickListener { basculerSimulateur() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(48))
        }
        col.addView(btnSim)
        col.addView(TextView(this).apply {
            text = getString(R.string.pv_sim_note)
            setTextColor(0xFF90A4AE.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        })
        // Bouton DIAGNOSTIC perception : demarre logger + perception + tick de journalisation
        // periodique (~2 Hz). Passif : ne touche pas au pilotage. A utiliser AVEC le simulateur.
        col.addView(Button(this).apply {
            text = "Diag perception (sol/sim)"; isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
            setOnClickListener { basculerDiagPerception(this) }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(46))
        })
        btnCommencer = Button(this).apply {
            text = getString(R.string.pv_commencer); isAllCaps = false; textSize = 16f
            setOnClickListener { demarrer() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(56))
        }
        col.addView(btnCommencer)
        col.addView(espace(dp(12)))

        btnStop = Button(this).apply {
            text = getString(R.string.pv_stop); isAllCaps = false; textSize = 18f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
            setOnClickListener { poserAuto() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(66))
            visibility = View.GONE
        }
        col.addView(btnStop)

        col.addView(espace(dp(16)))
        col.addView(TextView(this).apply {
            text = getString(R.string.pv_avertissement)
            setTextColor(0xFFFFB74D.toInt()); textSize = 13f
        })

        val scroll = ScrollView(this); scroll.addView(col); setContentView(scroll)
        majVerification()

        // BRANCHEMENT SDK : ce pont est une instance neuve ; il faut enregistrer le
        // SDK (deja fait si on vient du cockpit -> retour immediat) PUIS abonner les
        // listeners, sinon estConnecte()/gpsValide() restent faux et l'ecran affiche
        // "Drone non connecte". Les etats arrivent en asynchrone -> on rafraichit le
        // voyant pendant quelques secondes le temps que le SDK pousse la connexion.
        EnregistrementSdk.enregistrer(applicationContext) { ok, _ ->
            ui.post {
                if (ok) {
                    try { pont.initialiserListeners() } catch (_: Throwable) {}
                    // OBSERVATION VOCALE (Phase 2) : branchee sur CE pont (Premier vol).
                    try {
                        val voix = ca.cineflight.stage.voice.FlightVoiceSystem.obtenir(applicationContext)
                        voix.settings.active = true
                        voix.settings.observationDji = true
                        voix.demarrer(ca.cineflight.stage.LangueManager.langueActuelle(this@PremierVolActivity))
                        // Mode de voix choisi dans les reglages (defaut NORMAL).
                        try {
                            val vm = this@PremierVolActivity.getSharedPreferences("cineflight", MODE_PRIVATE).getInt("voix_mode", 1)
                            voix.settings.mode = when (vm) {
                                0 -> ca.cineflight.stage.voice.VoiceMode.MINIMAL
                                2 -> ca.cineflight.stage.voice.VoiceMode.DETAILLE
                                else -> ca.cineflight.stage.voice.VoiceMode.NORMAL
                            }
                        } catch (_: Throwable) {}
                        val obs = ca.cineflight.stage.voice.DjiVoiceObservationAdapter(voix)
                        pont.obsVsEnableAccepte = { obs.observerVsEnableAccepte() }
                        pont.obsVsEnableRefuse = { obs.observerVsEnableRefuse() }
                        pont.obsVsConfirme = { actif -> obs.observerVsConfirme(actif) }
                        pont.obsVsDesactivationVoulue = { obs.marquerDesactivationDemandee() }
                        pont.obsTakeoffActive = { obs.observerTakeoffActive() }
                        pont.obsTakeoffRefuse = { obs.observerTakeoffRefuse() }
                        pont.obsLandingActive = { obs.observerLandingActive() }
                        pont.obsLandingRefuse = { obs.observerLandingRefuse() }
                        pont.obsBatterie = { pct -> obs.observerBatterie(pct) }
                        pont.obsConnexionDrone = { c -> obs.observerConnexionDrone(c) }
                        pont.obsConnexionRc = { c -> obs.observerConnexionRc(c) }
                        pont.obsGpsSatellites = { n -> obs.observerGpsSatellites(n) }
                    } catch (_: Throwable) {}
                    demarrerRafraichissementEtat()
                } else {
                    voyant.text = getString(R.string.pv_err_connecte)
                }
            }
        }
    }

    /** Rafraichit le voyant + le bouton pendant ~8 s apres l'abonnement des
     *  listeners : la connexion et le GPS arrivent en asynchrone depuis le SDK. */
    private fun demarrerRafraichissementEtat() {
        if (enDemo) return
        majVerification()
        rafraichissementsRestants -= 1
        if (rafraichissementsRestants > 0 && !enDemo) {
            ui.postDelayed({ demarrerRafraichissementEtat() }, 500)
        }
    }

    /** Voyant "qui pilote" : vert = l'app (Virtual Stick actif), gris = la manette. */
    private fun majVoyantPilote(appPilote: Boolean) {
        if (!::voyantPilote.isInitialized) return
        voyantPilote.text = getString(
            if (appPilote) R.string.pv_pilote_app else R.string.pv_pilote_manette)
        voyantPilote.setTextColor(if (appPilote) 0xFF43A047.toInt() else 0xFF90A4AE.toInt())
    }

    /** Verifie l'etat du drone et met a jour le voyant + le bouton Commencer. */
    private fun majVerification() {
        val connecte = try { pont.estConnecte() } catch (_: Throwable) { false }
        val gps = try { pont.gpsValide() } catch (_: Throwable) { false }
        val batt = try { pont.batteriePourcent() } catch (_: Throwable) { 0 }
        val ok = connecte && gps && batt >= BATT_MIN
        btnCommencer.isEnabled = ok
        voyant.text = when {
            !connecte -> getString(R.string.pv_err_connecte)
            !gps -> getString(R.string.pv_err_gps)
            batt < BATT_MIN -> getString(R.string.pv_err_batterie, batt, BATT_MIN)
            else -> getString(R.string.pv_pret, batt)
        }
    }

    /** Active/desactive le simulateur du SDK (test sans vol reel, helices enlevees). */
    private fun basculerSimulateur() {
        if (pont.simulateurActif()) {
            pont.desactiverSimulateur { ui.post { btnSim.text = getString(R.string.pv_sim_activer); majVerification() } }
        } else {
            voyant.text = getString(R.string.pv_sim_encours)
            pont.activerSimulateur(SIM_LAT, SIM_LON) { ok -> ui.post {
                btnSim.text = if (ok) getString(R.string.pv_sim_desactiver) else getString(R.string.pv_sim_activer)
                if (!ok) voyant.text = getString(R.string.pv_sim_echec) else majVerification()
            } }
        }
    }

    /** Demande confirmation (decollage AUTOMATIQUE) avant de lancer. */
    private fun demarrer() {
        if (enDemo) return
        if (!pont.estConnecte() || !pont.gpsValide() || pont.batteriePourcent() < BATT_MIN) {
            majVerification(); return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pv_confirm_titre))
            .setMessage(getString(R.string.pv_confirm_msg))
            .setNegativeButton(getString(R.string.pv_annuler), null)
            .setPositiveButton(getString(R.string.pv_decoller)) { _, _ -> lancerVol() }
            .show()
    }

    private fun lancerVol() {
        if (enDemo) return
        enDemo = true; arrete = false
        btnCommencer.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
        voyant.text = getString(R.string.pv_etat_decollage)
        pont.decoller { ok ->
            ui.post {
                if (arrete) return@post
                if (!ok) { arreter(getString(R.string.pv_err_decollage)); return@post }
                try { pont.orienterNacelle(-20f, 0f, false) } catch (_: Throwable) {}  // legere plongee
                // Le Virtual Stick est re-etabli par PontDjiReel.decoller() APRES le
                // decollage (DJI refuse tant que le decollage n'est pas fini). Au lieu
                // d'un delai fixe, on ATTEND la confirmation reelle (virtualStickConfirmeActif)
                // avant de piloter : le mouvement demarre PILE quand le drone rend la main,
                // sans commande perdue. Timeout de securite 15 s. Le drone tient son hover seul.
                voyant.text = getString(R.string.pv_etat_stabilise)
                val tAttente0 = SystemClock.elapsedRealtime()
                fun attendreVsPuisPiloter() {
                    if (!enDemo || arrete) return
                    val pret = try { pont.virtualStickConfirmeActif() } catch (_: Throwable) { false }
                    val tropLong = SystemClock.elapsedRealtime() - tAttente0 > 15000
                    if (pret || tropLong) {
                        majVoyantPilote(pret)   // vert seulement si VS reellement actif
                        t0 = SystemClock.elapsedRealtime()
                        boucle()
                    } else {
                        ui.postDelayed({ attendreVsPuisPiloter() }, 200)
                    }
                }
                attendreVsPuisPiloter()
            }
        }
    }

    /** Sequenceur ~10 Hz. pitch=roll=0 TOUJOURS : aucun deplacement horizontal. */
    private fun boucle() {
        if (!enDemo || arrete) return
        val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
        var throttle = 0f
        var yaw = 0f
        when {
            t < 3.0  -> voyant.text = getString(R.string.pv_etat_stabilise)
            t < 7.0  -> { throttle = MONTEE_MS;        voyant.text = getString(R.string.pv_etat_montee) }
            t < 17.0 -> { yaw = YAW_DPS;               voyant.text = getString(R.string.pv_etat_rotation) }
            t < 19.0 -> { throttle = MONTEE_MS * 0.8f; voyant.text = getString(R.string.pv_etat_vertical) }
            t < 21.0 -> { throttle = -MONTEE_MS * 0.8f; voyant.text = getString(R.string.pv_etat_vertical) }
            t < 23.0 -> voyant.text = getString(R.string.pv_etat_termine)
            else -> { atterrir(); return }
        }
        // plafond d'altitude dur : ne jamais monter au-dela.
        val alt = try { pont.altitudeDrone() } catch (_: Throwable) { 0.0 }
        if (throttle > 0f && alt >= ALT_PLAFOND_M) throttle = 0f
        try { pont.envoyerVitesses(0f, 0f, throttle, yaw, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        ui.postDelayed({ boucle() }, 100)
    }

    private fun atterrir() {
        voyant.text = getString(R.string.pv_etat_atterrissage)
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        pont.atterrir { _ -> ui.post { fin() } }
    }

    /** STOP (debutant) : fige puis fait ATTERRIR le drone tout seul. En Premier vol il reste
     *  a l'aplomb du depart (aucun deplacement horizontal), donc il se pose sur le point de
     *  decollage, deja degage. Le debutant n'a rien a piloter. */
    private fun poserAuto() {
        arrete = true; enDemo = false
        majVoyantPilote(false)
        ui.removeCallbacksAndMessages(null)
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        voyant.text = getString(R.string.pv_etat_pose_auto)
        btnStop.visibility = View.GONE
        btnCommencer.visibility = View.GONE
        try { pont.atterrir { _ -> ui.post { fin() } } } catch (_: Throwable) { ui.post { fin() } }
    }

    /** Ancien STOP (hover + main rendue) : garde pour l'echec de decollage et la mise en arriere-plan. */
    private fun arreter(message: String) {
        arrete = true; enDemo = false
        majVoyantPilote(false)
        ui.removeCallbacksAndMessages(null)
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        try { pont.activerVirtualStick(false) } catch (_: Throwable) {}
        voyant.text = message
        btnStop.visibility = View.GONE
        btnCommencer.visibility = View.VISIBLE
        btnCommencer.isEnabled = true
    }

    private fun fin() {
        enDemo = false; arrete = false
        majVoyantPilote(false)
        try { pont.activerVirtualStick(false) } catch (_: Throwable) {}
        voyant.text = getString(R.string.pv_etat_fin)
        btnStop.visibility = View.GONE
        btnCommencer.visibility = View.VISIBLE
        majVerification()
    }

    override fun onPause() {
        super.onPause()
        // Securite : si l'ecran passe en arriere-plan pendant la demo -> hover + main au pilote.
        if (enDemo && !arrete) arreter(getString(R.string.pv_etat_arrete))
        // Arrete proprement le diagnostic perception si actif (pas de fuite de handler).
        if (diagActif) {
            diagActif = false
            diagTick?.let { ui.removeCallbacks(it) }; diagTick = null
            try { perceptionDiag.arreter() } catch (_: Throwable) {}
            try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.desactiver() } catch (_: Throwable) {}
        }
    }

    // ---- helpers UI ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun espace(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, h)
    }

    /** Active/desactive le diagnostic perception (passif). Demarre le logger + la
     *  perception sur CE pont, et journalise l'etat toutes les 500 ms pour voir si un
     *  flux de trames arrive (avec le simulateur DJI actif). N'influence RIEN. */
    private fun basculerDiagPerception(btn: Button) {
        if (!diagActif) {
            diagActif = true
            try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.activer() } catch (_: Throwable) {}
            try { perceptionDiag.demarrer() } catch (_: Throwable) {}
            btn.text = "Diag perception : ON (voir Logcat)"
            val r = object : Runnable {
                override fun run() {
                    try { perceptionDiag.journaliserTrame() } catch (_: Throwable) {}
                    if (diagActif) ui.postDelayed(this, 500)
                }
            }
            diagTick = r
            ui.postDelayed(r, 500)
        } else {
            diagActif = false
            diagTick?.let { ui.removeCallbacks(it) }
            diagTick = null
            try { perceptionDiag.arreter() } catch (_: Throwable) {}
            try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.desactiver() } catch (_: Throwable) {}
            btn.text = "Diag perception (sol/sim)"
        }
    }
}
