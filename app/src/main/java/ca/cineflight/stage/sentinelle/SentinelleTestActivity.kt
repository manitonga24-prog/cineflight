package ca.cineflight.stage.sentinelle
import ca.cineflight.stage.R

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * SentinelleTestActivity — TEST EN SIMULATION (option A).
 *
 * Fait tourner la Sentinelle V2 sur un FAUX PontDrone (aucun drone réel), à ~10 Hz,
 * et affiche l'état en direct à l'écran + dans Logcat (tag "SentinelleTest").
 * Score fixe (70/80) -> la Sentinelle doit monter, scanner, puis PROPOSER_DEPART.
 *
 * Aucun risque : rien n'est envoyé à un vrai drone. C'est la logique pure qui
 * tourne, alimentée par une télémétrie simulée localement.
 */
class SentinelleTestActivity : AppCompatActivity() {

    private val TAG = "SentinelleTest"
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var vue: TextView
    private var runtime: SentinelleRuntime? = null

    // --- Faux drone : simule la physique (throttle -> altitude, yaw -> cap) ---
    // On implémente directement PiloteDrone.PontDji (signatures Float) ; c'est ce
    // que SentinelleRuntime attend. Aucun vrai drone n'est touché.
    private val dt = 0.1
    @Volatile private var altSim = 0.0
    @Volatile private var capSim = 0.0
    private val pontSimule = object : ca.cineflight.stage.control.PiloteDrone.PontDji {
        override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                     origin: ca.cineflight.stage.control.CommandOrigin) {
            // intégration simple : throttle (m/s) -> altitude, yaw (deg/s) -> cap
            altSim = (altSim + throttle * dt).coerceAtLeast(0.0)
            capSim = (capSim + yaw * dt) % 360.0
        }
        override fun orienterNacelle(pitchDeg: Float, yawDeg: Float, yawAbsolu: Boolean) { }
        override fun activerVirtualStick(actif: Boolean) { }
        override fun decoller(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun atterrir(onFini: (Boolean) -> Unit) { onFini(true) }
        override fun capDroneDeg(): Float = capSim.toFloat()
        override fun batteriePourcent(): Int = 90
        override fun estConnecte(): Boolean = true
        override fun latitudeDrone(): Double = 45.5455
        override fun longitudeDrone(): Double = -73.6868
        override fun altitudeDrone(): Double = altSim
        override fun gpsValide(): Boolean = true
        override fun demarrerEnregistrement() { }
        override fun arreterEnregistrement() { }
        override fun enregistreEnCours(): Boolean = false
        override fun modeleDrone(): String = "SIMULATEUR"
    }

    private var stopPilote = false
    @Volatile private var testIntrusion = false          // simule une personne dans le champ
    @Volatile private var testPlafondProche = false       // simule un obstacle a 2 m au-dessus
    @Volatile private var testMauvaisScore = false        // force un score insuffisant (oblige a monter)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI minimale construite en code (pas de layout XML nécessaire)
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titre = TextView(this).apply {
            text = getString(R.string.se_titre)
            textSize = 18f
        }
        racine.addView(titre)

        val boutonLancer = Button(this).apply { text = getString(R.string.se_lancer) }
        racine.addView(boutonLancer)

        val boutonStop = Button(this).apply { text = getString(R.string.se_stop) }
        racine.addView(boutonStop)

        val boutonIntrusion = Button(this).apply { text = getString(R.string.se_intrusion_off) }
        racine.addView(boutonIntrusion)
        boutonIntrusion.setOnClickListener {
            testIntrusion = !testIntrusion
            boutonIntrusion.text = if (testIntrusion) getString(R.string.se_intrusion_on) else getString(R.string.se_intrusion_off)
            ajouter(getString(R.string.se_dbg_intrusion, testIntrusion))
        }

        val boutonPlafond = Button(this).apply { text = getString(R.string.se_plafond_off) }
        racine.addView(boutonPlafond)
        boutonPlafond.setOnClickListener {
            testPlafondProche = !testPlafondProche
            boutonPlafond.text = if (testPlafondProche) getString(R.string.se_plafond_on) else getString(R.string.se_plafond_off)
            ajouter(getString(R.string.se_dbg_plafond, testPlafondProche))
        }

        val boutonScore = Button(this).apply { text = getString(R.string.se_score_off) }
        racine.addView(boutonScore)
        boutonScore.setOnClickListener {
            testMauvaisScore = !testMauvaisScore
            boutonScore.text = if (testMauvaisScore) getString(R.string.se_score_on) else getString(R.string.se_score_off)
            ajouter(getString(R.string.se_dbg_score, testMauvaisScore))
        }

        val scroll = ScrollView(this)
        vue = TextView(this).apply {
            text = getString(R.string.se_pret)
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        scroll.addView(vue)
        racine.addView(scroll)

        setContentView(racine)

        boutonLancer.setOnClickListener { lancer() }
        boutonStop.setOnClickListener {
            stopPilote = true
            ajouter(getString(R.string.se_stop_presse))
        }
    }

    private fun lancer() {
        // réinitialise
        altSim = 0.0; capSim = 0.0; stopPilote = false
        vue.text = getString(R.string.se_demarrage)

        // Sentinelle avec score FIXE (70 score, 80 confiance) -> doit proposer
        runtime = SentinelleRuntime(
            pont = PontDjiAdapter(pontSimule),
            scoreFn = { if (testMauvaisScore) Pair(20.0, 80.0) else Pair(70.0, 80.0) },
            config = ConfigV2(),
            dt = dt
        )

        var etatPrecedent: EtatV2? = null
        val boucle = object : Runnable {
            override fun run() {
                val rt = runtime ?: return
                // EtatCockpit simulé localement (vidéo OK, connecté, etc.)
                val etatCockpit = ca.cineflight.stage.control.EtatCockpit(
                    connecte = true,
                    enVol = true,
                    batteriePct = 90,
                    altitudeAgl = altSim,
                    capDeg = capSim.toFloat(),
                    signalVideoPct = 100,   // vidéo OK -> videoOk = true
                    signalRcPct = 100
                )
                val etat = rt.tick(
                    etatCockpit,
                    stopPilote = stopPilote,
                    intrusionDetectee = testIntrusion,
                    plafondCapteurM = if (testPlafondProche) 2.0 else Double.MAX_VALUE
                )

                // log seulement les changements d'état (sinon spam)
                if (etat != etatPrecedent) {
                    val score = rt.dernierScore?.let { " score=%.0f".format(it) } ?: ""
                    val msg = "%5.1f m  cap=%3.0f  -> %s%s  [paliers=%d, %s]".format(
                        altSim, capSim, etat.name, score, rt.nbPaliers, rt.derniereRaison.name
                    )
                    ajouter(msg)
                    Log.i(TAG, msg)
                    etatPrecedent = etat
                }

                // continuer tant qu'on n'a pas fini
                if (etat != EtatV2.TERMINE && etat != EtatV2.PROPOSER_DEPART && etat != EtatV2.ARRET_PLAFOND) {
                    handler.postDelayed(this, (dt * 1000).toLong())
                } else {
                    ajouter(getString(R.string.se_fin, etat))
                    if (etat == EtatV2.PROPOSER_DEPART) {
                        ajouter(getString(R.string.se_validerait))
                    }
                }
            }
        }
        handler.post(boucle)
    }

    private fun ajouter(ligne: String) {
        vue.append(ligne + "\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        runtime = null
    }
}

