package ca.cineflight.stage.voice

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * PHASE 1 — Ecran de diagnostic. AUTONOME : aucun drone requis. Rien ne touche
 * au pilotage. Valide tout le moteur (priorite, dedup, expiration, recuperation,
 * modes, bilinguisme, resistance TTS).
 */
class FlightVoiceTestActivity : AppCompatActivity() {

    private lateinit var voix: FlightVoiceSystem
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var journal: TextView
    private val obs by lazy { DjiVoiceObservationAdapter(voix) }
    private val moniteurVlos by lazy { VlosDistanceMonitor(voix) }
    private val moniteurAltitude by lazy { AltitudeMonitor(voix) }
    private val moniteurSujet by lazy { SubjectVoiceMonitor(voix) }
    private val moniteurDivergence by lazy { DivergenceVisionRtk(voix) }
    private val moniteurRthPhase by lazy { RthPhaseMonitor { code -> obs.observerRthPhase(code) } }
    private val perceptionSol by lazy { ca.cineflight.stage.sentinelle.LecteurPerception() }

    private fun maintenant() = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voix = FlightVoiceSystem.obtenir(applicationContext)
        voix.settings.active = true
        voix.settings.mode = VoiceMode.DETAILLE
        voix.demarrer(voix.settings.langue)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        col.addView(titre("Test assistant vocal (Phase 1)"))
        col.addView(sousTitre("Aucun drone requis. Rien n'est branche au pilotage."))

        col.addView(bouton("Tester information") { pub("voice.test.info", VoiceSeverity.INFO) })
        col.addView(bouton("Tester confirmation") { pub("command.takeoff.active", VoiceSeverity.COMMAND) })
        col.addView(bouton("Tester alerte critique") { pub("flight.connection.lost", VoiceSeverity.CRITICAL, InterruptPolicy.FLUSH, dedup = "liaison_drone") })
        col.addView(bouton("Tester interruption (info puis critique)") { testInterruption() })
        col.addView(bouton("Tester deduplication (x3 rapide)") { testDedup() })
        col.addView(bouton("Tester expiration") { testExpiration() })
        col.addView(bouton("Tester recuperation") { testRecuperation() })
        col.addView(bouton("Tester francais") { voix.definirLangue("fr"); pub("voice.test.fr", VoiceSeverity.INFO) })
        col.addView(bouton("Tester anglais") { voix.definirLangue("en"); pub("voice.test.en", VoiceSeverity.INFO) })
        col.addView(bouton("Simuler TTS indisponible") { voix.feedback.simulerIndisponible = true; pub("voice.test.info", VoiceSeverity.INFO) })
        col.addView(bouton("Reactiver TTS") { voix.feedback.simulerIndisponible = false })
        col.addView(bouton("Arreter la voix") { voix.arreterTout() })
        col.addView(bouton("[Phase 2] Activer observation DJI") {
            voix.settings.active = true
            voix.settings.observationDji = true
            voix.settings.mode = VoiceMode.DETAILLE
        })
        col.addView(bouton("[Phase 2] Couper observation DJI") { voix.settings.observationDji = false })
        col.addView(sousTitre("Phase 3 - etats critiques (via l\'observateur reel)"))
        col.addView(bouton("Phase 3 : batterie faible (28%)") { activerObs(); obs.observerBatterie(28) })
        col.addView(bouton("Phase 3 : batterie critique (12%)") { activerObs(); obs.observerBatterie(12) })
        col.addView(bouton("Phase 3 : batterie retablie (60%)") { activerObs(); obs.observerBatterie(60) })
        col.addView(bouton("Phase 3 : liaison drone PERDUE") { activerObs(); obs.observerConnexionDrone(true); obs.observerConnexionDrone(false) })
        col.addView(bouton("Phase 3 : liaison drone RETABLIE") { activerObs(); obs.observerConnexionDrone(false); obs.observerConnexionDrone(true) })
        col.addView(bouton("Phase 3 : telecommande DECONNECTEE") { activerObs(); obs.observerConnexionRc(true); obs.observerConnexionRc(false) })
        col.addView(bouton("Phase 3 : telecommande RECONNECTEE") { activerObs(); obs.observerConnexionRc(false); obs.observerConnexionRc(true) })
        col.addView(bouton("Phase 3 : GPS insuffisant (5 sat)") { activerObs(); obs.observerGpsSatellites(15); obs.observerGpsSatellites(5) })
        col.addView(bouton("Phase 3 : GPS retabli (18 sat)") { activerObs(); obs.observerGpsSatellites(5); obs.observerGpsSatellites(18) })
        col.addView(bouton("Phase 3 : ARRET D'URGENCE") { activerObs(); obs.observerArretUrgence() })
        col.addView(sousTitre("Phase 3 - distance VLOS (pilote fixe a 0,0 ; limite 600 m)"))
        col.addView(bouton("VLOS : approche (~450 m)") { activerObs(); vlosSim(0.004042) })
        col.addView(bouton("VLOS : avertissement (~520 m)") { activerObs(); vlosSim(0.004671) })
        col.addView(bouton("VLOS : limite atteinte (~610 m)") { activerObs(); vlosSim(0.005486) })
        col.addView(bouton("VLOS : depassement (~720 m)") { activerObs(); vlosSim(0.006468) })
        col.addView(bouton("VLOS : retour dans la limite (~400 m)") { activerObs(); vlosSim(0.003593) })
        col.addView(bouton("VLOS : position pilote absente") { activerObs(); moniteurVlos.observer(0.0, 0.006, null, null, Double.NaN, maintenant()) })
        col.addView(sousTitre("Phase 3A - maitrise du drone"))
        col.addView(bouton("3A watchdog : commandes interrompues") { activerObs(); obs.observerWatchdog(true) })
        col.addView(bouton("3A watchdog : commandes retablies") { activerObs(); obs.observerWatchdog(false) })
        col.addView(bouton("3A : controle auto non confirme") { activerObs(); obs.observerVsNonConfirme(true) })
        col.addView(bouton("3A : point maison confirme") { activerObs(); obs.observerPointMaison(true) })
        col.addView(bouton("3A : point maison invalide") { activerObs(); obs.observerPointMaisonInvalide() })
        col.addView(bouton("3A RTH : montee") { activerObs(); obs.observerRthPhase(1) })
        col.addView(bouton("3A RTH : retour") { activerObs(); obs.observerRthPhase(2) })
        col.addView(bouton("3A RTH : descente") { activerObs(); obs.observerRthPhase(3) })
        col.addView(bouton("3A RTH : termine") { activerObs(); obs.observerRthPhase(4) })
        col.addView(bouton("3A alt : approche (~105 m)") { activerObs(); moniteurAltitude.observer(105.0, maintenant()) })
        col.addView(bouton("3A alt : limite (~124 m)") { activerObs(); moniteurAltitude.observer(124.0, maintenant()) })
        col.addView(bouton("3A alt : depassement (~140 m)") { activerObs(); moniteurAltitude.observer(140.0, maintenant()) })
        col.addView(bouton("3A alt : retour (~90 m)") { activerObs(); moniteurAltitude.observer(90.0, maintenant()) })
        col.addView(bouton("3A liaison : signal cmd faible (30%)") { activerObs(); obs.observerSignalCommande(30) })
        col.addView(bouton("3A liaison : signal cmd critique (10%)") { activerObs(); obs.observerSignalCommande(10) })
        col.addView(bouton("3A liaison : signal cmd retabli (80%)") { activerObs(); obs.observerSignalCommande(80) })
        col.addView(bouton("3A liaison : video perdue (0%)") { activerObs(); obs.observerSignalVideo(0) })
        col.addView(sousTitre("Phase 3B - suivi cinematographique"))
        col.addView(bouton("3B sujet : perime") { activerObs(); moniteurSujet.observerPresence(2, false); moniteurSujet.observerPresence(1, false) })
        col.addView(bouton("3B sujet : balise perdue") { activerObs(); moniteurSujet.observerPresence(2, false); moniteurSujet.observerPresence(0, true) })
        col.addView(bouton("3B sujet : retrouve") { activerObs(); moniteurSujet.observerPresence(0, false); moniteurSujet.observerPresence(2, false) })
        col.addView(bouton("3B RTK : FIX->FLOAT degrade") { activerObs(); moniteurSujet.observerRtkQualite(3); moniteurSujet.observerRtkQualite(2) })
        col.addView(bouton("3B RTK : precision insuffisante") { activerObs(); moniteurSujet.observerRtkQualite(2); moniteurSujet.observerRtkQualite(1) })
        col.addView(bouton("3B RTK : fixe") { activerObs(); moniteurSujet.observerRtkQualite(1); moniteurSujet.observerRtkQualite(3) })
        col.addView(bouton("3B sujet : trop proche") { activerObs(); moniteurSujet.observerTropProche(false); moniteurSujet.observerTropProche(true) })
        col.addView(bouton("3B fusion : vision perdue RTK ok") { activerObs(); moniteurSujet.observerFusion(2, true, true); moniteurSujet.observerFusion(1, false, true) })
        col.addView(bouton("3B fusion : RTK perdu vision ok") { activerObs(); moniteurSujet.observerFusion(2, true, true); moniteurSujet.observerFusion(1, true, false) })
        col.addView(bouton("3B fusion : sujet perdu") { activerObs(); moniteurSujet.observerFusion(2, true, true); moniteurSujet.observerFusion(0, false, false) })
        col.addView(bouton("3B fusion : incoherent") { activerObs(); moniteurSujet.observerIncoherence(true) })
        col.addView(bouton("3B fusion : sujet retrouve") { activerObs(); moniteurSujet.observerFusion(0, false, false); moniteurSujet.observerFusion(2, true, true) })
        col.addView(bouton("3B pred : disponible") { activerObs(); moniteurSujet.observerPrediction(0); moniteurSujet.observerPrediction(1) })
        col.addView(bouton("3B pred : suspendue") { activerObs(); moniteurSujet.observerPrediction(1); moniteurSujet.observerPrediction(3) })
        col.addView(bouton("3B pred : trajectoire incertaine") { activerObs(); moniteurSujet.observerTrajectoireIncertaine(true) })
        col.addView(bouton("3B serveur : indisponible") { activerObs(); moniteurSujet.observerServeur(2); moniteurSujet.observerServeur(0) })
        col.addView(bouton("3B serveur : retard reseau") { activerObs(); moniteurSujet.observerServeur(2); moniteurSujet.observerServeur(1) })
        col.addView(bouton("3B serveur : reconnecte") { activerObs(); moniteurSujet.observerServeur(0); moniteurSujet.observerServeur(2) })
        col.addView(bouton("3B camera : enregistrement demarre") { activerObs(); obs.observerRecordStarted() })
        col.addView(bouton("3B camera : enregistrement arrete") { activerObs(); obs.observerRecordStopped() })
        col.addView(bouton("3B camera : enregistrement impossible") { activerObs(); obs.observerRecordFailed() })
        col.addView(bouton("3B camera : carte presque pleine") { activerObs(); obs.observerCarteSd(true, 99); obs.observerCarteSd(true, 2) })
        col.addView(bouton("3B camera : carte pleine") { activerObs(); obs.observerCarteSd(true, 99); obs.observerCarteSd(true, 0) })
        col.addView(bouton("3B camera : carte absente") { activerObs(); obs.observerCarteSd(true, 99); obs.observerCarteSd(false, -1) })
        col.addView(bouton("3B camera : sujet hors cadre") { activerObs(); obs.observerSujetHorsCadre(false); obs.observerSujetHorsCadre(true) })
        col.addView(bouton("3B : divergence vision-RTK") { activerObs(); repeat(6) { moniteurDivergence.observer(45.0, -73.0, 0.0, 45.01, -73.0, 0.95, maintenant()) } })
        col.addView(sousTitre("DIAGNOSTIC EVITEMENT (test au sol, lecture seule)"))
        col.addView(TextView(this).apply {
            text = "Passif : n'ecrit rien vers le drone, ne modifie aucune vitesse. Off par defaut. Voir Logcat tag PerceptionDiag."
            setTextColor(0xFFB0BEC5.toInt()); textSize = 11f; setPadding(dp(4), 0, dp(4), dp(8))
        })
        col.addView(bouton("Demarrer logger perception (sol)") {
            ca.cineflight.stage.sentinelle.PerceptionDiagLogger.activer()
            try { perceptionSol.demarrer() } catch (_: Throwable) {}
        })
        col.addView(bouton("Journaliser une trame maintenant") {
            try { perceptionSol.journaliserTrame() } catch (_: Throwable) {}
        })
        col.addView(bouton("Arreter logger perception") {
            try { perceptionSol.arreter() } catch (_: Throwable) {}
            ca.cineflight.stage.sentinelle.PerceptionDiagLogger.desactiver()
        })
        col.addView(bouton("3A RTH cycle deduit (montee->retour->descente->termine)") { activerObs();
            moniteurRthPhase.observer(true, 20.0, 80.0, 1.0)    // debut : montee
            moniteurRthPhase.observer(true, 50.0, 80.0, 0.0)    // plafond -> retour
            moniteurRthPhase.observer(true, 50.0, 5.0, -1.0)    // proche + descend -> descente
            moniteurRthPhase.observer(true, 1.0, 3.0, -0.5)     // pose -> termine
            moniteurRthPhase.observer(false, 0.0, 3.0, 0.0)     // RTH fini
        })
        col.addView(bouton("SCENARIO AUTOMATIQUE") { scenario() })
        col.addView(bouton("Rafraichir le journal") { majJournal() })

        journal = TextView(this).apply {
            setTextColor(0xFF9CCC65.toInt()); textSize = 11f
            setPadding(dp(4), dp(12), dp(4), dp(4))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        col.addView(journal)

        val sc = ScrollView(this); sc.addView(col); setContentView(sc)
        majJournal()
    }

    private fun pub(
        key: String,
        sev: VoiceSeverity,
        interrupt: InterruptPolicy = InterruptPolicy.NONE,
        dedup: String? = null,
        recovery: String? = null,
        expiresInMs: Long? = null
    ) {
        val t = maintenant()
        voix.publishVoiceEventSafely(
            FlightVoiceEvent(
                eventId = voix.engine.prochainId("test"),
                messageKey = key,
                source = VoiceEventSource.TEST,
                severity = sev,
                createdAtMs = t,
                expiresAtMs = expiresInMs?.let { t + it },
                deduplicationKey = dedup,
                recoveryKey = recovery,
                interruptPolicy = interrupt
            ), t
        )
        ui.postDelayed({ majJournal() }, 300)
    }

    private fun testInterruption() {
        pub("voice.test.info", VoiceSeverity.INFO)
        ui.postDelayed({ pub("battery.critical", VoiceSeverity.CRITICAL, InterruptPolicy.FLUSH, dedup = "batterie_critique") }, 150)
    }

    private fun testDedup() {
        repeat(3) { pub("battery.low", VoiceSeverity.SAFETY, dedup = "batterie_faible") }
    }

    private fun testExpiration() {
        pub("voice.test.expirable", VoiceSeverity.INFO, expiresInMs = 50L)
    }

    private fun testRecuperation() {
        pub("gps.drone.weak", VoiceSeverity.SAFETY, dedup = "gps_drone")
        ui.postDelayed({ pub("gps.drone.restored", VoiceSeverity.STATE, recovery = "gps_drone") }, 60)
    }

    private fun scenario() {
        voix.definirLangue("fr")
        pub("battery.low", VoiceSeverity.SAFETY, dedup = "batterie_faible")
        ui.postDelayed({ pub("battery.low", VoiceSeverity.SAFETY, dedup = "batterie_faible") }, 100)
        ui.postDelayed({ pub("voice.test.info", VoiceSeverity.INFO) }, 400)
        ui.postDelayed({ pub("flight.connection.lost", VoiceSeverity.CRITICAL, InterruptPolicy.FLUSH, dedup = "liaison_drone") }, 550)
        ui.postDelayed({ pub("flight.connection.restored", VoiceSeverity.STATE, recovery = "liaison_drone") }, 1500)
        ui.postDelayed({ pub("voice.test.expirable", VoiceSeverity.INFO, expiresInMs = 30L) }, 1800)
        ui.postDelayed({ majJournal() }, 3000)
    }

    private fun activerObs() {
        voix.settings.active = true
        voix.settings.observationDji = true
        voix.settings.mode = VoiceMode.DETAILLE
        ui.postDelayed({ majJournal() }, 300)
    }

    /** Simule le drone a une latitude donnee, pilote fixe a (0,0) -> distance = lat*111320 m. */
    private fun vlosSim(droneLat: Double) {
        moniteurVlos.observer(droneLat, 0.0, 0.0, 0.0, 5.0, maintenant())
        ui.postDelayed({ majJournal() }, 300)
    }

    private fun majJournal() {
        val sb = StringBuilder("- Journal (dernieres entrees) -\n")
        for (e in voix.logger.dernieres(40)) {
            sb.append(e.outcome.name + "  " + e.messageKey + "\n")
        }
        journal.text = sb.toString()
    }

    private fun titre(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 18f
        setPadding(0, 0, 0, dp(6)); typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private fun sousTitre(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFFB0BEC5.toInt()); textSize = 12f; setPadding(0, 0, 0, dp(12))
    }
    private fun bouton(t: String, action: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) }
        setOnClickListener { try { action() } catch (_: Throwable) {} }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        voix.arreterTout()
    }
}
