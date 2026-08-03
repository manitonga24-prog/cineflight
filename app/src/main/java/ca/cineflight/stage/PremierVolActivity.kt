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
    private val MONTEE_MS = 0.4f        // vitesse verticale douce (m/s) — phases lentes
    /**
     * Montée INITIALE, plus franche, pour quitter vite la zone de dérive.
     *
     * ⚠ MESURE DU 2026-07-27 (vol 13:19) : posé à 1,2 m, commandes de l'app TOUTES À ZÉRO
     * et manches non touchés, le drone s'est éloigné de **9,2 m en 15 s**. Dès qu'il est
     * monté à 3,5 m, il est revenu à 0,6 m du départ et n'a plus bougé. Les vols du même
     * jour à 15 et 30 m dérivent de moins de 0,8 m en six minutes.
     * En dessous d'environ 2 m, ce drone tient sa position avec ses CAPTEURS DE VISION vers
     * le bas, pas avec le GPS ; au-dessus d'un gazon uniforme ils n'ont aucun motif à
     * suivre et lâchent. C'est le firmware DJI, pas l'app — mais la démo passait tout son
     * temps dans cette zone, donc elle exhibait le défaut à chaque fois.
     */
    private val MONTEE_INITIALE_MS = 1.0f
    /** Hauteur à atteindre avant toute autre manœuvre : au-dessus de la zone de vision. */
    private val ALT_SECURE_M = 5.0
    private val ALT_PLAFOND_M = 8.0     // ne JAMAIS monter au-dela (m AGL)
    private val BATT_MIN = 30           // batterie minimale pour lancer (%)
    /**
     * Sous ce niveau, la démo s'interrompt et l'aéronef se pose.
     *
     * Nettement sous `BATT_MIN` : on ne peut pas décoller à 29 %, mais si on est en l'air
     * et que la batterie tombe à 15 %, se poser vaut mieux que finir la chorégraphie.
     * Le retour automatique du firmware existe, il se déclenche plus bas et décide seul.
     */
    private val BATT_CRITIQUE = 15

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

        // SECTION « MODE TEST » (dev/debug) : simulateur DJI + diag perception. Invisible pour
        // l'utilisateur final — regroupée sous un en-tête clair, jamais présentée comme une
        // action principale de la démo. Le bouton reste TOUJOURS créé (lateinit) : on ne fait
        // que masquer, pour que basculerSimulateur/onDestroy ne rencontrent jamais un champ nul.
        val afficherModeTest = ca.cineflight.stage.BuildConfig.DEBUG ||
            getSharedPreferences("cineflight", MODE_PRIVATE).getBoolean("afficher_mode_test", false)
        val visModeTest = if (afficherModeTest) android.view.View.VISIBLE else android.view.View.GONE
        col.addView(TextView(this).apply {
            text = getString(R.string.pv_sim_section_titre)
            setTextColor(0xFFB0BEC5.toInt()); textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2)); visibility = visModeTest
        })
        btnSim = Button(this).apply {
            text = getString(R.string.pv_sim_activer); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
            setOnClickListener { basculerSimulateur() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)); visibility = visModeTest
        }
        col.addView(btnSim)
        col.addView(TextView(this).apply {
            text = getString(R.string.pv_sim_section_sous)
            setTextColor(0xFF90A4AE.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(10)); visibility = visModeTest
        })
        // Bouton DIAGNOSTIC perception (mode test) : logger + perception + tick ~2 Hz. Passif.
        col.addView(Button(this).apply {
            text = "Diag perception (sol/sim)"; isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
            setOnClickListener { basculerDiagPerception(this) }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(46)); visibility = visModeTest
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
                    // RAFRAÎCHISSEMENT PILOTÉ PAR ÉVÉNEMENTS (correctif 2026-07-25).
                    // Ce pont est une instance NEUVE : connexion/GPS/batterie n'arrivent
                    // qu'au fil des poussées SDK. L'ancien rafraîchissement s'arrêtait
                    // après 8 s : une batterie poussée à la 9e seconde laissait le bouton
                    // « Commencer » MORT pour toujours, sans message (vol du 2026-07-25 :
                    // 74 s d'attente, aucun décollage demandé au SDK, rien au journal).
                    // On chaîne la vérification sur les hooks SANS écraser l'observation
                    // vocale déjà branchée.
                    val avantBatt = pont.obsBatterie
                    pont.obsBatterie = { pct ->
                        try { avantBatt?.invoke(pct) } catch (_: Throwable) {}
                        ui.post { if (!enDemo) majVerification() }
                    }
                    val avantConn = pont.obsConnexionDrone
                    pont.obsConnexionDrone = { c ->
                        try { avantConn?.invoke(c) } catch (_: Throwable) {}
                        ui.post { if (!enDemo) majVerification() }
                    }
                    val avantSat = pont.obsGpsSatellites
                    pont.obsGpsSatellites = { n ->
                        try { avantSat?.invoke(n) } catch (_: Throwable) {}
                        ui.post { if (!enDemo) majVerification() }
                    }
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

    // Dernier état journalisé (on ne log que les CHANGEMENTS, pas les 2 Hz).
    private var dernierEtatVerifLog = ""

    /** Verifie l'etat du drone et met a jour le voyant + le bouton Commencer. */
    private fun majVerification() {
        val connecte = try { pont.estConnecte() } catch (_: Throwable) { false }
        val gps = try { pont.gpsValide() } catch (_: Throwable) { false }
        val batt = try { pont.batteriePourcent() } catch (_: Throwable) { 0 }
        val ok = connecte && gps && batt >= BATT_MIN
        // INSTRUMENTATION (2026-07-25) : l'essai au terrain était INVISIBLE au journal —
        // impossible de savoir après coup pourquoi le bouton restait mort.
        val etatLog = "connecte=$connecte gps=$gps batt=$batt ok=$ok"
        if (etatLog != dernierEtatVerifLog) {
            dernierEtatVerifLog = etatLog
            android.util.Log.i("PremierVol", "verification: $etatLog")
        }
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
            android.util.Log.w("PremierVol", "Commencer REFUSE: connecte=${pont.estConnecte()}" +
                " gps=${pont.gpsValide()} batt=${pont.batteriePourcent()} (min $BATT_MIN)")
            majVerification(); return
        }
        android.util.Log.i("PremierVol", "confirmation de decollage affichee")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pv_confirm_titre))
            .setMessage(getString(R.string.pv_confirm_msg))
            .setNegativeButton(getString(R.string.pv_annuler), null)
            .setPositiveButton(getString(R.string.pv_decoller)) { _, _ -> lancerVol() }
            .show()
    }

    private fun lancerVol() {
        if (enDemo) return
        android.util.Log.i("PremierVol", "decollage AUTOMATIQUE demande au SDK")
        // JOURNAL DE VOL PERSISTANT : le « décollage d'essai » du 25/07 (drone qui ne montait
        // pas et dérivait) n'a pu être diagnostiqué que par lecture du code — aucune trace.
        try {
            ca.cineflight.stage.control.JournalVol.demarrer(this, "DECOUVERTE",
                "montee=${MONTEE_MS}m/s yaw=${YAW_DPS}deg/s plafond=${ALT_PLAFOND_M}m" +
                " battMin=$BATT_MIN sim=${pont.simulateurActif()}" +
                " inverserRollPitch=${ca.cineflight.stage.control.PontDjiReel.INVERSER_ROLL_PITCH}")
        } catch (_: Throwable) {}
        enDemo = true; arrete = false
        btnCommencer.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
        voyant.text = getString(R.string.pv_etat_decollage)
        pont.decoller { ok ->
            ui.post {
                if (arrete) return@post
                if (!ok) { arreter(getString(R.string.pv_err_decollage)); return@post }
                try { pont.orienterNacelle(-20f, 0f, false) } catch (_: Throwable) {}  // legere plongee
                // ENREGISTREMENT VIDÉO AUTO (2026-07-25) : le vol découverte est le premier
                // souvenir du débutant — on le filme. Arrêté à fin() (après l'atterrissage).
                try {
                    if (!pont.enregistreEnCours()) {
                        pont.demarrerEnregistrement()
                        android.util.Log.i("PremierVol", "Enregistrement vidéo démarré")
                    }
                } catch (_: Throwable) {}
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
                    if (pret) {
                        majVoyantPilote(true)
                        t0 = SystemClock.elapsedRealtime()
                        boucle()
                    } else if (tropLong) {
                        // ⚠ DÉFAUT CORRIGÉ (2026-07-28). L'ancienne version démarrait la
                        // séquence MÊME SANS Virtual Stick : l'app envoyait ses commandes
                        // dans le vide pendant une vingtaine de secondes, le voyant passait
                        // au rouge, et rien n'était écrit au journal. Le drone, lui, restait
                        // en stationnaire — donc « il ne se passe rien » sans explication,
                        // exactement le symptôme le plus coûteux à diagnostiquer.
                        // Un mode qui ne peut pas commander l'aéronef doit le DIRE et rendre
                        // la main, pas faire semblant de voler.
                        try { ca.cineflight.stage.control.JournalVol.anomalie(
                            "VS_NON_ACCORDE delai=15s — séquence annulée, l'aéronef reste " +
                            "en stationnaire et la main revient au pilote")
                        } catch (_: Throwable) {}
                        arreter(getString(R.string.pv_err_vs))
                    } else {
                        ui.postDelayed({ attendreVsPuisPiloter() }, 200)
                    }
                }
                attendreVsPuisPiloter()
            }
        }
    }

    /** Instant où la hauteur de sécurité a été atteinte ; -1 tant que ce n'est pas fait. */
    private var tMonteeFinie = -1.0
    /** La rotation démarre après la montée, pas à une seconde fixe. */
    private fun tRotationFin(): Double = (if (tMonteeFinie >= 0) tMonteeFinie else 0.0) + 10.0

    private var latDepart = Double.NaN
    private var lonDepart = Double.NaN
    private var deriveSignalee = false

    /**
     * Surveille l'éloignement alors que l'app ne commande AUCUN déplacement horizontal.
     *
     * Ce n'est pas un garde-fou de sécurité — on ne peut rien y faire depuis l'app, le
     * drone dérive sous sa propre logique de maintien de position. C'est une PREUVE : sans
     * elle, le pilote voit son drone partir et personne ne peut dire ensuite si l'app y
     * était pour quelque chose. Le vol du 2026-07-27 a demandé une heure d'analyse pour
     * établir ce que cette ligne aurait dit tout de suite.
     */
    private fun surveillerDerive(alt: Double) {
        val la = try { pont.latitudeDrone() } catch (_: Throwable) { Double.NaN }
        val lo = try { pont.longitudeDrone() } catch (_: Throwable) { Double.NaN }
        if (la.isNaN() || lo.isNaN()) return
        if (latDepart.isNaN()) { latDepart = la; lonDepart = lo; return }
        val mLat = 111_320.0
        val mLon = 111_320.0 * kotlin.math.cos(Math.toRadians(la))
        val d = kotlin.math.hypot((lo - lonDepart) * mLon, (la - latDepart) * mLat)
        if (d > 3.0 && !deriveSignalee) {
            deriveSignalee = true
            try { ca.cineflight.stage.control.JournalVol.anomalie(
                "DÉRIVE SANS COMMANDE : %.1f m du point de décollage à %.1f m d'altitude — ".format(d, alt) +
                "l'app n'envoie aucun déplacement horizontal (pitch=roll=0). " +
                "Sous ~2 m le maintien de position se fait à la VISION : au-dessus d'une " +
                "surface uniforme elle lâche.") } catch (_: Throwable) {}
        }
    }

    /** Sequenceur ~10 Hz. pitch=roll=0 TOUJOURS : aucun deplacement horizontal. */
    private fun boucle() {
        if (!enDemo || arrete) return
        val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
        var throttle = 0f
        var yaw = 0f
        val alt = try { pont.altitudeDrone() } catch (_: Throwable) { 0.0 }
        // ⚠ ON MONTE D'ABORD. L'ancienne séquence commençait par 3 s d'immobilité à 1,2 m,
        // throttle à zéro, puis montait à 0,4 m/s : toute la démo se déroulait entre 1,2 et
        // 3,4 m, c'est-à-dire dans la zone où le maintien de position se fait à la vision.
        // Mesure du 2026-07-27 : 9,2 m de dérive en 15 s à cette hauteur, zéro dérive dès 3,5 m.
        // La phase de montée n'est donc plus une durée fixe mais une CONDITION D'ALTITUDE :
        // un drone lourd, ou du vent, ne doivent pas laisser la démo commencer trop bas.
        // ⚠ MONTÉE QUI N'ABOUTIT PAS. Le garde-fou de 20 s empêche de rester bloqué en
        // montée — mais l'ancienne version enchaînait alors sur des conditions toutes
        // fausses (`tMonteeFinie` restant à −1, `tRotationFin()` valant 10 s alors que t
        // vaut déjà 20) et tombait dans `else -> atterrir()`. L'aéronef se posait donc
        // aussitôt, SANS un mot, et le pilote voyait une démo qui s'arrête net.
        // Le comportement final est le bon ; ce qui manquait, c'est de le DIRE et de ne
        // pas y arriver par accident.
        if (alt < ALT_SECURE_M && t >= 20.0) {
            // Codes STRUCTURÉS, pas une phrase : c'est ce qui rend un journal exploitable
            // par recherche sur des dizaines de vols. La phrase explique, le code se
            // compte.
            try {
                ca.cineflight.stage.control.JournalVol.anomalie(
                    "MONTEE_TIMEOUT duree=%.0fs".format(t))
                ca.cineflight.stage.control.JournalVol.anomalie(
                    "ALTITUDE_CIBLE_NON_ATTEINTE atteinte=%.1fm cible=%.1fm".format(alt, ALT_SECURE_M))
                ca.cineflight.stage.control.JournalVol.anomalie(
                    "ATTERRISSAGE_SECURITE cause=montee_non_aboutie — vent, charge, " +
                    "ou throttle sans effet")
            } catch (_: Throwable) {}
            voyant.text = getString(R.string.pv_err_montee)
            atterrir(); return
        }
        // ⚠ BATTERIE PENDANT LA DÉMO. Le contrôle d'avant décollage (30 %) ne dit rien de
        // ce qui se passe ensuite : une cellule fatiguée peut s'effondrer en quelques
        // secondes sous charge. La démo est courte, mais un seuil critique ne coûte rien
        // et évite de laisser un débutant en l'air avec une batterie qui lâche.
        val batt = try { pont.batteriePourcent() } catch (_: Throwable) { 100 }
        if (batt in 1 until BATT_CRITIQUE) {
            try {
                ca.cineflight.stage.control.JournalVol.anomalie(
                    // Chaîne simple, pas un format : « %% » s'écrirait tel quel ici.
                    "BATTERIE_CRITIQUE niveau=$batt% seuil=$BATT_CRITIQUE%")
                ca.cineflight.stage.control.JournalVol.anomalie(
                    "ATTERRISSAGE_SECURITE cause=batterie_critique")
            } catch (_: Throwable) {}
            voyant.text = getString(R.string.pv_err_batt_vol, batt)
            atterrir(); return
        }
        when {
            alt < ALT_SECURE_M -> {
                throttle = MONTEE_INITIALE_MS
                voyant.text = getString(R.string.pv_etat_montee)
            }
            t < tRotationFin() -> { yaw = YAW_DPS;         voyant.text = getString(R.string.pv_etat_rotation) }
            t < tRotationFin() + 2.0 -> { throttle = MONTEE_MS * 0.8f;  voyant.text = getString(R.string.pv_etat_vertical) }
            t < tRotationFin() + 4.0 -> { throttle = -MONTEE_MS * 0.8f; voyant.text = getString(R.string.pv_etat_vertical) }
            t < tRotationFin() + 6.0 -> voyant.text = getString(R.string.pv_etat_termine)
            else -> { atterrir(); return }
        }
        if (tMonteeFinie < 0.0 && alt >= ALT_SECURE_M) {
            tMonteeFinie = t
            try { ca.cineflight.stage.control.JournalVol.evenement(
                "hauteur de sécurité atteinte : %.1f m en %.1f s".format(alt, t)) } catch (_: Throwable) {}
        }
        surveillerDerive(alt)
        if (throttle > 0f && alt >= ALT_PLAFOND_M) throttle = 0f
        try { pont.envoyerVitesses(0f, 0f, throttle, yaw, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        // Trace ~1 Hz : altitude RÉELLE vs commande envoyée — c'est exactement ce qui
        // manquait pour prouver « le drone ne monte pas » ou « il dérive ».
        try {
            ca.cineflight.stage.control.JournalVol.etatSimple(
                altM = alt, capDeg = pont.capDroneDeg().toDouble(),
                lat = pont.latitudeDrone(), lon = pont.longitudeDrone(),
                pitch = 0f, roll = 0f, throttle = throttle, yaw = yaw,
                batteriePct = pont.batteriePourcent(), satellites = pont.nbSatellitesActuel(),
                phase = "t=%.1fs".format(t))
        } catch (_: Throwable) {}
        ui.postDelayed({ boucle() }, 100)
    }

    /**
     * SORTIE UNIQUE de la démo. Tout chemin de fin passe par ici — réussite, échec,
     * arrêt du pilote, passage en arrière-plan.
     *
     * ⚠ POURQUOI UNE SEULE FONCTION. Le nettoyage était dupliqué entre `fin()` et
     * `arreter()`, et il DIVERGEAIT : l'arrêt vidéo n'existait que dans `fin()`. Un appel
     * entrant pendant la démo laissait donc la caméra tourner indéfiniment. Deux chemins
     * qui doivent faire la même chose finissent toujours par ne plus la faire — c'est
     * moins une question de discipline que de structure.
     *
     * @param libererVs faux pendant un atterrissage automatique : le SDK se pose seul, et
     *   couper l'autorité au milieu de la manœuvre n'apporte rien.
     */
    private fun nettoyerMission(raison: String, libererVs: Boolean) {
        enDemo = false
        ui.removeCallbacksAndMessages(null)
        majVoyantPilote(false)
        // 1. Les commandes D'ABORD : plus rien ne doit partir vers l'aéronef.
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        // 2. La vidéo, quel que soit le chemin de sortie.
        var videoCoupee = false
        try {
            if (pont.enregistreEnCours()) { pont.arreterEnregistrement(); videoCoupee = true }
        } catch (_: Throwable) {}
        // 3. L'autorité, seulement si on ne compte pas sur elle pour la suite.
        if (libererVs) try { pont.activerVirtualStick(false) } catch (_: Throwable) {}
        try { ca.cineflight.stage.control.JournalVol.evenement(
            "NETTOYAGE_MISSION raison=$raison video_coupee=$videoCoupee vs_libere=$libererVs")
        } catch (_: Throwable) {}
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
        arrete = true
        // VS conservé : c'est le SDK qui pose l'aéronef, la libération se fera dans fin().
        nettoyerMission("STOP_PILOTE", libererVs = false)
        voyant.text = getString(R.string.pv_etat_pose_auto)
        btnStop.visibility = View.GONE
        btnCommencer.visibility = View.GONE
        try { pont.atterrir { _ -> ui.post { fin() } } } catch (_: Throwable) { ui.post { fin() } }
    }

    /** Ancien STOP (hover + main rendue) : garde pour l'echec de decollage et la mise en arriere-plan. */
    private fun arreter(message: String) {
        android.util.Log.w("PremierVol", "arret: $message")
        try { ca.cineflight.stage.control.JournalVol.terminer("ARRÊT : $message") } catch (_: Throwable) {}
        arrete = true
        // L'aéronef reste EN VOL stationnaire et la main revient au pilote : on libère
        // donc l'autorité automatique.
        nettoyerMission("ARRET_$message".take(60).replace(' ', '_'), libererVs = true)
        voyant.text = message
        btnStop.visibility = View.GONE
        btnCommencer.visibility = View.VISIBLE
        btnCommencer.isEnabled = true
    }

    private fun fin() {
        arrete = false
        nettoyerMission("FIN_NORMALE", libererVs = true)
        try { ca.cineflight.stage.control.JournalVol.terminer("vol découverte terminé") } catch (_: Throwable) {}
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
