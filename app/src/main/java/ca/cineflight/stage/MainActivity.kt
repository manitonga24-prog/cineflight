package ca.cineflight.stage

import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.control.EtatCockpit
import ca.cineflight.stage.control.FluxCamera
import ca.cineflight.stage.control.PiloteDrone
import ca.cineflight.stage.control.PontCockpit
import ca.cineflight.stage.control.PontDjiReelCockpit
import ca.cineflight.stage.control.PontDjiSimuleCockpit
import ca.cineflight.stage.control.GestionnaireModeVol
import ca.cineflight.stage.control.RecepteurBridge
import ca.cineflight.stage.control.RecepteurBoxes
import ca.cineflight.stage.control.OverlayYolo
import com.tencent.yolo11ncnn.YOLO11Ncnn
import ca.cineflight.stage.control.YoloSuivi
import ca.cineflight.stage.control.CapacitesDrone
import ca.cineflight.stage.control.CommandeVocale
import ca.cineflight.stage.control.EcouteContinue
import ca.cineflight.stage.control.Macros
import ca.cineflight.stage.control.Reglages
import ca.cineflight.stage.control.StreamRtsp
import ca.cineflight.stage.control.LecteurMissionKmz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
// --- Moteur cine (assistant realisateur) : socle + pont ---
import ca.cineflight.stage.cine.AssistantRealisateur
import ca.cineflight.stage.cine.ClientClicker
import ca.cineflight.stage.cine.CommandesVol
import ca.cineflight.stage.cine.ContexteValidation
import ca.cineflight.stage.cine.Espace
import ca.cineflight.stage.cine.Evitement
import ca.cineflight.stage.cine.ProfilDrone

/**
 * MainActivity - COCKPIT de pilotage manuel (sticks RC-N1).
 *
 * PILOTAGE : en MANUEL, les sticks de la RC-N1 pilotent le drone (Virtual Stick
 * En MANUEL les sticks RC pilotent (Virtual Stick OFF). L'app affiche la telemetrie.
 * offre des commandes annexes (photo, REC, gimbal tilt, RTH). Le toggle AUTO
 * passe la main au bridge (QUESTSERVER) comme avant.
 *
 * TELEMETRIE : boucle ~3 Hz qui lit un EtatCockpit et met a jour bandeau + alertes.
 * (batterie faible, perte GPS, perte signal, deconnexion).
 *
 * MODE_SIMULE=true : tout tourne sans drone ni SDK (pont simule).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_SIMULE = false
        // MAILLON 2 - observation passive perception d'obstacle, chemin NORMAL.
        // false = comportement identique a aujourd'hui. NE MODIFIE JAMAIS de commande.
        private const val PERCEPTION_NORMAL_PATH_OBSERVATION_ENABLED = true
        // Verrou de développement : le Clicker modifie seulement un état virtuel.
        const val CLICKER_MODE_SIMULATION = true
        // SPORT SOCCER — observation MIROIR (Phase 8). false = comportement identique
        // a aujourd'hui : le pipeline soccer ne tourne pas. true = on OBSERVE seulement
        // (logs SOCCER_MIRROR) ; AUCUNE commande n'est jamais envoyee au drone.
        const val SOCCER_MIRROR_ENABLED = false
        const val PORT_BRIDGE = 9100
        // seuils d'alerte
        const val BATT_FAIBLE = 25
        const val BATT_CRITIQUE = 15
        const val SIGNAL_FAIBLE = 30
        const val SAT_MIN_AUTO = 14   // satellites minimum requis pour le vol automatique
        // position du drone partagee avec l'ecran carte (mise a jour dans majCockpit)
        @JvmStatic var derniereLatCarte = Double.NaN
        @JvmStatic var derniereLonCarte = Double.NaN
        @JvmStatic var dernierCapCarte = Float.NaN
        @JvmStatic var derniereGpsOkCarte = false
        // code drone pour la meteo serveur (mini3|mini4|air3|mavic3), maj a chaque chargement meteo
        @JvmStatic var codeMeteoDrone: String = "mini3"
        // code profil drone pour la generation de mission (mini4pro|air3|m30)
        @JvmStatic var codeMissionDrone: String = "mini4pro"
        // plage de temperature FABRICANT du drone (min/max en C), maj a chaque releve meteo
        @JvmStatic var tempMinDrone: Int = -10
        @JvmStatic var tempMaxDrone: Int = 40
        @JvmStatic var ancreLatCarte = Double.NaN
        @JvmStatic var ancreLonCarte = Double.NaN
        // Intention de lumiere en attente (depuis l'assistant Lumieres du jour).
        // Consommee dans onResume -> lance DefinitionSujetActivity avec le contexte lumiere.
        @JvmStatic var lumiereEnAttente: android.os.Bundle? = null
        // Contexte lumiere retenu pendant la PREPARATION (static : survit a la recreation d'activite).
        @JvmStatic var prepLumiere: android.os.Bundle? = null
        @JvmStatic var parcoursReco: List<Pair<Double, Double>> = emptyList()
        // Rail Cable-Cam defini sur la carte : A et B (lat/lon). railCarteDefini = true quand pose validee.
        @JvmStatic var railACarteLat = Double.NaN
        @JvmStatic var railACarteLon = Double.NaN
        @JvmStatic var railBCarteLat = Double.NaN
        @JvmStatic var railBCarteLon = Double.NaN
        @JvmStatic var railCarteDefini = false
        const val GIMBAL_PAS_DEG = 10f   // increment tilt par appui
    }

    private lateinit var pilote: PiloteDrone
    private lateinit var modeVol: GestionnaireModeVol
    private var sentinelle: ca.cineflight.stage.sentinelle.SentinelleVol? = null
    private val compteurSessionSentinelle = java.util.concurrent.atomic.AtomicLong(0L)
    // --- Mode sujet mobile RTK + vision V4.2 ---
    // FIX : controle complet selon les garde-fous existants.
    // FLOAT + YOLO stable : assistance reelle limitee a la nacelle;
    // vx/vy/vz sont annules avant toute soumission au pilote.
    private var profilSujetV4 = ca.cineflight.stage.control.ProfilSujetMobile.MARCHE
    private var suiviSujet = ca.cineflight.stage.control.SuiviSujetRtk(profilSujetV4.configurationSuivi())
    private val moteurFusionRtkV4 = ca.cineflight.stage.control.MoteurFusionRtkV4(profilSujetV4)
    private val autorisationControleRtkVision = ca.cineflight.stage.control.AutorisationControleRtkVision()
    @Volatile private var dernierePositionSujetV4: ca.cineflight.stage.cine.ClientRtkSujet.PositionSujet? = null
    @Volatile private var dernierVerdictControleRtkVision: ca.cineflight.stage.control.AutorisationControleRtkVision.Verdict? = null
    private var clientRtkSujetV4: ca.cineflight.stage.cine.ClientRtkSujetV4? = null
    private var jobFluxRtkV4: Job? = null
    private var jobFusionRtkV4: Job? = null
    private var jobContexteRtkV4: Job? = null
    @Volatile private var journalSessionRtkV41: ca.cineflight.stage.control.JournalSessionRtkV41? = null
    private var vueDiagnosticPredictionRtk: ca.cineflight.stage.control.VueDiagnosticPredictionRtk? = null
    private var dernierLogPredictionMs = 0L
    @Volatile private var dernierEtatSujet: ca.cineflight.stage.control.SuiviSujetRtk.EtatSujet? = null
    @Volatile private var dernierePredictionV4: ca.cineflight.stage.control.MoteurFusionRtkV4.PredictionTempsReel? = null
    private var bandeauRtkV4: TextView? = null
    // Bandeau technique "V4.2 . FIX . Hz . ms ...": utile pour le DEBUG seulement,
    // inutile pour l'utilisateur normal, et il recouvrait le menu du haut.
    // Masque par defaut. Passer a true si on veut le revoir pour diagnostiquer.
    private val AFFICHER_BANDEAU_DIAG = false
    private var pollingSujetActif = false
    // --- Brique 4 : moniteur de corridor (sujet mobile vs trajectoire prévue) ---
    private val moniteurCorridor = ca.cineflight.stage.control.MoniteurCorridor()
    // --- Géo-barrière : enveloppe de vol (le drone n'en sort jamais) ---
    @Volatile private var zoneVol: ca.cineflight.stage.cine.ClientGeoBarriere.ZoneVol? = null
    @Volatile private var droneDansZone = true   // position drone dans la zone ? (défaut true = pas de géo-barrière active)
    @Volatile private var trajectoireSuivi: ca.cineflight.stage.cine.ClientTrajectoireSuivi.TrajectoireSuivi? = null
    @Volatile private var dernierStatutCorridor: ca.cineflight.stage.control.MoniteurCorridor.Resultat? = null
    // hystérésis anti-flapping : compte de lectures consécutives hors NORMAL avant
    // de confirmer un statut dégradé (HORS_CORRIDOR/RTK_PERDU restent francs).
    private var comptHorsNormal = 0
    // bandeau d'affichage du statut corridor (créé par code au 1er affichage).
    private var bandeauCorridor: TextView? = null

    // ---- CLICKER : bandeau flottant en bas affichant la commande recue
    //      de la telecommande du sujet. AFFICHE seulement, ne pilote RIEN (le
    //      NoyauSecurite reste souverain). Poll decouple du flux RTK. ----
    private var bandeauClicker: TextView? = null
    // MAILLON 2 : bandeau flottant d'observation passive de la perception d'obstacle.
    // Affiche a l'ecran l'etat des trames capteur (sans Logcat). N'agit sur RIEN.
    private var bandeauPerception: TextView? = null
    private var jobPollClicker: Job? = null
    private val clientValidationClicker =
        ca.cineflight.stage.control.MouvementServeurClient()
    private val simulateurClicker =
        ca.cineflight.stage.control.SimulateurClicker(this)
    private val verificateurDegagementClicker by lazy {
        ca.cineflight.stage.cine.VerificateurDegagementClicker(this, analyseurLieu)
    }
    @Volatile private var verificationCartographiqueClickerEnCours = false
    private var eventIdClickerEnCours: Long? = null
    private var ttsClicker: TextToSpeech? = null
    private var ttsClickerPret = false
    private var nbEvenementsClicker = 0
    private val lecteurPerception = ca.cineflight.stage.sentinelle.LecteurPerception()
    private val fusionPerception = ca.cineflight.stage.sentinelle.FusionPerception(lecteurPerception, active = false)
    private lateinit var pont: PontCockpit
    private var lecteurMission: LecteurMissionKmz? = null
    private var miniCarteVue: org.osmdroid.views.MapView? = null
    private var radarVue: ca.cineflight.stage.control.RadarView? = null
    // mode boite : 0 = rien, 1 = carte, 2 = radar
    private var modeAffichage = 0
    private var marqueurMini: org.osmdroid.views.overlay.Marker? = null
    private var traceParcours: org.osmdroid.views.overlay.Polyline? = null   // trajectoire mission
    private var recoPosesChargees: List<Pair<Double, Double>> = emptyList()
    private var recoChargee = false
    private var recoKmzPath: String? = null
    private var miniVisible = false
    // Capture Auto Intelligente : photo auto quand le sujet est bien compose
    private var captureAutoActive = false
    private var dernierePhotoAutoMs = 0L
    private var compteurPhotosAuto = 0
    /** Reference typee au pont reel (null en simule) pour reabonner les listeners. */
    private var pontReel: PontDjiReelCockpit? = null
    private var observateurVocal: ca.cineflight.stage.voice.DjiVoiceObservationAdapter? = null
    private var moniteurVlos: ca.cineflight.stage.voice.VlosDistanceMonitor? = null
    private var moniteurAltitude: ca.cineflight.stage.voice.AltitudeMonitor? = null
    private var moniteurSujet: ca.cineflight.stage.voice.SubjectVoiceMonitor? = null
    private var moniteurDivergence: ca.cineflight.stage.voice.DivergenceVisionRtk? = null
    private var moniteurRthPhase: ca.cineflight.stage.voice.RthPhaseMonitor? = null
    private lateinit var recepteur: RecepteurBridge
    private var flux: FluxCamera? = null
    private lateinit var recepteurBoxes: RecepteurBoxes
    private lateinit var overlayYolo: OverlayYolo

    // --- SPORT SOCCER (observation MIROIR, Phase 8) : pipeline PUR, aucune commande drone. ---
    private val soccerEstimator = ca.cineflight.stage.sport.soccer.SoccerActionEstimator()
    private val soccerTracker = ca.cineflight.stage.sport.soccer.SoccerActionTracker()
    private val soccerMirror = ca.cineflight.stage.sport.soccer.SoccerMirrorPlanner()
    // Suivi MULTI-JOUEURS (section 4-5) : tracker identites + centre du groupe principal.
    private val soccerPlayerTracker = ca.cineflight.stage.sport.soccer.PlayerTracker()
    // Rail d'essai par defaut (remplace plus tard par le profil SOCCER_RAIL du Web).
    private val soccerRail = ca.cineflight.stage.sport.soccer.DroneRail(
        start = ca.cineflight.stage.sport.soccer.RailPoint(45.0, -73.0),
        end = ca.cineflight.stage.sport.soccer.RailPoint(45.0, -72.999),
    )
    // Position ACTUELLE (simulee) du drone sur le rail, en mode miroir : le centre.
    private var soccerRailPos = 0.5f
    private lateinit var txtSujet: android.widget.TextView
    private val yolo = YOLO11Ncnn()
    private var yoloSuivi: YoloSuivi? = null
    private var vocal: CommandeVocale? = null
    private var ecoute: EcouteContinue? = null
    private val macros by lazy { Macros(this) }
    val reglages by lazy { Reglages(this) }
    private var jobMacro: kotlinx.coroutines.Job? = null
    private lateinit var txtCommandeVoc: android.widget.TextView
    private var cibleHPlan = 0.55f   // cadrage courant (americain par defaut)
    private var mouvementActuel = 0   // 0=statik 1=orbite 2=travel 3=revel
    @Volatile private var facteurVitesseCine = 1f   // RALENTI cine : multiplie les vitesses (<=1 = plus lent)
    private val cableCam = ca.cineflight.stage.control.CableCam()   // rail virtuel A->B
    private val hyperlapse = ca.cineflight.stage.control.Hyperlapse()  // capture photo periodique pendant le rail
    private var hyperlapseArme = false   // mode hyperlapse arme pour le prochain rail
    @Volatile private var dernierCxSujet = 0.5f   // derniere position horizontale du sujet (0..1)
    @Volatile private var dernierSujetTrouve = false
    // --- Orbite reglable (hauteur fiable via altitude AGL, rayon estime via taille image) ---
    private var orbiteHauteur = 3.0f   // metres AGL cible pendant l'orbite
    private var orbiteRayon = 6.0f     // metres : distance drone-sujet cible
    private var calibK = 1.7f          // constante distance = calibK / h (calibree terrain)

    // Profil de capacites du drone connecte (evitement d'obstacles -> plafond de recul).
    // Mis a jour a la connexion ; AUCUN evitement par defaut (prudent).
    private var profilDrone: CapacitesDrone.Profil =
        CapacitesDrone.analyser(null)
    private var avertiReculAveugle = false   // pour n'avertir qu'une fois par session
    private var avertiBatterieRec = false     // suggestion d'arret batterie basse : une fois
    private var etaitEnVolPourRec = false      // suivi du passage vol->sol pour couper le REC

    // vues telemetrie
    private lateinit var txtBatterie: TextView
    private lateinit var txtAltitude: TextView
    private lateinit var txtDistance: TextView
    private var txtStockage: TextView? = null
    private lateinit var txtVitesse: TextView
    private lateinit var txtGps: TextView
    private lateinit var txtCap: TextView
    private lateinit var txtSignalRc: TextView
    private lateinit var txtSignalVideo: TextView
    private lateinit var txtAlerte: TextView
    private lateinit var vEtat: TextView
    private lateinit var vStats: TextView
    private lateinit var btnMode: Button
    private lateinit var btnRec: Button
    private var voyantRec: android.widget.TextView? = null
    private var pastilleMeteo: android.widget.TextView? = null
    private var derniereConditionsMeteo: ca.cineflight.stage.control.ConditionsVol.Conditions? = null

    // Masquage auto des controles de config en vol (null = jamais applique encore)
    private var configMasqueeEnVol: Boolean? = null

    // === TEST UNIQUEMENT : mettre a true pour voir le cockpit "replie" sans decoller.
    // REMETTRE A false avant un vrai usage, sinon les controles restent caches au sol. ===
    private val TEST_FORCER_VOL = false

    private var modeAuto = false
    private var evCourant = 0f
    // pont + pilote DEDIES a la simulation de mission (jamais le vrai drone)
    private var pontSim: PontDjiSimuleCockpit? = null
    private var piloteSim: PiloteDrone? = null
    private var cibleVerrouillee = false   // le drone ne suit QUE si une cible est verrouillee
    // --- Point d'ancrage GPS facon "chien fidele" ---
    private var ancreLat = Double.NaN      // position du drone quand il voit bien la cible
    private var ancreLon = Double.NaN
    private var ancreValide = false
    private val SEUIL_BIEN_VU = 0.30f      // hauteur de boite mini pour considerer "bien vu"
    private var tPerteCible = 0L           // moment ou la cible a ete perdue (0 = vue)
    private val DELAI_AVANT_RETOUR = 3000L // ms de hover avant de lancer le retour GPS
    private val DIST_MIN_SUIVI_M = 4.0f    // distance mini de securite en SUIVI (mode 7), EXPERIMENTAL (> approche 2.5m)
    // Info de suivi remontee par YoloSuivi.onSuiviInfo (garde-fous top-down).
    // OBSERVATION uniquement pour l'instant : affichee a l'ecran, pas encore
    // utilisee pour piloter. A valider en vol avant tout mode autonome.
    @Volatile private var derniereConfianceSujet = 0f
    @Volatile private var dernierNbPersonnes = 0
    @Volatile private var derniereHauteurBoiteSujet = 0f
    private var gimbalCible = 0f   // tilt courant demande (deg)

    // === MOTEUR CINE : pont entre l'assistant et les mouvements REELS de l'app ===
    // Ne reimplemente aucun mouvement : pilote l'app comme des appuis boutons.
    private val commandesCine = object : CommandesVol {
        override fun appliquerPlan(cibleHPlan: Float) {
            this@MainActivity.cibleHPlan = cibleHPlan
        }
        override fun appliquerMouvement(code: Int) {
            mouvementActuel = code
            majValeursMouvement()
        }
        override fun assurerModeAuto() {
            if (!modeAuto) basculerMode(true)
        }
        override fun terminer() {
            mouvementActuel = 0          // retour Statique, sans couper le suivi
            facteurVitesseCine = 1f      // fin d'un plan : on annule tout ralenti
            majValeursMouvement()
        }
        override fun reglerVitesseCine(facteur: Float) {
            // RALENTI : ralentit UNIQUEMENT. Jamais > 1 (pas d'acceleration), plancher 0.2.
            facteurVitesseCine = facteur.coerceIn(0.2f, 1f)
        }
        override fun bandeau(message: String) {
            txtCommandeVoc.text = message
            txtCommandeVoc.visibility = android.view.View.VISIBLE
            txtCommandeVoc.removeCallbacks(null)
            txtCommandeVoc.postDelayed(
                { txtCommandeVoc.visibility = android.view.View.GONE }, 2500
            )
        }
    }
    private val assistantCine by lazy { AssistantRealisateur(lifecycleScope, commandesCine, reglagesCine) }

    // ============ AUTO-CADRAGE SOLO : decollage + montee + cadrage ; lancement du plan MANUEL ============
    @Volatile private var autoControleDirect = false   // true = l'auto-cadrage commande (onSujet se tait)
    @Volatile private var autoSujetPresent = false
    @Volatile private var autoCadrageOk = false
    private var jobMontee: kotlinx.coroutines.Job? = null
    private var jobTickAuto: kotlinx.coroutines.Job? = null
    private var autoCadrage: ca.cineflight.stage.control.AutoCadrageSolo? = null

    private fun envoyerHoverAuto() {
        pilote.soumettre(RecepteurBridge.CommandeBridge(
            System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))
    }
    /** Montee DOUCE vers l'altitude cible : vz asservi (comme l'orbite), uniquement vers le haut,
     *  plafonne a +0.5 m/s, avec l'evitement d'obstacles PRIORITAIRE par-dessus. */
    private fun demarrerMonteeAuto(cibleM: Double) {
        jobMontee?.cancel()
        jobMontee = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val e = pont.lireEtat(pilote.enVol)
                val alt = e.altitudeAgl
                val vz = if (alt.isNaN()) 0.3f else ((cibleM - alt) * 0.4).toFloat().coerceIn(0f, 0.5f)
                val v = appliquerEvitement(0f, 0f, vz, 0f)
                pilote.soumettre(RecepteurBridge.CommandeBridge(
                    System.currentTimeMillis().toDouble(), v.vx, v.vy, v.vz, v.yaw, "actif", System.currentTimeMillis()))
                delay(100)
            }
        }
    }

    private val actionsAuto = object : ca.cineflight.stage.control.ActionsAuto {
        override fun decoller(onFini: (Boolean) -> Unit) { pilote.decoller { ok -> runOnUiThread { onFini(ok) } } }
        override fun commencerMontee(altitudeCibleM: Double) {
            autoControleDirect = true
            if (!modeAuto) basculerMode(true)
            demarrerMonteeAuto(altitudeCibleM)
        }
        override fun maintenirPosition() {
            jobMontee?.cancel(); jobMontee = null
            if (autoControleDirect) envoyerHoverAuto()   // en mode suivi, onSujet tient deja la position
        }
        override fun activerCadrage() {
            jobMontee?.cancel(); jobMontee = null
            autoControleDirect = false                    // le suivi YOLO existant reprend : cadre si present, tient si absent
            if (!modeAuto) basculerMode(true)
            if (!cibleVerrouillee) verrouillerCible()
        }
        override fun arreterTout() {
            jobMontee?.cancel(); jobMontee = null
            jobTickAuto?.cancel(); jobTickAuto = null
            autoControleDirect = false
            deverrouillerCible()
            pilote.arretUrgence()
            modeAuto = false; majBoutonMode()
        }
        override fun onEtat(etat: ca.cineflight.stage.control.EtatAuto, message: String) {
            runOnUiThread {
                val fini = etat == ca.cineflight.stage.control.EtatAuto.ANNULE || etat == ca.cineflight.stage.control.EtatAuto.ARRET
                panneauRecettes.majEtatAuto(message, autoCadrage?.pretAConfirmer == true, fini)
            }
        }
    }

    private fun demarrerAutoCadrage() {
        val m = autoCadrage ?: ca.cineflight.stage.control.AutoCadrageSolo(this, actionsAuto).also { autoCadrage = it }
        m.reset()
        autoSujetPresent = false; autoCadrageOk = false; autoControleDirect = false
        jobTickAuto?.cancel()
        jobTickAuto = lifecycleScope.launch(Dispatchers.Main) {
            var dernier = System.currentTimeMillis()
            m.demarrer()
            while (isActive && m.enCours) {
                val maintenant = System.currentTimeMillis()
                val dt = maintenant - dernier; dernier = maintenant
                val alt = pont.lireEtat(pilote.enVol).altitudeAgl
                m.tick(dt, alt, autoSujetPresent, autoCadrageOk)
                delay(250)
            }
        }
    }
    // ============ fin AUTO-CADRAGE SOLO ============
    /** Reglages ajustables de l'assistant (duree, vitesse, pivot). */
    private val reglagesCine by lazy { ca.cineflight.stage.cine.ReglagesCine(this) }
    /** Analyseur vision (couche premium). FACTICE pour l'instant : a remplacer par
     *  l'implementation reelle quand le fournisseur LLM vision sera choisi. */
    private val analyseurVision: ca.cineflight.stage.cine.AnalyseurVision =
        ca.cineflight.stage.cine.AnalyseurVisionFactice()
    /** Analyseur de lieu : client du serveur Explorer (techno "preparer le lieu"). */
    private val analyseurLieu: ca.cineflight.stage.cine.AnalyseurLieu =
        ca.cineflight.stage.cine.AnalyseurLieuServeur()
    /** Panneau de recettes a deux etapes (overlay). Reutilise l'assistant et le contexte reel. */
    private val panneauRecettes by lazy {
        ca.cineflight.stage.cine.PanneauRecettes(
            this, assistantCine,
            fournirContexte = { contexteCineReel() },
            etatVolOk = {
                val e = pont.lireEtat(pilote.enVol)
                val enVol = e.enVol || TEST_FORCER_VOL
                when {
                    !e.connecte -> getString(R.string.ma_plan_drone_deco)
                    !enVol      -> getString(R.string.ma_plan_envol)
                    e.batteriePct in 0 until BATT_FAIBLE -> getString(R.string.ma_plan_batt, e.batteriePct, BATT_FAIBLE)
                    else -> null
                }
            },
            estPoseEtPret = {
                val e = pont.lireEtat(pilote.enVol)
                e.connecte && !(e.enVol || TEST_FORCER_VOL) && e.batteriePct >= BATT_FAIBLE
            },
            decollerAuSol = { onFini -> pilote.decoller { ok -> onFini(ok) } },
            lancerAnalyse = { pivoter, onRapport ->
                lancerAnalyseCine(pivoter) { r -> runOnUiThread { onRapport(r) } }
            },
            reglages = reglagesCine,
            analyseurVision = analyseurVision,
            // CAPTURE IMAGE : v1 renvoie vide (l'analyseur factice n'en a pas besoin).
            // TODO reel : extraire une frame NV21 du flux camera (meme source que YoloSuivi)
            //             et la convertir en JPEG base64 ici, quand le fournisseur sera branche.
            capturerImages = { emptyList() },
            lancerCoroutine = { bloc -> lifecycleScope.launch { bloc() } },
            analyseurLieu = analyseurLieu,
            // Position pour l'analyse de lieu : on utilise le GPS du drone (fix requis).
            fournirPosition = {
                // 1) position du drone si connecte avec fix GPS
                val e = pont.lireEtat(pilote.enVol)
                if (e.gpsValide && !e.latitude.isNaN() && !e.longitude.isNaN())
                    e.latitude to e.longitude
                else
                    positionTelephone()   // 2) sinon, GPS du telephone (mode 3 sans drone)
            },
            demanderPositionFraiche = { cb -> demanderPositionFraiche(cb) },
            geocoderAdresse = { adresse, cb ->
                lifecycleScope.launch {
                    val res = analyseurLieu.geocoder(adresse)
                    cb(res)
                }
            },
            lancerPanoramaPaysage = { preset, onProgres, onFini ->
                dernierPanoramaNb = preset.nbPhotos()
                lancerPanoramaPaysage(preset, onProgres) { nb ->
                    if (nb > 0) dernierPanoramaNb = nb
                    onFini(nb)
                }
            },
            annulerPanorama = { annulerPanorama() },
            assemblerPano360 = { onProgres, onFini -> assemblerPanorama360(onProgres, onFini) },
            // === LECTURE MISSION KMZ ? TOUJOURS EN SIMULATION ===
            // Le sequenceur tourne TOUJOURS sur un pont SIMULE dedie (jamais le vrai
            // drone). Aucun deblocage requis : se lance directement a la demande.
            lancerMissionSimulee = { fichier, onProgres, onFini ->
                // 1) lire le 1er waypoint du KMZ pour positionner le drone simule la-bas
                val depart = LecteurMissionKmz.premierPoint(fichier)
                if (depart == null) {
                    android.widget.Toast.makeText(this, getString(R.string.ma_toast_kmz_illisible),
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 2) pont + pilote SIMULES dedies (le vrai drone ne recoit RIEN)
                    val ps = PontDjiSimuleCockpit(depart.first, depart.second)
                    val pp = PiloteDrone(this, ps)
                    pontSim = ps
                    piloteSim = pp
                    pp.demarrer(lifecycleScope)              // Virtual Stick simule
                    pp.decoller { }                           // "decolle" en simule
                    val lect = LecteurMissionKmz(pp, ps,
                        onProgression = { i, n, etat -> onProgres(i, n, etat) },
                        onTermine = { onFini() })
                    lecteurMission = lect
                    val n = lect.charger(fichier)
                    if (n >= 2) {
                        // trace la trajectoire sur la mini-carte + l'ouvre
                        tracerParcoursMission(lect.pointsParcours())
                        lifecycleScope.launch {
                            delay(2000)                       // stabilisation "decollage"
                            lect.lancer(lifecycleScope)
                        }
                    } else {
                        android.widget.Toast.makeText(this, getString(R.string.ma_toast_mission_vide, n),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            arreterMissionSimulee = {
                lecteurMission?.arreter()
                piloteSim?.arreter()
            },
            // Vol REEL d'une mission KMZ generee dans l'app (ex. plan de haut).
            // Confirmation explicite -> executerVol (upload + progression + arret RTH).
            lancerMissionReelle = { kmz ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
                    .setTitle(getString(R.string.ma_dlg_mission_titre))
                    .setMessage(getString(R.string.ma_dlg_mission_reelle_msg))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(getString(R.string.ma_dlg_decoller)) { _, _ -> executerVol(kmz) }
                    .show()
            },

            debloquerSimulation = {
                // plus de barriere : la simulation se lance toujours directement
            },
            demarrerAutoCadrageCb = { demarrerAutoCadrage() },
            commandeAutoCadrage = { action -> when (action) {
                "arret" -> autoCadrage?.arret()
                "annuler" -> autoCadrage?.annuler()
                "lancer" -> { jobTickAuto?.cancel(); autoControleDirect = false }
                else -> {}
            } },
            ramenerDrone = {
                // stoppe tout mouvement en cours, PUIS retour maison + atterrissage au point de depart
                try { assistantCine.annuler() } catch (_: Exception) {}
                try { autoCadrage?.arret() } catch (_: Exception) {}
                try { deverrouillerCible() } catch (_: Exception) {}
                try { pilote.arretUrgence() } catch (_: Exception) {}
                modeAuto = false; majBoutonMode()
                pont.lancerRth { }
            },
            onDefinirSujet = {
                startActivityForResult(android.content.Intent(this, DefinitionSujetActivity::class.java), REQ_DEF_SUJET)
            },
            onVerifierMission = { verifierMissionComplete() },
            onAnalyserPhotos = { startActivity(android.content.Intent(this, ReconnaissanceActivity::class.java)) },
            onMissionsPreparees = { ouvrirMissionsPreparees() },
            fournirMissionsPreparees = {
                val st = ca.cineflight.stage.cine.MissionsPrepareesStore(this)
                st.nettoyerOrphelines()
                st.lister()
            },
            onLancerMissionPreparee = { id ->
                val st = ca.cineflight.stage.cine.MissionsPrepareesStore(this)
                val m = st.parId(id)
                if (m != null) confirmerMissionPreparee(m)
                else android.widget.Toast.makeText(this, getString(R.string.ma_toast_mission_introuvable), android.widget.Toast.LENGTH_LONG).show()
            },
            onReglerEv = { ev -> try { pont.reglerEv(ev) } catch (_: Exception) {} },
            onReglerModeExpo = { m -> try { pont.reglerModeExpo(m) } catch (_: Exception) {} },
            onReglerIso = { iso -> try { pont.reglerIso(iso) } catch (_: Exception) {} },
            onReglerShutter = { s -> try { pont.reglerShutter(s) } catch (_: Exception) {} },
            onReglerWb = { wb -> try { pont.reglerWb(wb) } catch (_: Exception) {} },
            onReglerResFps = { res, fps -> try { pont.reglerResolutionFps(res, fps) } catch (_: Exception) {} },
            fournirEtatCamera = {
                arrayOf(
                    try { pont.lireEvBrut() } catch (_: Exception) { null },
                    try { pont.lireIsoBrut() } catch (_: Exception) { null },
                    try { pont.lireModeExpoBrut() } catch (_: Exception) { null },
                    try { pont.lireShutterBrut() } catch (_: Exception) { null },
                    try { pont.lireWbBrut() } catch (_: Exception) { null }
                )
            }
        )
    }
    /** Agregateur de reperage (analyse de scene). Alimente par YoloSuivi.onDetections. */
    private val agregateurCine = ca.cineflight.stage.cine.AgregateurReperage()
    @Volatile private var analyseCineEnCours = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialiserVoixClicker()
        // ping ACK de diagnostic retire : affichait un Toast "Erreur ACK : failed to connect" au demarrage
        // Disclaimer obligatoire au premier lancement
        if (!DisclaimerActivity.dejaAccepte(this)) {
            startActivity(android.content.Intent(this, DisclaimerActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // mini-carte (osmdroid) : init, cachee au depart
        try {
            org.osmdroid.config.Configuration.getInstance().load(this,
                android.preference.PreferenceManager.getDefaultSharedPreferences(this))
            org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
            miniCarteVue = findViewById(R.id.miniCarte)
            miniCarteVue?.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            miniCarteVue?.setMultiTouchControls(true)
            miniCarteVue?.controller?.setZoom(16.0)
            val mk = org.osmdroid.views.overlay.Marker(miniCarteVue)
            mk.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
            miniCarteVue?.overlays?.add(mk)
            marqueurMini = mk
        } catch (_: Exception) {}

        // radar (toggle avec la carte via btnCarte). Clic sur le radar -> agrandi.
        try {
            radarVue = findViewById(R.id.radarVue)
            radarVue?.setOnClickListener { agrandirRadar() }
        } catch (_: Exception) {}

        // --- liaison des vues ---
        txtBatterie = findViewById(R.id.txtBatterie)
        txtAltitude = findViewById(R.id.txtAltitude)
        txtDistance = findViewById(R.id.txtDistance)
        txtStockage = findViewById(R.id.txtStockage)
        txtVitesse = findViewById(R.id.txtVitesse)
        txtGps = findViewById(R.id.txtGps)
        txtCap = findViewById(R.id.txtCap)
        txtSignalRc = findViewById(R.id.txtSignalRc)
        txtSignalVideo = findViewById(R.id.txtSignalVideo)
        txtAlerte = findViewById(R.id.txtAlerte)
        vEtat = findViewById(R.id.texteEtat)
        vStats = findViewById(R.id.texteStats)
        btnMode = findViewById(R.id.btnMode)
        btnRec = findViewById(R.id.btnRec)
        voyantRec = findViewById(R.id.voyantRec)
        val surfaceFlux = findViewById<SurfaceView>(R.id.surfaceFlux)
        overlayYolo = findViewById(R.id.overlayYolo)
        txtSujet = findViewById(R.id.txtSujet)
        vueDiagnosticPredictionRtk = ca.cineflight.stage.control.VueDiagnosticPredictionRtk(this, miniCarteVue)

        // --- pastille METEO (conditions de vol) : ajoutee en code par-dessus le layout ---
        // Discrete, en haut a gauche sous la barre d'etat. Touchee -> detail.
        try {
            val racine = findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup
            pastilleMeteo = android.widget.TextView(this).apply {
                text = "\uD83C\uDF24\uFE0F \u26AA"
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpPx(12), dpPx(5), dpPx(12), dpPx(5))
                setBackgroundColor(0xCC1C1C1E.toInt())
                isClickable = true
                setOnClickListener { afficherDetailPret() }
            }
            val lp = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                topMargin = dpPx(16)
                leftMargin = dpPx(210)
            }
            racine?.addView(pastilleMeteo, lp)
            pastilleMeteo?.bringToFront()
        } catch (_: Exception) {}
        chargerMeteoCockpit()
        // --- TOUCHER POUR DESIGNER LA CIBLE (mode pilote + sujet) ---
        overlayYolo.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                val cx = (ev.x / v.width).coerceIn(0f, 1f)
                val cy = (ev.y / v.height).coerceIn(0f, 1f)
                yoloSuivi?.designerCible(cx, cy)
                verrouillerCible()
                txtCommandeVoc.text = getString(R.string.ma_cible_designee)
                txtCommandeVoc.visibility = android.view.View.VISIBLE
                txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 1500)
                v.performClick()
            }
            true
        }
        txtCommandeVoc = findViewById(R.id.txtCommandeVoc)
        vocal = CommandeVocale(this,
            onCommande = { action -> runOnUiThread { executerCommandeVocale(action) } },
            onTexte = { txt -> runOnUiThread {
                txtCommandeVoc.text = txt
                txtCommandeVoc.visibility = android.view.View.VISIBLE
                txtCommandeVoc.removeCallbacks(null)
                txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
            } }
        )
        ecoute = EcouteContinue(this,
            onCommande = { action -> runOnUiThread { executerCommandeVocale(action) } },
            onTexte = { txt -> runOnUiThread {
                txtCommandeVoc.text = txt
                txtCommandeVoc.visibility = android.view.View.VISIBLE
            } }
        )
        findViewById<Button>(R.id.btnEv).setOnClickListener { panneauRecettes.ouvrir(); panneauRecettes.afficherReglagesCamera() }
        findViewById<Button>(R.id.btnCarte).setOnClickListener { basculerMiniCarte() }
        findViewById<Button>(R.id.btnAgrandirCarte).setOnClickListener {
            try { MainActivity.ancreLatCarte = ancreLat; MainActivity.ancreLonCarte = ancreLon } catch (_: Exception) {}
            val enVolMaintenant = pont.lireEtat(pilote.enVol).enVol || TEST_FORCER_VOL
            startActivity(android.content.Intent(this, CarteActivity::class.java)
                .putExtra("enVol", enVolMaintenant))
        }
        findViewById<Button>(R.id.btnTags).setOnClickListener {
            startActivity(android.content.Intent(this, TagsActivity::class.java))
        }
        // Bouton LIVE : ouvre l'ecran de diffusion en direct (meme cible que l'appui long RTSP).
        findViewById<Button>(R.id.btnLive).setOnClickListener {
            startActivity(android.content.Intent(this, LiveStreamActivity::class.java))
        }
        // Commande vocale : plus de bouton en haut. Activee/desactivee depuis Reglages.
        // Si activee dans les preferences, on demarre l'ecoute automatiquement.
        if (getSharedPreferences("cineflight", MODE_PRIVATE).getBoolean("voix_active", false)) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ecoute?.demarrer()
            } else {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            }
        }
        run {
            val bGros = findViewById<Button>(R.id.btnPlanGros)
            val bAmer = findViewById<Button>(R.id.btnPlanAmericain)
            val bPied = findViewById<Button>(R.id.btnPlanPied)
            val bEns  = findViewById<Button>(R.id.btnPlanEnsemble)
            val tous = listOf(bGros, bAmer, bPied, bEns)
            fun teinte(b: Button, couleur: Int) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            }
            fun choisir(sel: Button, cible: Float) {
                cibleHPlan = cible
                tous.forEach {
                    teinte(it, 0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                teinte(sel, 0xFF00C853.toInt())
                sel.setTextColor(0xFFFFFFFF.toInt())
            }
            bGros.setOnClickListener { choisir(bGros, 0.75f) }
            bAmer.setOnClickListener { choisir(bAmer, 0.55f) }
            bPied.setOnClickListener { choisir(bPied, 0.40f) }
            bEns.setOnClickListener  { choisir(bEns, 0.25f) }
            choisir(bAmer, 0.55f)   // etat initial : Americain allume (defaut)
        }
        run {
            val mStat = findViewById<Button>(R.id.btnMouvStatique)
            val mOrb  = findViewById<Button>(R.id.btnMouvOrbite)
            val mTrav = findViewById<Button>(R.id.btnMouvTravel)
            val mRev  = findViewById<Button>(R.id.btnMouvRevel)
            val mSpot = findViewById<Button>(R.id.btnMouvSpotlight)
            val mApp = findViewById<Button>(R.id.btnMouvApproche)
            val mRec = findViewById<Button>(R.id.btnMouvRecul)
            val mSui = findViewById<Button>(R.id.btnMouvSuivi)
            val tousM = listOf(mStat, mOrb, mTrav, mRev, mSpot, mApp, mRec, mSui)
            fun teinteM(b: Button, couleur: Int) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            }
            fun choisirMouv(sel: Button, m: Int) {
                // --- TRANSITION SECURITAIRE Recul <-> Suivi ---
                // Recul (6) place le drone DEVANT vous ; Suivi (7) le veut DERRIERE.
                // Basculer directement de l'un a l'autre en marchant peut rapprocher le drone
                // de vous (il etait devant et se met a "poursuivre"). On insere donc une phase
                // de stabilisation Statique : le drone se fige et vous garde cadre pendant 3 s,
                // le temps que vous le repositionniez, avant d'appliquer le mouvement demande.
                val transitionDangereuse =
                    (mouvementActuel == 6 && m == 7) || (mouvementActuel == 7 && m == 6)
                if (transitionDangereuse && modeAuto) {
                    mouvementActuel = 0          // Statique immediat : le drone se stabilise
                    majValeursMouvement()
                    tousM.forEach { teinteM(it, 0xFF263238.toInt()); it.setTextColor(0xFF90A4AE.toInt()) }
                    teinteM(mStat, 0xFFFFA000.toInt())   // ambre = stabilisation en cours
                    mStat.setTextColor(0xFFFFFFFF.toInt())
                    txtCommandeVoc.text = getString(R.string.ma_stabilisation)
                    txtCommandeVoc.visibility = android.view.View.VISIBLE
                    sel.postDelayed({
                        // applique enfin le mouvement demande, sauf si l'utilisateur a change d'avis entre-temps
                        mouvementActuel = m
                        majValeursMouvement()
                        tousM.forEach { teinteM(it, 0xFF263238.toInt()); it.setTextColor(0xFF90A4AE.toInt()) }
                        teinteM(sel, 0xFF00C853.toInt())
                        sel.setTextColor(0xFFFFFFFF.toInt())
                        txtCommandeVoc.visibility = android.view.View.GONE
                    }, 3000)
                    return
                }
                mouvementActuel = m
                majValeursMouvement()
                tousM.forEach {
                    teinteM(it, 0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                teinteM(sel, 0xFF00C853.toInt())
                sel.setTextColor(0xFFFFFFFF.toInt())
            }
            mStat.setOnClickListener { choisirMouv(mStat, 0) }
            mOrb.setOnClickListener  { choisirMouv(mOrb, 1) }
            mTrav.setOnClickListener { choisirMouv(mTrav, 2) }
            mRev.setOnClickListener  { choisirMouv(mRev, 3) }
            mSpot.setOnClickListener { choisirMouv(mSpot, 5) }
            mApp.setOnClickListener  { choisirMouv(mApp, 4) }
            mRec.setOnClickListener  { choisirMouv(mRec, 6) }
            mSui.setOnClickListener  { choisirMouv(mSui, 7) }
            choisirMouv(mStat, 0)   // etat initial : Statique allume (defaut)
        }

        // === CABLE-CAM : Rail A / Rail B / Go ===
        run {
            val bRailA = findViewById<Button>(R.id.btnRailA)
            val bRailB = findViewById<Button>(R.id.btnRailB)
            val bRailGo = findViewById<Button>(R.id.btnRailGo)
            fun teinte(b: Button, c: Int) { b.backgroundTintList = android.content.res.ColorStateList.valueOf(c) }

            bRailA.setOnClickListener {
                val e = pont.lireEtat(pilote.enVol)
                if (!e.gpsValide) { android.widget.Toast.makeText(this, getString(R.string.ma_toast_gps_ptA), android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                cableCam.memoriserA(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                teinte(bRailA, 0xFF00C853.toInt()); bRailA.setTextColor(0xFFFFFFFF.toInt())
                android.widget.Toast.makeText(this, getString(R.string.ma_toast_ptA_ok), android.widget.Toast.LENGTH_SHORT).show()
            }
            // Appui LONG sur Rail A = definir tout le rail AUTOMATIQUEMENT vers le sujet detecte (YOLO)
            bRailA.setOnLongClickListener {
                val e = pont.lireEtat(pilote.enVol)
                if (!e.gpsValide) {
                    android.widget.Toast.makeText(this, getString(R.string.ma_toast_gps_court), android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnLongClickListener true
                }
                if (!dernierSujetTrouve) {
                    android.widget.Toast.makeText(this, getString(R.string.ma_toast_aucun_sujet), android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnLongClickListener true
                }
                cableCam.memoriserA(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                val errX = dernierCxSujet - 0.5f
                val angleSujet = errX * 73f
                val capVersSujet = ((if (e.capDeg.isNaN()) 0f else e.capDeg) + angleSujet + 360f) % 360f
                val distM = reglages.getRailSujetDist().toDouble()
                val R = 6371000.0
                val capRad = Math.toRadians(capVersSujet.toDouble())
                val dLat = (distM * Math.cos(capRad)) / R
                val dLon = (distM * Math.sin(capRad)) / (R * Math.cos(Math.toRadians(e.latitude)))
                val latB = e.latitude + Math.toDegrees(dLat)
                val lonB = e.longitude + Math.toDegrees(dLon)
                cableCam.memoriserB(latB, lonB, e.altitudeAgl, e.capDeg)
                teinte(bRailA, 0xFF00C853.toInt()); bRailA.setTextColor(0xFFFFFFFF.toInt())
                teinte(bRailB, 0xFF00C853.toInt()); bRailB.setTextColor(0xFFFFFFFF.toInt())
                android.widget.Toast.makeText(this, getString(ca.cineflight.stage.R.string.ma_toast_rail_sujet, distM.toInt()), android.widget.Toast.LENGTH_LONG).show()
                true
            }
            bRailB.setOnClickListener {
                val e = pont.lireEtat(pilote.enVol)
                if (!e.gpsValide) { android.widget.Toast.makeText(this, getString(R.string.ma_toast_gps_ptB), android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                cableCam.memoriserB(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                teinte(bRailB, 0xFF00C853.toInt()); bRailB.setTextColor(0xFFFFFFFF.toInt())
                android.widget.Toast.makeText(this, getString(R.string.ma_toast_ptB_ok), android.widget.Toast.LENGTH_SHORT).show()
            }
            bRailGo.setOnClickListener {
                if (cableCam.enCours) {
                    cableCam.arreter()
                    hyperlapse.arreter()
                    teinte(bRailGo, 0xFF263238.toInt()); bRailGo.text = "Go"
                    val msg = if (hyperlapse.nbPhotos > 0)
                        getString(R.string.ma_rail_stop_photos, hyperlapse.nbPhotos) else getString(R.string.ma_rail_stop)
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    if (!cableCam.pret()) { android.widget.Toast.makeText(this, getString(R.string.ma_toast_rail_manque), android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    if (!modeAuto) basculerMode(true)   // le rail a besoin du mode auto pour piloter
                    cableCam.demarrer()
                    if (hyperlapseArme) hyperlapse.demarrer()   // capture photo periodique pendant ce rail
                    teinte(bRailGo, 0xFFD32F2F.toInt()); bRailGo.text = "Stop"
                    val msg = if (hyperlapseArme)
                        getString(R.string.ma_rail_hyperlapse_go) else getString(R.string.ma_rail_go_msg)
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // Appui LONG sur Go = armer/desarmer le mode Hyperlapse pour le prochain rail
            bRailGo.setOnLongClickListener {
                hyperlapseArme = !hyperlapseArme
                val msg = if (hyperlapseArme)
                    getString(R.string.ma_hyperlapse_arme) else getString(R.string.ma_hyperlapse_desarme)
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                // teinte violette quand arme (au repos), gris sinon
                if (!cableCam.enCours) teinte(bRailGo, if (hyperlapseArme) 0xFF6A1B9A.toInt() else 0xFF263238.toInt())
                true
            }
        }

        // --- CineFlight Solo : YOLO embarque ---
        if (!MODE_SIMULE) {
            // Qualite YOLO depuis Reglages : 0=Rapide(nano/320/CPU), 1=Precis(nano/480/GPU).
            // loadModel(assets, taskid, modelid, cpugpu). modelid 3 = nano/480 ; cpugpu 1 = GPU Vulkan.
            val (modelId, cpuGpu) = if (reglages.getQualiteYolo() == 1) Pair(3, 1) else Pair(0, 0)
            val okModel = yolo.loadModel(assets, 0, modelId, cpuGpu)
            android.util.Log.i("Solo", "loadModel = $okModel (modelId=$modelId cpuGpu=$cpuGpu)")
            yoloSuivi = YoloSuivi(yolo,
                onSujet = { trouve, cx, cy, w, h ->
                    runOnUiThread {
                        // memorise le dernier sujet vu (pour le bouton "Rail vers sujet")
                        dernierSujetTrouve = trouve
                        if (trouve) {
                            dernierCxSujet = cx
                            derniereHauteurBoiteSujet = h
                        } else {
                            derniereHauteurBoiteSujet = 0f
                            derniereConfianceSujet = 0f
                            dernierNbPersonnes = 0
                        }
                        // AUTO-CADRAGE : etat de detection pour la machine a etats.
                        autoSujetPresent = trouve
                        autoCadrageOk = trouve && kotlin.math.abs(cx - 0.5f) < 0.18f &&
                                kotlin.math.abs(cy - 0.5f) < 0.22f && h > 0.15f
                        // === CABLE-CAM : si un rail est en cours, il prend le controle (ignore le suivi) ===
                        if (cableCam.enCours) {
                            val e = pont.lireEtat(pilote.enVol)
                            // GARDE-FOU RTH : si le retour auto s'enclenche, le rail LACHE le controle
                            // (sinon le rail et le RTH se battent pour piloter le drone).
                            if (e.rthEnCours) {
                                cableCam.arreter()
                                hyperlapse.arreter()
                                txtSujet.text = getString(R.string.ma_rail_rth)
                                txtSujet.setTextColor(0xFFE65100.toInt())
                                findViewById<Button>(R.id.btnRailGo)?.let {
                                    it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
                                    it.text = "Go"
                                }
                                return@runOnUiThread
                            }
                            if (e.gpsValide) {
                                // Si un sujet est detecte : on glisse sur le rail TOUT EN gardant le sujet cadre.
                                // Sinon : rail GPS simple (le drone pointe vers B).
                                val v = if (trouve)
                                    cableCam.calculerAvecSuivi(e.latitude, e.longitude, e.altitudeAgl, e.capDeg, cx, cy)
                                else
                                    cableCam.calculer(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                                if (v != null) {
                                    soumettreCommandeRtkVision(
                                        RecepteurBridge.CommandeBridge(
                                            t = System.currentTimeMillis() / 1000.0,
                                            vx = v[0], vy = v[1], vz = v[2], yawRate = v[3],
                                            mode = "actif", recuA = System.currentTimeMillis(),
                                            gimbalYaw = if (v.size > 4) v[4] else 0f
                                        ),
                                        visionRequise = trouve,
                                        yoloTrouve = trouve,
                                        hauteurBoite = if (trouve) h else 0f
                                    )
                                    // HYPERLAPSE : photo periodique pendant le glissement
                                    if (hyperlapse.tempsPourPhoto()) {
                                        pont.declencherPhoto()
                                        findViewById<Button>(R.id.btnPhoto)?.text = "\uD83D\uDCF8 ${hyperlapse.nbPhotos}"
                                    }
                                    // affiche l'etat du rail + suivi eventuel
                                    overlayYolo.majBoxes(if (trouve)
                                        listOf(RecepteurBoxes.Box(cx - w / 2f, cy - h / 2f, w, h, 1f, true))
                                        else emptyList())
                                    val distB = cableCam.distanceVersB(e.latitude, e.longitude)
                                    val distTxt = if (distB >= 0) " \u00B7 ${distB.toInt()} m" else ""
                                    txtSujet.text = (if (trouve) getString(R.string.ma_lbl_rail_suivi) else getString(R.string.ma_lbl_rail_ab)) + distTxt
                                    txtSujet.setTextColor(0xFF1565C0.toInt())
                                } else {
                                    // arrive a B : hover + reset
                                    cableCam.arreter()
                                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                                        System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))
                                    txtSujet.text = getString(R.string.ma_rail_termine)
                                }
                            } else {
                                // pas de GPS fiable : hover de securite
                                pilote.soumettre(RecepteurBridge.CommandeBridge(
                                    System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))
                            }
                            return@runOnUiThread
                        }
                        val boxes = if (trouve)
                            listOf(RecepteurBoxes.Box(cx - w / 2f, cy - h / 2f, w, h, 1f, true))
                        else emptyList()
                        overlayYolo.majBoxes(boxes)
                        txtSujet.text = if (trouve) getString(R.string.ma_lbl_sujet_suivi) else getString(R.string.ma_lbl_aucun_sujet)
                        txtSujet.setTextColor(if (trouve) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                        if (!autoControleDirect) {
                        if (trouve && modeAuto && cibleVerrouillee) {
                            tPerteCible = 0L   // cible revue : reset
                            soumettreCommandeRtkVision(
                                calculerSuivi(cx, cy, w, h),
                                visionRequise = true,
                                yoloTrouve = true,
                                hauteurBoite = h
                            )
                            if (h >= SEUIL_BIEN_VU) {
                                val e = pont.lireEtat(pilote.enVol)
                                if (e.gpsValide) {
                                    ancreLat = e.latitude
                                    ancreLon = e.longitude
                                    ancreValide = true
                                }
                            }
                        } else if (modeAuto && cibleVerrouillee && !trouve) {
                            // CIBLE PERDUE : hover quelques secondes, puis retour GPS facon chien fidele
                            if (tPerteCible == 0L) tPerteCible = System.currentTimeMillis()
                            val perdueDepuis = System.currentTimeMillis() - tPerteCible
                            if (perdueDepuis < DELAI_AVANT_RETOUR || !ancreValide) {
                                pilote.soumettre(commandeHoldRtkVision())
                            } else {
                                // Retour a l'ancre uniquement en FIX complet. En FLOAT,
                                // l'absence de YOLO impose HOLD sans translation.
                                val verdictRetour = evaluerControleRtkVision(
                                    visionRequise = false,
                                    yoloTrouve = false,
                                    hauteurBoite = 0f
                                )
                                if (verdictRetour.mode == ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FIX_COMPLET) {
                                    pilote.soumettre(retourAncre())
                                } else {
                                    pilote.soumettre(commandeHoldRtkVision())
                                }
                            }
                        } else if (modeAuto && !cibleVerrouillee) {
                            pilote.soumettre(commandeHoldRtkVision())
                        }
                        }
                    }
                },
                onTag = { id -> runOnUiThread { executerTag(id) } },
                onDetections = { dets ->
                    // n'agrege que pendant une passe d'analyse (sinon ignore : cout nul)
                    if (analyseCineEnCours) agregateurCine.ajouterFrame(dets)
                },
                onSuiviInfo = { confiance, nbPersonnes ->
                    // V4.2 : ces metriques alimentent la barriere FLOAT+YOLO.
                    derniereConfianceSujet = confiance
                    dernierNbPersonnes = nbPersonnes
                    runOnUiThread {
                        txtSujet.text = getString(R.string.ma_sujet_conf, "%.2f".format(confiance), nbPersonnes)
                        txtSujet.setTextColor(
                            if (confiance >= ca.cineflight.stage.control.AutorisationControleRtkVision.CONFIANCE_YOLO_MIN && nbPersonnes == 1) 0xFF2E7D32.toInt()
                            else 0xFFFFA000.toInt())
                    }
                }
            )
        }
        val btnDecoller = findViewById<Button>(R.id.btnDecoller)
        val btnAtterrir = findViewById<Button>(R.id.btnAtterrir)
        val btnRth = findViewById<Button>(R.id.btnRth)
        val btnUrgence = findViewById<Button>(R.id.btnUrgence)
        val btnPhoto = findViewById<Button>(R.id.btnPhoto)
        val btnRtsp = findViewById<Button>(R.id.btnRtsp)
        val btnGimbalUp = findViewById<Button>(R.id.btnGimbalUp)
        val btnGimbalDown = findViewById<Button>(R.id.btnGimbalDown)

        // --- pont cockpit : reel ou simule ---
        // NOTE TELEMETRIE : en reel, pas d'initialiserTout() ici.
        // En MSDK v5 les listeners abonnes avant connexion ne se redeclenchent pas.
        // -> batterie/GPS/altitude resteraient vides.
        // Donc on abonne les listeners APRES onProductConnect.
        pont = if (MODE_SIMULE) {
            PontDjiSimuleCockpit()
        } else {
            PontDjiReelCockpit()
        }
        // Reference typee au pont reel pour reabonner les listeners.
        // connexion du drone. Null en mode simule.
        pontReel = pont as? PontDjiReelCockpit

        // --- OBSERVATION VOCALE (Phase 2, Lot 2A : Virtual Stick). NON BLOQUANT, isole.
        // On branche des hooks sur le pont reel : la voix OBSERVE, ne commande jamais.
        // Si le flag observationDji est off (defaut), rien n'est prononce. Aucun impact
        // sur le pilotage : les hooks sont appeles avec ?.invoke() et blindes.
        try {
            val voix = ca.cineflight.stage.voice.FlightVoiceSystem.obtenir(applicationContext)
            // PHASE 2 TEST : on active l'infrastructure + l'observation DJI directement
            // ici pour tester sans passer par l'ecran de diagnostic. (Deviendra un vrai
            // reglage persistant plus tard.) N'impacte PAS le pilotage : la voix observe.
            voix.settings.active = true
            voix.settings.observationDji = true
            voix.demarrer(ca.cineflight.stage.LangueManager.langueActuelle(this))
            // Mode de voix choisi dans les reglages (defaut NORMAL).
            try {
                val vm = this.getSharedPreferences("cineflight", MODE_PRIVATE).getInt("voix_mode", 1)
                voix.settings.mode = when (vm) {
                    0 -> ca.cineflight.stage.voice.VoiceMode.MINIMAL
                    2 -> ca.cineflight.stage.voice.VoiceMode.DETAILLE
                    else -> ca.cineflight.stage.voice.VoiceMode.NORMAL
                }
            } catch (_: Throwable) {}
            val obs = ca.cineflight.stage.voice.DjiVoiceObservationAdapter(voix)
            observateurVocal = obs
            // Phase 3 : moniteur de distance VLOS (observe, ne commande jamais le RTH).
            moniteurVlos = ca.cineflight.stage.voice.VlosDistanceMonitor(voix)
            // Limite VLOS configurable (defaut 600 m), lue dans les reglages.
            try {
                val limVlos = getSharedPreferences("cineflight", MODE_PRIVATE).getInt("vlos_limite_m", 600)
                moniteurVlos?.limiteM = limVlos.toDouble()
            } catch (_: Throwable) {}
            moniteurAltitude = ca.cineflight.stage.voice.AltitudeMonitor(voix)
            // Phase 3B : moniteur du suivi du sujet (RTK, fusion, serveur).
            moniteurSujet = ca.cineflight.stage.voice.SubjectVoiceMonitor(voix)
            // Complement 3B : divergence vision (YOLO) vs RTK sujet.
            moniteurDivergence = ca.cineflight.stage.voice.DivergenceVisionRtk(voix)
            // Complement 3A : phases RTH deduites (montee/retour/descente/termine).
            moniteurRthPhase = ca.cineflight.stage.voice.RthPhaseMonitor { code -> obs.observerRthPhase(code) }
            try {
                val limAlt = getSharedPreferences("cineflight", MODE_PRIVATE).getInt("alt_limite_m", 122)
                moniteurAltitude?.limiteM = limAlt.toDouble()
            } catch (_: Throwable) {}
            pontReel?.basePourObservation()?.let {
                it.obsVsEnableAccepte = { obs.observerVsEnableAccepte() }
                it.obsVsEnableRefuse = { obs.observerVsEnableRefuse() }
                it.obsVsConfirme = { actif -> obs.observerVsConfirme(actif) }
                it.obsVsDesactivationVoulue = { obs.marquerDesactivationDemandee() }
                // Lot 2C : decollage / atterrissage
                it.obsTakeoffActive = { obs.observerTakeoffActive() }
                it.obsTakeoffRefuse = { obs.observerTakeoffRefuse() }
                it.obsLandingActive = { obs.observerLandingActive() }
                it.obsLandingRefuse = { obs.observerLandingRefuse() }
                // Phase 3 : etats critiques (batterie, liaison drone/RC, GPS)
                it.obsBatterie = { pct -> obs.observerBatterie(pct) }
                it.obsConnexionDrone = { c -> obs.observerConnexionDrone(c) }
                it.obsConnexionRc = { c -> obs.observerConnexionRc(c) }
                it.obsGpsSatellites = { n -> obs.observerGpsSatellites(n) }
                // Phase 3B : camera / enregistrement
                it.obsRecordStarted = { obs.observerRecordStarted() }
                it.obsRecordStopped = { obs.observerRecordStopped() }
                it.obsRecordFailed = { obs.observerRecordFailed() }
            }
            // Lot 2B : hooks RTH (sur le cockpit lui-meme, pas le base)
            (pontReel)?.let { pc ->
                pc.obsRthActive = { obs.observerRthActive() }
                pc.obsRthRefuse = { obs.observerRthRefuse() }
                pc.obsRthAnnule = { obs.observerRthAnnule() }
                // Phase 3A : point maison + qualite de liaison
                pc.obsHomeConfirme = { obs.observerPointMaison(true) }
                pc.obsSignalCommande = { pct -> obs.observerSignalCommande(pct) }
                pc.obsSignalVideo = { pct -> obs.observerSignalVideo(pct) }
            }
        } catch (_: Throwable) { /* fail-open : la voix ne bloque jamais le demarrage */ }

        pilote = PiloteDrone(this, pont)
        // Fournisseurs de contexte pour le logger de perception diagnostic (passif, off par
        // defaut) : modele drone + etat Virtual Stick. Lecture seule, aucun impact vol.
        try {
            lecteurPerception.fournisseurModele = { try { profilDrone.modeleBrut } catch (_: Throwable) { "?" } }
            lecteurPerception.fournisseurVsActif = { try { pont.lireEtat(pilote.enVol).enVol } catch (_: Throwable) { false } }
        } catch (_: Throwable) {}
        // ObstacleSafetyGate : le cablage du gate est desormais TYPE au constructeur de PontDjiReel
        // (ObstacleGateWiring). MainActivity utilise le cockpit avec ObstacleGateWiring.Off par
        // defaut -> aucun miroir ici. Le mode Mirror est cable dans Phase3Activity.
        // Annonce vocale de l'arret d'urgence (via l'observateur cree plus haut).
        // Isole/fail-open : si la voix est off, obsArretUrgence reste inoffensif.
        observateurVocal?.let { o -> pilote.obsArretUrgence = { o.observerArretUrgence() } }
        // Phase 3A : watchdog Virtual Stick (observe la boucle, ne pilote pas).
        observateurVocal?.let { o ->
            pilote.obsWatchdogTimeout = { t -> o.observerWatchdog(t) }
            pilote.obsVsNonConfirme = { nc -> o.observerVsNonConfirme(nc) }
        }
        modeVol = GestionnaireModeVol(this, pilote, lifecycleScope)

        if (!MODE_SIMULE) flux = FluxCamera(surfaceFlux)

        recepteur = RecepteurBridge(PORT_BRIDGE) { cmd -> pilote.soumettre(cmd) }

        // --- recepteur des boites YOLO (port 9103) -> overlay dessine par-dessus la video ---
        recepteurBoxes = RecepteurBoxes(9103) { boxes, tEmis ->
            runOnUiThread { overlayYolo.majBoxes(boxes) }
            // Observation MIROIR soccer (Phase 8) : PUR, jamais de commande drone.
            if (SOCCER_MIRROR_ENABLED) observerMiroirSoccer(boxes, tEmis)
        }
        recepteurBoxes.demarrer(lifecycleScope)

        if (MODE_SIMULE) {
            demarrerChaine(getString(R.string.ma_dlg_simule_etat)); com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight).setTitle(getString(R.string.ma_dlg_simule_titre)).setMessage(getString(R.string.ma_dlg_simule_msg)).setCancelable(false).setPositiveButton(android.R.string.ok, null).show()
        } else {
            // Quand le SDK signale connexion/deconnexion du drone,
            // on reabonne les listeners de telemetrie au bon moment.
            // callback sur thread SDK -> on repasse en UI.
            EnregistrementSdk.onConnexionProduit = { connecte ->
                runOnUiThread {
                    if (connecte) {
                        // Drone connecte : on abonne les listeners maintenant.
                        // MAINTENANT, avec l'appareil present -> la telemetrie remonte.
                        pontReel?.initialiserTout()
                        ca.cineflight.stage.test.TestStatsVol.lancer(applicationContext)
                        flux?.demarrer()
                        yoloSuivi?.demarrer()
                        yoloSuivi?.setClassesSuivies(reglages.classesPourSujet())
                        val mdl = pont.lireEtat(pilote.enVol).modele
                        profilDrone = CapacitesDrone.analyser(mdl)
                        demarrerObservationPerceptionNormale()  // maillon 2 : connexion
                        vEtat.text = mdl.let { if (it.isNotEmpty() && it != "Drone connecte" && it != "Simulateur") getString(R.string.ma_modele_connecte, it) else getString(R.string.ma_drone_connecte) }
                        // --- demarre le serveur RTSP du SDK (test Mini 3) ---
                        StreamRtsp.demarrer { ok, msg ->
                            runOnUiThread {
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity, R.style.DialogCineFlight)
                                    .setTitle(if (ok) getString(R.string.ma_dlg_rtsp_ok) else getString(R.string.ma_dlg_rtsp_echec))
                                    .setMessage(msg)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    } else {
                        vEtat.text = getString(R.string.ma_drone_deco)
                        arreterObservationPerceptionNormale()  // maillon 2 : deconnexion
                    }
                }
            }
            EnregistrementSdk.enregistrer(applicationContext) { ok, message ->
                runOnUiThread {
                    if (ok) {
                        // SDK enregistre. La boucle cockpit demarre tout de suite.
                        // les listeners s'abonneront a onProductConnect.
                        // Si le drone est deja connecte, on abonne aussi.
                        demarrerChaine(getString(R.string.ma_chaine_attente_drone))
                        if (EnregistrementSdk.produitConnecte) {
                            pontReel?.initialiserTout()
                            ca.cineflight.stage.test.TestStatsVol.lancer(applicationContext)
                            flux?.demarrer()
                            demarrerObservationPerceptionNormale()  // maillon 2 : deja-connecte
                        }
                    } else {
                        vEtat.text = getString(R.string.ma_echec_sdk, message)
                    }
                }
            }
        }

        // --- commandes de vol ---
        btnDecoller.setOnClickListener {
            confirmerVol(getString(R.string.ma_dec_titre), getString(R.string.ma_dec_msg), getString(R.string.ma_dec_oui), false) {
                feedbackVol(btnDecoller, getString(R.string.ma_fb_decollage)); pilote.decoller { }
            }
        }
        btnDecoller.setOnLongClickListener {
            val sessionSentinelle = compteurSessionSentinelle.incrementAndGet()
            sentinelle = ca.cineflight.stage.sentinelle.SentinelleVol(
                activite  = this,
                pilote    = pilote,
                sessionId = sessionSentinelle,
                lireEtat  = { pont.lireEtat(pilote.enVol) },
                scoreFn   = fusionPerception.envelopper { yoloSuivi?.scoreOuvertureCourant() ?: Pair(0.0, 0.0) },
                intrusionFn = { yoloSuivi?.intrusionVisible() ?: false },
                rtkSujetOkFn = {
                    val s = dernierEtatSujet
                    // FIX complet : RTK fiable + distance geometrique au-dessus de la limite.
                    // En FLOAT, la sentinelle de vol reste fail-closed : aucun axe de vol
                    // n'est autorise. L'assistance de nacelle reste separee du mouvement.
                    val rtkOk = s != null && s.rtkSujetOk && !s.distanceSousLimite
                    // Volet corridor (brique 4) : BLOCAGE si le sujet est HORS du
                    // corridor validé OU RTK perdu au sens moniteur. TOLERANCE et
                    // PRUDENCE ne bloquent PAS (avertissements affichés, pilote maître).
                    val c = dernierStatutCorridor
                    val corridorBloque = c != null && (
                        c.statut == ca.cineflight.stage.control.MoniteurCorridor.Statut.HORS_CORRIDOR ||
                        c.statut == ca.cineflight.stage.control.MoniteurCorridor.Statut.RTK_PERDU)
                    // Volet géo-barrière (étape 4) : BLOCAGE si le DRONE sort de la
                    // zone de vol validée. droneDansZone reste true tant qu'aucune
                    // géo-barrière n'est active (pas de restriction en vol normal).
                    val geoBloque = !droneDansZone
                    // sûr seulement si TOUS les volets sont OK.
                    rtkOk && !corridorBloque && !geoBloque
                },
                plafondFn = { lecteurPerception.distanceHaut()?.let { it / 1000.0 } ?: Double.MAX_VALUE },
                secteurDegageFn = {
                    lecteurPerception.secteurLePlusDegage()?.let { Pair(it.index, it.angleDeg.toFloat()) }
                },
                prendrePhotoFn = { alt -> try { pont.declencherPhoto() } catch (_: Exception) {}
                    android.util.Log.i("SentinellePhoto", "Palier %.1f m -> photo".format(alt)) },
                plafondInfoFn = {
                    val haut = lecteurPerception.distanceHaut()
                    val deg = lecteurPerception.secteurLePlusDegage()
                    if (haut == null && deg == null) null
                    else buildString {
                        if (haut != null) append(getString(R.string.ma_sent_obstacle_haut, haut / 1000.0))
                        append(getString(R.string.ma_sent_montee_bloquee))
                        if (deg != null) {
                            append(getString(R.string.ma_sent_secteur, deg.index, deg.nbSecteurs))
                            if (deg.angleDeg >= 0) append(getString(R.string.ma_sent_angle, deg.angleDeg))
                            append(getString(R.string.ma_sent_deplace))
                        }
                    }
                },
                demarrerControleVol = { modeVol.activerVirtualStick { } },
                onMission = { android.widget.Toast.makeText(this,
                    getString(R.string.ma_toast_sentinelle),
                    android.widget.Toast.LENGTH_SHORT).show(); ouvrirMissionsPreparees() }
            )
            lecteurPerception.demarrer()
            demarrerPollingSujet()   // AJOUT RTK : polling position sujet + securite
            sentinelle?.
            demanderConfirmation()
            true
        }
        // PREDICTION RTK AUTO_START
        // Le diagnostic passif fonctionne sans decollage ni connexion drone.
        demarrerPollingSujet()

        btnAtterrir.setOnClickListener {
            confirmerVol(getString(R.string.ma_atter_titre), getString(R.string.ma_atter_msg), getString(R.string.ma_atter_oui), false) {
                feedbackVol(btnAtterrir, getString(R.string.ma_fb_atterrissage))
                if (modeAuto) basculerMode(false)
                pilote.atterrir { }
            }
        }
        btnRth.setOnClickListener {
            // en manuel on lance le retour du drone ; un 2e appui l'annule
            val etat = pont.lireEtat(pilote.enVol)
            if (etat.rthEnCours) {
                confirmerVol(getString(R.string.ma_rth_ann_titre), getString(R.string.ma_rth_ann_msg), getString(R.string.ma_rth_ann_oui), false) {
                    feedbackVol(btnRth, getString(R.string.ma_fb_rth_annule)); pont.annulerRth { }
                }
            } else {
                confirmerVol(getString(R.string.ma_rth_titre), getString(R.string.ma_rth_msg), getString(R.string.ma_rth_oui), false) {
                    feedbackVol(btnRth, getString(R.string.ma_fb_rth)); pont.lancerRth { }
                }
            }
        }
        btnUrgence.setOnClickListener {
            confirmerVol(getString(R.string.ma_urg_titre),
                getString(R.string.ma_urg_msg),
                getString(R.string.ma_urg_oui), true) {
                feedbackVol(btnUrgence, getString(R.string.ma_urg_titre))
                pilote.arretUrgence(); modeAuto = false; majBoutonMode()
            }
        }
        btnMode.setOnClickListener { basculerMode(!modeAuto) }
        // Appui LONG sur le bouton mode -> ouvre le panneau de recettes (assistant realisateur).
        btnMode.setOnLongClickListener { panneauRecettes.ouvrir(); true }

        // --- bouton Mission : ouvre l'assistant (formation -> chanson -> prise -> lancer) ---

        // --- bouton Placement : placer les musiciens au Vive Tracker ---

        // --- commandes camera / gimbal ---
        btnPhoto.setOnClickListener { effetCapture(); pont.declencherPhoto() }
        btnPhoto.setOnLongClickListener {
            captureAutoActive = !captureAutoActive
            compteurPhotosAuto = 0
            if (captureAutoActive) {
                btnPhoto.text = "\u25C9\u00B7"
                btnPhoto.setBackgroundColor(0xFF2E7D32.toInt())
                android.widget.Toast.makeText(this, getString(R.string.ma_toast_capture_on), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                btnPhoto.text = "\u25C9"
                btnPhoto.setBackgroundColor(0xFF37474F.toInt())
                android.widget.Toast.makeText(this, getString(R.string.ma_toast_capture_off), android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }
        btnRtsp.setOnClickListener { if (StreamRtsp.enCours) { com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight).setTitle(getString(R.string.ma_dlg_rtsp_actif_titre)).setMessage(getString(R.string.ma_dlg_rtsp_actif_msg)).setPositiveButton(android.R.string.ok, null).show() } else { StreamRtsp.demarrer { ok, msg -> runOnUiThread { com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight).setTitle(if (ok) getString(R.string.ma_dlg_rtsp_ok) else getString(R.string.ma_dlg_rtsp_echec)).setMessage(msg).setPositiveButton(android.R.string.ok, null).show() } } } }
        // Appui LONG sur le bouton RTSP -> ecran de diffusion en direct (prototype V1 YouTube).
        btnRtsp.setOnLongClickListener {
            startActivity(android.content.Intent(this, LiveStreamActivity::class.java))
            true
        }
        btnRec.setOnClickListener {
            val etat = pont.lireEtat(pilote.enVol)
            if (!etat.connecte && !etat.enregistre) {
                android.widget.Toast.makeText(this, getString(R.string.ma_toast_rec_connect), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (etat.enregistre) pont.arreterEnregistrement() else pont.demarrerEnregistrement()
        }
        btnGimbalUp.setOnClickListener {
            gimbalCible = (gimbalCible + GIMBAL_PAS_DEG).coerceIn(-90f, 30f)
            pont.reglerGimbalPitch(gimbalCible)
        }
        btnGimbalDown.setOnClickListener {
            gimbalCible = (gimbalCible - GIMBAL_PAS_DEG).coerceIn(-90f, 30f)
            pont.reglerGimbalPitch(gimbalCible)
        }

        // === BOUTON "Assistant" (clap cinema) place A DROITE de Photo, meme taille ===
        run {
            val parent = btnPhoto.parent as? android.view.ViewGroup
            if (parent != null) {
                val dpW = (44 * resources.displayMetrics.density).toInt()
                val dpH = (40 * resources.displayMetrics.density).toInt()
                val idx = parent.indexOfChild(btnPhoto)
                val btnRecettes = Button(this).apply {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
                    minHeight = 0; minWidth = 0
                    setPadding(0,

                        0, 0, 0)
                    foreground = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_recettes)
                    foregroundGravity = android.view.Gravity.CENTER
                    setOnClickListener { panneauRecettes.ouvrir() }
                }
                btnRecettes.layoutParams = LinearLayout.LayoutParams(dpW, dpH).also { it.setMargins((4 * resources.displayMetrics.density).toInt(), 0, 0, 0) }
                parent.addView(btnRecettes, idx + 1)   // juste APRES Photo (a sa droite)
                // Un seul bouton RECON regroupe le workflow de reconnaissance d'un sujet
                // (Definir le sujet -> Verifier la mission -> Analyser les photos),
                // pour ne plus encombrer le haut ni confondre avec REC (video).
                val btnRecon = Button(this).apply {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF37474F.toInt())
                    minHeight = 0; minWidth = 0
                    setPadding(0, 0, 0, 0)
                    foreground = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_recon)
                    foregroundGravity = android.view.Gravity.CENTER
                    setOnClickListener { panneauRecettes.ouvrir(); panneauRecettes.afficherMenuReconnaissance() }
                }
                btnRecon.layoutParams = LinearLayout.LayoutParams(dpW, dpH).also { it.setMargins((4 * resources.displayMetrics.density).toInt(), 0, 0, 0) }
                parent.addView(btnRecon, idx + 2)
            } else {
                // repli : si on ne trouve pas le parent, bouton flottant (ancienne methode)
                val btnRecettes = Button(this).apply {
                    text = "?"; textSize = 20f
                    setBackgroundColor(0xCC1B5E9C.toInt()); setTextColor(0xFFFFFFFF.toInt())
                    setOnClickListener { panneauRecettes.ouvrir() }
                }
                val lp = FrameLayout.LayoutParams(
                    (64 * resources.displayMetrics.density).toInt(),
                    (64 * resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = (90 * resources.displayMetrics.density).toInt()
                    rightMargin = (12 * resources.displayMetrics.density).toInt()
                }
                addContentView(btnRecettes, lp)
            }
        }

        // Demarre le suivi de position du telephone (si permission deja accordee)
        // pour avoir un fix GPS pret des le premier "Preparer le lieu".
        demarrerSuiviPosition()

        majBoutonMode()
        demarrerPollClicker()   // CLICKER : valide l'intention, annonce le verdict, puis ACK
    }

    /** Construit le contexte de validation a partir de la telemetrie reelle.
     *  Espace par defaut = MOYEN (l'analyse YOLO le mesurera plus tard). */
    /** Position GPS du TELEPHONE (pour le mode 3 sans drone connecte).
     *  Renvoie la derniere position connue, ou null si indisponible / permission absente.
     *  Demande la permission si elle manque (le resultat sera dispo au prochain essai). */
    /** Derniere position connue du telephone, tenue a jour en continu (voir demarrerSuiviPosition). */
    @Volatile private var dernierePositionTel: Pair<Double, Double>? = null

    /** Demarre un suivi leger de la position du telephone pour garder un fix frais.
     *  Appele une fois la permission accordee. Met a jour dernierePositionTel. */

    private fun envoyerAckAndroidTest() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://161.35.188.68:8095/api/mouvement/ack")
                val connection = url.openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val payload = """
                    {
                      "source": "android",
                      "device_id": "samsung_SM_A546W",
                      "ok": true,
                      "android_status": "test_ack_button",
                      "message": "ACK Android depuis CineFlightSolo"
                    }
                """.trimIndent()

                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                val code = connection.responseCode
                val response = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: "Erreur HTTP $code"
                }

                connection.disconnect()

                android.util.Log.i("CineFlightACK", "ACK envoye HTTP=$code reponse=$response")

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ma_toast_ack, code),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                android.util.Log.e("CineFlightACK", "Erreur ACK Android", e)

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ma_toast_ack_err, e.message ?: ""),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    private fun demarrerSuiviPosition() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val listener = android.location.LocationListener { loc ->
                dernierePositionTel = loc.latitude to loc.longitude
            }
            // demande des mises a jour GPS et reseau (le 1er qui repond remplit le cache)
            if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 2000L, 5f, listener, mainLooper)
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener, mainLooper)
            // amorce immediate avec le cache existant
            val c = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (c != null) dernierePositionTel = c.latitude to c.longitude
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }

    /** Position GPS du TELEPHONE (pour le mode 3 sans drone connecte).
     *  Demande la permission si absente (et lance le suivi), sinon renvoie le dernier fix. */
    private fun positionTelephone(): Pair<Double, Double>? {
        val finePerm = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePerm = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val accordee = finePerm == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                       coarsePerm == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!accordee) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), 300)
            return null
        }
        // la permission est la : s'assurer que le suivi tourne, puis renvoyer le dernier fix
        if (dernierePositionTel == null) demarrerSuiviPosition()
        // tenter aussi une lecture directe du cache (au cas ou le suivi vient de demarrer)
        if (dernierePositionTel == null) {
            try {
                val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                if (loc != null) dernierePositionTel = loc.latitude to loc.longitude
            } catch (e: Exception) {}
        }
        return dernierePositionTel
    }

    /** Demande une position FRAICHE (active, pas le cache) et la renvoie via callback.
     *  Utilise pour "Preparer le lieu" : ne bloque pas, attend un vrai fix. */
    private fun demanderPositionFraiche(callback: (Pair<Double, Double>?) -> Unit) {
        val e = pont.lireEtat(pilote.enVol)
        if (e.gpsValide && !e.latitude.isNaN() && !e.longitude.isNaN()) {
            callback(e.latitude to e.longitude); return
        }
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            coarse != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), 300)
            callback(null); return
        }
        try {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            dernierePositionTel?.let { callback(it); return }
            val cache = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (cache != null) { callback(cache.latitude to cache.longitude); return }
            var repondu = false
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) {
                    if (repondu) return
                    repondu = true
                    dernierePositionTel = loc.latitude to loc.longitude
                    try { lm.removeUpdates(this) } catch (_: Exception) {}
                    callback(loc.latitude to loc.longitude)
                }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            }
            val provider = if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
                android.location.LocationManager.GPS_PROVIDER
            else android.location.LocationManager.NETWORK_PROVIDER
            lm.requestLocationUpdates(provider, 0L, 0f, listener, mainLooper)
            lifecycleScope.launch(Dispatchers.Main) {
                delay(20000)
                if (!repondu) {
                    repondu = true
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                    callback(dernierePositionTel)
                }
            }
        } catch (ex: SecurityException) {
            callback(null)
        } catch (ex: Exception) {
            callback(null)
        }
    }

    // === PANORAMA PHOTO : execution sequentielle (machine a etats) ===
    @Volatile private var panoramaEnCours = false

    /**
     * Lance un panorama PAYSAGE : le drone reste sur place, pivote par paliers,
     * regle le gimbal par rangee, prend une photo a chaque angle.
     * onProgres(indice, total) pour l'UI ; onFini(nbPhotos) a la fin (ou annulation -1).
     */
    private fun lancerPanoramaPaysage(
        preset: ca.cineflight.stage.cine.PanoramaPreset,
        onProgres: (Int, Int) -> Unit,
        onFini: (Int) -> Unit
    ) {
        if (panoramaEnCours) return
        val etat0 = pont.lireEtat(pilote.enVol)
        if (etat0.enVol) { demarrerBouclePanorama(preset, onProgres, onFini); return }
        // AU SOL : proposer un decollage automatique (confirmation + choix 15/30 m).
        // Choix de hauteur en LISTE VERTICALE (deux options bien visibles) + Annuler.
        // (Les boites a 3 boutons tassent les libelles ; la liste est sans ambiguite.)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_pano_deco_titre))
            .setItems(arrayOf(
                getString(R.string.ma_pano_deco_15),
                getString(R.string.ma_pano_deco_30)
            )) { _, which ->
                decollerEtPanorama(if (which == 0) 15.0 else 30.0, preset, onProgres, onFini)
            }
            .setNegativeButton(getString(android.R.string.cancel)) { _, _ -> onFini(0) }
            .setCancelable(false)
            .show()
    }

    /** Au sol : decolle, monte a hauteurM (vy = vertical +), puis lance le panorama. */
    private fun decollerEtPanorama(
        hauteurM: Double,
        preset: ca.cineflight.stage.cine.PanoramaPreset,
        onProgres: (Int, Int) -> Unit,
        onFini: (Int) -> Unit
    ) {
        if (panoramaEnCours) return
        if (!modeAuto) basculerMode(true)
        // GARDE : si le mode auto n'a PAS pu s'activer (satellites insuffisants ->
        // basculerMode a fait return sans passer modeAuto a true), on N'ARME PAS le
        // decollage. Sinon le drone monterait sans que l'app le controle vraiment.
        if (!modeAuto) {
            android.widget.Toast.makeText(this,
                getString(R.string.ma_pano_mode_auto_refuse),
                android.widget.Toast.LENGTH_LONG).show()
            onFini(-2); return
        }
        try { pont.activerVirtualStick(true) } catch (_: Exception) {}
        panoramaEnCours = true   // reserve : permet l'annulation pendant decollage/montee
        pilote.decoller { ok ->
            runOnUiThread {
                if (!ok) { panoramaEnCours = false; onFini(-2); return@runOnUiThread }
                lifecycleScope.launch(Dispatchers.Main) {
                    // DJI refuse le Virtual Stick tant que le decollage n'est pas fini.
                    // On ATTEND la confirmation reelle (virtualStickConfirmeActif) avant
                    // d'envoyer les commandes de montee : elles partent PILE quand le drone
                    // rend la main, aucune commande perdue. Timeout securite 15 s.
                    val tAttenteVs = System.currentTimeMillis()
                    while (panoramaEnCours &&
                           !(try { pont.virtualStickConfirmeActif() } catch (_: Throwable) { false }) &&
                           System.currentTimeMillis() - tAttenteVs < 15000) {
                        delay(200)
                    }
                    if (!panoramaEnCours) { onFini(0); return@launch }
                    val tDebut = System.currentTimeMillis()
                    val timeoutMs = (hauteurM / 1.2 * 1000.0).toLong() + 8000L
                    while (panoramaEnCours) {
                        val e = pont.lireEtat(pilote.enVol)
                        if (e.batteriePct in 0 until BATT_CRITIQUE) break
                        val alt = try { pont.altitudeDrone() } catch (_: Throwable) { Double.NaN }
                        if (!alt.isNaN() && alt >= hauteurM - 0.5) break
                        if (System.currentTimeMillis() - tDebut > timeoutMs) break   // backstop anti-montee-infinie
                        // vy = +1.2 m/s = MONTEE (convention TraductionAxes : vy vertical +). vx/vz = 0.
                        pilote.soumettre(RecepteurBridge.CommandeBridge(
                            System.currentTimeMillis() / 1000.0, 0f, 1.2f, 0f, 0f,
                            "actif", System.currentTimeMillis()))
                        delay(150)
                    }
                    // hover apres montee
                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                        System.currentTimeMillis() / 1000.0, 0f, 0f, 0f, 0f,
                        "actif", System.currentTimeMillis()))
                    if (!panoramaEnCours) { onFini(0); return@launch }   // annule pendant la montee
                    delay(1200)   // stabilisation avant les photos
                    panoramaEnCours = false          // libere pour l'entree standard
                    demarrerBouclePanorama(preset, onProgres, onFini, atterrirALaFin = true)
                }
            }
        }
    }

    // --- Assemblage panorama 360 (serveur Hugin) ---
    private var mediaDronePano: ca.cineflight.stage.control.MediaDrone? = null
    private var dernierPanoramaNb: Int = 0
    private val SERVEUR_PANO = "http://161.35.188.68:8095"

    /** Recupere les N dernieres photos du drone (carte SD), les envoie au serveur
     *  pour assemblage 360 haute qualite, renvoie l'image finale.
     *  onProgres(message, pct 0..100) et onFini(fichier ou null) : la couche UI
     *  (PanneauRecettes) reposte deja sur le thread principal. */
    private fun assemblerPanorama360(onProgres: (String, Int) -> Unit, onFini: (java.io.File?) -> Unit) {
        val nb = dernierPanoramaNb
        if (nb < 2) { onProgres(getString(R.string.ma_pano_aucune), 0); onFini(null); return }
        val media = mediaDronePano ?: ca.cineflight.stage.control.MediaDrone().also { mediaDronePano = it }
        onProgres(getString(R.string.ma_pano_connexion_sd), 2)
        media.activer { ok ->
            if (!ok) { onProgres(getString(R.string.ma_pano_acces_impossible), 0); onFini(null); return@activer }
            media.listerPhotos { photos ->
                if (photos.size < 2) {
                    media.quitter(); onProgres(getString(R.string.ma_pano_aucune_trouvee), 0); onFini(null); return@listerPhotos
                }
                android.util.Log.i("CineFlightPano", "photos sur SD: ${photos.size}")
                val cibles = photos.sortedBy { it.fileName }.takeLast(nb)   // tri par nom = ordre chrono
                android.util.Log.i("CineFlightPano", "lot cible (${cibles.size}): ${cibles.joinToString { it.fileName }}")
                val fichiers = java.util.ArrayList<java.io.File>()
                fun telechargerSuivant(i: Int) {
                    if (i >= cibles.size) {
                        media.quitter()
                        if (fichiers.size < 2) { onProgres(getString(R.string.ma_pano_dl_echoue), 0); onFini(null); return }
                        android.util.Log.i("CineFlightPano", "envoi de ${fichiers.size} photos au serveur")
                        Thread {
                            ca.cineflight.stage.cine.PanoramaAssemblage.assembler(
                                this@MainActivity, fichiers, SERVEUR_PANO,
                                onProgres = onProgres, onFini = onFini)
                        }.start()
                        return
                    }
                    onProgres(getString(R.string.ma_pano_dl_progres, i + 1, cibles.size), 2 + (i * 20) / cibles.size)
                    media.telecharger(this@MainActivity, cibles[i], { }, { f ->
                        android.util.Log.i("CineFlightPano", "photo ${i + 1}/${cibles.size} -> ${if (f != null) f.name else "ECHEC"}")
                        if (f != null) fichiers.add(f)
                        telechargerSuivant(i + 1)
                    })
                }
                telechargerSuivant(0)
            }
        }
    }

    /** Boucle photo (drone deja en vol) : positionne yaw + gimbal et prend la grille. */
    private fun demarrerBouclePanorama(
        preset: ca.cineflight.stage.cine.PanoramaPreset,
        onProgres: (Int, Int) -> Unit,
        onFini: (Int) -> Unit,
        atterrirALaFin: Boolean = false
    ) {
        if (panoramaEnCours) return
        val etat0 = pont.lireEtat(pilote.enVol)
        val modeAutoAvant = modeAuto
        if (!modeAuto) basculerMode(true)
        val capDepart = if (etat0.capDeg.isNaN()) 0f else etat0.capDeg
        val pas = ca.cineflight.stage.cine.PanoramaGrille.construire(preset, capDepart)
        val machine = ca.cineflight.stage.cine.PanoramaStateMachine(pas, preset)
        panoramaEnCours = true
        var photosPrises = 0

        lifecycleScope.launch(Dispatchers.Main) {
            var pitchApplique = Float.NaN
            while (panoramaEnCours) {
                val e = pont.lireEtat(pilote.enVol)
                val cap = if (e.capDeg.isNaN()) capDepart else e.capDeg
                // SECURITE : batterie critique -> on arrete le panorama (les garde-fous
                // globaux prennent le relais). Les photos deja prises sont conservees.
                if (e.batteriePct in 0 until BATT_CRITIQUE) break
                // GIMBAL : appliquer l'inclinaison du PAS COURANT des qu'elle change, AVANT
                // toute photo. Corrige le 1er cliche (cap depart deja aligne -> AllerVers
                // jamais emis -> ancien gimbal utilise).
                machine.pasCourant?.let { pc ->
                    if (pitchApplique.isNaN() || kotlin.math.abs(pitchApplique - pc.pitchDeg) > 0.5f) {
                        pont.reglerGimbalPitch(pc.pitchDeg.coerceIn(-90f, 30f))
                        pitchApplique = pc.pitchDeg
                    }
                }
                when (val action = machine.avancer(cap, System.currentTimeMillis())) {
                    is ca.cineflight.stage.cine.PanoramaStateMachine.Action.AllerVers -> {
                        // (gimbal deja applique en tete de boucle selon le pas courant)
                        // tourner vers le cap cible : yawRate proportionnel a l'ecart, plafonne
                        val ecart = ca.cineflight.stage.cine.PanoramaStateMachine
                            .ecartAngulaire(cap, action.yawDeg)
                        val yawRate = (ecart * 1.5f).coerceIn(-30f, 30f)
                        pilote.soumettre(RecepteurBridge.CommandeBridge(
                            System.currentTimeMillis() / 1000.0, 0f, 0f, 0f, yawRate,
                            "actif", System.currentTimeMillis()))
                        machine.pasCourant?.let { onProgres(it.index, it.total) }
                    }
                    is ca.cineflight.stage.cine.PanoramaStateMachine.Action.Patienter -> {
                        // hover strict pendant stabilisation / ecriture fichier
                        pilote.soumettre(RecepteurBridge.CommandeBridge(
                            System.currentTimeMillis() / 1000.0, 0f, 0f, 0f, 0f,
                            "actif", System.currentTimeMillis()))
                    }
                    is ca.cineflight.stage.cine.PanoramaStateMachine.Action.PrendrePhoto -> {
                        pilote.soumettre(RecepteurBridge.CommandeBridge(
                            System.currentTimeMillis() / 1000.0, 0f, 0f, 0f, 0f,
                            "actif", System.currentTimeMillis()))
                        pont.declencherPhoto()
                        photosPrises++
                        machine.pasCourant?.let { onProgres(it.index + 1, it.total) }
                    }
                    is ca.cineflight.stage.cine.PanoramaStateMachine.Action.Termine -> {
                        break
                    }
                    is ca.cineflight.stage.cine.PanoramaStateMachine.Action.Annule -> {
                        break
                    }
                }
                delay(150)   // ~6 Hz
            }
            // hover final propre
            pilote.soumettre(RecepteurBridge.CommandeBridge(
                System.currentTimeMillis() / 1000.0, 0f, 0f, 0f, 0f,
                "actif", System.currentTimeMillis()))
            panoramaEnCours = false
            // Restaurer le mode : si l'usager etait en MANUEL, on lui rend la main.
            if (!modeAutoAvant && modeAuto) basculerMode(false)
            onFini(photosPrises)
            // Decollage auto -> on complete le trajet : atterrissage VERTICAL. Le panorama
            // n'a fait que pivoter + monter droit, donc le drone est PILE au-dessus du point
            // de decollage ; descendre = revenir exactement au meme endroit.
            if (atterrirALaFin) pilote.atterrir { }
        }
    }

    private fun annulerPanorama() { panoramaEnCours = false }

    private fun contexteCineReel(espace: Espace = Espace.MOYEN): ContexteValidation {
        val e = pont.lireEtat(pilote.enVol)
        val modele = if (e.modele.isNotBlank()) e.modele else profilDrone.nomLisible
        // Reutilise l'evitement DEJA deduit par l'app (CapacitesDrone) : meme classification.
        val evit = when (profilDrone.evitement) {
            CapacitesDrone.Evitement.COMPLET -> Evitement.COMPLET
            CapacitesDrone.Evitement.PARTIEL -> Evitement.PARTIEL
            CapacitesDrone.Evitement.AUCUN   -> Evitement.AUCUN
        }
        return ContexteValidation(
            drone = ProfilDrone(modele, evit),
            espace = espace,
            espaceArriereConnu = false,        // prudent : on ignore ce qu'il y a derriere
            gpsValide = e.gpsValide,
            batteriePct = if (e.batteriePct >= 0) e.batteriePct else 100
        )
    }

    /** Analyse de scene. mode A (fixe) ou B (panoramique : pivot lent sur place).
     *  pivoter=false : observe droit devant (aucun mouvement).
     *  pivoter=true  : le drone tourne lentement sur place (yaw) pour balayer la scene.
     *  onResultat est appele sur le thread UI avec le rapport mesure. */
    private fun lancerAnalyseCine(
        pivoter: Boolean = false,
        dureeMs: Long = reglagesCine.getDureeAnalyseS() * 1000L,
        onResultat: (ca.cineflight.stage.cine.RapportReperage) -> Unit
    ) {
        if (analyseCineEnCours) return
        // Le pivot exige le Virtual Stick actif (mode auto). Sinon on retombe en mode fixe.
        val peutPivoter = pivoter && modeAuto && pont.lireEtat(pilote.enVol).enVol
        val pivotDps = reglagesCine.getVitessePivotDps()
        agregateurCine.demarrer()
        analyseCineEnCours = true
        commandesCine.bandeau(if (peutPivoter) getString(R.string.ma_analyse_pano) else getString(R.string.ma_analyse_scene))
        lifecycleScope.launch(Dispatchers.Main) {
            var restant = dureeMs
            while (restant > 0 && analyseCineEnCours) {
                if (peutPivoter) {
                    // pivot lent sur place : yaw doux reglable, aucune translation (vx=vy=vz=0)
                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                        System.currentTimeMillis().toDouble(), 0f, 0f, 0f, pivotDps,
                        "actif", System.currentTimeMillis()))
                }
                val tranche = if (restant > 250L) 250L else restant
                delay(tranche)
                restant -= tranche
            }
            // arret du pivot : hover (renvoie zero), le suivi normal reprendra ensuite
            if (peutPivoter) {
                pilote.soumettre(RecepteurBridge.CommandeBridge(
                    System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f,
                    "actif", System.currentTimeMillis()))
            }
            analyseCineEnCours = false
            agregateurCine.arreter()
            val rapport = agregateurCine.produire()
            onResultat(rapport)
        }
    }

    private fun basculerMode(versAuto: Boolean) {
        // GARDE-FOU GPS : interdit le vol automatique sous le seuil de satellites.
        if (versAuto) {
            val sat = pont.lireEtat(pilote.enVol).satellites
            if (sat < SAT_MIN_AUTO) {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.ma_toast_vol_sat_bloque, sat, SAT_MIN_AUTO),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                modeAuto = false
                majBoutonMode()
                return
            }
        }
        modeAuto = versAuto
        if (versAuto) {
            modeVol.activerVirtualStick { ok ->
                if (!ok) runOnUiThread {
                    bandeauEphemere(getString(R.string.ma_ban_vs_refus))
                    modeAuto = false; majBoutonMode()
                }
            }
        } else {
            pilote.arreter()
        }
        majBoutonMode()
    }

    private fun majBoutonMode() {
        if (modeAuto) {
            btnMode.text = ""
            btnMode.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_mode_target)
            btnMode.foregroundGravity = android.view.Gravity.CENTER
            btnMode.setBackgroundColor(0xFFEF6C00.toInt())  // orange = suivi en cours
        } else {
            btnMode.text = ""
            btnMode.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_mode_manuel)
            btnMode.foregroundGravity = android.view.Gravity.CENTER
            btnMode.setBackgroundColor(0xFF455A64.toInt())  // gris ardoise
        }
    }

    private fun demarrerChaine(etatInitial: String) {
        recepteur.demarrer(lifecycleScope)
        vEtat.text = etatInitial
        // boucle cockpit ~3 Hz
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                majCockpit(pont.lireEtat(pilote.enVol))
                try {
                    val em = pont.lireEtat(pilote.enVol)
                    val tel = dernierePositionTel
                    if (em.enVol && !em.latitude.isNaN() && !em.longitude.isNaN()) {
                        moniteurVlos?.observer(
                            droneLat = em.latitude, droneLon = em.longitude,
                            piloteLat = tel?.first, piloteLon = tel?.second,
                            precisionPiloteM = Double.NaN,
                            nowMs = System.currentTimeMillis()
                        )
                    }
                    if (em.enVol) {
                        moniteurAltitude?.observer(em.altitudeAgl, System.currentTimeMillis())
                    }
                    // Phase 3B : carte SD (etat cockpit ~3 Hz). Transition seulement cote adaptateur.
                    observateurVocal?.observerCarteSd(em.carteSdPresente, em.minutesEnregRestantes)
                    // Complement 3A : phases RTH deduites de l'altitude/distance pendant le RTH.
                    moniteurRthPhase?.observer(
                        rthActif = em.rthEnCours,
                        altitudeAglM = em.altitudeAgl,
                        distanceMaisonM = em.distanceDecollageM,
                        vitesseVertMps = em.vitesseVertM
                    )
                } catch (_: Throwable) {}
                delay(330)
            }
        }
    }

    /** Met a jour tout le bandeau + alertes a partir d'un instantane. */
    // Voyant REC : point rouge clignotant tant que l'enregistrement est actif.
    private var recClignoteActif = false
    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Position pour la meteo : drone si connecte, sinon GPS telephone (reutilise
     *  positionTelephone() qui gere la demande de permission et le suivi). */
    private fun positionPourMeteo(): Pair<Double, Double>? {
        // 1) position drone partagee (si drone connecte avec GPS)
        if (!derniereLatCarte.isNaN() && !derniereLonCarte.isNaN()) {
            return derniereLatCarte to derniereLonCarte
        }
        // 2) GPS du telephone : positionTelephone() demande la permission si absente,
        //    demarre le suivi, et renvoie le dernier fix (ou null le temps du 1er fix).
        return positionTelephone()
    }

    /** Recupere la meteo du lieu (GPS telephone/drone) en arriere-plan et met a jour la pastille. */
    private fun chargerMeteoCockpit() {
        chargerMeteoCockpitAvecEssais(3)
    }

    private fun chargerMeteoCockpitAvecEssais(essaisRestants: Int) {
        Thread {
            val pos = positionPourMeteo()
            if (pos == null) {
                // pas encore de fix GPS : reessayer un peu plus tard (le suivi vient de demarrer)
                if (essaisRestants > 1) {
                    pastilleMeteo?.postDelayed({ chargerMeteoCockpitAvecEssais(essaisRestants - 1) }, 2500)
                } else {
                    runOnUiThread { afficherPastilleMeteo(null) }
                }
                return@Thread
            }
            val codeDrone = try { ca.cineflight.stage.control.CapacitesDrone.codeMeteo(pont.lireEtat(pilote.enVol).modele) } catch (_: Exception) { "mini3" }
            codeMeteoDrone = codeDrone
            codeMissionDrone = try { ca.cineflight.stage.control.CapacitesDrone.codeMission(pont.lireEtat(pilote.enVol).modele) } catch (_: Exception) { codeMissionDrone }
            val plageT = try { ca.cineflight.stage.control.CapacitesDrone.plageTemp(pont.lireEtat(pilote.enVol).modele) } catch (_: Exception) { Pair(tempMinDrone, tempMaxDrone) }
            tempMinDrone = plageT.first; tempMaxDrone = plageT.second
            val c = ca.cineflight.stage.control.ConditionsVol.recuperer(pos.first, pos.second, codeDrone, plageT.first, plageT.second)
            runOnUiThread { afficherPastilleMeteo(c) }
        }.start()
    }

    private fun afficherPastilleMeteo(c: ca.cineflight.stage.control.ConditionsVol.Conditions?) {
        derniereConditionsMeteo = c
        // La pastille affiche desormais l'etat PRET/PAS PRET (GPS + batterie + meteo).
        // La boucle cockpit la rafraichit ~2 Hz ; ici on la met a jour tout de suite.
        try { majPastillePret(pont.lireEtat(pilote.enVol)) } catch (_: Exception) {}
    }

    /** Feu GPS : rouge = pas de fix fiable ; jaune = fix mais < seuil auto (au sol) ; vert = OK. */
    private fun feuGps(e: EtatCockpit): String = when {
        !e.gpsValide -> "rouge"
        !e.enVol && e.satellites < SAT_MIN_AUTO -> "jaune"
        else -> "vert"
    }

    /** Feu batterie selon les seuils faible / critique. */
    private fun feuBatterie(e: EtatCockpit): String = when {
        e.batteriePct < 0 -> "indisponible"
        e.batteriePct < BATT_CRITIQUE -> "rouge"
        e.batteriePct < BATT_FAIBLE -> "jaune"
        else -> "vert"
    }

    /** Verdict global PRET/PAS PRET = le pire des feux GPS / batterie / meteo (+ signal RC). */
    private fun evaluerPretVol(e: EtatCockpit): Pair<String, String> {
        if (!e.connecte) return Pair("indisponible", getString(R.string.prep_attente))
        val g = feuGps(e); val b = feuBatterie(e)
        val c = derniereConditionsMeteo
        val m = if (c != null && c.disponible) c.global else "indisponible"
        val rc = e.signalRcPct in 0 until SIGNAL_FAIBLE
        val feu = when {
            g == "rouge" || b == "rouge" || m == "rouge" -> "rouge"
            g == "jaune" || b == "jaune" || m == "jaune" || rc -> "jaune"
            else -> "vert"
        }
        val label = when (feu) {
            "vert" -> getString(R.string.prep_pret)
            "jaune" -> getString(R.string.prep_prudence)
            else -> getString(R.string.prep_pas_pret)
        }
        return Pair(feu, label)
    }

    /** Met a jour la pastille PRET/PAS PRET (haut-gauche du cockpit). */
    private fun majPastillePret(e: EtatCockpit) {
        val p = pastilleMeteo ?: return
        val CV = ca.cineflight.stage.control.ConditionsVol
        val (feu, label) = evaluerPretVol(e)
        p.text = CV.pastille(feu) + " " + label
    }

    /** Detail au toucher de la pastille : bilan GPS + batterie + meteo + verdict PRET. */
    private fun afficherDetailPret() {
        val CV = ca.cineflight.stage.control.ConditionsVol
        val e = try { pont.lireEtat(pilote.enVol) } catch (_: Exception) { EtatCockpit() }
        if (!e.connecte) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.prep_titre))
                .setMessage(getString(R.string.prep_drone_off))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.ma_dlg_actualiser) { _, _ -> chargerMeteoCockpit() }
                .show()
            return
        }
        val c = derniereConditionsMeteo
        val gFeu = feuGps(e); val bFeu = feuBatterie(e)
        val gTxt = when (gFeu) {
            "vert" -> getString(R.string.prep_gps_ok, e.satellites)
            "jaune" -> getString(R.string.prep_gps_faible, e.satellites, SAT_MIN_AUTO)
            else -> getString(R.string.prep_gps_attente)
        }
        val bTxt = when (bFeu) {
            "vert" -> getString(R.string.prep_batt_ok, e.batteriePct)
            "jaune" -> getString(R.string.prep_batt_faible, e.batteriePct)
            "rouge" -> getString(R.string.prep_batt_critique, e.batteriePct)
            else -> getString(R.string.prep_batt_attente)
        }
        val feu = evaluerPretVol(e).first
        val verdict = when (feu) {
            "vert" -> getString(R.string.prep_verdict_pret)
            "jaune" -> getString(R.string.prep_verdict_prudence)
            else -> getString(R.string.prep_verdict_pas_pret)
        }
        val msg = buildString {
            append("${CV.pastille(gFeu)}  GPS : $gTxt\n")
            append("${CV.pastille(bFeu)}  ${getString(R.string.prep_batt_label)} : $bTxt\n")
            if (c != null && c.disponible) {
                append("${CV.pastille(c.vent.feu)}  ${c.vent.texte}\n")
                append("${CV.pastille(c.pluie.feu)}  ${c.pluie.texte}\n")
                append("${CV.pastille(c.lumiere.feu)}  ${c.lumiere.texte}\n")
                append("${CV.pastille(c.temperature.feu)}  ${c.temperature.texte}\n")
            } else {
                append("\u26AA  ${getString(R.string.prep_meteo_indispo)}\n")
            }
            append("\n$verdict\n\n")
            append(getString(R.string.prep_sur_place))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.prep_titre))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.ma_dlg_actualiser) { _, _ -> chargerMeteoCockpit() }
            .show()
    }

    /** Demande une confirmation avant une commande de vol. Si "danger", le bouton
     *  de confirmation est rouge. On execute seulement si l'usager confirme. */
    private fun confirmerVol(titre: String, message: String, labelOui: String, danger: Boolean, onOui: () -> Unit) {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(message)
            .setNegativeButton(getString(R.string.ma_annuler), null)
            .setPositiveButton(labelOui) { _, _ -> onOui() }
            .show()
        // LISIBILITE PLEIN SOLEIL : gros texte BLANC sur les boutons de confirmation
        // (RAMENER / DECOLLER / ATTERRIR / ANNULER...). Action rouge vif si danger.
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (danger) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())
        }
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    /** Retour immediat quand on touche une commande de vol : l'icone s'enfonce,
     *  vibration courte, et un message confirme l'action demandee. */
    private fun feedbackVol(b: android.view.View, message: String) {
        b.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70).withEndAction {
            b.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
        try { b.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Exception) {}
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun majVoyantRec(enregistre: Boolean, connecte: Boolean = true) {
        // Aspect du bouton REC :
        //  - drone non connecte : bouton grisi + point rouge attenue (on ne peut pas enregistrer)
        //  - connecte, au repos  : gris + point rouge vif (pret a enregistrer)
        //  - en enregistrement    : rouge vif + carre blanc (STOP)
        btnRec.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (enregistre) 0xFFD32F2F.toInt() else 0xFF37474F.toInt()
        )
        btnRec.foreground = androidx.core.content.ContextCompat.getDrawable(
            this, if (enregistre) R.drawable.ic_stop else R.drawable.ic_rec
        )
        btnRec.alpha = if (!connecte && !enregistre) 0.35f else 1f
        val v = voyantRec ?: return
        if (enregistre) {
            if (v.visibility != android.view.View.VISIBLE) {
                v.visibility = android.view.View.VISIBLE
            }
            if (!recClignoteActif) {
                recClignoteActif = true
                val clignote = android.view.animation.AlphaAnimation(1f, 0.15f).apply {
                    duration = 600
                    repeatCount = android.view.animation.Animation.INFINITE
                    repeatMode = android.view.animation.Animation.REVERSE
                }
                v.startAnimation(clignote)
            }
        } else {
            if (recClignoteActif) {
                recClignoteActif = false
                v.clearAnimation()
            }
            if (v.visibility != android.view.View.GONE) {
                v.visibility = android.view.View.GONE
            }
        }
    }

    private fun majCockpit(e: EtatCockpit) {
        // Profil de capacites : si le modele a change (arrive apres connexion), on recalcule.
        if (e.modele.isNotEmpty() && e.modele != profilDrone.modeleBrut) {
            profilDrone = CapacitesDrone.analyser(e.modele)
        }
        // position partagee avec l'ecran carte
        derniereLatCarte = e.latitude
        derniereLonCarte = e.longitude
        dernierCapCarte = e.capDeg
        derniereGpsOkCarte = e.gpsValide
        try { majMiniCarte() } catch (_: Exception) {}
        try { majRadar() } catch (_: Exception) {}
        // batterie
        txtBatterie.text = if (e.batteriePct >= 0) "\uD83D\uDD0B ${e.batteriePct}%" else "\uD83D\uDD0B \u2014"
        txtBatterie.setTextColor(
            when {
                e.batteriePct in 0 until BATT_CRITIQUE -> 0xFFFF5252.toInt()
                e.batteriePct in 0 until BATT_FAIBLE -> 0xFFFFC107.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )
        // altitude
        txtAltitude.text = if (!e.altitudeAgl.isNaN())
            "\u2195 ${fmt1(e.altitudeAgl)} m" else "\u2195 \u2014"
        // distance
        txtDistance.text = if (!e.distanceDecollageM.isNaN())
            "\u2922 ${fmt1(e.distanceDecollageM)} m" else "\u2922 \u2014"
        // stockage carte SD + minutes restantes
        txtStockage?.let { ts ->
            when {
                !e.carteSdPresente -> {
                    ts.text = getString(R.string.ma_stockage_non)          // pas de carte
                    ts.setTextColor(0xFFFF5252.toInt())   // rouge
                }
                e.minutesEnregRestantes in 0..4 -> {
                    ts.text = "\uD83D\uDCBE ${e.minutesEnregRestantes}min"
                    ts.setTextColor(0xFFFFC107.toInt())   // jaune : presque pleine
                }
                e.minutesEnregRestantes >= 5 -> {
                    ts.text = "\uD83D\uDCBE ${e.minutesEnregRestantes}min"
                    ts.setTextColor(0xFF69F0AE.toInt())   // vert : OK
                }
                else -> {
                    ts.text = "\uD83D\uDCBE \u2713"        // carte pr?sente, dur?e inconnue
                    ts.setTextColor(0xFF69F0AE.toInt())
                }
            }
        }
        // vitesse (H + V)
        txtVitesse.text = if (!e.vitesseHorizM.isNaN())
            "\u27A4 ${fmt1(e.vitesseHorizM)} | \u2191${fmt1(e.vitesseVertM)}" else "\u27A4 \u2014"
        // GPS
        txtGps.text = "\uD83D\uDEF0 ${e.satellites}" + if (e.gpsValide) " \u2713" else " \u2717"
        txtGps.setTextColor(if (e.gpsValide) 0xFFFFFFFF.toInt() else 0xFFFF5252.toInt())
        // pastille PRET / PAS PRET (agrege GPS + batterie + meteo)
        majPastillePret(e)
        // cap
        txtCap.text = if (!e.capDeg.isNaN()) "\uD83E\uDDED ${e.capDeg.roundToInt()}\u00B0" else "\uD83E\uDDED \u2014"
        // signaux
        txtSignalRc.text = if (e.signalRcPct >= 0) "\uD83D\uDCF6 RC ${e.signalRcPct}%" else "\uD83D\uDCF6 RC \u2014"
        txtSignalVideo.text = if (e.signalVideoPct >= 0) "\uD83C\uDFA5 ${e.signalVideoPct}%" else "\uD83C\uDFA5 \u2014"
        // REC visuel
        btnRec.backgroundTintList = android.content.res.ColorStateList.valueOf(if (e.enregistre) 0xFFC62828.toInt() else 0xFF37474F.toInt())
        majVoyantRec(e.enregistre, e.connecte)

        // --- Enregistrement intelligent ---
        // 1) Coupe AUTO a l'atterrissage / RTH : le plan est fini, on sauvegarde le fichier.
        if (etaitEnVolPourRec && (!e.enVol || e.rthEnCours) && e.enregistre) {
            pont.arreterEnregistrement()
            bandeauEphemere(getString(R.string.ma_ban_rec_arret))
        }
        etaitEnVolPourRec = e.enVol && !e.rthEnCours
        // 2) Suggestion batterie basse (une fois) : on NE coupe pas, on previent.
        if (e.enregistre && e.batteriePct in 1..14 && !avertiBatterieRec) {
            avertiBatterieRec = true
            bandeauEphemere(getString(R.string.ma_ban_batt_faible))
        }
        if (e.batteriePct >= 20) avertiBatterieRec = false   // re-arme si la batterie remonte (changement)

        // etat pilote + bridge
        // --- SUIVI YOLO : reflete la derniere commande de cadrage recue ---
        val cmd = recepteur.derniereCommande
        val suiviTxt = if (modeAuto && cmd != null && cmd.cadrage)
            getString(R.string.ma_suivi_yolo, cmd.gimbalYaw.toInt()) +
            (if (cmd.rec) " \u23FAREC" else "")
        else ""

        val modeTxt = if (modeAuto) getString(R.string.ma_mode_auto) else getString(R.string.ma_mode_manuel)
        vEtat.text = "[$modeTxt] ${pilote.dernierEtat}" +
            (if (e.enVol) getString(R.string.ma_en_vol) else "") + suiviTxt
        val ageMs = System.currentTimeMillis() - recepteur.derniereReception
        vStats.text = if (recepteur.derniereReception > 0 && ageMs < 1000)
            getString(R.string.ma_bridge_ok, ageMs, recepteur.nbRecus)
        else getString(R.string.ma_bridge_none)

        // --- ALERTES (priorite : la plus grave d'abord) ---
        val alerte: String? = when {
            !e.connecte -> getString(R.string.ma_alert_deco)
            e.batteriePct in 0 until BATT_CRITIQUE -> getString(R.string.ma_alert_batt_crit, e.batteriePct)
            e.rthEnCours -> getString(R.string.ma_alert_rth)
            e.satellites in 0 until SAT_MIN_AUTO && !e.enVol -> getString(R.string.ma_alert_sat, e.satellites, SAT_MIN_AUTO)
            e.batteriePct in 0 until BATT_FAIBLE -> getString(R.string.ma_alert_batt_faible, e.batteriePct)
            !e.gpsValide && e.enVol -> getString(R.string.ma_alert_gps)
            e.signalRcPct in 0 until SIGNAL_FAIBLE -> getString(R.string.ma_alert_rc, e.signalRcPct)
            modeAuto && cibleVerrouillee && e.enVol && !e.enregistre -> getString(R.string.ma_alert_rec_off)
            else -> null
        }
        if (alerte != null) {
            txtAlerte.text = alerte
            txtAlerte.visibility = android.view.View.VISIBLE
        } else {
            txtAlerte.visibility = android.view.View.GONE
        }

        // --- MASQUAGE AUTO DES CONTROLES DE CONFIG EN VOL ---
        // En vol : on replie plans / mouvements / macros / valeurs pour degager l'image.
        // On NE TOUCHE PAS a la rangee Rail (Cable-Cam se definit et se declenche en vol),
        // ni aux boutons critiques (Decoller / Atterrir / RTH / Urgence), ni au suivi YOLO.
        // Au sol : tout revient. Applique seulement quand l'etat change (pas a chaque frame).
        val enVolAffichage = e.enVol || TEST_FORCER_VOL
        if (configMasqueeEnVol != enVolAffichage) {
            configMasqueeEnVol = enVolAffichage
            val vis = if (enVolAffichage) android.view.View.GONE else android.view.View.VISIBLE
            // Option C : plans et mouvements restent accessibles en vol (on change le
            // comportement du drone a la volee). On masque seulement macros + valeurs.
            findViewById<android.widget.HorizontalScrollView>(R.id.groupeMacros)?.visibility = vis
            findViewById<android.widget.TextView>(R.id.txtValeursMouv)?.visibility = vis
        }
    }

    private fun fmt1(v: Double): String =
        if (v.isNaN()) "\u2014" else ((v * 10).roundToInt() / 10.0).toString()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Permission micro accordee -> demarrer l'ecoute vocale (activee depuis Reglages)
        if (requestCode == 200 && grantResults.isNotEmpty()
            && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (getSharedPreferences("cineflight", MODE_PRIVATE).getBoolean("voix_active", false)) {
                ecoute?.demarrer()
            }
        }
        // Permission localisation accordee -> (re)charger la meteo du cockpit
        if (requestCode == 300 && grantResults.isNotEmpty()
            && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            chargerMeteoCockpit()
        }
    }

    override fun onDestroy() {
        ttsClicker?.stop()
        ttsClicker?.shutdown()
        ttsClicker = null
        ttsClickerPret = false
        clientValidationClicker.fermer()
        perceptionObsJob?.cancel()  // maillon 2 : cleanup onDestroy
        perceptionObsJob = null
        lecteurPerception.arreter()
        vueDiagnosticPredictionRtk?.fermer()
        vueDiagnosticPredictionRtk = null
        super.onDestroy()
        // (masquage auto config en vol gere plus bas)
        EnregistrementSdk.onConnexionProduit = null
        arreterPollingSujet()   // AJOUT RTK : stoppe le polling position sujet
        jobPollClicker?.cancel() // CLICKER : stoppe le poll de la telecommande
        jobPollClicker = null
        flux?.arreter()
        yoloSuivi?.arreter()
        vocal?.arreter()
        if (::recepteur.isInitialized) recepteur.arreter()
        if (::recepteurBoxes.isInitialized) recepteurBoxes.arreter()
        if (::pilote.isInitialized) pilote.arreter()
    }

    // ============================================================================
    // EVITEMENT CINEMATIQUE (orbite/mouvements VirtualStick) - OFF par defaut.
    // Principe : ne rend le vol que PLUS prudent. Si un obstacle est proche, on
    // ELARGIT le rayon (recule du sujet) et on RALENTIT - jamais l'inverse.
    // Bornes strictes. A TESTER EN SIMULATEUR DJI avant tout vol reel.
    // Distances LecteurPerception en MILLIMETRES (confirme doc DJI ObstacleData).
    // ============================================================================
    // Etat d'evitement DERIVE du mode regle (Reglages.getModeEvitement) :
    //   0 = OFF  -> jamais d'evitement (test du suivi seul)
    //   1 = ON   -> evitement force (test evitement / suivi+evitement)
    //   2 = AUTO -> evitement seulement si le drone fournit des donnees capteurs recentes
    // DEFAUT = OFF (Reglages). Recalcule a chaque appel : AUTO reagit a la connexion drone.
    // ===== MAILLON 2 - OBSERVATION PASSIVE (Option A). LecteurPerception INTACT.
    // Demarre le lecteur apres connexion drone, sonde 500ms, journalise les
    // transitions. NE MODIFIE AUCUNE COMMANDE. N'active PAS FusionPerception. =====
    private enum class PerceptionObservationState { STOPPED, WAITING_FIRST_FRAME, FRESH, STALE }
    @Volatile private var perceptionObsState = PerceptionObservationState.STOPPED
    private var perceptionObsJob: Job? = null

    /** Demarre l'observation passive (idempotent). Appele sur connexion drone. */
    private fun demarrerObservationPerceptionNormale() {
        if (!PERCEPTION_NORMAL_PATH_OBSERVATION_ENABLED) return
        if (perceptionObsJob?.isActive == true) return
        // Active le canal de diagnostic (idempotent) pour que les transitions soient journalisees.
        try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.activer() } catch (_: Throwable) {}
        try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("LISTENER_START_REQUESTED") } catch (_: Throwable) {}
        lecteurPerception.demarrer()
        perceptionObsState = PerceptionObservationState.WAITING_FIRST_FRAME
        try {
            val mdl = try { profilDrone.modeleBrut } catch (_: Throwable) { "?" }
            ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("LISTENER_STARTED", "model=$mdl")
        } catch (_: Throwable) {}
        // LECTURE SEULE : interroge le type d'evitement via le GETTER direct (fiable,
        // independant du listener). Aucun setter. Journalise le resultat.
        try {
            ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("OA_TYPE_QUERY_REQUESTED")
            lecteurPerception.interrogerTypeEvitement(
                onResultat = { t -> try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("OA_TYPE_RESULT", "type=$t") } catch (_: Throwable) {} },
                onErreur = { e -> try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("OA_TYPE_ERROR", "desc=$e") } catch (_: Throwable) {} }
            )
        } catch (_: Throwable) {}
        perceptionObsJob = lifecycleScope.launch {
            while (isActive) {
                try { observerEtatPerceptionNormale() } catch (_: Throwable) {}
                delay(500)
            }
        }
    }

    /** Un tick de sondage : calcule et journalise les transitions (Option A). */
    private fun observerEtatPerceptionNormale() {
        val vsActif = try { pont.lireEtat(pilote.enVol).enVol } catch (_: Throwable) { false }
        val d = try { lecteurPerception.distanceHorizontale() } catch (_: Throwable) { null }
        // Une trame n'est consideree VALIDE que si elle porte une distance non nulle.
        // perceptionDisponible() seul peut etre vrai sur une trame vide (raw_list=[])
        // recue trop tot apres connexion : ce n'est PAS une "premiere trame".
        val valide = (d != null)
        // Ce moniteur s'execute toutes les 500 ms : etat d'affichage seulement.
        val modeleAff = try { profilDrone.modeleBrut } catch (_: Throwable) { "?" }
        // Re-interroge le getter a chaque tick (leger) pour capter un changement eventuel.
        try { lecteurPerception.interrogerTypeEvitement(onResultat = {}, onErreur = {}) } catch (_: Throwable) {}
        val oaGetter = try { lecteurPerception.typeEvitementGetterLu() } catch (_: Throwable) { null }
        val oaListener = try { lecteurPerception.typeEvitementLu() } catch (_: Throwable) { null }
        val oaType = oaGetter ?: oaListener
        val vision = try { lecteurPerception.visionPositioningActive() } catch (_: Throwable) { false }
        runOnUiThread { majBandeauPerception(perceptionObsState, d, vsActif, modeleAff, oaType, vision) }
        when (perceptionObsState) {
            PerceptionObservationState.WAITING_FIRST_FRAME -> {
                if (valide) {
                    perceptionObsState = PerceptionObservationState.FRESH
                    try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("FIRST_FRAME", "vs_active=$vsActif raw=$d") } catch (_: Throwable) {}
                } else {
                    // SDK peut-etre pas encore pret a la connexion : on re-tente
                    // l'abonnement (idempotent) tant qu'aucune donnee valide n'arrive.
                    try { lecteurPerception.demarrer() } catch (_: Throwable) {}
                }
            }
            PerceptionObservationState.FRESH -> {
                if (!valide) {
                    perceptionObsState = PerceptionObservationState.STALE
                    try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("STALE", "vs_active=$vsActif") } catch (_: Throwable) {}
                }
            }
            PerceptionObservationState.STALE -> {
                if (valide) {
                    perceptionObsState = PerceptionObservationState.FRESH
                    try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("RESTORED", "vs_active=$vsActif raw=$d") } catch (_: Throwable) {}
                } else {
                    // Toujours pas de donnee valide : re-tenter l'abonnement (idempotent).
                    try { lecteurPerception.demarrer() } catch (_: Throwable) {}
                }
            }
            PerceptionObservationState.STOPPED -> { }
        }
    }

    /**
     * MAILLON 2 : met a jour le bandeau d'observation a l'ecran (cree a la volee,
     * comme bandeauCorridor). Purement informatif : n'agit sur AUCUNE commande.
     */
    private fun majBandeauPerception(etat: PerceptionObservationState, rawMm: Int?, vsActif: Boolean, modele: String, oaType: String? = null, vision: Boolean = false) {
        try {
            if (bandeauPerception == null) {
                val racine = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
                val tv = TextView(this).apply {
                    setPadding(24, 16, 24, 16)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT)
                    lp.gravity = android.view.Gravity.BOTTOM
                    layoutParams = lp
                    visibility = android.view.View.GONE
                }
                racine.addView(tv)
                bandeauPerception = tv
            }
            val tv = bandeauPerception ?: return
            val vs = if (vsActif) "VS actif" else "VS inactif"
            val (couleur, texte) = when (etat) {
                PerceptionObservationState.STOPPED ->
                    Pair(0xFF616161.toInt(), "PERCEPTION : arretee")
                PerceptionObservationState.WAITING_FIRST_FRAME ->
                    Pair(0xFFF9A825.toInt(), "PERCEPTION : en attente de trames ($modele, $vs)")
                PerceptionObservationState.FRESH -> {
                    val m = if (rawMm != null) "%.2f m".format(rawMm / 1000.0) else "?"
                    Pair(0xFF2E7D32.toInt(), "PERCEPTION : OK  $m  ($modele, $vs)")
                }
                PerceptionObservationState.STALE ->
                    Pair(0xFFC62828.toInt(), "PERCEPTION : trames perdues ($modele, $vs)")
            }
            val oaSuffixe = "  [OA=" + (oaType ?: "?") + " vision=" + (if (vision) "on" else "off") + "]"
            tv.setBackgroundColor(couleur)
            tv.text = texte + oaSuffixe
            tv.visibility = if (etat == PerceptionObservationState.STOPPED)
                android.view.View.GONE else android.view.View.VISIBLE
        } catch (_: Throwable) { /* affichage best-effort : ne bloque jamais */ }
    }


    /** Arret propre de l'observation (idempotent). Appele sur deconnexion drone. */
    private fun arreterObservationPerceptionNormale() {
        if (!PERCEPTION_NORMAL_PATH_OBSERVATION_ENABLED) return
        perceptionObsJob?.cancel()
        perceptionObsJob = null
        if (perceptionObsState != PerceptionObservationState.STOPPED) {
            perceptionObsState = PerceptionObservationState.STOPPED
            try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.transition("LISTENER_STOPPED") } catch (_: Throwable) {}
        }
        lecteurPerception.arreter()
        // Masque le bandeau d'observation a l'ecran.
        try { runOnUiThread { bandeauPerception?.visibility = android.view.View.GONE } } catch (_: Throwable) {}
        // Ferme le canal de diagnostic (idempotent) : vide la file puis stoppe le consumer.
        try { ca.cineflight.stage.sentinelle.PerceptionDiagLogger.desactiver() } catch (_: Throwable) {}
    }

    private fun evitementDoitAgir(): Boolean = when (reglages.getModeEvitement()) {
        1 -> true                                      // ON
        2 -> lecteurPerception.perceptionDisponible()  // AUTO : capteurs presents et recents
        else -> false                                  // OFF (0) et toute valeur inattendue
    }
    private val EVIT_DANGER_MM = 4000      // sous 4 m : obstacle proche, on reagit
    private val EVIT_CRITIQUE_MM = 2000    // sous 2 m : on stoppe la progression laterale
    private val EVIT_RECUL_MAX = 0.6f      // m/s max d'eloignement ajoute
    private data class Vit(val vx: Float, val vy: Float, val vz: Float, val yaw: Float)
    /**
     * Applique l'evitement aux vitesses cinematiques. Retourne des vitesses
     * EGALES ou PLUS PRUDENTES que l'entree. Ne peut jamais pousser vers l'obstacle.
     */
    private fun appliquerEvitement(vx: Float, vy: Float, vz: Float, yaw: Float): Vit {
        // === DIAGNOSTIC PASSIF (off par defaut) : cycle_id + trace INPUT. Ne modifie RIEN. ===
        val diagOn = ca.cineflight.stage.sentinelle.PerceptionDiagLogger.actif
        val cid = if (diagOn) ca.cineflight.stage.sentinelle.PerceptionDiagLogger.nouveauCycleId() else ""
        if (diagOn) {
            try {
                val dbg = lecteurPerception.distanceHorizontale()
                ca.cineflight.stage.sentinelle.PerceptionDiagLogger.avoidanceInput(
                    cycleId = cid, ts = System.currentTimeMillis(),
                    perceptionAgeMs = -1L,
                    modeEvitement = reglages.getModeEvitement(),
                    aircraftModel = try { profilDrone.modeleBrut } catch (_: Throwable) { "?" },
                    vxBefore = vx, vyBefore = vy, vzBefore = vz, yawBefore = yaw,
                    nearestSector = "MIN_ALL", nearestRaw = dbg, nearestM = dbg?.let { it / 1000.0 }
                )
            } catch (_: Throwable) {}
        }
        fun sortie(v: Vit, action: String, reason: String): Vit {
            if (diagOn) {
                try {
                    ca.cineflight.stage.sentinelle.PerceptionDiagLogger.avoidanceOutput(
                        cycleId = cid, vxAfter = v.vx, vyAfter = v.vy, vzAfter = v.vz, yawAfter = v.yaw,
                        action = action, reason = reason
                    )
                } catch (_: Throwable) {}
            }
            return v
        }
        if (!evitementDoitAgir()) return sortie(Vit(vx, vy, vz, yaw), "UNCHANGED", "mode_off_ou_indispo")
        val d = lecteurPerception.distanceHorizontale() ?: return sortie(Vit(vx, vy, vz, yaw), "UNCHANGED", "pas_de_donnee")
        if (d >= EVIT_DANGER_MM) return sortie(Vit(vx, vy, vz, yaw), "UNCHANGED", "obstacle_loin")   // rien de proche : nominal
        // proche : facteur 0 (a la limite danger) -> 1 (tres proche)
        val intensite = ((EVIT_DANGER_MM - d).toFloat() / (EVIT_DANGER_MM - EVIT_CRITIQUE_MM)).coerceIn(0f, 1f)
        // 1) eloignement : composante radiale negative (recule du sujet = elargit le rayon)
        val recul = -EVIT_RECUL_MAX * intensite
        val vxNouv = minOf(vx, recul)          // on ne garde que la plus "reculante" -> jamais plus avance
        // 2) ralentir la rotation/laterale proportionnellement
        val vyNouv = vy * (1f - intensite)
        // 3) sous le seuil critique : stopper toute progression laterale
        val vyFinal = if (d < EVIT_CRITIQUE_MM) 0f else vyNouv
        // altitude et yaw inchanges (l'evitement horizontal suffit pour une 1e version)
        val action = if (d < EVIT_CRITIQUE_MM) "BLOCKED" else "SLOWED"
        return sortie(Vit(vxNouv, vyFinal, vz, yaw), action, "d=$d intensite=$intensite")
    }

    private fun commandeHoldRtkVision(): RecepteurBridge.CommandeBridge =
        RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = 0f,
            vy = 0f,
            vz = 0f,
            yawRate = 0f,
            mode = "actif",
            recuA = System.currentTimeMillis(),
            gimbalPitch = 0f,
            gimbalYaw = 0f
        )

    private fun evaluerControleRtkVision(
        visionRequise: Boolean,
        yoloTrouve: Boolean,
        hauteurBoite: Float
    ): ca.cineflight.stage.control.AutorisationControleRtkVision.Verdict {
        val verdict = autorisationControleRtkVision.evaluer(
            position = dernierePositionSujetV4,
            predictionControlReady = dernierePredictionV4?.controlReady == true,
            cibleVerrouillee = cibleVerrouillee,
            visionRequise = visionRequise,
            yoloTrouve = yoloTrouve,
            confianceYolo = derniereConfianceSujet,
            nbCibles = dernierNbPersonnes,
            hauteurBoite = hauteurBoite
        )
        val precedent = dernierVerdictControleRtkVision
        dernierVerdictControleRtkVision = verdict
        if (precedent?.mode != verdict.mode || precedent.raison != verdict.raison) {
            android.util.Log.i(
                "CF_RtkVisionControl",
                "mode=${verdict.mode} reason=${verdict.raison} " +
                    "rtk=${dernierePositionSujetV4?.rtk?.name ?: "LOST"} " +
                    "yolo=${if (yoloTrouve) "LOCK" else "LOST"} " +
                    "confidence=${"%.2f".format(java.util.Locale.US, derniereConfianceSujet)} " +
                    "targets=$dernierNbPersonnes box_h=${"%.3f".format(java.util.Locale.US, hauteurBoite)}"
            )
            journalSessionRtkV41?.enregistrerSysteme(
                "RTK_VISION_CONTROL",
                "mode=${verdict.mode};reason=${verdict.raison}"
            )
        }
        return verdict
    }

    /**
     * Point unique de soumission des commandes issues du suivi YOLO.
     * En FLOAT+VISION, vx/vy/vz et yaw sont forces a zero avant l'appel au pilote.
     */
    private fun soumettreCommandeRtkVision(
        commande: RecepteurBridge.CommandeBridge,
        visionRequise: Boolean,
        yoloTrouve: Boolean,
        hauteurBoite: Float
    ) {
        val verdict = evaluerControleRtkVision(visionRequise, yoloTrouve, hauteurBoite)
        when (verdict.mode) {
            ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FIX_COMPLET -> {
                pilote.soumettre(commande)
            }
            ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FLOAT_VISION -> {
                val axes = autorisationControleRtkVision.limiterFloatVision(
                    ca.cineflight.stage.control.AutorisationControleRtkVision.Axes(
                        vx = commande.vx,
                        vy = commande.vy,
                        vz = commande.vz,
                        yawRate = commande.yawRate,
                        gimbalPitch = commande.gimbalPitch,
                        gimbalYaw = commande.gimbalYaw
                    )
                )
                pilote.soumettre(
                    RecepteurBridge.CommandeBridge(
                        t = System.currentTimeMillis() / 1000.0,
                        vx = axes.vx,
                        vy = axes.vy,
                        vz = axes.vz,
                        yawRate = axes.yawRate,
                        mode = "actif",
                        recuA = System.currentTimeMillis(),
                        gimbalPitch = axes.gimbalPitch,
                        gimbalYaw = axes.gimbalYaw
                    )
                )
            }
            ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.BLOQUE -> {
                pilote.soumettre(commandeHoldRtkVision())
            }
        }
    }

    private fun calculerSuivi(cx: Float, cy: Float, w: Float, h: Float): RecepteurBridge.CommandeBridge {
        val errX = cx - 0.5f
        val yawRate = (errX * 40f).coerceIn(-25f, 25f)
        val cibleH = cibleHPlan
        val errH = cibleH - h
        // Vitesse avant/arriere pour tenir le cadrage. L'AVANCE (se rapprocher) garde
        // un plafond fixe prudent. Le RECUL (vx negatif) depend des capteurs du drone :
        // plus large si le drone a l'evitement arriere, prudent (0.8) sinon (recul a l'aveugle).
        val plafReco = profilDrone.plafondReculMps
        val vx = (errH * 1.2f).coerceIn(-plafReco, 0.8f)
        val errY = cy - 0.5f
        val gimbalPitch = (-errY * 25f).coerceIn(-20f, 20f)
        // --- composante mouvement cinematographique ---
        var vyMouv = 0f
        var vxMouv = vx
        var vzMouv = 0f
        var yawMouv = yawRate
        when (mouvementActuel) {
            1 -> {
                val rayonCible = reglages.get(Reglages.ORBITE_RAYON)
                val hautCible = reglages.get(Reglages.ORBITE_HAUTEUR)
                val vitRot = reglages.get(Reglages.ORBITE_VITESSE)
                val distEstimee = if (h > 0.01f) calibK / h else rayonCible
                val errRayon = distEstimee - rayonCible
                vxMouv = (errRayon * 0.5f).coerceIn(-0.7f, 0.7f)
                val alt = pont.lireEtat(pilote.enVol).altitudeAgl
                if (!alt.isNaN()) {
                    val errAlt = (hautCible - alt).toFloat()
                    vzMouv = (errAlt * 0.4f).coerceIn(-0.5f, 0.5f)
                }
                vyMouv = vitRot
            }                    // ORBITE : translation laterale + yaw recentre (deja calcule)
            2 -> { vyMouv = (errX * reglages.get(Reglages.TRAVEL_REACTIV)).coerceIn(-0.6f, 0.6f) }
            3 -> { vxMouv = (vx - reglages.get(Reglages.REVEL_RECUL)).coerceIn(-0.8f, 0.8f); vzMouv = reglages.get(Reglages.REVEL_MONTEE) }
            4 -> {
                // APPROCHE SECURISEE : avance vers le sujet MAIS jamais sous la distance
                // minimale de securite. La cible d'approche est une personne -> on garde
                // une marge (>= 2.5 m). L'evitement (appliquerEvitement) reste prioritaire
                // par-dessus et peut toujours forcer le recul si un obstacle physique approche.
                val APP_DIST_MIN_PERSONNE = 2.5f
                val distArret = maxOf(reglages.get(Reglages.APP_DISTANCE), APP_DIST_MIN_PERSONNE)
                val distEstimee = if (h > 0.01f) calibK / h else distArret
                val marge = distEstimee - distArret
                vxMouv = when {
                    marge > 0.3f  -> reglages.get(Reglages.APP_VITESSE)        // assez loin : on approche
                    marge > 0f    -> reglages.get(Reglages.APP_VITESSE) * (marge / 0.3f)  // ralentit en arrivant
                    marge > -0.3f -> 0f                                         // a la distance d'arret : stop
                    else          -> -0.3f                                      // trop pres : recul doux
                }
            }
            5 -> {
                // SPOTLIGHT : le pilote vole, le drone NE bouge PAS tout seul.
                // Seule la camera (gimbal) suit le sujet pour le garder cadre.
                vxMouv = 0f; vyMouv = 0f; vzMouv = 0f; yawMouv = 0f
            }
            6 -> {
                // RECUL : le drone vous precede et recule pendant que vous avancez.
                // - vxMouv = vx : recul REACTIF deja calcule (asservi a la taille du sujet),
                //   plafond de recul adapte au drone (capteurs arriere). Vous avancez -> il recule ;
                //   vous ralentissez -> il ralentit ; vous vous arretez -> il s'arrete.
                // - altitude asservie a une hauteur cible (hauteur d'oeil ou legere plongee).
                // - leger decalage lateral pour un rendu trois-quarts (optionnel).
                // - recentrage (yaw) + nacelle deja calcules pour vous garder de face et cadre.
                vxMouv = vx
                val hautCible = reglages.get(Reglages.RECUL_HAUTEUR)
                val alt = pont.lireEtat(pilote.enVol).altitudeAgl
                if (!alt.isNaN()) {
                    val errAlt = (hautCible - alt).toFloat()
                    vzMouv = (errAlt * 0.4f).coerceIn(-0.5f, 0.5f)
                }
                val cote = reglages.get(Reglages.RECUL_COTE)   // angle en degres -45..+45
                vyMouv = ((cote / 45f) * 0.4f).coerceIn(-0.4f, 0.4f)  // angle -> decalage lateral doux
            }
            7 -> {
                // SUIVI : le drone vous suit par-derriere pendant que vous avancez.
                // Miroir du Recul, mais le drone DEMARRE derriere vous (vous le lancez de dos).
                // - quand vous vous eloignez (errH > 0 -> vx > 0), on AMPLIFIE l'avance par
                //   la reactivite de poursuite pour qu'il vous colle ; le recul (errH < 0)
                //   reste au gain normal. Plafond avant 0.8 (il avance vers vous, il VOIT devant).
                val react = reglages.get(Reglages.SUIVI_REACTIV)
                vxMouv = if (errH > 0f) (errH * 1.2f * react).coerceIn(-0.8f, 0.8f) else vx
                val hautCible = reglages.get(Reglages.SUIVI_HAUTEUR)
                val alt = pont.lireEtat(pilote.enVol).altitudeAgl
                if (!alt.isNaN()) {
                    val errAlt = (hautCible - alt).toFloat()
                    vzMouv = (errAlt * 0.4f).coerceIn(-0.5f, 0.5f)
                }
                val cote = reglages.get(Reglages.SUIVI_COTE)   // angle en degres -45..+45
                vyMouv = ((cote / 45f) * 0.4f).coerceIn(-0.4f, 0.4f)  // angle -> decalage lateral doux (trois-quarts dos)
            }
            8 -> {
                // ORBITE MOBILE - EXPERIMENTAL : tourne autour d'un sujet QUI MARCHE.
                // = orbite (rotation + maintien du rayon + yaw recentre) combinee a
                //   l'avance qui rattrape le sujet s'il s'eloigne. Base CAMERA (calibK/h),
                //   donc APPROXIMATIF : valable a vitesse de marche + rotation lente seulement.
                // Garde-fous : rayon 8 m, vx borne +/-0.5, vy fixe 0.3, vz=0 (altitude libre),
                //   distance mini 4 m (garde-fou commun ci-dessous), perte YOLO -> hover.
                // A TESTER seulement APRES validation du suivi simple (mode 7).
                val ORBM_RAYON_M = 8.0f
                val ORBM_ROT_MPS = 0.3f
                val distEstimee = if (h > 0.01f) calibK / h else ORBM_RAYON_M
                val errRayon = distEstimee - ORBM_RAYON_M
                // avance/recul pour tenir le rayon (rattrape aussi le sujet qui s'eloigne)
                vxMouv = (errRayon * 0.5f).coerceIn(-0.5f, 0.5f)
                // rotation laterale lente (l'orbite proprement dite)
                vyMouv = ORBM_ROT_MPS
                // altitude LIBRE : on garde l'altitude actuelle, pas d'asservissement
                vzMouv = 0f
                // yawMouv deja calcule (errX) : garde le sujet centre dans le cadre
            }
        }
        // RALENTI cine : ralentit les vitesses cinematiques (<=1, jamais plus vite). Applique
        // AVANT le garde-fou et l'evitement, pour ne PAS attenuer un recul de securite.
        if (facteurVitesseCine < 0.999f) {
            vxMouv *= facteurVitesseCine
            vyMouv *= facteurVitesseCine
            vzMouv *= facteurVitesseCine
        }
        // GARDE-FOU SUIVI / ORBITE MOBILE (modes 7 et 8) : distance minimale de securite.
        // EXPERIMENTAL : repose sur calibK/h, a VALIDER en vol (calibK non confirme).
        // Le doigt sur STOP reste la vraie securite pendant les essais.
        // S'applique APRES le when (vxMouv calcule) et AVANT l'evitement (qui reste prioritaire).
        // Borne uniquement vx (avance/recul) : en mode 8 la rotation (vy) reste permise.
        if ((mouvementActuel == 7 || mouvementActuel == 8) && h > 0.01f) {
            val distEstimeeSuivi = calibK / h
            when {
                distEstimeeSuivi < DIST_MIN_SUIVI_M - 0.5f -> vxMouv = -0.25f          // vraiment trop pres : recul doux
                distEstimeeSuivi < DIST_MIN_SUIVI_M        -> vxMouv = minOf(vxMouv, 0f) // trop pres : interdit d'avancer
            }
        }
        // Capture Auto Intelligente : evalue si c'est un beau moment
        if (captureAutoActive) try { evaluerBeauMoment(errX, errY, h, vxMouv, vyMouv, vzMouv, yawMouv) } catch (_: Exception) {}
        val vitEvit = appliquerEvitement(vxMouv, vyMouv, vzMouv, yawMouv)
        return RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = vitEvit.vx, vy = vitEvit.vy, vz = vitEvit.vz, yawRate = vitEvit.yaw,
            mode = "actif",
            recuA = System.currentTimeMillis(),
            gimbalPitch = gimbalPitch,
            gimbalYaw = if (mouvementActuel == 5) (errX * 25f).coerceIn(-20f, 20f) else 0f
        )
    }


    /** Capture Auto Intelligente : declenche une photo si le sujet est bien compose.
     *  Beau moment = sujet centre (errX, errY petits) + taille de boite correcte + delai respecte. */
    private fun evaluerBeauMoment(errX: Float, errY: Float, h: Float, vx: Float, vy: Float, vz: Float, yaw: Float) {
        // pas pendant un enregistrement video
        if (pont.lireEtat(pilote.enVol).enregistre) return
        // delai minimum entre deux photos auto (3 s) pour ne pas mitrailler
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierePhotoAutoMs < 3000L) return
        // criteres de belle composition
        val centreOk = kotlin.math.abs(errX) < 0.12f && kotlin.math.abs(errY) < 0.15f
        // taille de sujet correcte : ni trop petit (loin) ni trop gros (trop pres)
        val tailleOk = h in 0.25f..0.75f
        // fin de mouvement : un mouvement cinematique (orbite/revelation/approche)
        // qui se stabilise (vitesses quasi nulles) = souvent le plus beau cadre
        val enMouvementCine = mouvementActuel == 1 || mouvementActuel == 3 || mouvementActuel == 4
        val vitesseFaible = kotlin.math.abs(vx) < 0.08f && kotlin.math.abs(vy) < 0.08f &&
                            kotlin.math.abs(vz) < 0.08f && kotlin.math.abs(yaw) < 4f
        val finMouvement = enMouvementCine && vitesseFaible && kotlin.math.abs(errX) < 0.20f && tailleOk
        if ((centreOk && tailleOk) || finMouvement) {
            pont.declencherPhoto()
            dernierePhotoAutoMs = maintenant
            compteurPhotosAuto++
            runOnUiThread { try { flashCaptureAuto() } catch (_: Exception) {} }
        }
    }
    private fun flashCaptureAuto() {
        // petit retour visuel : le bouton AUTO clignote + compteur
        val b = findViewById<Button>(R.id.btnPhoto)
        b?.text = "\uD83D\uDCF8 $compteurPhotosAuto"
    }
    // Execute une commande vocale reconnue (mappe vers SUIVRE / mouvements / plan)
    private fun executerCommandeVocale(action: String) {
        when (action) {
            "stop" -> { stopMacro(); deverrouillerCible(); pilote.arretUrgence(); modeAuto = false; majBoutonMode() }
            "suivi" -> if (!modeAuto) basculerMode(true)
            "pause" -> if (modeAuto) basculerMode(false)
            "orbite" -> findViewById<Button>(R.id.btnMouvOrbite).performClick()
            "travelling" -> findViewById<Button>(R.id.btnMouvTravel).performClick()
            "revelation" -> findViewById<Button>(R.id.btnMouvRevel).performClick()
            "approche" -> findViewById<Button>(R.id.btnMouvApproche).performClick()
            "recul" -> findViewById<Button>(R.id.btnMouvRecul).performClick()
            "poursuite" -> findViewById<Button>(R.id.btnMouvSuivi).performClick()
            "orbite_mobile" -> {
                // MODE 8 EXPERIMENTAL : pas de bouton visible. Active par voix uniquement.
                if (!modeAuto) basculerMode(true)
                mouvementActuel = 8
                majValeursMouvement()
                bandeauEphemere(getString(R.string.ma_ban_orbite_exp))
            }
            "statique" -> findViewById<Button>(R.id.btnMouvStatique).performClick()
            "plan_suivant" -> cyclerPlan()
            "plan_gros" -> { planIndex = 0; findViewById<Button>(R.id.btnPlanGros).performClick() }
            "plan_americain" -> { planIndex = 1; findViewById<Button>(R.id.btnPlanAmericain).performClick() }
            "plan_pied" -> { planIndex = 2; findViewById<Button>(R.id.btnPlanPied).performClick() }
            "plan_ensemble" -> { planIndex = 3; findViewById<Button>(R.id.btnPlanEnsemble).performClick() }
        }
    }

    private var planIndex = 1  // 0=GP 1=AM 2=PD 3=ENS (americain par defaut)
    private fun cyclerPlan() {
        planIndex = (planIndex + 1) % 4
        val ids = listOf(R.id.btnPlanGros, R.id.btnPlanAmericain, R.id.btnPlanPied, R.id.btnPlanEnsemble)
        findViewById<Button>(ids[planIndex]).performClick()
    }


    private val REQ_VOCAL = 4242
    private val REQ_DEF_SUJET = 4243
    private fun lancerPopupVocale() {
        try {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-CA")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.ma_voix_prompt))
            }
            startActivityForResult(intent, REQ_VOCAL)
        } catch (e: Exception) {
            txtCommandeVoc.text = getString(R.string.ma_pas_vocal)
            txtCommandeVoc.visibility = android.view.View.VISIBLE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEF_SUJET && resultCode == RESULT_OK) {
            val chemin = data?.getStringExtra("kmz_path")
            val recap = data?.getStringExtra("recap") ?: ""
            // ROUTAGE : si on vient de l'assistant Lumieres du jour -> PREPARER (sauvegarder),
            // pas voler. Le vol reel se fera plus tard via le menu Missions preparees.
            val ctxLum = prepLumiere
            if (ctxLum != null && chemin != null) {
                prepLumiere = null   // consomme
                sauvegarderMissionPreparee(chemin, recap, ctxLum)
                return
            }
            if (chemin != null) {
                val fichier = java.io.File(chemin)
                val depart = ca.cineflight.stage.control.LecteurMissionKmz.premierPoint(fichier)
                if (depart == null) {
                    android.widget.Toast.makeText(this, getString(R.string.ma_toast_kmz_reco_illisible), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val pts = ca.cineflight.stage.control.LecteurMissionKmz.pointsKmz(fichier)
                    confirmerReconnaissance(recap, pts.size) { tracerParcoursMission(pts); recoPosesChargees = pts; recoChargee = true; recoKmzPath = chemin }
                }
            }
        }
        if (requestCode == REQ_VOCAL && resultCode == RESULT_OK) {
            val res = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val brut = res?.joinToString(" ")?.lowercase() ?: ""
            android.util.Log.i("Vocal", "popup entendu: $brut")
            val action = interpreterVocal(brut)
            if (action != null) {
                txtCommandeVoc.text = "\u2713 " + action.uppercase()
                executerCommandeVocale(action)
            } else {
                txtCommandeVoc.text = getString(R.string.ma_non_reconnu)
            }
            txtCommandeVoc.visibility = android.view.View.VISIBLE
            txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
        }
    }

    private fun interpreterVocal(t: String): String? {
        return when {
            "poursuite" in t || "poursuis" in t || "derri\u00e8re" in t || "derriere" in t -> "poursuite"
            "recul" in t || "recule" in t -> "recul"
            "suivi" in t || "suis" in t || "suivre" in t -> "suivi"
            "pause" in t || "stop" in t || "arr\u00eate" in t || "arret" in t -> "pause"
            ("orbite" in t && "mobile" in t) || "orbite mobile" in t -> "orbite_mobile"
            "orbite" in t || "tourne" in t -> "orbite"
            "travel" in t -> "travelling"
            "r\u00e9v\u00e9l" in t || "revel" in t -> "revelation"
            "approche" in t || "rapproche" in t -> "approche"
            "gros" in t -> "plan_gros"
            ("am\u00e9ricain" in t || "americain" in t || "am\u00e9ricaine" in t) -> "plan_americain"
            ("d\u00e9taill" in t || "detaill" in t || "pied" in t) -> "plan_pied"
            "ensemble" in t || "large" in t -> "plan_ensemble"
            "suivant" in t -> "plan_suivant"
            "statique" in t || "fixe" in t -> "statique"
            else -> null
        }
    }


    // ====================================================================
    // === ArUco : sequence PLAN -> MOUVEMENT avec compatibilite + securite
    // ====================================================================
    //
    // SCHEMA DES TAGS :
    //   0       = PRESENTATION : verrouille la cible (a montrer en premier)
    //   31..34  = PLANS    : 31 GP, 32 Americain, 33 Pied, 34 Ensemble
    //   40..47  = MOUVEMENTS: 40 Statique,41 Orbite,42 Travel,43 Revelation,
    //                         44 Approche,45 Spotlight,46 Recul,47 Suivi
    //   9       = Pause   10 = STOP   11..30 = Macros (inchange)
    //
    // REGLES (demandees) :
    //   - Pour un plan precis : montrer d'abord le PLAN, puis le MOUVEMENT.
    //   - Plan seul puis rien pendant 15 s  -> annulation (plan en attente efface).
    //   - Mouvement seul (sans plan avant)  -> le systeme choisit un PLAN SUR.
    //   - Combinaison dangereuse demandee   -> REFUS (le mouvement n'est pas applique).
    //   - Toute demande de MOUVEMENT par ArUco -> enregistrement video assure (REC ON).

    // Indices internes : plan 0=GP 1=Americain 2=Pied 3=Ensemble ; mouvement 0..7.
    // Niveau de compatibilite : 0 = OK, 1 = avertir, 2 = refuser.
    private val COMPAT_PLAN_MOUV: Array<IntArray> = arrayOf(
        //               GP  AM  PD  ENS
        /*0 Statique */ intArrayOf(0, 0, 0, 0),
        /*1 Orbite   */ intArrayOf(1, 0, 0, 1),
        /*2 Travel   */ intArrayOf(2, 0, 0, 0),
        /*3 Revel    */ intArrayOf(2, 1, 0, 0),
        /*4 Approche */ intArrayOf(0, 0, 1, 2),
        /*5 Spotlight*/ intArrayOf(0, 0, 0, 1),
        /*6 Recul    */ intArrayOf(2, 1, 0, 0),
        /*7 Suivi    */ intArrayOf(1, 0, 0, 0)
    )
    // Plan choisi automatiquement quand seul le mouvement est montre (index plan 0..3).
    private val PLAN_AUTO_PAR_MOUV: IntArray = intArrayOf(
        1, // Statique -> Americain
        1, // Orbite   -> Americain
        2, // Travel   -> Pied
        3, // Revel    -> Ensemble
        0, // Approche -> GP
        1, // Spotlight-> Americain
        3, // Recul    -> Ensemble
        1  // Suivi    -> Americain
    )
    private val NOM_PLAN by lazy { resources.getStringArray(R.array.ma_noms_plan) }
    private val NOM_MOUV by lazy { resources.getStringArray(R.array.ma_noms_mouv) }

    // Plan en attente (montre par ArUco, pas encore confirme par un mouvement). -1 = aucun.
    private var planArucoEnAttente = -1
    private var planArucoExpireA = 0L          // horodatage d'expiration (ms)
    private val planArucoExpiration = Runnable { annulerPlanArucoEnAttente(getString(R.string.ma_aruco_delai)) }
    private val DELAI_PLAN_ARUCO_MS = 15_000L

    private fun annulerPlanArucoEnAttente(raison: String) {
        if (planArucoEnAttente < 0) return
        planArucoEnAttente = -1
        planArucoExpireA = 0L
        bandeauEphemere(getString(R.string.ma_ban_plan_annule, raison))
    }

    // Applique reellement le plan via le bouton existant (reutilise toute la logique UI).
    private fun appliquerPlanParIndex(planIdx: Int) {
        planIndex = planIdx
        val ids = listOf(R.id.btnPlanGros, R.id.btnPlanAmericain, R.id.btnPlanPied, R.id.btnPlanEnsemble)
        findViewById<Button>(ids[planIdx]).performClick()
    }

    // Applique reellement le mouvement via le bouton existant (reutilise transitions/securite).
    private fun appliquerMouvementParIndex(mouvIdx: Int) {
        val ids = listOf(
            R.id.btnMouvStatique, R.id.btnMouvOrbite, R.id.btnMouvTravel, R.id.btnMouvRevel,
            R.id.btnMouvApproche, R.id.btnMouvSpotlight, R.id.btnMouvRecul, R.id.btnMouvSuivi
        )
        findViewById<Button>(ids[mouvIdx]).performClick()
    }

    // Assure que la video enregistre (toute demande de MOUVEMENT ArUco est filmee).
    private fun assurerEnregistrement() {
        val etat = pont.lireEtat(pilote.enVol)
        if (pilote.enVol && !etat.enregistre) {
            if (!etat.carteSdPresente) {
                bandeauEphemere(getString(R.string.ma_ban_pas_sd))
            }
            pont.demarrerEnregistrement()
            bandeauEphemere(getString(R.string.ma_ban_rec_aruco))
        }
    }

    // Traite une demande de MOUVEMENT venant d'ArUco, en tenant compte du plan en attente.
    private fun traiterMouvementAruco(mouvIdx: Int) {
        if (!modeAuto) basculerMode(true)
        val planIdx = if (planArucoEnAttente in 0..3) planArucoEnAttente
                      else PLAN_AUTO_PAR_MOUV[mouvIdx]      // mouvement seul -> plan sur auto
        val planExplicite = planArucoEnAttente in 0..3
        // consomme le plan en attente (et coupe le timer d'expiration)
        txtCommandeVoc.removeCallbacks(planArucoExpiration)
        planArucoEnAttente = -1
        planArucoExpireA = 0L

        val niveau = COMPAT_PLAN_MOUV[mouvIdx][planIdx]
        when (niveau) {
            2 -> {
                // REFUS : combinaison dangereuse. Rien n'est applique, le drone
                // garde son mouvement precedent. On indique un plan sur a la place.
                val planSur = NOM_PLAN[PLAN_AUTO_PAR_MOUV[mouvIdx]]
                bandeauEphemere(getString(R.string.ma_ban_refus, NOM_MOUV[mouvIdx], NOM_PLAN[planIdx], planSur))
                return
            }
            1 -> {
                // AVERTISSEMENT : applique mais previent.
                appliquerPlanParIndex(planIdx)
                appliquerMouvementParIndex(mouvIdx)
                assurerEnregistrement()
                bandeauEphemere(getString(R.string.ma_ban_limite, NOM_MOUV[mouvIdx], NOM_PLAN[planIdx]))
            }
            else -> {
                // OK : applique plan puis mouvement.
                appliquerPlanParIndex(planIdx)
                appliquerMouvementParIndex(mouvIdx)
                assurerEnregistrement()
                val tag = if (planExplicite) "" else getString(R.string.ma_tag_auto)
                bandeauEphemere(getString(R.string.ma_ban_ok, NOM_MOUV[mouvIdx], NOM_PLAN[planIdx], tag))
            }
        }
    }

    // Memorise un PLAN montre par ArUco et arme le timeout 15 s.
    private fun traiterPlanAruco(planIdx: Int) {
        planArucoEnAttente = planIdx
        planArucoExpireA = System.currentTimeMillis() + DELAI_PLAN_ARUCO_MS
        txtCommandeVoc.removeCallbacks(planArucoExpiration)
        txtCommandeVoc.postDelayed(planArucoExpiration, DELAI_PLAN_ARUCO_MS)
        bandeauEphemere(getString(R.string.ma_ban_plan_arme, NOM_PLAN[planIdx]))
    }

    // Mapping des tags ArUco vers les actions (sequence plan -> mouvement)
    private fun executerTag(id: Int) {
        when (id) {
            0 -> { verrouillerCible(); bandeauEphemere(getString(R.string.ma_ban_cible_verrou)); return }
            in 31..34 -> { traiterPlanAruco(id - 31); return }
            in 40..47 -> { traiterMouvementAruco(id - 40); return }
            9 -> { if (modeAuto) basculerMode(false); bandeauEphemere(getString(R.string.ma_ban_pause)); return }
            10 -> { pilote.arretUrgence(); modeAuto = false; majBoutonMode(); bandeauEphemere(getString(R.string.ma_ban_stop)); return }
            in 11..30 -> { lancerMacro(id); bandeauEphemere(getString(R.string.ma_ban_macro, id)); return }
            else -> bandeauEphemere(getString(R.string.ma_ban_tag_libre, id))
        }
    }


    // Lance la macro associee a un tag (11..30). Interruptible par STOP.
    private fun majValeursMouvement() {
        val t = findViewById<android.widget.TextView>(R.id.txtValeursMouv) ?: return
        val r = reglages
        fun f(p: Reglages.Param) = "%.1f".format(r.get(p))
        t.text = when (mouvementActuel) {
            1 -> getString(R.string.ma_mouv_orbite, f(Reglages.ORBITE_HAUTEUR), f(Reglages.ORBITE_RAYON), f(Reglages.ORBITE_VITESSE))
            2 -> getString(R.string.ma_mouv_travel, f(Reglages.TRAVEL_DISTANCE), f(Reglages.TRAVEL_HAUTEUR), f(Reglages.TRAVEL_REACTIV))
            3 -> getString(R.string.ma_mouv_revel, f(Reglages.REVEL_RECUL), f(Reglages.REVEL_MONTEE))
            4 -> getString(R.string.ma_mouv_approche, f(Reglages.APP_VITESSE), f(Reglages.APP_DISTANCE))
            5 -> getString(R.string.ma_mouv_spotlight)
            6 -> getString(R.string.ma_mouv_recul, f(Reglages.RECUL_HAUTEUR), f(Reglages.RECUL_COTE))
            7 -> getString(R.string.ma_mouv_suivi, f(Reglages.SUIVI_HAUTEUR), f(Reglages.SUIVI_COTE), f(Reglages.SUIVI_REACTIV))
            else -> getString(R.string.ma_mouv_statique)
        }
    }

    /** Menu du workflow de reconnaissance d'un sujet (regroupe SUJET / VERIF / RECO). */
    private fun ouvrirMenuReconnaissance() {
        val options = arrayOf(
            getString(R.string.ma_reco_1),
            getString(R.string.ma_reco_2),
            getString(R.string.ma_reco_3),
            getString(R.string.ma_reco_4),
            getString(R.string.ma_reco_5)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_reco_titre))
            .setItems(options) { _, i ->
                when (i) {
                    0 -> startActivityForResult(
                        android.content.Intent(this, DefinitionSujetActivity::class.java), REQ_DEF_SUJET)
                    1 -> verifierMissionComplete()
                    2 -> startActivity(
                        android.content.Intent(this, ReconnaissanceActivity::class.java))
                    3 -> ouvrirMissionsPreparees()
                    4 -> startActivity(
                        android.content.Intent(this, MesMissionsActivity::class.java))
                }
            }
            .setNegativeButton(R.string.ma_fermer, null)
            .show()
    }

    private fun ouvrirMenuEv() {
        val crans = floatArrayOf(2.0f, 1.0f, 0.3f, 0f, -0.3f, -1.0f, -2.0f)
        val labels = crans.map { evLabel(it) }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_expo_titre))
            .setItems(labels) { _, i ->
                evCourant = crans[i]
                try { pont.reglerEv(evCourant) } catch (_: Exception) {}
                // EV affiche une icone fixe ; la valeur reste visible dans le menu EV
            }
            .setNegativeButton(R.string.ma_fermer, null)
            .show()
    }

    private fun evLabel(v: Float): String = when {
        kotlin.math.abs(v) < 0.05f -> "\u2600 0"
        v > 0 -> "\u2600+%.1f".format(v)
        else -> "\u2600%.1f".format(v)
    }

    /** Dessine la trajectoire de la mission (waypoints relies) sur la mini-carte,
     *  l'ouvre et cadre la vue dessus. Le marqueur du drone avancera dessus. */
    /** Dialogue de confirmation pilote avant d'accepter une reconnaissance. */
    /** V?rifie la mission compl?te (Home/approche/retour/batterie) au d?collage. */
    /** Lance le vol R?EL : v?rifications finales + ex?cution du KMZ par le drone. */
    private fun lancerVolReel() {
        val path = recoKmzPath
        if (path == null) {
            android.widget.Toast.makeText(this, getString(R.string.ma_toast_aucun_kmz_reco), android.widget.Toast.LENGTH_LONG).show(); return
        }
        val e = pont.lireEtat(pilote.enVol)
        if (!e.enVol || !e.gpsValide) {
            android.widget.Toast.makeText(this, getString(R.string.ma_toast_vol_annule), android.widget.Toast.LENGTH_LONG).show(); return
        }
        // Decollage AUTONOME : seuil GPS strict (>= SAT_MIN_AUTO), plus exigeant que gpsValide (8 sats).
        if (e.satellites < SAT_MIN_AUTO) {
            android.widget.Toast.makeText(this, getString(R.string.ma_toast_vol_sat_bloque, e.satellites, SAT_MIN_AUTO), android.widget.Toast.LENGTH_LONG).show(); return
        }
        // Derni?re confirmation explicite avant que le drone s engage.
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_decollage_titre))
            .setMessage(getString(R.string.ma_dlg_decollage_msg))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ma_dlg_decoller) { _, _ -> executerVol(java.io.File(path)) }
            .setCancelable(false)
            .show()
    }

    private fun executerVol(kmz: java.io.File) {
        // PRE-VOL (recommande par l'entete d'ExecuteurMissionWpml) : on ne lance pas
        // une mission autonome si le drone n'est pas pret. DJI refuserait de son cote,
        // mais on bloque proprement AVANT l'upload, avec un message clair.
        val ev = pont.lireEtat(pilote.enVol)
        val blocage = when {
            !ev.connecte                               -> getString(R.string.ma_bloc_deco)
            !ev.gpsValide                              -> getString(R.string.ma_bloc_gps)
            ev.batteriePct in 0 until BATT_FAIBLE      -> getString(R.string.ma_bloc_batt, ev.batteriePct, BATT_FAIBLE)
            else                                       -> null
        }
        if (blocage != null) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
                .setTitle(getString(R.string.ma_bloc_titre))
                .setMessage(blocage + getString(R.string.ma_bloc_corrige))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_mission_titre))
            .setMessage(getString(R.string.ma_dlg_mission_upload))
            .setCancelable(false)
            .setNegativeButton(R.string.ma_dlg_arret_rth) { _, _ ->
                ca.cineflight.stage.control.ExecuteurMissionWpml.interrompre { _, _ -> }
                pont.lancerRth { }
            }
            .show()
        modeVol.lancerMission(kmz,
            onProgres = { wp -> runOnUiThread { dlg.setMessage(getString(R.string.ma_dlg_waypoint, wp)) } },
            onTermine = { ok, msg -> runOnUiThread {
                dlg.dismiss()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight).setTitle(if (ok) getString(R.string.ma_dlg_mission_termine) else getString(R.string.ma_dlg_mission_arrete))
                    .setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
            } })
    }

    private fun verifierMissionComplete() {
        if (!recoChargee || recoPosesChargees.size < 2) {
            android.widget.Toast.makeText(this, getString(R.string.ma_toast_charger_reco), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val e = pont.lireEtat(pilote.enVol)
        if (!e.enVol || !e.gpsValide || e.latitude.isNaN() || e.longitude.isNaN()) {
            android.widget.Toast.makeText(this,
                getString(R.string.ma_toast_decollez_stabilisez),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Home = position actuelle du drone (point de d?collage, drone stabilis? au-dessus).
        val poses = recoPosesChargees.map {
            ca.cineflight.stage.control.MissionCompleteBuilder.WaypointMission(
                it.first, it.second, 60.0, "reconnaissance")
        }
        val m = ca.cineflight.stage.control.MissionCompleteBuilder.construire(
            this, e.latitude, e.longitude, poses)
        val resume = ca.cineflight.stage.control.MissionCompleteBuilder.resume(this, m)
        val titre = if (m.realisable) getString(R.string.ma_miss_ok) else getString(R.string.ma_miss_refus)
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight).setTitle(titre).setMessage(resume).setCancelable(false)
        if (m.realisable) {
            dlg.setPositiveButton(R.string.ma_dlg_lancer_vol) { _, _ -> lancerVolReel() }
            dlg.setNegativeButton(android.R.string.cancel, null)
        } else {
            dlg.setNegativeButton(R.string.ma_fermer, null)
        }
        dlg.show()
    }

    private fun confirmerReconnaissance(recap: String, nbPts: Int, onConfirme: () -> Unit) {
        val sb = StringBuilder()
        try {
            val o = org.json.JSONObject(recap)
            sb.append(getString(R.string.ma_rep_type)).append(o.optString("type", "?")).append("\n")
            val nbP = o.optInt("nb_poses", nbPts)
            sb.append(getString(R.string.ma_rep_prises)).append(nbP).append("\n")
            if (o.has("hauteur_mesuree_m") && !o.isNull("hauteur_mesuree_m"))
                sb.append(getString(R.string.ma_rep_h_mesuree)).append(o.optDouble("hauteur_mesuree_m")).append(" m\n")
            if (o.has("hauteur_sure_m") && !o.isNull("hauteur_sure_m"))
                sb.append(getString(R.string.ma_rep_h_securite)).append(o.optDouble("hauteur_sure_m")).append(" m\n")
            if (o.has("hauteur_tablier_m") && !o.isNull("hauteur_tablier_m"))
                sb.append(getString(R.string.ma_rep_h_tablier)).append(o.optDouble("hauteur_tablier_m")).append(" m\n")
            if (o.optBoolean("mode_securite_conservateur", false))
                sb.append(getString(R.string.ma_rep_mode_conservateur))
            val av = o.optJSONArray("avertissements")
            if (av != null) for (i in 0 until av.length())
                sb.append("\u26A0 ").append(av.getString(i)).append("\n")
        } catch (e: Exception) {
            sb.append(getString(R.string.ma_rep_prises)).append(nbPts)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_reco_confirm_titre))
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ma_dlg_confirmer_vol) { _, _ -> onConfirme() }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    private fun tracerParcoursMission(points: List<Pair<Double, Double>>) {
        val carte = miniCarteVue ?: return
        MainActivity.parcoursReco = points
        if (points.size < 2) return
        // retire l'ancien trace si present
        traceParcours?.let { try { carte.overlays.remove(it) } catch (_: Exception) {} }
        val ligne = org.osmdroid.views.overlay.Polyline().apply {
            setPoints(points.map { org.osmdroid.util.GeoPoint(it.first, it.second) })
            outlinePaint.color = 0xFF00C853.toInt()
            outlinePaint.strokeWidth = 6f
        }
        // on insere la ligne SOUS le marqueur (le drone reste visible au-dessus)
        carte.overlays.add(0, ligne)
        traceParcours = ligne
        // ouvre la mini-carte si fermee + cadre sur le depart
        if (!miniVisible) basculerMiniCarte()
        val p0 = org.osmdroid.util.GeoPoint(points.first().first, points.first().second)
        carte.controller?.setZoom(17.0)
        carte.controller?.animateTo(p0)
        carte.invalidate()
    }

    private fun basculerMiniCarte() {
        // cycle : 0 rien -> 1 carte -> 2 radar -> 0 rien
        modeAffichage = (modeAffichage + 1) % 3
        appliquerModeAffichage()
    }

    private fun appliquerModeAffichage() {
        val boite = findViewById<android.view.View>(R.id.boiteMiniCarte)
        val carteOn = (modeAffichage == 1)
        val radarOn = (modeAffichage == 2)
        miniVisible = carteOn   // garde la compat avec le reste du code (mission, onResume)
        boite.visibility = if (carteOn || radarOn) android.view.View.VISIBLE else android.view.View.GONE
        miniCarteVue?.visibility = if (carteOn) android.view.View.VISIBLE else android.view.View.GONE
        radarVue?.visibility = if (radarOn) android.view.View.VISIBLE else android.view.View.GONE
        if (carteOn) { miniCarteVue?.onResume(); majMiniCarte() }
        else { miniCarteVue?.onPause() }
        if (radarOn) majRadar()
    }

    private fun majMiniCarte() {
        if (!miniVisible) return
        // Pendant une mission simulee : suivre la position du drone SIMULE (pas le reel).
        val ms = lecteurMission
        val ps = pontSim
        var lat: Double; var lon: Double; var cap = dernierCapCarte
        if (ms != null && ms.actif && ps != null) {
            lat = ps.latitudeDrone(); lon = ps.longitudeDrone(); cap = ps.capDroneDeg()
        } else {
            lat = derniereLatCarte; lon = derniereLonCarte
        }
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return
        val p = org.osmdroid.util.GeoPoint(lat, lon)
        marqueurMini?.position = p
        if (!cap.isNaN()) marqueurMini?.rotation = -cap
        miniCarteVue?.controller?.animateTo(p)
        miniCarteVue?.invalidate()
    }

    /** Met a jour le radar : position drone (reel ou simule) + position pilote + cap. */
    private fun majRadar() {
        val r = radarVue ?: return
        if (modeAffichage != 2) return
        val ms = lecteurMission
        val ps = pontSim
        var lat: Double; var lon: Double; var cap = dernierCapCarte
        if (ms != null && ms.actif && ps != null) {
            lat = ps.latitudeDrone(); lon = ps.longitudeDrone(); cap = ps.capDroneDeg()
        } else {
            lat = derniereLatCarte; lon = derniereLonCarte
        }
        // position pilote : GPS telephone (la ou est l'operateur)
        val pilotePos = positionTelephone()
        val pLat = pilotePos?.first ?: Double.NaN
        val pLon = pilotePos?.second ?: Double.NaN
        r.maj(lat, lon, pLat, pLon, cap)
    }

    /** Radar agrandi : ouvre une grande vue plein ecran avec distance + cap. */
    private fun agrandirRadar() {
        val grand = ca.cineflight.stage.control.RadarView(this)
        val ms = lecteurMission; val ps = pontSim
        var lat: Double; var lon: Double; var cap = dernierCapCarte
        if (ms != null && ms.actif && ps != null) {
            lat = ps.latitudeDrone(); lon = ps.longitudeDrone(); cap = ps.capDroneDeg()
        } else { lat = derniereLatCarte; lon = derniereLonCarte }
        val pilotePos = positionTelephone()
        grand.maj(lat, lon, pilotePos?.first ?: Double.NaN, pilotePos?.second ?: Double.NaN, cap)
        val taille = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(grand)
            .setPositiveButton("Fermer", null)
            .create()
        grand.layoutParams = android.view.ViewGroup.LayoutParams(taille, taille)
        grand.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun rafraichirBoutonsMacros() {
        val rangee = findViewById<LinearLayout>(R.id.rangeeMacros) ?: return
        rangee.removeAllViews()
        val d = resources.displayMetrics.density
        fun dpx(v: Int) = (v * d).toInt()
        for (tagId in macros.tagsAvecMacro()) {
            val m = macros.charger(tagId) ?: continue
            val label = if (m.nom.isNotEmpty()) m.nom else "T$tagId"
            val b = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpx(6), 0, dpx(6), 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF6A1B9A.toInt())
                val lp = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dpx(36))
                lp.marginEnd = dpx(4)
                layoutParams = lp
                setOnClickListener { lancerMacro(tagId) }
            }
            rangee.addView(b)
        }
    }

    override fun onResume() {
        super.onResume()
        try { rafraichirBoutonsMacros() } catch (_: Exception) {}
        try { yoloSuivi?.setClassesSuivies(reglages.classesPourSujet()) } catch (_: Exception) {}
        try { pont.reglerResolutionFps(reglages.resolutionNom(), reglages.getFps()) } catch (_: Exception) {}
        try { majValeursMouvement() } catch (_: Exception) {}
        try { if (miniVisible) miniCarteVue?.onResume() } catch (_: Exception) {}
        try { chargerRailDepuisCarte() } catch (_: Exception) {}
        try { ouvrirSujetSiLumiereEnAttente() } catch (_: Exception) {}
    }

    /** Au retour de la carte : si un rail A/B a ete defini la-bas, le charger dans le Cable-Cam. */
    private fun chargerRailDepuisCarte() {
        if (!MainActivity.railCarteDefini) return
        MainActivity.railCarteDefini = false
        val e = pont.lireEtat(pilote.enVol)
        // altitude/cap : on prend l'etat actuel du drone (rail horizontal a l'altitude courante)
        val alt = if (e.gpsValide) e.altitudeAgl else 10.0
        val cap = e.capDeg
        cableCam.memoriserA(MainActivity.railACarteLat, MainActivity.railACarteLon, alt, cap)
        cableCam.memoriserB(MainActivity.railBCarteLat, MainActivity.railBCarteLon, alt, cap)
        findViewById<Button>(R.id.btnRailA)?.let {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt()); it.setTextColor(0xFFFFFFFF.toInt())
        }
        findViewById<Button>(R.id.btnRailB)?.let {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt()); it.setTextColor(0xFFFFFFFF.toInt())
        }
        android.widget.Toast.makeText(this, getString(R.string.ma_toast_rail_charge), android.widget.Toast.LENGTH_LONG).show()
    }

    /** Au retour de l'assistant Lumieres du jour : ouvre la definition de sujet
     *  en transmettant le contexte de lumiere choisi. Le KMZ reviendra par le
     *  onActivityResult existant (REQ_DEF_SUJET), inchange. */
    private fun ouvrirSujetSiLumiereEnAttente() {
        val extras = MainActivity.lumiereEnAttente ?: return
        MainActivity.lumiereEnAttente = null   // consomme une seule fois
        prepLumiere = extras   // retient le contexte lumiere jusqu'au retour du KMZ
        val it = android.content.Intent(this, DefinitionSujetActivity::class.java)
        it.putExtras(extras)
        startActivityForResult(it, REQ_DEF_SUJET)
    }

    /** PREPARER (drone eteint OK) : sauvegarde la mission + son contexte lumiere
     *  dans la bibliotheque locale. Le vol reel se fera plus tard, sur place. */
    /** PREPARER : demande un nom de mission, puis sauvegarde (drone eteint OK). */
    /** PREPARER : demande un nom OBLIGATOIRE, puis sauvegarde (drone eteint OK). */
    private fun sauvegarderMissionPreparee(chemin: String, recap: String, ctx: android.os.Bundle) {
        val momentNom = ctx.getString("momentNom") ?: "Lumiere"
        val momentCle = ctx.getString("momentCle") ?: ""
        val momentDebut = ctx.getString("momentDebut") ?: ""
        val momentFin = ctx.getString("momentFin") ?: ""
        val momentPic = ctx.getString("momentPic") ?: ""
        val nomLieu = ctx.getString("nomLieu") ?: "Lieu"
        val plage = if (momentDebut.isNotEmpty()) "$momentDebut \u2192 $momentFin" else ""

        val champ = android.widget.EditText(this).apply {
            hint = getString(R.string.ma_nom_hint)
            setSingleLine(true)
        }
        val cont = android.widget.FrameLayout(this).apply {
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(champ)
        }
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_nommer_titre))
            .setMessage(getString(R.string.ma_dlg_nommer_msg, momentNom, plage))
            .setView(cont)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ma_dlg_enregistrer, null)   // intercepte plus bas
            .setCancelable(false)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nom = champ.text.toString().trim()
                if (nom.isEmpty()) {
                    champ.error = getString(R.string.ma_nom_erreur)
                    champ.requestFocus()
                    return@setOnClickListener   // ne ferme PAS le dialogue
                }
                val store = ca.cineflight.stage.cine.MissionsPrepareesStore(this)
                val m = ca.cineflight.stage.cine.MissionsPrepareesStore.MissionPreparee(
                    id = ca.cineflight.stage.cine.MissionsPrepareesStore.nouvelId(),
                    nomMission = nom,
                    kmzPath = chemin,
                    nomLieu = nomLieu,
                    momentCle = momentCle,
                    momentNom = momentNom,
                    momentDebut = momentDebut,
                    momentFin = momentFin,
                    momentPic = momentPic,
                    recap = recap,
                    preparesLe = System.currentTimeMillis()
                )
                val ok = store.sauvegarder(m)
                dlg.dismiss()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
                    .setTitle(if (ok) getString(R.string.ma_save_ok_titre) else getString(R.string.ma_save_err_titre))
                    .setMessage(if (ok) getString(R.string.ma_save_ok_msg, nom, momentNom, plage) else getString(R.string.ma_save_err_msg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        dlg.show()
    }

    /** VOLER : liste les missions preparees. Au choix -> confirmation -> verification drone. */
    private fun ouvrirMissionsPreparees() {
        // Unifie : ouvre TOUJOURS la page stylee "Mes missions preparees" du panneau
        // (meme UI que le hub Preparer un tournage), au lieu d'un dialogue separe.
        panneauRecettes.ouvrirSurMissionsPreparees()
    }

    /** Confirmation courte avant de passer a la verification drone. */
    /** Distance au sol approximative entre deux points GPS, en metres (haversine). */
    private fun distanceGpsM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    private fun confirmerMissionPreparee(m: ca.cineflight.stage.cine.MissionsPrepareesStore.MissionPreparee) {
        // GARDE-FOU LIEU : une mission preparee est FIGEE sur ses waypoints GPS. La lancer
        // loin d'ici (ex. mission de Granby alors qu'on est a Montreal) enverrait le drone
        // voler la-bas. On BLOQUE si le depart de la mission est a plus de 2 km d'ici.
        val cibleMission = try { LecteurMissionKmz.premierPoint(java.io.File(m.kmzPath)) } catch (_: Exception) { null }
        val iciMaintenant = positionPourMeteo()
        if (cibleMission != null && iciMaintenant != null) {
            val dM = distanceGpsM(iciMaintenant.first, iciMaintenant.second, cibleMission.first, cibleMission.second)
            if (dM > 2000.0) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
                    .setTitle(getString(R.string.ma_loin_titre))
                    .setMessage(getString(R.string.ma_loin_msg, m.nomLieu, (dM / 1000).toInt()))
                    .setPositiveButton(getString(R.string.ma_compris), null)
                    .show()
                return
            }
        }
        val plage = if (m.momentDebut.isNotEmpty()) "${m.momentDebut} \u2192 ${m.momentFin}" else ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setTitle(getString(R.string.ma_dlg_moment_titre, m.momentNom))
            .setMessage(getString(R.string.ma_dlg_moment_msg, m.nomLieu, plage))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ma_dlg_continuer) { _, _ -> chargerEtVerifier(m) }
            .show()
    }

    /** Charge le KMZ de la mission preparee puis lance la verification (exige drone en vol). */
    private fun chargerEtVerifier(m: ca.cineflight.stage.cine.MissionsPrepareesStore.MissionPreparee) {
        val fichier = java.io.File(m.kmzPath)
        if (!fichier.exists()) {
            android.widget.Toast.makeText(this, getString(R.string.ma_toast_kmz_introuvable), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val pts = ca.cineflight.stage.control.LecteurMissionKmz.pointsKmz(fichier)
        if (pts.size < 2) {
            android.widget.Toast.makeText(this, getString(R.string.ma_toast_kmz_illisible), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        recoPosesChargees = pts
        recoChargee = true
        recoKmzPath = m.kmzPath
        tracerParcoursMission(pts)
        verifierMissionComplete()   // ici la securite drone+GPS+RTH s'applique
    }

    // Bouton RETOUR (systeme) : si le panneau de recettes est ouvert, on le FERME
    // au lieu de quitter l'application.
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (panneauRecettes.estOuvert) panneauRecettes.fermer()
        else super.onBackPressed()
    }

    private fun lancerMacro(tagId: Int) {
        val macro = macros.charger(tagId) ?: return
        // une seule macro a la fois : annule la precedente
        jobMacro?.cancel()
        jobMacro = lifecycleScope.launch {
            txtCommandeVoc.text = "\u25B6 MACRO: " + macro.nom
            txtCommandeVoc.visibility = android.view.View.VISIBLE
            for (etape in macro.etapes) {
                if (!isActive) break          // STOP a annule la macro
                // executer l'action de l'etape (reutilise le mapping existant)
                executerCommandeVocale(etape.action)
                // attendre la duree demandee (en verifiant l'annulation)
                val ms = (etape.attenteS * 1000).toLong()
                var reste = ms
                while (reste > 0 && isActive) {
                    val pas = minOf(100L, reste)
                    kotlinx.coroutines.delay(pas)
                    reste -= pas
                }
            }
            txtCommandeVoc.text = getString(R.string.ma_macro_terminee)
            txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2000)
        }
    }

    // Interrompt toute macro en cours (appele par STOP)
    private fun stopMacro() {
        jobMacro?.cancel()
        jobMacro = null
    }


    // Verrouille la personne actuellement detectee comme cible a suivre.
    private fun verrouillerCible() {
        cibleVerrouillee = true
        if (!modeAuto) basculerMode(true)
        // Avertissement unique : drone sans evitement -> il recule a l'aveugle en suivi.
        if (!profilDrone.reculAutonomeSur && !avertiReculAveugle) {
            avertiReculAveugle = true
            android.widget.Toast.makeText(
                this,
                getString(R.string.ma_toast_pas_evitement, profilDrone.nomLisible),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        // Enregistrement auto au suivi : on demarre la video si l'option est ON, qu'on est
        // en vol et que ca n'enregistre pas deja. Evite d'oublier de filmer un beau plan.
        if (reglages.getEnregAuto()) {
            val etat = pont.lireEtat(pilote.enVol)
            if (pilote.enVol && !etat.enregistre) {
                pont.demarrerEnregistrement()
                bandeauEphemere(getString(R.string.ma_ban_rec_simple))
            }
        }
        val ancreTxt = if (ancreValide) getString(R.string.ma_ancre_val, ancreLat, ancreLon) else getString(R.string.ma_ancre_non)
        txtCommandeVoc.text = getString(R.string.ma_ban_cible_verrou) + ancreTxt
        txtCommandeVoc.visibility = android.view.View.VISIBLE
        txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
    }

    private fun deverrouillerCible() {
        cibleVerrouillee = false
        // Arret intelligent NON destructif : si ca enregistre encore, on SUGGERE d'arreter
        // (sans couper) -> l'utilisateur garde la main sur le bouton REC.
        if (pont.lireEtat(pilote.enVol).enregistre) {
            bandeauEphemere(getString(R.string.ma_ban_plan_fini))
        }
    }

    // Affiche un court message dans le bandeau, efface apres 3 s. Reutilise txtCommandeVoc.
    private fun bandeauEphemere(msg: String) {
        txtCommandeVoc.text = msg
        txtCommandeVoc.visibility = android.view.View.VISIBLE
        txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 3000)
    }


    // Retour "chien fidele" : revient vers le point d'ancrage GPS, en montant un peu,
    // a vitesse douce. La vision reprend la main des qu'elle retrouve la cible.
    private fun retourAncre(): RecepteurBridge.CommandeBridge {
        val e = pont.lireEtat(pilote.enVol)
        // si GPS plus fiable : securite -> hover
        if (!e.gpsValide || ancreLat.isNaN()) {
            return RecepteurBridge.CommandeBridge(
                System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis())
        }
        // cap vers l'ancre (bearing) a partir des coordonnees GPS
        val lat1 = Math.toRadians(e.latitude)
        val lon1 = Math.toRadians(e.longitude)
        val lat2 = Math.toRadians(ancreLat)
        val lon2 = Math.toRadians(ancreLon)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        // distance approx (m) via equirectangulaire
        val R = 6371000.0
        val dx = dLon * Math.cos((lat1 + lat2) / 2)
        val dy = lat2 - lat1
        val dist = Math.sqrt(dx*dx + dy*dy) * R
        // ecart de cap entre le drone et la direction de l'ancre
        val capDrone = if (e.capDeg.isNaN()) 0.0 else e.capDeg.toDouble()
        var ecartCap = bearing - capDrone
        while (ecartCap > 180) ecartCap -= 360
        while (ecartCap < -180) ecartCap += 360
        // yaw doux pour s'orienter vers l'ancre
        val yawRate = (ecartCap * 0.6).coerceIn(-20.0, 20.0).toFloat()
        // avance douce seulement si on est globalement oriente vers l'ancre et pas arrive
        val vx = if (dist > 2.0 && Math.abs(ecartCap) < 45) 0.6f else 0f
        // monte un peu pour elargir la vue (plafonne via vz doux), arret de montee si proche
        val vz = if (dist > 3.0) 0.3f else 0f
        return RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = vx, vy = 0f, vz = vz, yawRate = yawRate,
            mode = "actif", recuA = System.currentTimeMillis())
    }

    /**
     * Effet visuel de capture facon DJI Fly / appareil photo.
     * Masque le gel du flux video pendant la bascule de mode camera (VIDEO<->PHOTO)
     * en affichant un flash blanc bref + une confirmation "Photo capturee".
     * Entierement en code : superpose un overlay sur le FrameLayout racine.
     */
    private fun effetCapture() {
        val racine = findViewById<android.view.ViewGroup>(android.R.id.content)

        // 1) FLASH blanc bref (obturateur)
        val flash = android.view.View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            alpha = 0f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        racine.addView(flash)
        flash.animate().alpha(0.85f).setDuration(60).withEndAction {
            flash.animate().alpha(0f).setDuration(220).withEndAction {
                racine.removeView(flash)
            }.start()
        }.start()

        // 2) Confirmation "Photo capturee" (apparait puis disparait)
        val confirme = android.widget.TextView(this).apply {
            text = getString(R.string.ma_photo_capturee)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(40, 24, 40, 24)
            // fond arrondi semi-transparent
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0xCC000000.toInt())
            }
            alpha = 0f
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
        }
        racine.addView(confirme)
        confirme.animate().alpha(1f).scaleX(1.05f).scaleY(1.05f).setDuration(180).withEndAction {
            confirme.animate().setStartDelay(900).alpha(0f).setDuration(400).withEndAction {
                racine.removeView(confirme)
            }.start()
        }.start()
    }

    // ------------------------------------------------------------------
    //  MODE SUJET MOBILE RTK — WSS 10 Hz + securite (option A)
    //  + BRIQUE 4 : moniteur de corridor (position réelle vs trajectoire prévue)
    // ------------------------------------------------------------------
    /** Flux WSS (~10 Hz) : lit la position sujet, la combine avec la
     *  position drone (EtatCockpit) -> dernierEtatSujet (distance/sécurité RTK), ET
     *  évalue le CORRIDOR (position réelle vs trajectoire prévue du serveur) ->
     *  dernierStatutCorridor. Les deux alimentent rtkSujetOkFn (blocage noyau sur
     *  distance < 3 m, RTK perdu, OU sujet HORS_CORRIDOR). TOLERANCE/PRUDENCE =
     *  avertissements (pas de blocage). */
    private fun demarrerPollingSujet() {
        if (pollingSujetActif) return
        pollingSujetActif = true

        val profilDejaConfigure = ca.cineflight.stage.control.ProfilSujetMobile.estConfigure(this)
        profilSujetV4 = ca.cineflight.stage.control.ProfilSujetMobile.charger(this)
        appliquerProfilSujetV4(profilSujetV4, sauvegarder = false)
        creerBandeauRtkV4SiNecessaire()
        journalSessionRtkV41 = ca.cineflight.stage.control.JournalSessionRtkV41(this).also { journal ->
            journal.demarrer(profilSujetV4)
            journal.enregistrerSysteme("ANDROID_V4_2_STARTED", "control_policy=FIX_FULL_FLOAT_VISION_ONLY")
        }
        android.util.Log.i(
            "CF_RtkV4Diag",
            "android_v4_started version=${ca.cineflight.stage.control.MoteurFusionRtkV4.VERSION} " +
                "profile=${profilSujetV4.code} transport=WSS target_hz=10 fusion_hz=50 control_policy=FIX_FULL_FLOAT_VISION_ONLY"
        )

        if (!profilDejaConfigure) {
            demanderProfilSujetV4(force = true)
        }

        // Charge la trajectoire et la geo-barriere sans ralentir le flux RTK.
        jobContexteRtkV4 = lifecycleScope.launch(Dispatchers.Main) {
            while (pollingSujetActif && isActive) {
                try {
                    val nt = ca.cineflight.stage.cine.ClientTrajectoireSuivi.lireTrajectoire()
                    if (nt.present) {
                        trajectoireSuivi = nt
                        moniteurCorridor.appliquerSeuils(
                            nt.seuilNormalM,
                            nt.seuilToleranceM,
                            nt.seuilPrudenceM
                        )
                    }
                    zoneVol = ca.cineflight.stage.cine.ClientGeoBarriere.lireZone()
                } catch (e: Exception) {
                    android.util.Log.w("CF_RtkV4Diag", "contexte mission non recharge", e)
                }
                delay(10_000L)
            }
        }

        val client = ca.cineflight.stage.cine.ClientRtkSujetV4()
        clientRtkSujetV4 = client
        jobFluxRtkV4 = lifecycleScope.launch(Dispatchers.Main) {
            client.evenements.collect { evenement ->
                if (!pollingSujetActif) return@collect
                traiterPositionSujetV4(evenement)
            }
        }

        try {
            client.demarrer()
        } catch (e: Exception) {
            android.util.Log.e("CF_RtkV4Diag", "demarrage WSS V4 impossible", e)
            majBandeauRtkV4(
                ca.cineflight.stage.cine.ClientRtkSujet.PositionSujet(
                    present = false,
                    reason = "CONFIG_V4_INVALIDE",
                    streamStatus = "DISCONNECTED"
                ),
                client.metriques()
            )
        }

        // Boucle passive a 50 Hz cadencee par echeances monotones.
        // Contrairement a delay(20) apres calcul, l'overhead n'est plus ajoute a chaque cycle.
        jobFusionRtkV4 = lifecycleScope.launch(Dispatchers.Default) {
            val periodeNs = ca.cineflight.stage.control.MoteurFusionRtkV4.FUSION_PERIOD_NS
            var prochaineEcheanceNs = android.os.SystemClock.elapsedRealtimeNanos()
            while (pollingSujetActif && isActive) {
                val maintenantNs = android.os.SystemClock.elapsedRealtimeNanos()
                val attenteNs = prochaineEcheanceNs - maintenantNs
                if (attenteNs > 0L) {
                    if (attenteNs >= 2_000_000L) {
                        delay((attenteNs / 1_000_000L).coerceAtLeast(1L))
                    } else {
                        kotlinx.coroutines.yield()
                    }
                    continue
                }

                val prediction = moteurFusionRtkV4.predireTempsReel(maintenantNs)
                dernierePredictionV4 = prediction
                val maintenantMs = System.currentTimeMillis()
                if (maintenantMs - dernierLogPredictionMs >= 1000L) {
                    dernierLogPredictionMs = maintenantMs
                    android.util.Log.i("CF_RtkV4Diag", prediction.journal())
                    prediction.diagnostic?.let {
                        android.util.Log.i("CF_PredictionDiag", it.journal())
                    }
                    journalSessionRtkV41?.enregistrerPrediction(prediction)
                }

                prochaineEcheanceNs += periodeNs
                // Apres une suspension longue, reprendre au temps courant sans rafale de rattrapage.
                if (maintenantNs - prochaineEcheanceNs > periodeNs * 5L) {
                    prochaineEcheanceNs = maintenantNs + periodeNs
                }
            }
        }
    }

    /**
     * Observation MIROIR soccer (Phase 8). Transforme les boites YOLO en position
     * d'action, la stabilise, calcule la commande THEORIQUE de rail, et la JOURNALISE.
     * INVARIANT : rien n'est jamais envoye au drone / a la nacelle. Fail-open.
     */
    private fun observerMiroirSoccer(boxes: List<RecepteurBoxes.Box>, tEmis: Long) {
        try {
            val ts = if (tEmis > 0L) tEmis else android.os.SystemClock.elapsedRealtime()

            // 1) SUIVI MULTI-JOUEURS : chaque box (person) -> detection ; le tracker garde
            //    les identites entre frames (centre = coin + demi-taille).
            val dets = boxes.map {
                ca.cineflight.stage.sport.soccer.PlayerTracker.Detection(
                    cx = it.x + it.w / 2f, cy = it.y + it.h / 2f, w = it.w, h = it.h, conf = it.conf
                )
            }
            val joueurs = soccerPlayerTracker.update(dets, ts)

            // 2) CENTRE DU GROUPE PRINCIPAL (ecarte les isoles) -> centre de l'action.
            val centre = ca.cineflight.stage.sport.soccer.ActionCenter.calculer(
                joueurs.map { it.cx to it.cy })

            // 3) Estimation stabilisee de la position horizontale de l'action (rail).
            val estimate = if (centre.present)
                ca.cineflight.stage.sport.soccer.SoccerActionEstimate(
                    positionNormalized = centre.x.coerceIn(0f, 1f),
                    confidence = 0.9f,
                    source = ca.cineflight.stage.sport.soccer.SoccerActionEstimate.Source.PLAYERS,
                    timestampMs = ts,
                ) else null
            val stable = soccerTracker.update(estimate, ts)

            val estimateStable = stable.positionNormalized?.let { pos ->
                ca.cineflight.stage.sport.soccer.SoccerActionEstimate(
                    positionNormalized = pos, confidence = stable.confidence,
                    source = ca.cineflight.stage.sport.soccer.SoccerActionEstimate.Source.PLAYERS,
                    timestampMs = stable.timestampMs,
                )
            }
            val decision = soccerMirror.decideMirror(estimateStable, soccerRail, soccerRailPos)
            android.util.Log.i(
                ca.cineflight.stage.sport.soccer.SoccerMirrorLog.TAG,
                ca.cineflight.stage.sport.soccer.SoccerMirrorLog.ligne(decision) +
                    " joueurs=${joueurs.size} groupe=${centre.taille} centre=%.2f,%.2f etalement=%.2f"
                        .format(centre.x, centre.y, centre.etalement)
            )
        } catch (_: Throwable) { /* observation seulement : jamais bloquant */ }
    }

    private fun traiterPositionSujetV4(
        evenement: ca.cineflight.stage.cine.ClientRtkSujetV4.Evenement
    ) {
        val pos = evenement.position
        dernierePositionSujetV4 = pos
        journalSessionRtkV41?.enregistrerPosition(
            position = pos,
            metriques = evenement.metriques,
            rawType = evenement.rawType,
            profil = profilSujetV4
        )
        val etatDrone = pont.lireEtat(pilote.enVol)
        dernierEtatSujet = suiviSujet.evaluer(pos, etatDrone.latitude, etatDrone.longitude)

        // Une seule ingestion par source_sequence. Le moteur V4 rejette les doublons.
        val etatPrediction = moteurFusionRtkV4.ingerer(pos)
        vueDiagnosticPredictionRtk?.mettreAJour(etatPrediction)
        // --- OBSERVATION VOCALE Phase 3B : etat consolide du suivi du sujet. Mappe les
        // enums de l'app vers des primitives ; la voix observe, n'agit jamais. Fail-open. ---
        try {
            val es = dernierEtatSujet
            if (es != null) {
                // Presence + fraicheur : 2=present-frais, 1=perime, 0=perdu.
                val presNiv = when {
                    !es.sujetPresent -> 0
                    !es.rtkSujetOk -> 1     // present mais donnees perimees / non fiables
                    else -> 2
                }
                moniteurSujet?.observerPresence(presNiv, balise = !pos.networkConnected)
                // Qualite RTK : FIX=3, FLOAT=2, GPS=1, LOST=0.
                val rtkNiv = when (es.rtk) {
                    ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk.FIX -> 3
                    ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk.FLOAT -> 2
                    ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk.GPS -> 1
                    ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk.LOST -> 0
                }
                moniteurSujet?.observerRtkQualite(rtkNiv)
                // Distance de securite avec le sujet.
                moniteurSujet?.observerTropProche(
                    es.zone == ca.cineflight.stage.control.SuiviSujetRtk.ZoneDistance.DANGER)
            }
            // Fusion vision/RTK : depuis le dernier verdict de controle.
            val vd = dernierVerdictControleRtkVision
            if (vd != null) {
                val fusNiv = when (vd.mode) {
                    ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FIX_COMPLET -> 2
                    ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FLOAT_VISION -> 1
                    ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.BLOQUE -> 0
                }
                val rtkOk = (dernierEtatSujet?.rtk == ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk.FIX)
                moniteurSujet?.observerFusion(fusNiv, visionOk = true, rtkOk = rtkOk)
            }
            // Prediction : disponible / degrade / suspendu depuis la readiness.
            val predNiv = when {
                etatPrediction.controlePret -> 1        // disponible pour controle
                etatPrediction.pret -> 2                // affichable mais degrade pour controle
                else -> 3                                // suspendu
            }
            moniteurSujet?.observerPrediction(predNiv)
            // Serveur / reseau base sur l'age et le streamStatus.
            val srvNiv = when (pos.streamStatus) {
                "LIVE" -> 2
                "STALE" -> 1
                else -> 0   // DISCONNECTED / NO_DATA
            }
            moniteurSujet?.observerServeur(srvNiv)
        } catch (_: Throwable) {}
        // --- DIVERGENCE vision/RTK : n'a de sens que si RTK sujet valide ET YOLO a
        // trouve le sujet cette frame. Sinon on reinitialise (comparaison sans objet). ---
        try {
            val es = dernierEtatSujet
            val rtkOk = es != null && es.sujetPresent && es.rtkSujetOk
            if (rtkOk && dernierSujetTrouve) {
                moniteurDivergence?.observer(
                    droneLat = etatDrone.latitude, droneLon = etatDrone.longitude,
                    droneCapDeg = if (etatDrone.capDeg.isNaN()) Double.NaN else etatDrone.capDeg.toDouble(),
                    sujetLat = es!!.sujetLat, sujetLon = es.sujetLon,
                    cxBoite = dernierCxSujet.toDouble(),
                    nowMs = System.currentTimeMillis()
                )
            } else {
                moniteurDivergence?.reinitialiser()
            }
        } catch (_: Throwable) {}
        majBandeauRtkV4(pos, evenement.metriques)

        // GEO-BARRIERE: le drone doit rester dans la zone validee.
        val z = zoneVol
        droneDansZone = if (
            z != null && z.present &&
            !etatDrone.latitude.isNaN() && !etatDrone.longitude.isNaN()
        ) {
            val b = z.barriere()
            val dp = ca.cineflight.stage.control.GeoBarriere.Point(
                etatDrone.latitude,
                etatDrone.longitude
            )
            b.estDedans(dp) && b.distanceAuBordM(dp) >= z.margeM
        } else true

        // Corridor: une position peut etre affichee en FLOAT/GPS, mais le noyau
        // bloque toute future commande tant que controleFiable n'est pas vrai.
        val t = trajectoireSuivi
        if (t != null && t.present) {
            val sujetPt = if (pos.fiable) {
                ca.cineflight.stage.control.MoniteurCorridor.Point(pos.lat, pos.lon)
            } else null
            val res = moniteurCorridor.evaluer(t.points, sujetPt, pos.ageS, null)
            val st = res.statut
            val franc = st == ca.cineflight.stage.control.MoniteurCorridor.Statut.HORS_CORRIDOR ||
                st == ca.cineflight.stage.control.MoniteurCorridor.Statut.RTK_PERDU
            if (st == ca.cineflight.stage.control.MoniteurCorridor.Statut.NORMAL) {
                comptHorsNormal = 0
                dernierStatutCorridor = res
            } else if (franc) {
                dernierStatutCorridor = res
            } else {
                comptHorsNormal++
                if (comptHorsNormal >= 2) dernierStatutCorridor = res
            }
            majBandeauCorridor(dernierStatutCorridor)
        }
    }

    private fun appliquerProfilSujetV4(
        profil: ca.cineflight.stage.control.ProfilSujetMobile,
        sauvegarder: Boolean = true
    ) {
        profilSujetV4 = profil
        suiviSujet = ca.cineflight.stage.control.SuiviSujetRtk(profil.configurationSuivi())
        moteurFusionRtkV4.reconfigurer(profil)
        if (sauvegarder) {
            ca.cineflight.stage.control.ProfilSujetMobile.sauvegarder(this, profil)
        }
        bandeauRtkV4?.text = getString(R.string.ma_bandeau_connexion, profil.libelle)
        android.util.Log.i(
            "CF_RtkV4Diag",
            "profile_changed code=${profil.code} max_speed_mps=${profil.vitesseMaxMps} " +
                "vision_class=${profil.classeVision} control_policy=FIX_FULL_FLOAT_VISION_ONLY"
        )
        journalSessionRtkV41?.enregistrerSysteme("PROFILE_CHANGED", profil.code)
    }

    private fun demanderProfilSujetV4(force: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val profils = ca.cineflight.stage.control.ProfilSujetMobile.values()
        val selection = profils.indexOf(profilSujetV4).coerceAtLeast(0)
        val dialogue = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ma_dlg_sujet_mobile))
            .setSingleChoiceItems(
                profils.map { it.libelle }.toTypedArray(),
                selection
            ) { d, index ->
                appliquerProfilSujetV4(profils[index])
                d.dismiss()
            }
        if (!force) dialogue.setNegativeButton(android.R.string.cancel, null)
        dialogue.setCancelable(!force)
        dialogue.show()
    }

    /** Acces public au diagnostic : appele depuis le bouton "🐞 Diagnostic" tout
     *  en bas du menu Recettes. Ouvre/ferme le panneau. Null-safe si absent. */
    fun basculerDiagnostic() { vueDiagnosticPredictionRtk?.basculer() }

    private fun creerBandeauRtkV4SiNecessaire() {
        if (!AFFICHER_BANDEAU_DIAG) return   // bandeau technique masque (menu degage)
        if (bandeauRtkV4 != null) return
        try {
            val racine = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
            val tv = TextView(this).apply {
                text = getString(R.string.ma_bandeau_connexion, profilSujetV4.libelle)
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpPx(14), dpPx(7), dpPx(14), dpPx(7))
                setBackgroundColor(0xDD212121.toInt())
                isClickable = true
                setOnClickListener { demanderProfilSujetV4(force = false) }
            }
            val lp = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = dpPx(16)
                rightMargin = dpPx(20)
            }
            racine.addView(tv, lp)
            bandeauRtkV4 = tv
        } catch (e: Exception) {
            android.util.Log.w("CF_RtkV4Diag", "bandeau V4 non cree", e)
        }
    }

    private fun majBandeauRtkV4(
        pos: ca.cineflight.stage.cine.ClientRtkSujet.PositionSujet,
        metriques: ca.cineflight.stage.cine.ClientRtkSujetV4.Metriques
    ) {
        val tv = bandeauRtkV4 ?: return
        val hz = pos.measuredRateHz?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--"
        val ageMs = pos.ageS?.let { (it * 1000.0).roundToInt().toString() } ?: "--"
        val verdict = evaluerControleRtkVision(
            visionRequise = true,
            yoloTrouve = dernierSujetTrouve,
            hauteurBoite = derniereHauteurBoiteSujet
        )
        val modeControle = when (verdict.mode) {
            ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FIX_COMPLET -> "FIX COMPLET"
            ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FLOAT_VISION -> "FLOAT+YOLO GIMBAL"
            ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.BLOQUE -> "BLOQUE"
        }
        val texte = "V4.2 • ${profilSujetV4.libelle} • ${pos.rtk.name} • ${hz} Hz • ${ageMs} ms • $modeControle"
        tv.text = texte
        val couleur = when {
            !metriques.connected || pos.streamStatus == "DISCONNECTED" -> 0xFFD32F2F.toInt()
            pos.streamStatus != "LIVE" -> 0xFFF57C00.toInt()
            verdict.mode == ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FIX_COMPLET -> 0xFF2E7D32.toInt()
            verdict.mode == ca.cineflight.stage.control.AutorisationControleRtkVision.Mode.FLOAT_VISION -> 0xFF1565C0.toInt()
            pos.fiable -> 0xFFF9A825.toInt()
            else -> 0xFFC62828.toInt()
        }
        tv.setBackgroundColor(couleur)
    }

    /** Affiche un bandeau coloré du statut corridor pour le pilote (conscience de
     *  situation). NORMAL vert / TOLERANCE jaune / PRUDENCE orange / HORS rouge /
     *  RTK perdu rouge. N'AGIT PAS sur le drone (le blocage passe par le noyau). */
    private fun majBandeauCorridor(res: ca.cineflight.stage.control.MoniteurCorridor.Resultat?) {
        // crée le bandeau au 1er appel (ancré en haut de la vue racine).
        if (bandeauCorridor == null) {
            try {
                val racine = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
                val tv = TextView(this).apply {
                    setPadding(24, 16, 24, 16)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT)
                    lp.gravity = android.view.Gravity.TOP
                    layoutParams = lp
                    visibility = android.view.View.GONE
                }
                racine.addView(tv)
                bandeauCorridor = tv
            } catch (e: Exception) { return }
        }
        val tv = bandeauCorridor ?: return
        if (res == null) { tv.visibility = android.view.View.GONE; return }
        val (couleur, prefixe) = when (res.statut) {
            ca.cineflight.stage.control.MoniteurCorridor.Statut.NORMAL ->
                Pair(0xFF2E7D32.toInt(), getString(R.string.ma_corr_normal))
            ca.cineflight.stage.control.MoniteurCorridor.Statut.TOLERANCE ->
                Pair(0xFFF9A825.toInt(), getString(R.string.ma_corr_tolerance))
            ca.cineflight.stage.control.MoniteurCorridor.Statut.PRUDENCE ->
                Pair(0xFFEF6C00.toInt(), getString(R.string.ma_corr_prudence))
            ca.cineflight.stage.control.MoniteurCorridor.Statut.HORS_CORRIDOR ->
                Pair(0xFFC62828.toInt(), getString(R.string.ma_corr_hors))
            ca.cineflight.stage.control.MoniteurCorridor.Statut.RTK_PERDU ->
                Pair(0xFFC62828.toInt(), getString(R.string.ma_corr_rtk_perdu))
        }
        tv.setBackgroundColor(couleur)
        tv.text = prefixe + " — " + res.message
        tv.visibility = android.view.View.VISIBLE
    }

    /**
     * CLICKER : sonde le serveur pour le dernier evenement de la
     * telecommande du sujet, l'AFFICHE dans un bandeau flottant en bas, puis
     * l'acquitte. Ne pilote PAS le drone, ne touche PAS au NoyauSecurite : c'est
     * une validation suivie d'une simulation virtuelle, sans execution moteur.
     * Poll decouple du flux RTK. Chaque evenement traite exactement une fois.
     */
    private fun demarrerPollClicker() {
        jobPollClicker?.cancel()
        ClientClicker.reset()
        eventIdClickerEnCours = null

        jobPollClicker = lifecycleScope.launch {
            while (isActive) {
                val evt = ClientClicker.prochainEvenement()

                if (
                    evt != null &&
                    !verificationCartographiqueClickerEnCours &&
                    eventIdClickerEnCours != evt.eventId
                ) {
                    eventIdClickerEnCours = evt.eventId
                    val demande = construireDemandeValidationClicker(evt)

                    if (demande == null) {
                        val validation =
                            ca.cineflight.stage.control.ValidationMouvement(
                                autorise = false,
                                status = "BLOCKED_UNKNOWN_CLICKER_COMMAND",
                                raison = "Commande Clicker inconnue"
                            )

                        annoncerVerdictClicker(evt.commande, validation)
                        ClientClicker.acquitter(evt.eventId, validation.status)
                        eventIdClickerEnCours = null
                    } else {
                        clientValidationClicker.fetchRtkSujet(
                            onResult = { rtk ->
                                val validationPosition =
                                    ca.cineflight.stage.control.MouvementSafetyValidator
                                        .validerDemande(demande, rtk)

                                if (
                                    CLICKER_MODE_SIMULATION &&
                                    validationPosition.autorise
                                ) {
                                    val mouvementSansTranslation =
                                        demande.movement in setOf(
                                            "PAUSE_HOVER",
                                            "STOP_HOVER"
                                        )

                                    if (mouvementSansTranslation) {
                                        val simulation =
                                            simulateurClicker.executer(
                                                demande.movement
                                            )

                                        annoncerResultatSimulationClicker(
                                            evt.commande,
                                            simulation,
                                            rayonCarteM = null
                                        )

                                        lifecycleScope.launch {
                                            ClientClicker.acquitter(
                                                evt.eventId,
                                                simulation.status
                                            )
                                            eventIdClickerEnCours = null
                                        }
                                    } else {
                                        verificationCartographiqueClickerEnCours = true

                                        lifecycleScope.launch {
                                            try {
                                                val verdictCarte =
                                                    verificateurDegagementClicker
                                                        .verifier(
                                                            demande = demande,
                                                            rtk = rtk,
                                                            distanceVirtuelleM =
                                                                simulateurClicker
                                                                    .etatCourant()
                                                                    .distanceSujetM
                                                        )

                                                if (
                                                    verdictCarte.validation.autorise
                                                ) {
                                                    val simulation =
                                                        simulateurClicker
                                                            .executer(
                                                                demande.movement
                                                            )

                                                    annoncerResultatSimulationClicker(
                                                        evt.commande,
                                                        simulation,
                                                        rayonCarteM =
                                                            verdictCarte.rayonM
                                                    )

                                                    ClientClicker.acquitter(
                                                        evt.eventId,
                                                        simulation.status
                                                    )
                                                } else {
                                                    annoncerVerdictCartographiqueClicker(
                                                        evt.commande,
                                                        verdictCarte.validation,
                                                        verdictCarte.rayonM
                                                    )

                                                    ClientClicker.acquitter(
                                                        evt.eventId,
                                                        verdictCarte
                                                            .validation
                                                            .status
                                                    )
                                                }
                                            } finally {
                                                verificationCartographiqueClickerEnCours =
                                                    false
                                                eventIdClickerEnCours = null
                                            }
                                        }
                                    }
                                } else {
                                    val validationFinale =
                                        validerEnvironnementClicker(
                                            demande = demande,
                                            validationPosition = validationPosition,
                                            rtk = rtk
                                        )

                                    annoncerVerdictClicker(
                                        evt.commande,
                                        validationFinale
                                    )

                                    lifecycleScope.launch {
                                        ClientClicker.acquitter(
                                            evt.eventId,
                                            validationFinale.status
                                        )
                                        eventIdClickerEnCours = null
                                    }
                                }
                            },
                            onError = { message ->
                                android.util.Log.w(
                                    "CF_Clicker",
                                    "Lecture RTK pour validation Clicker : $message"
                                )
                            }
                        )
                    }
                }

                delay(250)
            }
        }
    }

    /** Bandeau flottant clicker (ancre en bas). Calque sur majBandeauCorridor. */
    private fun majBandeauClicker(commande: ClientClicker.Commande, eventId: Long) {
        if (bandeauClicker == null) {
            try {
                val racine = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
                val tv = TextView(this).apply {
                    setPadding(24, 16, 24, 16)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    val lp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT)
                    lp.gravity = android.view.Gravity.BOTTOM
                    layoutParams = lp
                    visibility = android.view.View.GONE
                }
                racine.addView(tv)
                bandeauClicker = tv
            } catch (e: Exception) { return }
        }
        val tv = bandeauClicker ?: return
        val couleur = if (commande == ClientClicker.Commande.INCONNUE)
            0xFFC62828.toInt() else 0xCC1B5E9C.toInt()
        tv.setBackgroundColor(couleur)
        tv.text = getString(R.string.ma_clicker_bandeau, nbEvenementsClicker, commande.name, eventId)
        tv.visibility = android.view.View.VISIBLE
    }

    private fun initialiserVoixClicker() {
        ttsClicker = TextToSpeech(applicationContext) { statut ->
            if (statut != TextToSpeech.SUCCESS) {
                ttsClickerPret = false
                return@TextToSpeech
            }

            val enAnglais = LangueManager.langueActuelle(applicationContext) == "en"
            var resultat = ttsClicker?.setLanguage(if (enAnglais) Locale.CANADA else Locale.CANADA_FRENCH)
                ?: TextToSpeech.LANG_NOT_SUPPORTED

            if (
                resultat == TextToSpeech.LANG_MISSING_DATA ||
                resultat == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                resultat = ttsClicker?.setLanguage(if (enAnglais) Locale.ENGLISH else Locale.FRENCH)
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
            }

            ttsClickerPret =
                resultat != TextToSpeech.LANG_MISSING_DATA &&
                resultat != TextToSpeech.LANG_NOT_SUPPORTED

            ttsClicker?.setSpeechRate(0.95f)
            ttsClicker?.setPitch(1.0f)
        }
    }

    private fun construireDemandeValidationClicker(
        evt: ClientClicker.EvenementClicker
    ): ca.cineflight.stage.control.DemandeMouvement? {
        val mouvement = when (evt.commande) {
            ClientClicker.Commande.RAPPROCHE ->
                "RAPPROCHE_SUJET"

            ClientClicker.Commande.RAPPROCHE_PRONONCE ->
                "RAPPROCHE_SUJET_PRONONCE"

            ClientClicker.Commande.ELOIGNE ->
                "ELOIGNEMENT_SUJET"

            ClientClicker.Commande.REVEAL_ARRIERE ->
                "TRAVELLING_ARRIERE"

            ClientClicker.Commande.PAUSE ->
                "PAUSE_HOVER"

            ClientClicker.Commande.REPRENDRE ->
                "REPRENDRE_SUIVI"

            ClientClicker.Commande.CHANGER_COTE ->
                "CHANGER_COTE"

            ClientClicker.Commande.ORBITE ->
                "ORBITE_LARGE"

            ClientClicker.Commande.HOLD ->
                "STOP_HOVER"

            ClientClicker.Commande.INCONNUE ->
                return null
        }

        return ca.cineflight.stage.control.DemandeMouvement(
            ok = true,
            deviceId = "clicker_raspberry",
            kind = "CLICKER",
            movement = mouvement,
            source = evt.source ?: "kensington_presenter",
            safetyNote = "validation_only_no_drone_execution",
            serverRxTs = evt.timestamp
                ?: (System.currentTimeMillis() / 1000.0),
            rawJson = "{}"
        )
    }

    private fun validerEnvironnementClicker(
        demande: ca.cineflight.stage.control.DemandeMouvement,
        validationPosition: ca.cineflight.stage.control.ValidationMouvement,
        rtk: ca.cineflight.stage.control.RtkSujet?
    ): ca.cineflight.stage.control.ValidationMouvement {
        if (
            !validationPosition.autorise ||
            validationPosition.status == "SAFETY_COMMAND_OK_NO_EXECUTION"
        ) {
            return validationPosition
        }

        // Lecture seule. Si le drone n'est pas encore connecté, demarrer() échoue
        // proprement et pourra être retenté à la prochaine commande.
        lecteurPerception.demarrer()

        val etatDrone = try {
            if (::pont.isInitialized && ::pilote.isInitialized) {
                pont.lireEtat(pilote.enVol)
            } else null
        } catch (_: Exception) {
            null
        }

        val zone = zoneVol
        val positionDroneValide = etatDrone?.let {
            it.gpsValide &&
                !it.latitude.isNaN() &&
                !it.longitude.isNaN()
        } == true

        val droneDansZoneMaintenant =
            if (zone?.present == true && positionDroneValide && etatDrone != null) {
                val barriere = zone.barriere()
                val pointDrone = ca.cineflight.stage.control.GeoBarriere.Point(
                    etatDrone.latitude,
                    etatDrone.longitude
                )
                barriere.estDedans(pointDrone) &&
                    barriere.distanceAuBordM(pointDrone) >= zone.margeM
            } else {
                false
            }

        val altitudeRequise =
            zone?.altMinM != null || zone?.altMaxM != null

        val altitudeDroneConnue =
            etatDrone != null && !etatDrone.altitudeAgl.isNaN()

        val altitudeDansZone =
            if (!altitudeRequise) {
                true
            } else if (!altitudeDroneConnue || etatDrone == null || zone == null) {
                false
            } else {
                val altitude = etatDrone.altitudeAgl.toDouble()
                val assezHaut = zone.altMinM?.let { altitude >= it } ?: true
                val assezBas = zone.altMaxM?.let { altitude <= it } ?: true
                assezHaut && assezBas
            }

        val trajectoire = trajectoireSuivi
        val trajectoirePresente =
            trajectoire?.present == true &&
                trajectoire.points.size >= 2

        val resultatCorridor =
            if (trajectoirePresente && trajectoire != null) {
                moniteurCorridor.appliquerSeuils(
                    trajectoire.seuilNormalM,
                    trajectoire.seuilToleranceM,
                    trajectoire.seuilPrudenceM
                )

                val latSujet = rtk?.lat
                val lonSujet = rtk?.lon
                val pointSujet =
                    if (
                        rtk?.present == true &&
                        latSujet != null &&
                        lonSujet != null &&
                        latSujet.isFinite() &&
                        lonSujet.isFinite()
                    ) {
                        ca.cineflight.stage.control.MoniteurCorridor.Point(
                            latSujet,
                            lonSujet
                        )
                    } else {
                        null
                    }

                moniteurCorridor.evaluer(
                    trajectoire = trajectoire.points,
                    sujet = pointSujet,
                    ageRtkS = rtk?.ageS,
                    positionPrevue = null
                )
            } else {
                null
            }

        val contexte =
            ca.cineflight.stage.control.ContexteEnvironnementClicker(
                droneConnecte = etatDrone?.connecte == true,
                positionDroneValide = positionDroneValide,
                batteriePct = etatDrone?.batteriePct?.takeIf { it >= 0 },
                zonePresente = zone?.present == true,
                zoneValidee = zone?.valideSecurite == true,
                droneDansZone = droneDansZoneMaintenant,
                altitudeRequise = altitudeRequise,
                altitudeDroneConnue = altitudeDroneConnue,
                altitudeDansZone = altitudeDansZone,
                trajectoirePresente = trajectoirePresente,
                statutCorridor = resultatCorridor?.statut,
                perceptionDisponible =
                    lecteurPerception.perceptionDisponible(),
                distanceObstacleHorizontalMm =
                    lecteurPerception.distanceHorizontale()
            )

        return ca.cineflight.stage.control.ValidationEnvironnementClicker
            .valider(
                mouvement = demande.movement,
                validationPosition = validationPosition,
                contexte = contexte
            )
    }
    private fun complementNomCommandeClicker(
        commande: ClientClicker.Commande
    ): String = when (commande) {
        ClientClicker.Commande.RAPPROCHE ->
            getString(R.string.ma_clk_nom_rapproche)

        ClientClicker.Commande.RAPPROCHE_PRONONCE ->
            getString(R.string.ma_clk_nom_rappr_pron)

        ClientClicker.Commande.ELOIGNE ->
            getString(R.string.ma_clk_nom_eloigne)

        ClientClicker.Commande.REVEAL_ARRIERE ->
            getString(R.string.ma_clk_nom_reveal)

        ClientClicker.Commande.PAUSE ->
            getString(R.string.ma_clk_nom_pause)

        ClientClicker.Commande.REPRENDRE ->
            getString(R.string.ma_clk_nom_reprendre)

        ClientClicker.Commande.CHANGER_COTE ->
            getString(R.string.ma_clk_nom_cote)

        ClientClicker.Commande.ORBITE ->
            getString(R.string.ma_clk_nom_orbite)

        ClientClicker.Commande.HOLD ->
            getString(R.string.ma_clk_nom_hold)

        ClientClicker.Commande.INCONNUE ->
            getString(R.string.ma_clk_nom_inconnue)
    }

    private fun annoncerResultatSimulationClicker(
        commande: ClientClicker.Commande,
        simulation: ca.cineflight.stage.control.SimulateurClicker.Resultat,
        rayonCarteM: Int?
    ) {
        val nom = complementNomCommandeClicker(commande)
        val descriptionEtat =
            ca.cineflight.stage.control.SimulateurClicker
                .descriptionEtat(this@MainActivity, simulation.etat)

        android.util.Log.i(
            "CF_ClickerSim",
            "commande=${commande.name} status=${simulation.status} " +
                "sequence=${simulation.etat.sequence} " +
                "distance_m=${simulation.etat.distanceSujetM} " +
                "angle_deg=${simulation.etat.angleAutourSujetDeg} " +
                "pause=${simulation.etat.enPause} " +
                "rayon_carte_m=${rayonCarteM ?: 0}"
        )

        if (!ttsClickerPret) return

        val phraseCarte =
            if (rayonCarteM != null) {
                getString(R.string.ma_clk_phrase_carte, rayonCarteM)
            } else {
                ""
            }

        val phrase =
            getString(R.string.ma_clk_sim_p1, nom) +
                phraseCarte +
                simulation.message + " " +
                getString(R.string.ma_clk_sim_p2, descriptionEtat)

        ttsClicker?.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "clicker_sim_${System.currentTimeMillis()}"
        )
    }

    private fun annoncerVerdictCartographiqueClicker(
        commande: ClientClicker.Commande,
        validation: ca.cineflight.stage.control.ValidationMouvement,
        rayonM: Int
    ) {
        val nom = complementNomCommandeClicker(commande)

        android.util.Log.w(
            "CF_ClickerMap",
            "commande=${commande.name} status=${validation.status} " +
                "rayon_m=$rayonM raison=${validation.raison}"
        )

        if (!ttsClickerPret) return

        val explication = when (validation.status) {
            "BLOCKED_MAP_OBSTACLES" ->
                getString(R.string.ma_clk_map_obstacles, validation.raison)

            "BLOCKED_MAP_UNAVAILABLE" ->
                getString(R.string.ma_clk_map_unavail)

            "BLOCKED_MAP_POSITION_UNKNOWN" ->
                getString(R.string.ma_clk_map_pos_unknown)

            else ->
                getString(R.string.ma_clk_map_else)
        }

        val phrase =
            getString(R.string.ma_clk_map_phrase, nom, explication)

        ttsClicker?.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "clicker_map_${System.currentTimeMillis()}"
        )
    }
    private fun annoncerVerdictClicker(
        commande: ClientClicker.Commande,
        validation: ca.cineflight.stage.control.ValidationMouvement
    ) {
        if (!ttsClickerPret) return

        val nom = complementNomCommandeClicker(commande)

        val phrase = when {
            validation.status == "SAFETY_COMMAND_OK_NO_EXECUTION" ->
                getString(R.string.ma_clk_v_safety, nom)

            validation.status == "ENVIRONMENT_VALIDATION_OK_NO_EXECUTION" ->
                getString(R.string.ma_clk_v_env_ok, nom)

            validation.status ==
                "ENVIRONMENT_VALIDATION_OK_WITH_CAUTION_NO_EXECUTION" ->
                getString(R.string.ma_clk_v_env_caution, nom)

            validation.autorise ->
                getString(R.string.ma_clk_v_autorise, nom)

            else -> {
                val explication = when (validation.status) {
                    "BLOCKED_NO_MOVEMENT" ->
                        getString(R.string.ma_clk_b_no_move)

                    "BLOCKED_UNKNOWN_MOVEMENT",
                    "BLOCKED_UNKNOWN_CLICKER_COMMAND" ->
                        getString(R.string.ma_clk_b_unknown)

                    "BLOCKED_NO_TIMESTAMP" ->
                        getString(R.string.ma_clk_b_no_ts)

                    "BLOCKED_STALE_REQUEST" ->
                        getString(R.string.ma_clk_b_stale)

                    "BLOCKED_RTK_READ_ERROR" ->
                        getString(R.string.ma_clk_b_rtk_read)

                    "BLOCKED_NO_RTK_SUBJECT" ->
                        getString(R.string.ma_clk_b_no_rtk)

                    "BLOCKED_RTK_LOST" ->
                        getString(R.string.ma_clk_b_rtk_lost)

                    "BLOCKED_RTK_NO_AGE" ->
                        getString(R.string.ma_clk_b_rtk_noage)

                    "BLOCKED_RTK_TOO_OLD" ->
                        getString(R.string.ma_clk_b_rtk_old)

                    "BLOCKED_RTK_NOT_FIX" ->
                        getString(R.string.ma_clk_b_rtk_notfix)

                    "BLOCKED_DRONE_DISCONNECTED" ->
                        getString(R.string.ma_clk_b_drone_disc)

                    "BLOCKED_DRONE_POSITION_UNKNOWN" ->
                        getString(R.string.ma_clk_b_drone_pos)

                    "BLOCKED_BATTERY_UNKNOWN" ->
                        getString(R.string.ma_clk_b_batt_unk)

                    "BLOCKED_BATTERY_LOW" ->
                        getString(R.string.ma_clk_b_batt_low)

                    "BLOCKED_ZONE_MISSING" ->
                        getString(R.string.ma_clk_b_zone_miss)

                    "BLOCKED_ZONE_NOT_VALIDATED" ->
                        getString(R.string.ma_clk_b_zone_notval)

                    "BLOCKED_DRONE_OUTSIDE_ZONE" ->
                        getString(R.string.ma_clk_b_out_zone)

                    "BLOCKED_ALTITUDE_UNKNOWN" ->
                        getString(R.string.ma_clk_b_alt_unk)

                    "BLOCKED_ALTITUDE_OUTSIDE_ZONE" ->
                        getString(R.string.ma_clk_b_alt_out)

                    "BLOCKED_TRAJECTORY_MISSING" ->
                        getString(R.string.ma_clk_b_traj_miss)

                    "BLOCKED_CORRIDOR_UNAVAILABLE" ->
                        getString(R.string.ma_clk_b_corr_unavail)

                    "BLOCKED_CORRIDOR_POSITION_UNAVAILABLE" ->
                        getString(R.string.ma_clk_b_corr_pos)

                    "BLOCKED_CORRIDOR_PRUDENCE" ->
                        getString(R.string.ma_clk_b_corr_prud)

                    "BLOCKED_CORRIDOR_OUTSIDE" ->
                        getString(R.string.ma_clk_b_corr_out)

                    "BLOCKED_PERCEPTION_UNAVAILABLE" ->
                        getString(R.string.ma_clk_b_perc)

                    "BLOCKED_OBSTACLE_DISTANCE_UNKNOWN" ->
                        getString(R.string.ma_clk_b_obs_dist)

                    "BLOCKED_OBSTACLE_TOO_CLOSE" ->
                        getString(R.string.ma_clk_b_obs_close)

                    else ->
                        getString(R.string.ma_clk_b_else)
                }

                getString(R.string.ma_clk_v_blocked, nom, explication)
            }
        }

        ttsClicker?.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "clicker_${System.currentTimeMillis()}"
        )
    }
    private fun arreterPollingSujet() {
        pollingSujetActif = false
        jobFluxRtkV4?.cancel()
        jobFusionRtkV4?.cancel()
        jobContexteRtkV4?.cancel()
        jobFluxRtkV4 = null
        jobFusionRtkV4 = null
        jobContexteRtkV4 = null
        clientRtkSujetV4?.fermerDefinitivement()
        clientRtkSujetV4 = null
        journalSessionRtkV41?.enregistrerSysteme("ANDROID_V4_1_STOPPED")
        journalSessionRtkV41?.fermer()
        journalSessionRtkV41 = null
    }

}





