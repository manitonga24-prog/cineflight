package ca.cineflight.stage.sentinelle

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ca.cineflight.stage.control.EtatCockpit
import ca.cineflight.stage.control.PiloteDrone

/**
 * SentinelleVol - Branche la Sentinelle V2 sur le VRAI vol, sans toucher au layout XML.
 *
 * Flux complet :
 *   appui long (MainActivity) -> demanderConfirmation() -> dialogue explicatif
 *     -> [Lancer] -> demarrer() -> panneau flottant en direct + boucle 10 Hz
 *       -> PROPOSER_DEPART -> dialogue mission
 *       -> bouton STOP toujours visible dans le panneau
 *
 * Le panneau flottant (overlay) est construit ENTIEREMENT en code et ajoute a la
 * vue racine de l'activite (android.R.id.content). Aucun layout XML modifie.
 *
 * >>> SECURITE <<<
 * - Toutes les commandes passent par le noyau (STOP / perte video / perte
 *   telemetrie -> vitesses nulles).
 * - L'anti-intrusion YOLO temps reel n'est PAS cable (intrusion=false). Avertissement
 *   affiche dans le dialogue de confirmation ET dans le panneau.
 * - Le depart n'est JAMAIS automatique : la mission ne se lance que si le pilote
 *   valide explicitement.
 */
class SentinelleVol(
    private val activite: Activity,
    private val pilote: PiloteDrone,
    private val sessionId: Long,
    private val lireEtat: () -> EtatCockpit,
    scoreFn: () -> Pair<Double, Double>,
    private val intrusionFn: () -> Boolean = { false },
    private val rtkSujetOkFn: () -> Boolean = { true },   // AJOUT RTK : sujet fiable ET distance >= 3 m ? (option A). Defaut true = mode sujet inactif.
    private val plafondFn: () -> Double = { Double.MAX_VALUE },
    private val plafondInfoFn: () -> String? = { null },
    private val secteurDegageFn: () -> Pair<Int, Float>? = { null },   // (index secteur, angle deg) ou null
    private val prendrePhotoFn: (Double) -> Unit = { },   // capture une photo au palier (arg = altitude m). Vide = desactive.
    private val demarrerControleVol: () -> Unit = { },
    private val onMission: () -> Unit,
    config: ConfigV2 = ConfigV2()
) {
    private val TAG = "SentinelleVol"
    private val handler = Handler(Looper.getMainLooper())
    private val dt = 0.1
    // Ecrivain unique : la Sentinelle ne touche plus le SDK. Elle DEPOSE ses
    // manoeuvres dans le creneau securite de PiloteDrone via cet adaptateur ;
    // PiloteDrone (boucle 15 Hz) reste le seul a appeler pont.envoyerVitesses.
    private val adaptateurSecurite = PontPiloteSecuriteAdapter(pilote, sessionId)
    private val runtime = SentinelleRuntime(adaptateurSecurite, scoreFn, config, dt)

    init {
        // Libelle de securite precis cote PiloteDrone : reflete le motif de blocage
        // du noyau. Cable ICI (runtime deja construit) -> aucune reference avancee.
        adaptateurSecurite.raisonBlocage = { runtime.derniereRaison }
    }

    @Volatile private var stop = false
    @Volatile private var controleLibere = true
    private var etatPrecedent: EtatV2? = null
    private var enCours = false

    // --- Panneau flottant (cree en code) ---
    private var overlay: FrameLayout? = null
    private var txtEtat: TextView? = null
    private var txtAlt: TextView? = null
    private var txtScore: TextView? = null
    private var txtIntrusion: TextView? = null

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
        activite.resources.displayMetrics).toInt()

    /**
     * Etape 1 du flux : dialogue explicatif. A appeler depuis l'appui long.
     * Si le pilote confirme, lance demarrer().
     */
    fun demanderConfirmation() {
        AlertDialog.Builder(activite)
            .setTitle("Lancer la Sentinelle de montee ?")
            .setMessage(
                "Le drone montera par paliers de 5 m, fera un scan 360 a chaque " +
                "palier, puis proposera une altitude degagee.\n\n" +
                "Anti-intrusion YOLO actif si la video du drone est diffusee.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Lancer") { _, _ -> demarrer() }
            .setCancelable(true)
            .show()
    }

    /** Etape 2 : lance la Sentinelle. Le drone DOIT etre en vol stationnaire. */
    fun demarrer() {
        if (enCours) return
        val etat = lireEtat()
        if (!etat.enVol) {
            Toast.makeText(activite,
                "Sentinelle : le drone doit etre en vol stationnaire d'abord.",
                Toast.LENGTH_LONG).show()
            return
        }
        // Prise du controle securite AVANT toute commande (ecrivain unique).
        if (!pilote.prendreControleSecurite(sessionId)) {
            Toast.makeText(activite,
                "Sentinelle indisponible : controle securite deja pris.",
                Toast.LENGTH_LONG).show()
            return
        }
        controleLibere = false
        enCours = true
        stop = false
        etatPrecedent = null
        afficherPanneau()
        // S'assure que la boucle d'emission 15 Hz de PiloteDrone tourne : sinon la
        // manoeuvre securite ne serait jamais emise vers le drone.
        demarrerControleVol()
        Log.i(TAG, "Sentinelle demarree (ecrivain unique via PiloteDrone, session $sessionId)")
        handler.post(boucle)
    }

    /** STOP pilote : gele immediatement (le noyau impose vitesses nulles). */
    fun stopPilote() {
        stop = true
        runtime.arretImmediat()
        Log.i(TAG, "STOP pilote")
        majPanneau(lireEtat(), runtime.tick(lireEtat(), stopPilote = true))
    }

    fun acquitterErreur() = runtime.acquitterErreur()

    /** Libere le controle securite (idempotent). Le suivi normal reprend la main :
     *  PiloteDrone repasse en chemin normal (hover timeout tant qu'aucune nouvelle
     *  consigne n'arrive). NON appele sur STOP pilote (on veut rester fige). */
    private fun libererControleSecurite() {
        if (controleLibere) return
        controleLibere = true
        pilote.libererSecurite(sessionId)
    }

    fun arreter() {
        libererControleSecurite()
        enCours = false
        handler.removeCallbacksAndMessages(null)
        retirerPanneau()
    }

    private val boucle = object : Runnable {
        override fun run() {
            if (!enCours) return
            val e = lireEtat()
            val etat = runtime.tick(e, stopPilote = stop, intrusionDetectee = intrusionFn(), plafondCapteurM = plafondFn(), rtkSujetOk = rtkSujetOkFn())
            majPanneau(e, etat)

            if (etat != etatPrecedent) {
                val sc = runtime.dernierScore?.let { " score=%.0f".format(it) } ?: ""
                Log.i(TAG, "%.1f m cap=%.0f -> %s%s [paliers=%d, %s]".format(
                    if (e.altitudeAgl.isNaN()) 0.0 else e.altitudeAgl,
                    if (e.capDeg.isNaN()) 0.0 else e.capDeg.toDouble(),
                    etat.name, sc, runtime.nbPaliers, runtime.derniereRaison.name))
                etatPrecedent = etat
                // CAPTURE PALIER : une photo quand le drone vient de finir son balayage
                // et evalue l'ouverture (stable a l'altitude du palier). Sert a la
                // calibration du score d'ouverture (serie 05m/10m/... a rejouer hors ligne).
                if (etat == EtatV2.EVALUATION_OUVERTURE) {
                    val alt = if (e.altitudeAgl.isNaN()) 0.0 else e.altitudeAgl
                    try { prendrePhotoFn(alt) } catch (_: Exception) {}
                    Log.i(TAG, "Photo palier a %.1f m".format(alt))
                }
            }

            when (etat) {
                EtatV2.PROPOSER_DEPART -> { enCours = false; proposerDepart() }
                EtatV2.ARRET_PLAFOND   -> { enCours = false; arretPlafond() }
                EtatV2.TERMINE         -> { enCours = false; retirerPanneau() }
                else -> handler.postDelayed(this, (dt * 1000).toLong())
            }
        }
    }

    // ---------------------------------------------------------------- PANNEAU

    private fun afficherPanneau() {
        if (overlay != null) return
        val racine = activite.findViewById<ViewGroup>(android.R.id.content) ?: return

        val carte = LinearLayout(activite).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6101418"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val titre = TextView(activite).apply {
            text = "\uD83D\uDEE1 SENTINELLE V2"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        carte.addView(titre)

        txtEtat = TextView(activite).apply {
            text = "Etat : demarrage..."
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        carte.addView(txtEtat)

        txtAlt = TextView(activite).apply {
            text = "Altitude : --"
            setTextColor(Color.WHITE); textSize = 14f
        }
        carte.addView(txtAlt)

        txtScore = TextView(activite).apply {
            text = "Score : en attente"
            setTextColor(Color.WHITE); textSize = 14f
        }
        carte.addView(txtScore)

        val avert = TextView(activite).apply {
            text = "Anti-intrusion : surveillance..."
            setTextColor(Color.parseColor("#66BB6A")); textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        txtIntrusion = avert
        carte.addView(avert)

        val btnStop = Button(activite).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setOnClickListener { stopPilote() }
        }
        carte.addView(btnStop)

        val lp = FrameLayout.LayoutParams(
            dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(80); rightMargin = dp(12)
        }

        overlay = FrameLayout(activite).apply { addView(carte, lp) }
        racine.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun majPanneau(e: EtatCockpit, etat: EtatV2) {
        val nomLisible = when (etat) {
            EtatV2.ARME                 -> "Arme"
            EtatV2.MONTEE_PALIER        -> "Montee palier ${runtime.nbPaliers}"
            EtatV2.STATIONNAIRE         -> "Stabilisation"
            EtatV2.BALAYAGE_360         -> "Scan 360 (palier ${runtime.nbPaliers})"
            EtatV2.EVALUATION_OUVERTURE -> "Evaluation"
            EtatV2.PROPOSER_DEPART      -> "Altitude degagee trouvee"
            EtatV2.ARRET_PLAFOND        -> "Plafond atteint"
            EtatV2.TERMINE              -> "Termine"
            else                        -> etat.name
        }
        val raison = runtime.derniereRaison
        val etatTxt = if (raison != RaisonBlocage.AUCUNE) "$nomLisible  (\u26D4 ${raison.name})"
                      else nomLisible
        txtEtat?.text = "Etat : $etatTxt"
        txtAlt?.text = "Altitude : %.1f m".format(if (e.altitudeAgl.isNaN()) 0.0 else e.altitudeAgl)
        txtScore?.text = runtime.dernierScore?.let { "Score : %.0f".format(it) } ?: "Score : en cours..."
        val intrusion = intrusionFn()
        txtIntrusion?.text = if (intrusion) "\u26A0 INTRUSION detectee" else "Anti-intrusion : surveillance"
        txtIntrusion?.setTextColor(
            if (intrusion) Color.parseColor("#EF5350") else Color.parseColor("#66BB6A"))
    }

    private fun retirerPanneau() {
        overlay?.let { o ->
            (o.parent as? ViewGroup)?.removeView(o)
        }
        overlay = null; txtEtat = null; txtAlt = null; txtScore = null
    }

    // ---------------------------------------------------------------- DIALOGUES

    private fun proposerDepart() {
        val sc = runtime.dernierScore?.let { "%.0f".format(it) } ?: "?"
        AlertDialog.Builder(activite)
            .setTitle("Sentinelle : altitude degagee trouvee")
            .setMessage("Ouverture suffisante (score $sc) a ${runtime.nbPaliers} palier(s). " +
                        "Lancer la mission ?")
            .setPositiveButton("Lancer la mission") { _, _ ->
                Log.i(TAG, "Pilote valide -> lancement mission")
                libererControleSecurite()
                retirerPanneau()
                onMission()
            }
            .setNegativeButton("Annuler") { _, _ -> libererControleSecurite(); retirerPanneau() }
            .setCancelable(false)
            .show()
    }

    // ===== NIVEAU 2 (structure, DESACTIVE par defaut) =====
    // Deplacement lateral autonome vers le secteur degage, PUIS relance de la montee.
    // INACTIF tant que les secteurs ne sont pas calibres en vol + valides en simulateur DJI.
    @Volatile var deplacementAutoActif: Boolean = false

    /**
     * Deplace le drone lateralement vers le secteur degage, puis relance la Sentinelle.
     * GARDE-FOU : ne fait RIEN tant que deplacementAutoActif == false.
     * A activer seulement apres : (1) observation trame reelle, (2) convention secteur->direction
     * confirmee, (3) test simulateur DJI. Le mouvement futur devra etre : lateral pur (pas de
     * montee sous l'obstacle), borne (<= 2 m), lent, evitement horizontal actif, relance montee
     * uniquement si le plafond est redevenu libre.
     */
    private fun deplacerVersSecteurDegage(secteur: Int, angleDeg: Float) {
        if (!deplacementAutoActif) {
            Log.i(TAG, "Deplacement auto desactive (calibration requise) - secteur=$secteur angle=$angleDeg")
            android.widget.Toast.makeText(activite,
                "Deplacement automatique desactive : calibration requise.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // --- A IMPLEMENTER plus tard, seulement apres calibration + simulateur ---
        // 1. convertir secteur/angle en direction drone (convention confirmee en vol)
        // 2. deplacement lateral lent (vy borne), JAMAIS de montee sous l'obstacle
        // 3. distance max 2 m
        // 4. evitement horizontal actif pendant le decalage
        // 5. relancer la montee SEULEMENT si distanceHaut() est redevenue > seuil
        Log.w(TAG, "deplacerVersSecteurDegage: actif mais logique non encore implementee (volontaire)")
    }

    private fun arretPlafond() {
        val info = plafondInfoFn()
        val baseMsg = "Plafond atteint sans trouver d'environnement assez degage " +
                      "(${runtime.nbPaliers} paliers). Mission non lancee."
        val msg = if (info != null) "$baseMsg\n\n$info" else baseMsg
        val secteur = secteurDegageFn()
        AlertDialog.Builder(activite)
            .setTitle("Sentinelle : pas d'ouverture suffisante")
            .setMessage(msg)
            .apply {
                if (secteur != null) setNeutralButton("Deplacer automatiquement") { _, _ ->
                    deplacerVersSecteurDegage(secteur.first, secteur.second)
                }
            }
            .setPositiveButton("OK") { _, _ -> libererControleSecurite(); retirerPanneau() }
            .setOnDismissListener { libererControleSecurite(); retirerPanneau() }
            .show()
    }
}

