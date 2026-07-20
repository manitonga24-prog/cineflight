package ca.cineflight.stage.control
import ca.cineflight.stage.R

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ca.cineflight.stage.sentinelle.EtatCapteurs
import ca.cineflight.stage.sentinelle.NoyauSecurite
import ca.cineflight.stage.sentinelle.PontDrone
import ca.cineflight.stage.sentinelle.RaisonBlocage
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * SuiviTestActivity — banc d'essai du SUIVI ACTIF, 100% SIMULATION.
 *
 * >>> AUCUN DRONE RÉEL. AUCUN VOL. <<<
 * Fait tourner la chaîne complète générateur -> exécuteur -> géo-barrière -> noyau
 * sur un PONT SIMULÉ (intègre les vitesses pour déplacer une position virtuelle).
 *
 * OBJECTIF : prouver AVANT tout vol réel que :
 *   1. le drone simulé DÉCRIT UNE ORBITE autour du sujet (signes d'axes corrects) ;
 *   2. la distance reste ~constante pendant l'orbite ;
 *   3. RTK perdu -> le noyau GÈLE (drone s'arrête) ;
 *   4. cible hors zone -> la géo-barrière la RAMÈNE (drone reste dans le polygone).
 *
 * Si l'orbite part de travers -> un signe d'axe est inversé : corriger
 * ExecuteurMouvement.Config.signePitch/signeRoll/... et retester. NE PAS voler en
 * réel tant que cet écran ne montre pas une orbite propre + les gels attendus.
 */
class SuiviTestActivity : AppCompatActivity() {

    // ---- Pont SIMULÉ : implémente PontDrone, intègre les vitesses en position ----
    private class PontSimule : PontDrone {
        // position simulée du drone (départ à ~35 m au nord du sujet).
        @Volatile var lat = 45.4953   // ~33 m au nord de 45.4950
        @Volatile var lon = -73.5443
        @Volatile var altM = 40.0
        @Volatile var capDeg = 180.0  // regarde le sud (vers le sujet au départ)
        @Volatile var nacellePitch = 0.0
        @Volatile var nacelleYaw = 0.0
        private val dt = 0.1          // 10 Hz
        private val mLat = 111_320.0

        override fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double) {
            // repère BODY : pitch=avant, roll=droite, dans le repère du drone (cap).
            // on reconvertit body -> monde (est/nord) selon le cap pour déplacer.
            val capRad = Math.toRadians(capDeg)
            // avant du drone en (est,nord) = (sin cap, cos cap) ; droite = (cos cap, -sin cap)
            val est = pitch * sin(capRad) + roll * cos(capRad)
            val nord = pitch * cos(capRad) + roll * (-sin(capRad))
            val mLon = 111_320.0 * cos(Math.toRadians(lat))
            lat += (nord * dt) / mLat
            lon += (est * dt) / mLon
            altM += throttle * dt
            capDeg = ((capDeg + yaw * dt) % 360.0 + 360.0) % 360.0
        }
        override fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean) {
            nacellePitch = pitchDeg; nacelleYaw = yawDeg
        }
    }

    private val pont = PontSimule()
    private val noyau = NoyauSecurite(pont)
    private val executeur = ExecuteurMouvement(noyau)
    private val generateur = GenerateurMouvement()

    // sujet simulé : FIXE au centre (pour tester l'orbite proprement).
    private val sujetLat = 45.4950
    private val sujetLon = -73.5443

    // géo-barrière de test : carré ~200 m autour du sujet.
    private val geo = GeoBarriere(listOf(
        GeoBarriere.Point(sujetLat - 0.0009, sujetLon - 0.0009),
        GeoBarriere.Point(sujetLat - 0.0009, sujetLon + 0.0009),
        GeoBarriere.Point(sujetLat + 0.0009, sujetLon + 0.0009),
        GeoBarriere.Point(sujetLat + 0.0009, sujetLon - 0.0009)
    ))

    private val params = GenerateurMouvement.Params(
        type = GenerateurMouvement.TypeMouvement.ORBITE,
        distanceM = 35.0, hauteurM = 40.0, amplitudeDeg = 360.0
    )

    // simulateurs de panne (déclenchés par boutons) pour tester les gels.
    @Volatile private var simRtkPerdu = false
    @Volatile private var simStop = false

    private var phase = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private var enCours = false
    private lateinit var affichage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        val titre = TextView(this).apply {
            text = getString(R.string.st_titre)
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        affichage = TextView(this).apply {
            setTextColor(Color.parseColor("#8fd9a8")); textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 24)
        }
        racine.addView(titre)
        racine.addView(affichage)
        racine.addView(bouton(getString(R.string.st_demarrer)) { demarrer() })
        racine.addView(bouton(getString(R.string.st_stop)) { simStop = true })
        racine.addView(bouton(getString(R.string.st_sim_rtk)) { simRtkPerdu = !simRtkPerdu })
        racine.addView(bouton(getString(R.string.st_reset)) { reset() })
        setContentView(racine)
        majAffichage(null, 0.0, false)
    }

    private fun bouton(txt: String, action: () -> Unit): Button =
        Button(this).apply {
            text = txt
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }
        }

    private fun demarrer() {
        if (enCours) return
        enCours = true; simStop = false; simRtkPerdu = false
        executeur.reinitialiser()
        handler.post(boucle)
    }

    private fun reset() {
        enCours = false
        handler.removeCallbacksAndMessages(null)
        phase = 0.0; simStop = false; simRtkPerdu = false
        pont.lat = sujetLat + 0.0003; pont.lon = sujetLon; pont.altM = 40.0; pont.capDeg = 180.0
        executeur.reinitialiser()
        majAffichage(null, 0.0, false)
    }

    private val boucle = object : Runnable {
        override fun run() {
            if (!enCours) return
            // 1. cible du mouvement autour du sujet (fixe ici).
            val cible = generateur.cible(sujetLat, sujetLon, params, phase)
            // 2. capteurs : RTK perdu simulé -> le noyau doit geler (champ brique 4).
            val capteurs = EtatCapteurs(
                videoOk = true, yoloActif = true,
                telemetrieOk = true,
                intrusionDetectee = false,
                stopPilote = simStop,
                rtkSujetOk = !simRtkPerdu       // RTK perdu -> noyau gèle (RTK_SUJET_PERDU)
            )
            // 3. tick exécuteur : écrête géo-barrière + soumet au noyau.
            val r = executeur.tick(
                pont.lat, pont.lon, pont.altM, pont.capDeg,
                cible, geo, capteurs)
            // 4. gimbal + yaw (cadrage) — simulé.
            pont.orienterNacelle(cible.gimbalDeg, cible.yawDeg, true)

            majAffichage(r, cible.distanceReelle(pont.lat, pont.lon), r.cibleRamenee)

            // avancer la phase seulement si le mouvement est transmis (sinon on gèle).
            // avancer la phase seulement si le mouvement est transmis (sinon on gèle).
            // La vitesse angulaire de l'orbite est LIMITÉE à ce que le drone peut
            // suivre : omega_max = vitesseMax / rayon. Sinon le drone "coupe" vers
            // l'intérieur et la distance s'effondre (spirale). pas_phase adapté :
            if (r.transmis) {
                val rayon = params.distanceM.coerceAtLeast(1.0)
                val vmax = 1.5                                  // m/s (ExecuteurMouvement)
                val omegaMax = vmax / rayon                     // rad/s
                val amplRad = Math.toRadians(params.amplitudeDeg)
                val pas = if (amplRad > 1e-6) (omegaMax * 0.1) / amplRad else 0.001
                phase = (phase + pas) % 1.0
            }
            handler.postDelayed(this, 100)   // 10 Hz
        }
    }

    private fun GenerateurMouvement.Cible.distanceReelle(dLat: Double, dLon: Double): Double {
        val mLat = 111_320.0; val mLon = 111_320.0 * cos(Math.toRadians(dLat))
        return hypot((this.lon - dLon) * mLon, (this.lat - dLat) * mLat)
    }

    private fun majAffichage(r: ExecuteurMouvement.ResultatTick?, distSujet: Double, ramenee: Boolean) {
        val distDroneSujet = run {
            val mLat = 111_320.0; val mLon = 111_320.0 * cos(Math.toRadians(pont.lat))
            hypot((pont.lon - sujetLon) * mLon, (pont.lat - sujetLat) * mLat)
        }
        val statut = when {
            r == null -> "—"
            r.transmis -> getString(R.string.st_transmis)
            else -> getString(R.string.st_gele, noyau.derniereRaison)
        }
        val sb = StringBuilder()
        sb.append("Sujet   : $sujetLat, $sujetLon (fixe)\n")
        sb.append("Drone   : %.6f, %.6f\n".format(pont.lat, pont.lon))
        sb.append("Alt     : %.1f m   Cap : %.0f°\n".format(pont.altM, pont.capDeg))
        sb.append("Dist drone-sujet : %.1f m  (cible 35)\n".format(distDroneSujet))
        sb.append("Phase   : %.2f\n".format(phase))
        sb.append("Statut noyau : $statut\n")
        sb.append("Cible ramenée (géo-barrière) : ${if (ramenee) "OUI" else "non"}\n")
        if (r != null) {
            sb.append("Vitesses : pitch=%.2f roll=%.2f thr=%.2f yaw=%.1f\n"
                .format(r.pitch, r.roll, r.throttle, r.yaw))
        }
        sb.append("\nRTK perdu simulé : ${if (simRtkPerdu) "OUI (doit geler)" else "non"}")
        affichage.text = sb.toString()
        affichage.setTextColor(
            if (r != null && !r.transmis) Color.parseColor("#ff8a80")
            else Color.parseColor("#8fd9a8"))
    }
}
