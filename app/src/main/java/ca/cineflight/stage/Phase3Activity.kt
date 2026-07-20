package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ca.cineflight.stage.control.OverlayYolo
import ca.cineflight.stage.control.RecepteurBoxes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.control.PontDjiReel
import ca.cineflight.stage.control.TraductionAxes
import ca.cineflight.stage.control.DiagnosticPredictionRtk
import ca.cineflight.stage.control.ProfilSujetMobile
import ca.cineflight.stage.control.GenerateurMouvement
import ca.cineflight.stage.control.FluxCamera
import ca.cineflight.stage.control.YoloSuivi
import ca.cineflight.stage.control.CapacitesDrone
import ca.cineflight.stage.control.SuiviVisionYolo
import ca.cineflight.stage.sentinelle.LecteurPerception
import com.tencent.yolo11ncnn.YOLO11Ncnn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * PHASE 3 — SUIVI RTK DE L'AUTO EN VOL REEL (offset fixe).
 *
 * Le drone suit la voiture equipee du Pi RTK V4 (ZED-F9P). La position de l'auto
 * arrive du serveur DigitalOcean (GET /api/rtk/sujet), alimente par le Pi en 4G.
 * Le drone se maintient a une position CIBLE = position auto + OFFSET FIXE
 * (au-dessus + en recul), calculee a chaque tick et poursuivie en VirtualStick.
 *
 * ── CHAINE DE CONTROLE (par tick, ~10 Hz) ─────────────────────────────────────
 *   position RTK auto (serveur)
 *        -> cible = auto + offset (recul + hauteur)
 *        -> vecteur Nord/Est (drone -> cible), en metres
 *        -> map : Est=vx, Nord=vz, Haut=vy  (convention TraductionAxes)
 *        -> gain P borne  (vitesse proportionnelle a l'ecart, plafonnee)
 *        -> TraductionAxes.versDji(cap drone)  -> pitch/roll/throttle/yaw BODY
 *        -> bornage final -> envoyerVitesses
 *
 * ── SECURITE (NON NEGOCIABLE — vol reel qui suit un vehicule) ─────────────────
 *   - vitesses PLAFONNEES (V_MAX_HORIZ, V_MAX_VERT) : jamais de commande brusque.
 *   - FAILSAFE position RTK : si la derniere position auto date de plus de
 *     RTK_AGE_MAX_S (perte 4G/fix), on passe en HOVER immediat (le drone ne suit
 *     pas une position perimee -> il ne fonce pas vers un fantome).
 *   - FAILSAFE distance : si le drone est a plus de DIST_MAX_M de la cible
 *     (decrochage), on HOVER et on alerte (evite une course-poursuite non maitrisee).
 *   - FAILSAFE altitude : throttle bloque au-dela de ALT_MAX_M AGL.
 *   - qualite RTK : si le fix auto n'est pas FIX/FLOAT (juste GPS ou LOST), le
 *     suivi reste possible mais SIGNALE (precision degradee) ; l'operateur decide.
 *   - ARRET D'URGENCE : hover immediat + VirtualStick coupe -> la RC reprend la main.
 *   - gpsValide() du drone requis pour armer le suivi.
 *
 * ⚠ A FAIRE OBLIGATOIREMENT : espace degage, drone A VUE, telecommande DJI a
 * portee, arret d'urgence accessible. Premier test : auto A L'ARRET, on verifie
 * que le drone se cale a l'offset et tient position, AVANT de faire rouler l'auto.
 */
class Phase3Activity : AppCompatActivity() {

    // ObstacleSafetyGate étape 1 — MODE MIROIR pour PHASE 3 : le gate est CALCULE et JOURNALISE
    // (tags PERCEPTION_SAMPLE / GATE_MIRROR) mais JAMAIS applique (cmd_a_envoyer == cmd_assainie).
    // true = miroir actif ; false = Off. Seul ecran en Mirror.
    // RETOUR SECURITAIRE : false par defaut. Passer a true UNIQUEMENT le temps d'un essai
    // d'observation (sol simulateur ou vol stationnaire), puis remettre a false avant commit.
    private val OBSTACLE_GATE_MIROIR_ACTIF = false
    // ORDRE D'INIT : lecteurPerception DOIT etre declare AVANT pont (le wiring Mirror capture
    // lecteurPerception::snapshotPourGate ; les proprietes s'initialisent dans l'ordre du fichier).
    private val lecteurPerception = LecteurPerception()
    private val pont = PontDjiReel(
        if (OBSTACLE_GATE_MIROIR_ACTIF)
            ca.cineflight.stage.control.ObstacleGateWiring.Mirror(
                snapshotProvider = lecteurPerception::snapshotPourGate, source = "PHASE3")
        else ca.cineflight.stage.control.ObstacleGateWiring.Off
    )
    private var flux: FluxCamera? = null    // flux video live du drone (meme classe que le cockpit)

    // ── YOLO : CONFIRMATION VISUELLE + CADRAGE FIN (couche PAR-DESSUS le GNSS) ───
    // YOLO tourne sur le flux du drone (frames NV21, independant de l'affichage
    // FluxCamera) et CONFIRME la voiture (classes COCO 2/3/5/7). Il sert
    // UNIQUEMENT a : (1) afficher que la voiture est bien vue, (2) affiner le
    // POINTAGE camera (yaw + nacelle) pour la centrer dans l'image.
    // IL NE DEPLACE JAMAIS LE DRONE : la translation reste 100% pilotee par le
    // GNSS/RTK. Corrections BORNEES, et actives seulement si UNE seule voiture est
    // vue avec une confiance suffisante ; sinon on retombe sur le cadrage GNSS.
    private val yolo = YOLO11Ncnn()
    private var yoloSuivi: YoloSuivi? = null
    @Volatile private var yoloVoitureVue = false   // une voiture est-elle vue MAINTENANT ?
    @Volatile private var yoloCx = 0.5f            // centre X normalise (0..1) de la voiture
    @Volatile private var yoloCy = 0.5f            // centre Y normalise (0..1)
    @Volatile private var yoloConf = 0f            // confiance 0..1 de la detection choisie
    @Volatile private var yoloNbVoit = 0           // nb de vehicules vus dans la frame
    @Volatile private var yoloVueMs = 0L           // horodatage (ms) de la derniere detection

    // -- SUIVI CAMERA (sujet SANS boitier RTK, suivi par la camera / YOLO) --
    @Volatile private var yoloHauteur = 0f     // hauteur de boite normalisee (0..1), proxy de distance
    private val moteurVision = SuiviVisionYolo()
    @Volatile private var suiviVision = false          // true = mode camera (sans boitier)
    @Volatile private var gimbalVisionDeg = Double.NaN // angle nacelle accumule (mode camera)
    // Deplacement (avance/recul) en mode camera : COUPE par defaut. A activer SEULEMENT
    // apres validation en vol sur un drone a capteurs omni (Mini 4 Pro). Mini 3 = jamais.
    private val reglagesP3 by lazy { ca.cineflight.stage.control.Reglages(this) }
    // Deplacement camera : lu depuis les Reglages (defaut OFF). Sans evitement complet -> reste en pivot.
    private val AUTORISER_DEPLACEMENT_VISION get() = reglagesP3.getDeplacementCamera()
    private val VISION_CENTRE_AVANCE = 0.15f           // avance seulement si le sujet est bien centre en X
    @Volatile private var enVol = false
    @Volatile private var vsActif = false
    @Volatile private var suiviActif = false
    // MODE MANUEL : quand true, l'app LACHE le drone -> c'est la telecommande DJI
    // (le pilote) qui commande. VirtualStick est coupe. On peut reprendre le controle.
    @Volatile private var modeManuel = false

    // --- Position RTK de l'auto (rafraichie par un poller reseau) ---
    @Volatile private var autoLat = Double.NaN
    @Volatile private var autoLon = Double.NaN
    @Volatile private var autoCap = Double.NaN     // deg boussole (0=N, 90=E)
    @Volatile private var autoVitesseKmh = Double.NaN
    @Volatile private var autoRtk = "—"            // FIX/FLOAT/GPS/LOST (verdict serveur)
    @Volatile private var autoRtkBrut = "—"        // etat brut du module (avant peremption serveur)
    @Volatile private var autoSats = -1            // satellites vus (si le serveur le renvoie), -1 = inconnu
    @Volatile private var autoHacc = Double.NaN    // precision horizontale (m), si dispo
    @Volatile private var autoAgeS = 999.0         // age de la derniere position auto (s)
    @Volatile private var reseauOk = false

    private var jobPilote: Job? = null
    private var jobReseau: Job? = null

    private lateinit var voyant: TextView
    private lateinit var txtTelemetrie: TextView
    private lateinit var pastilleRtk: TextView   // badge etat RTK (mode RTK seulement)
    private lateinit var txtEtat: TextView
    private lateinit var txtSuivi: TextView
    private lateinit var txtParcours: TextView
    private lateinit var btnManuel: Button
    private lateinit var btnVision: Button
    private lateinit var btnSimPhase3: Button
    private val SIM_LAT_P3 = 45.5
    private val SIM_LON_P3 = -73.56

    // ── PARCOURS choisi (trace enregistree sur le serveur) ──────────────────────
    // L'utilisateur choisit une trace du serveur (bouton "Choisir un parcours"),
    // puis lance le suivi RTK avec le bouton existant "Demarrer le suivi".
    @Volatile private var traceChoisieId: String? = null
    @Volatile private var traceChoisieTitre: String? = null
    @Volatile private var traceChoisie: ca.cineflight.stage.cine.ClientTraces.TraceComplet? = null

    // ── PARAMETRES OFFSET (position du drone par rapport a l'auto) ──────────────
    // ETAPE 1 (offset RELATIF AU CAP) : le drone se cale RECUL metres DERRIERE
    // l'auto le long de son cap (donc toujours derriere, quel que soit le sens de
    // route), + un DECALAGE LATERAL optionnel (cote gauche/droite), + une HAUTEUR
    // au-dessus. Remplace l'ancien offset fixe plein SUD (qui mettait le drone
    // DEVANT l'auto quand elle roulait vers le sud).
    //
    // Repli sur : si le cap auto est invalide (auto a l'arret -> heading GNSS non
    // fiable), on reutilise le DERNIER cap valide connu ; si aucun cap n'a jamais
    // ete vu, on retombe sur un recul plein SUD (comportement V1, defini et sur).
    private val OFFSET_HAUTEUR_M get() = if (profilSujet == ProfilSujetMobile.MARCHE) 3.0 else 20.0     // au-dessus de l'auto (AGL cible)
    private val OFFSET_RECUL_M   get() = if (profilSujet == ProfilSujetMobile.MARCHE) 4.0 else 15.0     // recul DERRIERE l'auto, le long de son cap (m)
    private val OFFSET_LATERAL_M = 0.0      // decalage sur le cote (m). 0 = pile derriere.
    private val OFFSET_COTE      = "droite" // "droite" | "gauche" (cote du decalage lateral)

    // Dernier cap auto VALIDE memorise (deg boussole). Sert de repli quand l'auto
    // est a l'arret et que le heading GNSS instantane n'est plus fiable.
    @Volatile private var dernierCapValideDeg = Double.NaN

    // ── ETAPE 2 : ANTICIPATION (moteur de prediction) ───────────────────────────
    // On REUTILISE le noyau valide DiagnosticPredictionRtk (pas de duplication),
    // configure pour un vehicule (profil AUTO). A chaque position recue on le
    // nourrit ; il renvoie la position ANTICIPEE du sujet (ou il VA etre) + sa
    // vitesse et son cap. Le drone vise alors cette position anticipee ET ajoute
    // la vitesse du sujet en feed-forward -> plus de retard elastique.
    //
    // Mettre ANTICIPATION_ACTIVE = false pour revenir au comportement Etape 1
    // (poursuite de la position brute), utile pour comparer en test.
    private val ANTICIPATION_ACTIVE = true
    // ══════════════════════════════════════════════════════════════════════════════
    // ⚠️ TEST E-01 AU SOL — HÉLICES PHYSIQUEMENT RETIRÉES OBLIGATOIRE ⚠️
    // UN SEUL flag maître pilote TOUT le test de signe du throttle. Mettre à false
    // pour revenir à l'état de production. NE JAMAIS laisser cet APK décoller.
    // Déclaré ICI (avant profilSujet) pour garantir l'ordre d'initialisation.
    // Effets quand true : (1) profil PERSONNE force, (2) auto-arme, (3) emission 2D
    // autorisee, (4) vMax=0.2, (5) gate obstacle ouvert, (6) throttle force a +0.2,
    // (7) chaque ligne de log ecrite dans un fichier sur le telephone.
    private val TEST_E01_SIGNE_THROTTLE = false   // ← SEUL commutateur. false = production.
    // ══════════════════════════════════════════════════════════════════════════════
    // ⚠️ TEST E-06/E-07/E-08/E-09 AU SOL — HÉLICES RETIRÉES ⚠️
    // Flag pour valider AU SOL que les SÉCURITÉS coupent l'émission :
    //   E-06/E-07 : perte YOLO/vidéo -> bit AC=0 -> throttle_emis=0
    //   E-08      : arrêt d'urgence -> bit EM=1 -> throttle_emis=0
    //   E-09      : perte liaison RC -> détectée + (via arretUrgence) coupe l'émission
    // Quand true : écrit des lignes de diagnostic dédiées (E06/E08/E09) dans le fichier
    // test_secu_e0x.log, et BRANCHE la détection RC dans Phase3 (absente en production).
    // Mettre à false pour revenir à l'état de production. Hélices retirées obligatoire.
    // IMPORTANT : pour observer la COUPURE d'une émission active, activer AUSSI
    // TEST_E01_SIGNE_THROTTLE=true (fournit le contexte d'émission au sol : profil
    // personne, auto-armement, boucle 2D, throttle forcé +0.2). Les logs E06/E08/E09
    // montreront alors que la sécurité ramène throttle_emis à 0.
    private val TEST_E06_09_SECU = false   // ← SEUL commutateur pour E-06..E-09. false = production.
    // ══════════════════════════════════════════════════════════════════════════════
    // Profil du sujet suivi, choisi au lancement (carte "personne" -> MARCHE ;
    // carte "vehicule" ou defaut -> AUTO). Lu paresseusement : l'intent est pret des onCreate.
    private val profilSujet: ProfilSujetMobile by lazy {
        // TEST E-01 : force le profil PERSONNE (MARCHE -> classeVision=PERSON) pour que
        // YOLO detecte des personnes en mode soccer, condition necessaire a l'emission 2D
        // (sinon detection = voiture -> soccerNbJoueurs reste 0 -> aucun log). Flag=false -> comportement normal.
        if (TEST_E01_SIGNE_THROTTLE) ProfilSujetMobile.MARCHE
        else if (intent?.getStringExtra("PROFIL_SUJET") == "MARCHE") ProfilSujetMobile.MARCHE
        else ProfilSujetMobile.AUTO
    }
    private val predicteur by lazy { DiagnosticPredictionRtk(profilSujet.configurationPrediction()) }
    @Volatile private var predPret = false          // prediction fiable cette frame ?
    @Volatile private var predLat = Double.NaN      // position ANTICIPEE du sujet
    @Volatile private var predLon = Double.NaN
    @Volatile private var predVitesseMps = Double.NaN
    @Volatile private var predCapDeg = Double.NaN
    @Volatile private var predHorizonS = 0.0        // a quel point on regarde devant (s)
    @Volatile private var predIncertitudeM = Double.NaN

    // ── ETAPE 3 : CADRAGE CAMERA (pointer la camera sur l'auto) ─────────────────
    // Deux mouvements coordonnes :
    //  - YAW : le drone PIVOTE pour que son nez (donc la camera) fasse face a l'auto.
    //    Rotation douce, proportionnelle a l'ecart de cap, bornee (cinematographique).
    //  - NACELLE : la camera s'incline VERS LE BAS selon la geometrie hauteur/distance,
    //    pour garder l'auto dans le cadre.
    // Mettre CADRAGE_ACTIVE = false pour revenir au comportement Etape 2 (yaw fixe).
    private val CADRAGE_ACTIVE       = true
    private val GAIN_YAW             = 1.2f    // deg/s de rotation par deg d'ecart
    private val YAW_MAX_DPS          = 35f     // vitesse de rotation max (doux)
    private val YAW_ZONE_MORTE_DEG   = 3.0     // en-deca, on ne tourne pas (anti-jitter)
    private val GIMBAL_PITCH_MIN     = -90f    // nacelle a la verticale vers le bas
    private val GIMBAL_PITCH_MAX     = 10f     // un peu au-dessus de l'horizon
    private val GIMBAL_SEUIL_MAJ_DEG = 1.0     // n'envoie la nacelle que si ca bouge de 1°
    @Volatile private var dernierGimbalPitch = Double.NaN

    // ── YOLO : reglages de la confirmation + cadrage fin (voir champs plus haut) ─
    // Tout est BORNE : meme si YOLO se trompe, il ne peut qu'appliquer une petite
    // correction de pointage, jamais un deplacement du drone.
    private val CONFIRM_YOLO       = true              // couche YOLO active ?
    private val YOLO_CONF_MIN      = 0.35f             // confiance mini pour se fier a YOLO
    private val YOLO_FRAIS_MS      = 600L              // detection consideree "fraiche" (ms)
    private val GAIN_YAW_YOLO      = 40f               // deg/s par unite d'ecart image (borne ↓)
    private val YAW_YOLO_MAX_DPS   = 10f               // le nudge yaw YOLO ne depasse jamais ca

    // ── SPORT SOCCER — MIROIR DE MOUVEMENT (Phase 9B) ────────────────────────────
    // false = comportement identique a aujourd'hui : le pipeline ne tourne pas.
    // true  = on OBSERVE seulement (log SOCCER_RAIL_MIRROR, soccer_motion_applied=false).
    // Ce flag est SEPARE du flag d'emission reelle (jamais actives implicitement ensemble).
    // Active par le mode SOCCER (extra MODE_SOCCER) : observation a l'ecran, aucun mouvement.
    // TEST E-01 : ouvre le miroir de mouvement (sinon observerMiroirMouvementSoccer, qui
    // contient le bloc d'emission 2D, n'est JAMAIS appele). Flag=false -> false comme avant.
    @Volatile private var SOCCER_RAIL_MIRROR_ENABLED = TEST_E01_SIGNE_THROTTLE
    private var txtSoccer: TextView? = null
    private var overlayYoloP3: OverlayYolo? = null
    @Volatile private var overlayYoloVisible = false
    private var btnOverlayYolo: Button? = null
    // ── SPORT SOCCER — EMISSION ARBITREE (Phase 9D) ──────────────────────────────
    // DOUBLE VERROU. Les deux doivent etre volontairement changes pour tout mouvement.
    //  1) SOCCER_RAIL_REAL_ENABLED : autorise l'emission de la commande soccer ;
    //  2) SOCCER_MAX_SPEED_MPS = 0 : meme flag actif, la vitesse horizontale est neutre.
    // Ces flags NE DOIVENT JAMAIS etre actives implicitement ensemble avec le miroir.
    private val SOCCER_RAIL_REAL_ENABLED = false
    private val SOCCER_MAX_SPEED_MPS = 0f          // Essai 1 : zero. Ne PAS augmenter avant Essai 2.
    // ── EMISSION REELLE 2D (realisateur) — MEME DOUBLE VERROU, INERTE PAR DEFAUT ──
    //  1) SOCCER_2D_REAL_ENABLED : autorise l'emission de la commande 2D (throttle seul) ;
    //  2) SOCCER_2D_MAX_VSPEED_MPS = 0 : meme flag actif, la vitesse verticale reste neutre.
    // Essai 1 : ALTITUDE SEULE (throttle). roll/pitch/yaw = 0. Ne PAS augmenter vMax avant
    // d'avoir verifie le SENS du throttle par un test statique au sol.
    // Flags derives du flag maitre TEST_E01_SIGNE_THROTTLE (declare plus haut, avant profilSujet).
    private val SOCCER_2D_REAL_ENABLED = TEST_E01_SIGNE_THROTTLE
    private val SOCCER_2D_MAX_VSPEED_MPS = if (TEST_E01_SIGNE_THROTTLE) 0.2f else 0f
    private val SOCCER_BATT_MIN_PCT = 40           // batterie mini operationnelle pour le mode soccer
    private val SOCCER_DIST_MAX_M = 200.0          // distance max operateur (decollage) <-> rail
    // ARMEMENT runtime : le mode soccer n'est "arme" que si l'operateur l'a active ET
    // que le flag reel l'autorise. Toute reprise pilote / arret d'urgence le desarme.
    // TEST E-01 : auto-arme le mode soccer quand le flag de test est actif (le bouton
    // d'armement n'est visible que si SOCCER_RAIL_REAL_ENABLED, hors scope du test 2D).
    // En production (flag=false) -> false comme avant. REMETTRE le flag a false apres le test.
    @Volatile private var soccerArme = TEST_E01_SIGNE_THROTTLE
    @Volatile private var soccerArretUrgence = false
    private var btnSoccer: Button? = null
    // NB : en Phase3 la cible YOLO est unique (yoloCx), on construit l'estimation
    // directement -> pas besoin de SoccerActionEstimator (mediane multi-joueurs) ici.
    private val soccerTracker = ca.cineflight.stage.sport.soccer.SoccerActionTracker()
    private val soccerMotion = ca.cineflight.stage.sport.soccer.RailMotionController(
        maxSpeedMps = SOCCER_MAX_SPEED_MPS, deadband = 0.05f, staleAfterMs = 600L,
    )
    // Rail : d'essai par defaut, REMPLACE par le profil SOCCER_RAIL charge du serveur
    // Web (chargerProfilSoccer) quand on entre en mode soccer.
    private var soccerRail = ca.cineflight.stage.sport.soccer.DroneRail(
        start = ca.cineflight.stage.sport.soccer.RailPoint(45.0, -73.0),
        end = ca.cineflight.stage.sport.soccer.RailPoint(45.0, -72.999),
    )
    // Profil rail complet charge du serveur (altitude/vitesse/safe/verdict/terrain), ou null.
    @Volatile private var soccerProfil: ca.cineflight.stage.sport.soccer.SoccerRailProfile? = null

    // ── MODE 2D (option complete) : le drone se deplace dans le TERRAIN, en retrait de
    // l'action. false = mode RAIL (defaut, le drone reste sur le rail). Les composants
    // 2D sont purs et observationnels tant que l'emission reelle n'est pas armee.
    @Volatile private var soccerMode2D = false
    private var btnModeSoccer: Button? = null
    private val soccerDirection = ca.cineflight.stage.sport.soccer.SoccerPlayDirection()
    private val soccer2DPlanner = ca.cineflight.stage.sport.soccer.Soccer2DPlanner()
    private val soccerAltitude = ca.cineflight.stage.sport.soccer.SoccerAltitudePlanner()
    // Pipeline 2D complet (sections 8-11) : anticipation -> controleur 2D -> camera -> plan.
    private val soccerAnticipation = ca.cineflight.stage.sport.soccer.SoccerAnticipation()
    private val soccer2DMotion = ca.cineflight.stage.sport.soccer.Soccer2DMotionController(
        maxSpeedMps = SOCCER_MAX_SPEED_MPS,   // 0 par defaut (double verrou) : observation
    )
    private val soccerCameraAim = ca.cineflight.stage.sport.soccer.SoccerCameraAim()
    private val soccerPlanWidth = ca.cineflight.stage.sport.soccer.SoccerPlanWidth()
    private val soccerPhase = ca.cineflight.stage.sport.soccer.SoccerPhase()
    private val soccerCadrage = ca.cineflight.stage.sport.soccer.SoccerCadrageScore()
    // Realisateur autonome : optimise altitude ET decalage lateral (grille 2D), plus le
    // zoom OPTIQUE si la camera en a un. Mini 4 Pro = zoom numerique seulement -> axe zoom
    // desactive (detection dynamique de capacite). Observation seule.
    private val soccerDirector = ca.cineflight.stage.sport.soccer.SoccerDirector(
        soccerCadrage,
        capaciteZoom = ca.cineflight.stage.sport.soccer.CapaciteZoom.MINI_4_PRO)
    private var soccerLateralM = 0.0   // decalage lateral courant propose (m), etat observe
    // Controleur altitude->throttle (Essai 1 emission reelle : altitude SEULE).
    private val soccerAltThrottle = ca.cineflight.stage.sport.soccer.SoccerAltitudeThrottle()
    // PUBLICATION ATOMIQUE du SafetySnapshot (NC-T2-001). Un unique point de capture des
    // signaux bruts -> snapshot immuable + numero de generation. Remplace les lectures
    // eparpillees de variables @Volatile aux deux sites d'emission (rail + 2D).
    private val soccerSnapshotFactory = ca.cineflight.stage.sport.soccer.SafetySnapshotFactory()
    // WATCHDOG de cycle (Phase 1.2). "Bat" a chaque iteration saine de la boucle pilote ;
    // au point d'emission 2D, si le dernier battement est trop vieux (boucle figee), le
    // throttle est force a 0. FAIL-CLOSED : tant qu'aucun battement, le cycle est non frais.
    private val soccerWatchdog = ca.cineflight.stage.sport.soccer.CommandeWatchdog()
    // WATCHDOG INDEPENDANT (REQ-WDG-001) : thread B STRICTEMENT independant du fil
    // d'emission. Si le battement cesse (gel TOTAL de la boucle, ce que le watchdog de
    // cycle ne peut pas detecter), il desarme le mode, neutralise la commande et demande
    // la sortie du Virtual Stick — depuis son propre fil vivant. Latch one-shot ; le
    // re-armement passe par la decision humaine d'armer a nouveau le mode (dialog).
    // L'EFFET physique reste a caracteriser par E-03 (limite documentee au dossier).
    private val soccerWatchdogIndep = ca.cineflight.stage.sport.soccer.WatchdogIndependant(
        onDefaillance = { ageMs -> mettreEnSecuriteDepuisWatchdogIndep(ageMs) }
    )
    // MULTI-JOUEURS (mode soccer) : la liste complete des personnes YOLO -> tracker -> centre
    // de groupe. Alimente le vrai nbJoueurs + centre d'action du realisateur (au lieu de 1).
    private val soccerPlayerTracker = ca.cineflight.stage.sport.soccer.PlayerTracker()
    // Dernier centre de groupe calcule (volatile : ecrit dans le callback YOLO, lu dans le bloc 2D).
    @Volatile private var soccerNbJoueurs = 0
    @Volatile private var soccerCentreCx = 0.5f
    @Volatile private var soccerCentreCy = 0.5f
    @Volatile private var soccerConfMoyenne = 0f
    // Position simulee du drone dans le terrain (mode 2D observation) : centre au depart.
    @Volatile private var soccer2DPosX = 0.5f
    @Volatile private var soccer2DPosY = 0.5f
    // Repli si la position GNSS est indisponible (NaN) : centre du rail. La position
    // REELLE est calculee par projection GNSS (passe E) via positionDroneSurRail().
    private val SOCCER_RAIL_POS_REPLI = 0.5f
    private val GAIN_GIMBAL_YOLO   = 30f               // deg par unite d'ecart image (borne ↓)
    private val GIMBAL_YOLO_MAX    = 12f               // le nudge nacelle YOLO ne depasse jamais ca
    private val CLASSES_VOITURE    = setOf(2, 3, 5, 7) // COCO : voiture, moto, bus, camion
    // classes YOLO suivies selon le profil : personne (0) ou vehicules (voiture/moto/bus/camion).
    private val classesSuivi: Set<Int> get() = if (profilSujet.classeVision == "PERSON") setOf(0) else CLASSES_VOITURE

    // ── ETAPE 4 : DOCTRINE DE SECURITE RTK (fail-closed) ────────────────────────
    // FIX   : precision centimetrique -> suivi COMPLET (translation + altitude + yaw).
    // FLOAT : precision degradee -> translation VERROUILLEE : le drone TIENT sa
    //         position (il ne fonce pas vers une position imprecise). Le cadrage
    //         nacelle continue (inoffensif).
    // GPS / LOST / inconnu -> BLOQUE : gel total (hover). "Non defini = non sur."
    // AUTORISER_SUIVI_GPS = true assouplit : GPS tolere et traite comme FLOAT.
    // Par defaut false = doctrine stricte (recommande pour un vol pres d'un vehicule).
    private val AUTORISER_SUIVI_GPS = false

    // ── MOUVEMENTS CINEMATOGRAPHIQUES (changement en direct) ────────────────────
    // Quand un mouvement est actif, la cible du drone est calculee AUTOUR de la
    // voiture par GenerateurMouvement (orbite, travelling, reveal, rapproche), au
    // lieu du simple "derriere la voiture". On peut changer de mouvement a tout
    // moment pendant le vol ; la transition est douce (plafonds de vitesse).
    private val generateurMouv = GenerateurMouvement()
    @Volatile private var mouvementActif: GenerateurMouvement.TypeMouvement? = null  // null = suivi simple
    // Position du drone par rapport a la voiture (suivi simple, mouvementActif=null) :
    // derriere | devant | gauche | droite | plongee.
    @Volatile private var positionSuivi = "derriere"
    // Azimut courant du drone autour de la voiture (deg, relatif au cap : 0=avant,
    // 90=droite, 180=arriere, 270=gauche). Anime en douceur lors d'un changement de
    // position -> le drone CONTOURNE par l'arriere au lieu de passer au-dessus.
    // NaN = se recaler d'un coup sur la position cible (pas d'arc).
    @Volatile private var azimutCourantDeg = Double.NaN
    @Volatile private var phaseMouv = 0.0        // avancement du mouvement (0->1)
    private val DUREE_MOUV_S = 20.0              // duree d'un mouvement (orbite/reveal/rapproche)
    private val DIST_MOUV_M  get() = if (profilSujet == ProfilSujetMobile.MARCHE) 4.0 else 20.0             // distance drone-voiture pendant un mouvement

    // ── LIMITES DE SECURITE ─────────────────────────────────────────────────────
    private val V_MAX_HORIZ = 4.0f          // m/s horizontal max (doux et sur)
    private val V_MAX_VERT   = 2.0f         // m/s vertical max
    private val GAIN_P       = 0.6f         // gain proportionnel (vitesse = P * ecart)
    private val ZONE_MORTE_M = 1.0          // en-deca, on ne bouge pas (anti-oscillation)
    private val RTK_AGE_MAX_S = 2.0         // au-dela : HOVER (position perimee)
    private val DIST_MAX_M   = 120.0        // decrochage : HOVER si drone trop loin
    private val ALT_MAX_M    = 60.0         // plafond AGL

    // Serveur relais RTK (meme hote que CineFlight)
    private val URL_RTK = "http://161.35.188.68:8095/api/rtk/sujet"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        racine.addView(TextView(this).apply {
            text = if (intent?.getBooleanExtra("MODE_SOCCER", false) == true)
                getString(R.string.p3_titre_soccer) else getString(R.string.p3_titre)
            setTextColor(Color.WHITE); textSize = 18f
            setPadding(0, 0, 0, dp(12))
        })

        voyant = TextView(this).apply {
            text = getString(R.string.p3_verification)
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(0xFF7A5900.toInt())
        }
        racine.addView(voyant, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── Barre d'infos du drone (comme l'ecran d'ouverture) ──────────────────
        // Batterie · altitude · signal GPS · cap · position · modele, en direct.
        txtTelemetrie = TextView(this).apply {
            text = "🔋 --  ·  ⛰ --  ·  📡 --  ·  🧭 --"
            setTextColor(0xFFE6EDF3.toInt()); textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF10233A.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        racine.addView(txtTelemetrie, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── Badge etat RTK : visible SEULEMENT en mode RTK (boitier). Couleur = feu. ──
        pastilleRtk = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = android.view.View.GONE
            setOnClickListener { afficherDetailRtk() }
        }
        racine.addView(pastilleRtk, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        txtEtat = TextView(this).apply {
            setTextColor(0xFFE6EDF3.toInt()); textSize = 14f
            setPadding(0, dp(10), 0, dp(6))
        }
        racine.addView(txtEtat)

        txtSuivi = TextView(this).apply {
            setTextColor(0xFF9FD0FF.toInt()); textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        racine.addView(txtSuivi)

        // Ligne qui montre le PARCOURS choisi (trace serveur), ou "suivi direct".
        // Specifique au suivi de vehicule -> masquee en mode soccer.
        txtParcours = TextView(this).apply {
            setTextColor(0xFFB9F6CA.toInt()); textSize = 13f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF152033.toInt())
            if (intent?.getBooleanExtra("MODE_SOCCER", false) == true) visibility = android.view.View.GONE
        }
        racine.addView(txtParcours, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        majParcoursLabel()

        fun bouton(txt: String, couleur: Int, action: () -> Unit) = Button(this).apply {
            text = txt; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            setOnClickListener { action() }
        }

        // MODE SOCCER : on masque les controles specifiques au suivi de vehicule
        // (parcours, demarrer suivi, vision, positions/mouvements de plan). On garde le
        // vol essentiel, la telemetrie/RTK, et les controles soccer.
        val estModeSoccer = intent?.getBooleanExtra("MODE_SOCCER", false) == true
        val GONE = android.view.View.GONE

        // MENU : choisir un parcours enregistre sur le serveur (avant de lancer le suivi).
        val btnParcours = bouton(getString(R.string.p3_btn_choisir_parcours), 0xFF00695C.toInt()) { choisirParcours() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) }
            if (estModeSoccer) visibility = GONE
        }
        racine.addView(btnParcours)

        racine.addView(bouton(getString(R.string.p3_btn_decoller), 0xFF1565C0.toInt()) { confirmerVol(getString(R.string.ma_dec_titre), getString(R.string.ma_dec_msg), getString(R.string.ma_dec_oui), false) { decoller() } }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) }
        })

        val btnDemarrer = bouton(getString(R.string.p3_btn_demarrer), 0xFF2E7D32.toInt()) { demarrerSuivi() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) }
            if (estModeSoccer) visibility = GONE
        }
        racine.addView(btnDemarrer)

        btnVision = bouton(getString(R.string.p3_btn_vision_off), 0xFF6A1B9A.toInt()) { basculerVision() }
        racine.addView(btnVision.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
            if (estModeSoccer) visibility = GONE
        })
        btnSimPhase3 = bouton(getString(R.string.p3_btn_sim_off), 0xFF455A64.toInt()) { basculerSimulateur() }
        racine.addView(btnSimPhase3.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
            if (estModeSoccer) visibility = GONE
        })
        // Ouvert en "camera seule" ? on demarre directement en mode vision (sans boitier).
        // Suivi de VEHICULE (profil AUTO) : le boitier de suivi est OBLIGATOIRE ->
        // on retire l'option camera pour eviter tout basculement sans boitier.
        if (profilSujet != ProfilSujetMobile.MARCHE) {
            btnVision.visibility = android.view.View.GONE
        }
        if (intent?.getBooleanExtra("MODE_VISION", false) == true) { suiviVision = true; majBoutonVision() }

        // MODE SOCCER : active l'observation (miroir) et affiche un panneau lisible a l'ecran.
        // Aucun mouvement automatique : le pipeline calcule et affiche seulement.
        if (intent?.getBooleanExtra("MODE_SOCCER", false) == true) {
            SOCCER_RAIL_MIRROR_ENABLED = true
            // SOCCER = 100% YOLO, AUCUN boitier. On force le mode "sans boitier" (comme le
            // mode camera) : cela masque le badge RTK et toute la logique/affichage boitier.
            suiviVision = true
            chargerProfilSoccer()   // recupere le rail dessine dans le Web (sinon rail d'essai)
            txtSoccer = TextView(this).apply {
                text = "⚽ SOCCER — en attente de détection…"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC1B5E20.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            racine.addView(txtSoccer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            // Bouton pour AFFICHER / MASQUER les detections YOLO sur la video.
            btnOverlayYolo = bouton("Afficher les détections", 0xFF455A64.toInt()) { basculerOverlayYolo() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
            }
            racine.addView(btnOverlayYolo)
            // Bouton pour CHOISIR le rail parmi ceux enregistres sur le serveur.
            racine.addView(bouton("Choisir le rail", 0xFF1565C0.toInt()) { choisirRailSoccer() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
            })
            // Bouton BASCULE mode : Rail (defaut) <-> 2D terrain (drone en retrait).
            btnModeSoccer = bouton("Mode : RAIL", 0xFF455A64.toInt()) { basculerModeSoccer() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
            }
            racine.addView(btnModeSoccer)
        }

        // Bouton DIFFUSER EN DIRECT : ouvre l'ecran Live (YouTube / regie), le meme que
        // le bouton LIVE de l'ecran principal. Present dans TOUS les modes de suivi
        // (personne avec/sans boitier, vehicule, soccer). Diffusion independante du suivi.
        racine.addView(bouton("🔴 Diffuser en direct", 0xFFC62828.toInt()) {
            startActivity(android.content.Intent(this, LiveStreamActivity::class.java))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
        })

        // 🎬 ANGLE / MOUVEMENT : change en direct pendant le suivi. (Vehicule seulement.)
        racine.addView(TextView(this).apply {
            text = getString(R.string.p3_section_angle)
            setTextColor(0xFFB0BEC5.toInt()); textSize = 12f
            setPadding(dp(2), dp(14), 0, dp(4))
            if (estModeSoccer) visibility = GONE
        })
        // 4 boutons par rangee. Boutons un peu plus HAUTS + moins de marge interne
        // pour que le texte (icone + mot, jusqu'a 2 lignes) reste bien lisible dans
        // le panneau etroit. S'applique aux 8 boutons (angles + mouvements).
        fun poidsBtn() = LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        fun boutonPos(txt: String, pos: String, couleur: Int) =
            bouton(txt, couleur) { choisirPosition(pos) }.apply {
                textSize = 13f; maxLines = 2; setPadding(dp(2), dp(4), dp(2), dp(4)); layoutParams = poidsBtn()
            }
        fun boutonMouv(txt: String, mv: GenerateurMouvement.TypeMouvement, couleur: Int) =
            bouton(txt, couleur) { choisirMouvement(mv) }.apply {
                textSize = 13f; maxLines = 2; setPadding(dp(2), dp(4), dp(2), dp(4)); layoutParams = poidsBtn()
            }
        // Rangee 1 : ANGLES fixes autour de la voiture.
        val rangeeM1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rangeeM1.addView(boutonPos(getString(R.string.p3_angle_derriere), "derriere", 0xFF37474F.toInt()))
        rangeeM1.addView(boutonPos(getString(R.string.p3_angle_devant), "devant", 0xFF4E342E.toInt()))
        rangeeM1.addView(boutonPos(getString(R.string.p3_angle_gauche), "gauche", 0xFF37474F.toInt()))
        rangeeM1.addView(boutonPos(getString(R.string.p3_angle_droite), "droite", 0xFF37474F.toInt()))
        if (estModeSoccer) rangeeM1.visibility = GONE
        racine.addView(rangeeM1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        // Rangee 2 : VUE PLONGEE + mouvements cinematographiques.
        val rangeeM2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rangeeM2.addView(boutonPos(getString(R.string.p3_angle_plongee), "plongee", 0xFF4527A0.toInt()))
        rangeeM2.addView(boutonMouv(getString(R.string.p3_mouv_orbite), GenerateurMouvement.TypeMouvement.ORBITE, 0xFF00695C.toInt()))
        rangeeM2.addView(boutonMouv(getString(R.string.p3_mouv_reveal), GenerateurMouvement.TypeMouvement.REVEAL, 0xFF00695C.toInt()))
        rangeeM2.addView(boutonMouv(getString(R.string.p3_mouv_rapproche), GenerateurMouvement.TypeMouvement.RAPPROCHE, 0xFF00695C.toInt()))
        if (estModeSoccer) rangeeM2.visibility = GONE
        racine.addView(rangeeM2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        racine.addView(bouton(getString(R.string.p3_btn_arreter), 0xFF6D4C41.toInt()) { arreterSuivi() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
            if (estModeSoccer) visibility = GONE
        })

        // MODE MANUEL : rend le pilotage a la telecommande DJI (l'app lache le drone).
        // Rebascule dans l'autre sens pour reprendre le controle par l'app (hover).
        btnManuel = bouton(getString(R.string.p3_btn_manuel), 0xFF5D4037.toInt()) { basculerModeManuel() }
        racine.addView(btnManuel.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
        })
        racine.addView(TextView(this).apply {
            text = getString(R.string.p3_manuel_aide)
            textSize = 11f; setTextColor(0xFF90A4AE.toInt()); setPadding(dp(2), dp(4), dp(2), 0)
        })

        racine.addView(bouton(getString(R.string.p3_btn_atterrir), 0xFF455A64.toInt()) { confirmerVol(getString(R.string.ma_atter_titre), getString(R.string.ma_atter_msg), getString(R.string.ma_atter_oui), false) { atterrir() } }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
        })

        racine.addView(bouton(getString(R.string.p3_btn_urgence), 0xFFC62828.toInt()) { confirmerVol(getString(R.string.ma_urg_titre), getString(R.string.p3_urg_msg), getString(R.string.ma_urg_oui), true) { arretUrgence() } }.apply {
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(18) }
        })

        // BOUTON MODE SOCCER (armer/desarmer). Visible uniquement si l'emission reelle est
        // compilee active ; sinon inutile (le mode ne peut de toute facon pas s'appliquer).
        if (SOCCER_RAIL_REAL_ENABLED) {
            btnSoccer = bouton("Mode SOCCER : désarmé", 0xFF455A64.toInt()) { basculerArmementSoccer() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
            }
            racine.addView(btnSoccer)
        }
        racine.addView(TextView(this).apply {
            text = getString(R.string.p3_urgence_aide)
            textSize = 11f; setTextColor(0xFF90A4AE.toInt()); setPadding(dp(2), dp(6), dp(2), 0)
        })

        // ── AFFICHAGE COMPLET : VIDEO LIVE (gauche) + CONTROLES (panneau droite) ──
        // Meme flux que le cockpit (FluxCamera). En paysage : la video prend le plus
        // gros de l'ecran, les controles sont un panneau defilant a droite.
        val surfaceFlux = SurfaceView(this)
        flux = FluxCamera(surfaceFlux)
        // Overlay YOLO superpose a la video (masque par defaut). En mode soccer, un bouton
        // permet de l'AFFICHER/MASQUER. La video et l'overlay partagent le meme cadre.
        overlayYoloP3 = OverlayYolo(this).apply { visibility = android.view.View.GONE }
        val cadreVideo = FrameLayout(this).apply {
            addView(surfaceFlux, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlayYoloP3, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val panneauControles = ScrollView(this).apply { addView(racine) }
        val ecran = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF000000.toInt())
            addView(cadreVideo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
            addView(panneauControles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        }
        setContentView(ecran)

        EnregistrementSdk.enregistrer(applicationContext) { ok, _ ->
            runOnUiThread {
                if (ok) {
                    try { pont.initialiserListeners() } catch (_: Exception) {}
                    // SECURITE E-09 (correction, ACTIVE EN PRODUCTION) : détecte la perte de
                    // liaison radiocommande et déclenche l'arrêt d'urgence (coupe l'émission
                    // soccer + désarme). En complément du failsafe DJI natif. Le log fichier
                    // n'est écrit que sous TEST_E06_09_SECU (logSecuTest est gardé par le flag).
                    try {
                        pont.obsConnexionRc = { connecte ->
                            logSecuTest("E09 ts=${System.currentTimeMillis()} rc_connecte=$connecte estConnecte=${try { pont.estConnecte() } catch (_: Throwable) { false }} soccerArme=$soccerArme")
                            if (!connecte) runOnUiThread { try { arretUrgence() } catch (_: Throwable) {} }
                        }
                    } catch (_: Throwable) {}
                    try { flux?.demarrer() } catch (_: Exception) {}   // relie la video live
                    try { demarrerYolo() } catch (_: Exception) {}     // confirmation + cadrage fin
                    try { lecteurPerception.demarrer() } catch (_: Exception) {}  // etat capteurs (mode camera)
                }
            }
        }

        // ── POLLER RESEAU : recupere la position RTK de l'auto (~5 Hz) ──────────
        jobReseau = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                lirePositionAuto()
                delay(200)   // 5 Hz
            }
        }

        // ── BOUCLE PILOTE : envoie la commande de suivi (ou hover) a ~10 Hz ─────
        jobPilote = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                // ══ TEST E-01 AU SOL — HÉLICES RETIRÉES ══════════════════════════════════
                // Appelle DIRECTEMENT le pipeline soccer 2D (observerMiroirMouvementSoccer),
                // en sautant tickSuivi() qui exige un contexte voiture RTK absent en mode soccer.
                // But : observer le SIGNE du throttle au sol. NE JAMAIS voler avec ce build
                // (throttle force a +0.2 = montee continue). Flag=false -> code normal.
                if (TEST_E01_SIGNE_THROTTLE && soccerMode2D) {
                    try { observerMiroirMouvementSoccer(commandSent = "TEST_E01_SOL") } catch (_: Throwable) {}
                    soccerWatchdog.battement(System.nanoTime())
                    soccerWatchdogIndep.battement()   // REQ-WDG-001 : battement vers le thread B
                    delay(100)
                    continue
                }
                // ═════════════════════════════════════════════════════════════════════════
                if (vsActif) {
                    if (suiviActif && enVol) {
                        if (suiviVision) tickSuiviVision() else tickSuivi()
                    } else if (enVol) {
                        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {}  // hover
                    }
                }
                // WATCHDOG (Phase 1.2) : battement de fin d'iteration SAINE. Si la boucle se
                // fige, ce battement cesse ; l'emission 2D forcera alors le throttle a 0.
                soccerWatchdog.battement(System.nanoTime())
                // WATCHDOG INDEPENDANT (REQ-WDG-001) : meme battement publie vers le
                // thread B — si CETTE boucle gele totalement, B le detecte et met en
                // securite depuis son propre fil (desarme + neutre + sortie VS).
                soccerWatchdogIndep.battement()
                delay(100)   // 10 Hz
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) { rafraichir(); delay(400) }
        }
    }

    // ── RESEAU : GET /api/rtk/sujet ─────────────────────────────────────────────
    private fun lirePositionAuto() {
        try {
            val c = (URL(URL_RTK).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 1500; readTimeout = 1500
            }
            val code = c.responseCode
            if (code == 200) {
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(txt)
                // Champs renvoyes par le serveur (GET /api/rtk/sujet) :
                //   present, lat, lon, alt_m, rtk (FIX/FLOAT/GPS/LOST), age_s, hdg,
                //   ground_speed_mps, heading_deg, heading_valid, trace.
                val present = j.optBoolean("present", false)
                if (!present || j.isNull("lat") || j.isNull("lon")) {
                    reseauOk = false; autoAgeS = 999.0
                } else {
                    autoLat = j.optDouble("lat", Double.NaN)
                    autoLon = j.optDouble("lon", Double.NaN)
                    autoRtk = j.optString("rtk", "—")
                    autoRtkBrut = j.optString("rtk_brut", autoRtk)
                    // Champs de diagnostic (le serveur peut les relayer ou non) :
                    autoSats = if (j.isNull("num_sv")) j.optInt("satellites", -1) else j.optInt("num_sv", -1)
                    autoHacc = if (j.isNull("hacc_m")) Double.NaN else j.optDouble("hacc_m", Double.NaN)
                    autoAgeS = if (j.isNull("age_s")) 999.0 else j.optDouble("age_s", 999.0)
                    // cap : privilegie heading_deg (vehicule) s'il est valide, sinon hdg.
                    val headingValid = j.optBoolean("heading_valid", false)
                    autoCap = when {
                        headingValid && !j.isNull("heading_deg") -> j.optDouble("heading_deg", Double.NaN)
                        !j.isNull("hdg") -> j.optDouble("hdg", Double.NaN)
                        else -> Double.NaN
                    }
                    // ETAPE 1 : memoriser le cap tant qu'il est FIABLE (heading_valid).
                    // On n'accepte PAS le hdg de secours ici : au repos il est du bruit.
                    // Ce dernier cap valide sert de repli quand l'auto s'arrete.
                    if (headingValid && !j.isNull("heading_deg")) {
                        val hd = j.optDouble("heading_deg", Double.NaN)
                        if (hd.isFinite()) dernierCapValideDeg = hd
                    }
                    // vitesse sol en m/s -> km/h pour l'affichage.
                    val gs = if (j.isNull("ground_speed_mps")) Double.NaN
                             else j.optDouble("ground_speed_mps", Double.NaN)
                    autoVitesseKmh = if (gs.isNaN()) Double.NaN else gs * 3.6
                    reseauOk = !autoLat.isNaN() && !autoLon.isNaN()

                    // ETAPE 2 : nourrir le moteur de prediction avec cette position.
                    // fiable = fix RTK exploitable (FIX/FLOAT) ; timestamp derive de l'age.
                    val q = autoRtk.uppercase()
                    val fiable = reseauOk && (q == "FIX" || q == "FLOAT")
                    val ageP = if (autoAgeS.isFinite()) autoAgeS else Double.POSITIVE_INFINITY
                    val tsMs = System.currentTimeMillis() - (ageP.coerceAtLeast(0.0) * 1000.0).toLong()
                    val etat = predicteur.mettreAJour(
                        lat = autoLat, lon = autoLon, ageS = autoAgeS,
                        fiable = fiable, qualiteRtk = autoRtk, timestampMs = tsMs
                    )
                    majPrediction(etat)
                }
            } else {
                reseauOk = false
                majPredictionIndispo("HTTP_$code")
            }
            c.disconnect()
        } catch (_: Exception) {
            reseauOk = false
            autoAgeS = 999.0   // reseau coupe -> position consideree perimee
            majPredictionIndispo("ERREUR_RESEAU")
        }
    }

    // ETAPE 2 : recopie les sorties du moteur de prediction dans les champs lus
    // par la boucle pilote (thread-safe : mettreAJour est @Synchronized cote moteur,
    // et ces champs sont @Volatile).
    private fun majPrediction(etat: DiagnosticPredictionRtk.EtatDiagnostic) {
        predPret = etat.pret && etat.latPredite != null && etat.lonPredite != null
        predLat = etat.latPredite ?: Double.NaN
        predLon = etat.lonPredite ?: Double.NaN
        predVitesseMps = etat.vitesseMps ?: Double.NaN
        predCapDeg = etat.capDeg ?: Double.NaN
        predHorizonS = etat.horizonS
        predIncertitudeM = etat.incertitudeM ?: Double.NaN
    }

    private fun majPredictionIndispo(raison: String) {
        try { predicteur.signalerIndisponible(raison, autoRtk) } catch (_: Exception) {}
        predPret = false
        predLat = Double.NaN; predLon = Double.NaN
        predVitesseMps = Double.NaN; predCapDeg = Double.NaN
        predIncertitudeM = Double.NaN
    }

    // ── UN TICK DE SUIVI : calcule et envoie la commande VirtualStick ───────────
    /** Plafond horizontal (m/s) adapte au drone connecte : moins il a de capteurs, plus il est lent. */
    private fun vMaxHorizSelonDrone(): Double {
        val evit = try { CapacitesDrone.analyser(pont.modeleDrone()).evitement } catch (_: Throwable) { CapacitesDrone.Evitement.AUCUN }
        return when (evit) {
            CapacitesDrone.Evitement.COMPLET -> 15.0   // Mini 4 Pro : suit un vehicule rapide (~54 km/h). Reserver aux zones degagees.
            CapacitesDrone.Evitement.PARTIEL -> 6.0     // capteurs partiels : plafond modere
            CapacitesDrone.Evitement.AUCUN   -> 2.5     // aucun capteur : allure marche, tres prudent
        }
    }

    private fun tickSuivi() {
        // 1) FAILSAFE reseau/age : position auto perimee -> HOVER.
        if (!reseauOk || autoLat.isNaN() || autoLon.isNaN() || autoAgeS > RTK_AGE_MAX_S) {
            hover(); majSuivi(getString(R.string.p3_suivi_hover_age, autoAgeS))
            return
        }
        // 2) position du drone
        val dLat = pont.latitudeDrone(); val dLon = pont.longitudeDrone()
        val dAlt = pont.altitudeDrone()
        if (dLat.isNaN() || dLon.isNaN()) { hover(); majSuivi(getString(R.string.p3_suivi_hover_drone)); return }

        // 2bis) ETAPE 4 : DOCTRINE RTK (fail-closed). La qualite du fix decide de ce
        //        que le drone a le DROIT de faire.
        val q = autoRtk.uppercase()
        val modeRtk = when {
            q == "FIX"                        -> "FIX"     // suivi complet
            q == "FLOAT"                      -> "FLOAT"   // translation verrouillee
            AUTORISER_SUIVI_GPS && q == "GPS" -> "FLOAT"   // option : GPS tolere comme FLOAT
            else                              -> "BLOQUE"  // GPS/LOST/inconnu -> gel total
        }
        if (modeRtk == "BLOQUE") {
            hover(); majSuivi(getString(R.string.p3_suivi_arret_securite))
            return
        }
        val verrouTranslation = (modeRtk == "FLOAT")

        // 3) ANCRE DU SUJET (ETAPE 2) : position ANTICIPEE si la prediction est
        //    fiable (le sujet la ou il VA etre), sinon position brute (repli Etape 1).
        //    Puis offset RELATIF AU CAP autour de cette ancre : le drone se cale
        //    RECUL m DERRIERE l'auto le long de son cap (+ lateral, + hauteur).
        //    Repere boussole : 0=N, 90=E -> vecteur cap en (Est,Nord)=(sin,cos).
        val anticipe = ANTICIPATION_ACTIVE && predPret && predLat.isFinite() && predLon.isFinite()
        val sujLat = if (anticipe) predLat else autoLat
        val sujLon = if (anticipe) predLon else autoLon

        val mParDegLat = 111_320.0
        val mParDegLon = 111_320.0 * cos(Math.toRadians(sujLat))

        // Cap pour placer l'offset : cap PREDIT si on anticipe, sinon cap courant,
        // sinon dernier cap valide connu (repli auto a l'arret).
        val capEffectif = when {
            anticipe && predCapDeg.isFinite() -> predCapDeg
            autoCap.isFinite()                -> autoCap
            dernierCapValideDeg.isFinite()    -> dernierCapValideDeg
            else                              -> Double.NaN
        }

        // Offset selon la POSITION, avec TRANSITION SURE : quand on change de cote,
        // le drone CONTOURNE la voiture par l'ARRIERE (jamais au-dessus, jamais par
        // l'avant). L'azimut (relatif au cap : 0=avant, 90=droite, 180=arriere,
        // 270=gauche) est anime en douceur, a vitesse bornee pour ne pas decrocher.
        val offEst: Double
        val offNord: Double
        if (positionSuivi == "plongee") {
            azimutCourantDeg = Double.NaN     // en quittant la plongee, on se recalera
            offEst = 0.0; offNord = 0.0
        } else if (capEffectif.isFinite()) {
            val azCible = when (positionSuivi) {
                "devant" -> 0.0
                "droite" -> 90.0
                "gauche" -> 270.0
                else     -> 180.0             // derriere
            }
            // Init : partir de l'azimut REEL du drone (aucun saut), sinon 180 (arriere).
            if (azimutCourantDeg.isNaN()) {
                val bEst = (dLon - sujLon) * mParDegLon
                val bNord = (dLat - sujLat) * mParDegLat
                azimutCourantDeg = if (hypot(bEst, bNord) > 0.5)
                    (((Math.toDegrees(atan2(bEst, bNord)) - capEffectif) % 360.0) + 360.0) % 360.0
                else 180.0
            }
            // Vitesse de rotation SURE : le point cible ne va jamais plus vite que le
            // drone (sinon il couperait au lieu de suivre l'arc).
            val pasMax = Math.toDegrees((V_MAX_HORIZ * 0.6) / OFFSET_RECUL_M.coerceAtLeast(5.0)) * 0.1
            // Sens : rester dans l'ARRIERE [90..270] pour les cotes/arriere (jamais par l'avant).
            var d = ((azCible - azimutCourantDeg + 540.0) % 360.0) - 180.0
            if (azCible in 90.0..270.0 && azimutCourantDeg in 88.0..272.0) {
                d = azCible - azimutCourantDeg   // route directe DANS l'arc arriere (par 180)
            }
            azimutCourantDeg = (((azimutCourantDeg + d.coerceIn(-pasMax, pasMax)) % 360.0) + 360.0) % 360.0
            val dirRad = Math.toRadians(capEffectif + azimutCourantDeg)
            offEst  = OFFSET_RECUL_M * sin(dirRad)
            offNord = OFFSET_RECUL_M * cos(dirRad)
        } else {
            azimutCourantDeg = Double.NaN
            offEst = 0.0
            offNord = -OFFSET_RECUL_M
        }

        // 3bis) MOUVEMENT CINEMATOGRAPHIQUE : si un mouvement est actif, la cible est
        //       calculee AUTOUR de la voiture par GenerateurMouvement (orbite,
        //       travelling, reveal, rapproche). Sinon : suivi simple (offset derriere).
        val cibleLat: Double
        val cibleLon: Double
        val cibleAlt: Double
        val mv = mouvementActif
        if (mv != null) {
            // Avancer la phase 0->1. TRAVELLING = continu (offset fixe, pas de phase).
            if (mv != GenerateurMouvement.TypeMouvement.TRAVELLING) {
                phaseMouv += 0.1 / DUREE_MOUV_S            // ~0,1 s par tick (10 Hz)
                if (phaseMouv > 1.0)
                    phaseMouv = if (mv == GenerateurMouvement.TypeMouvement.ORBITE) 0.0 else 1.0
            }
            // Azimut de depart = DERRIERE la voiture (cap + 180). L'orbite balaie a partir de la.
            val azDepart = if (capEffectif.isFinite()) capEffectif + 180.0 else 180.0
            val params = GenerateurMouvement.Params(
                type = mv,
                distanceM = DIST_MOUV_M,
                hauteurM = OFFSET_HAUTEUR_M,
                azimutDepartDeg = azDepart,
                cote = OFFSET_COTE,
                distanceMinM = if (profilSujet == ProfilSujetMobile.MARCHE) 3.5 else 8.0,  // personne: cadrage 4 m (plancher dur noyau=3 m)
                altMaxM = ALT_MAX_M
            )
            val c = generateurMouv.cible(sujLat, sujLon, params, phaseMouv)
            cibleLat = c.lat; cibleLon = c.lon; cibleAlt = c.altM
        } else {
            cibleLat = sujLat + (offNord / mParDegLat)
            cibleLon = sujLon + (offEst / mParDegLon)
            cibleAlt = OFFSET_HAUTEUR_M
        }

        // 4) vecteur drone -> cible en METRES (Est, Nord)
        val dEst  = (cibleLon - dLon) * mParDegLon
        val dNord = (cibleLat - dLat) * mParDegLat
        val distHoriz = hypot(dEst, dNord)

        // 5) FAILSAFE decrochage : cible trop loin -> HOVER (pas de course folle).
        if (distHoriz > DIST_MAX_M) {
            hover(); majSuivi(getString(R.string.p3_suivi_decrochage, distHoriz.toInt(), DIST_MAX_M.toInt()))
            return
        }

        // 6) vitesse commandee = correction P (vers la cible) + FEED-FORWARD de la
        //    vitesse du sujet (ETAPE 2). Le feed-forward fait avancer le drone AVEC
        //    l'auto meme quand l'ecart est faible -> supprime le retard elastique.
        var vEst = 0.0; var vNord = 0.0
        // correction proportionnelle vers la cible (annulee dans la zone morte).
        if (distHoriz > ZONE_MORTE_M) {
            vEst  = (GAIN_P * dEst)
            vNord = (GAIN_P * dNord)
        }
        // feed-forward : egaler la vitesse du sujet (projetee sur son cap predit).
        // Ne s'applique QUE si on anticipe (prediction fiable) -> sinon Etape 1 pure.
        if (anticipe && predVitesseMps.isFinite() && predCapDeg.isFinite()) {
            val capR = Math.toRadians(predCapDeg)
            vEst  += predVitesseMps * sin(capR)
            vNord += predVitesseMps * cos(capR)
        }
        // bornage du VECTEUR (preserve la direction) a un plafond ADAPTE AU DRONE
        // (securite : moins de capteurs anti-obstacle -> suivi plus lent).
        val vMaxH = vMaxHorizSelonDrone()
        val vh = hypot(vEst, vNord)
        if (vh > vMaxH) { val k = vMaxH / vh; vEst *= k; vNord *= k }
        // vertical : rejoint l'altitude cible, borne + plafond.
        var vVert = (GAIN_P * (cibleAlt - dAlt))
        vVert = clampF(vVert.toFloat(), -V_MAX_VERT, V_MAX_VERT).toDouble()
        if (dAlt >= ALT_MAX_M && vVert > 0) vVert = 0.0   // plafond AGL

        // 7) map monde -> scene (convention TraductionAxes) : Est=vx, Nord=vz, Haut=vy.
        val vx = vEst.toFloat()      // lateral scene
        val vz = vNord.toFloat()     // profondeur scene
        val vy = vVert.toFloat()     // vertical scene

        // 7bis) ETAPE 3 : CADRAGE. On vise l'AUTO elle-meme (sujLat/sujLon), pas le
        //       point d'offset. Bearing = direction drone -> auto (0=N, 90=E).
        val dEstCar  = (sujLon - dLon) * mParDegLon
        val dNordCar = (sujLat - dLat) * mParDegLat
        val distCar  = hypot(dEstCar, dNordCar)
        val bearingCar = (Math.toDegrees(atan2(dEstCar, dNordCar)) + 360.0) % 360.0

        // YAW : rotation douce pour amener le nez du drone (donc la camera) vers l'auto.
        var yawRate = 0f
        var ecartCapDeg = 0.0
        if (CADRAGE_ACTIVE && distCar > 0.5) {
            var err = bearingCar - pont.capDroneDeg()          // ecart de cap (deg)
            err = ((err + 540.0) % 360.0) - 180.0              // ramene dans [-180, 180]
            ecartCapDeg = err
            if (abs(err) > YAW_ZONE_MORTE_DEG) {
                yawRate = clampF((GAIN_YAW * err).toFloat(), -YAW_MAX_DPS, YAW_MAX_DPS)
            }
        }

        // 7ter) YOLO — CADRAGE FIN (confirmation visuelle). Si UNE seule voiture est
        //       vue avec assez de confiance, on ajoute une PETITE correction de yaw
        //       (bornee) pour la centrer horizontalement dans l'image. Cela affine
        //       le cadrage GNSS sans jamais toucher a la translation du drone.
        //       Hors plongee (vue du dessus) : la voiture n'y est pas cadree ainsi.
        var yoloCadre = false
        if (CONFIRM_YOLO && CADRAGE_ACTIVE && distCar > 0.5 && positionSuivi != "plongee" &&
                yoloVoitureFraiche() && yoloConf >= YOLO_CONF_MIN && yoloNbVoit == 1) {
            yoloCadre = true
            val errX = yoloCx - 0.5f                        // >0 : voiture a droite de l'image
            val corrYaw = clampF(GAIN_YAW_YOLO * errX, -YAW_YOLO_MAX_DPS, YAW_YOLO_MAX_DPS)
            yawRate = clampF(yawRate + corrYaw, -YAW_MAX_DPS, YAW_MAX_DPS)
        }

        // 8) rotation translation vers le repere DRONE + yaw de cadrage.
        val cmd = TraductionAxes.versDji(vx, vy, vz, yawRate, pont.capDroneDeg())

        // 9) bornage final de securite (ceinture + bretelles)
        val pMaxH = vMaxH.toFloat()   // plafond horizontal adapte au drone (coherent avec le bornage du vecteur)
        val pitch = clampF(cmd.pitch, -pMaxH, pMaxH)
        val roll  = clampF(cmd.roll,  -pMaxH, pMaxH)
        val thr   = clampF(cmd.verticalThrottle, -V_MAX_VERT, V_MAX_VERT)
        val yaw   = clampF(cmd.yaw, -YAW_MAX_DPS, YAW_MAX_DPS)

        // ETAPE 4 : en FLOAT, on VERROUILLE tout mouvement (translation/altitude/yaw)
        // -> le drone tient position, il ne poursuit pas une position imprecise.
        // Seul le cadrage nacelle (9bis) continue. En FIX : suivi complet.
        // Commande AUTOMATIQUE existante (inchangee) : c'est la valeur par defaut.
        val existante = if (verrouTranslation)
            ca.cineflight.stage.control.AssainisseurVitesse.Vitesses(0f, 0f, 0f, 0f)
        else
            ca.cineflight.stage.control.AssainisseurVitesse.Vitesses(pitch, roll, thr, yaw)

        // SPORT SOCCER — EMISSION ARBITREE (Phase 9D). L'arbitre est dans le chemin, mais
        // la commande soccer n'est SELECTIONNEE que si le double verrou l'autorise.
        val aEnvoyer = deciderEmissionSoccer(existante)

        try {
            pont.envoyerVitesses(
                aEnvoyer.pitch, aEnvoyer.roll, aEnvoyer.throttle, aEnvoyer.yaw,
                ca.cineflight.stage.control.CommandOrigin.AUTOMATIC
            )
        } catch (_: Exception) {}

        // SPORT SOCCER — MIROIR DE MOUVEMENT (Phase 9B) : log de la vitesse theorique.
        if (SOCCER_RAIL_MIRROR_ENABLED) {
            observerMiroirMouvementSoccer(
                commandSent = if (verrouTranslation) "hover" else "auto(p=$pitch,r=$roll,t=$thr,y=$yaw)"
            )
        }

        // 9bis) ETAPE 3 : NACELLE inclinee vers l'auto (pitch = -atan(hauteur/dist)).
        //       Hauteur du drone au-dessus de l'auto ~ altitude AGL (decollage au sol).
        //       On n'envoie QUE si l'angle a bouge (>= seuil), pour ne pas saturer le SDK.
        var gimbalPitch = Float.NaN
        if (CADRAGE_ACTIVE) {
            gimbalPitch = when {
                positionSuivi == "plongee" -> GIMBAL_PITCH_MIN                 // droit vers le bas
                distCar > 0.5 -> clampF(
                    (-Math.toDegrees(atan2(dAlt.coerceAtLeast(0.0), distCar.coerceAtLeast(1.0)))).toFloat(),
                    GIMBAL_PITCH_MIN, GIMBAL_PITCH_MAX)
                else -> Float.NaN
            }
            // YOLO — cadrage fin vertical : si la voiture est vue (cas yoloCadre),
            // on affine la nacelle (borne) pour la centrer verticalement dans l'image.
            if (yoloCadre && !gimbalPitch.isNaN()) {
                val errY = yoloCy - 0.5f                    // >0 : voiture BAS de l'image
                val corrGim = clampF(-GAIN_GIMBAL_YOLO * errY, -GIMBAL_YOLO_MAX, GIMBAL_YOLO_MAX)
                gimbalPitch = clampF(gimbalPitch + corrGim, GIMBAL_PITCH_MIN, GIMBAL_PITCH_MAX)
            }
            if (!gimbalPitch.isNaN() && (dernierGimbalPitch.isNaN() ||
                    abs(gimbalPitch - dernierGimbalPitch) >= GIMBAL_SEUIL_MAJ_DEG)) {
                try { pont.orienterNacelle(gimbalPitch, 0f, false) } catch (_: Exception) {}
                dernierGimbalPitch = gimbalPitch.toDouble()
            }
        }

        val precision = when (autoRtk.uppercase()) {
            "FIX"   -> getString(R.string.p3_precision_precis)
            "FLOAT" -> getString(R.string.p3_precision_moyen)
            "LOST"  -> getString(R.string.p3_precision_perdu)
            else    -> getString(R.string.p3_precision_faible)
        }
        val capTxt = when {
            autoCap.isFinite()             -> getString(R.string.p3_cap_deg, autoCap.toInt())
            dernierCapValideDeg.isFinite() -> getString(R.string.p3_cap_repli, dernierCapValideDeg.toInt())
            else                           -> getString(R.string.p3_cap_sud)
        }
        // ETAPE 2 : etat de l'anticipation (mode + horizon + incertitude).
        val antTxt = if (anticipe) {
            val inc = if (predIncertitudeM.isFinite()) getString(R.string.p3_ant_inc, predIncertitudeM) else ""
            val vff = if (predVitesseMps.isFinite()) getString(R.string.p3_ant_vff, predVitesseMps) else ""
            getString(R.string.p3_ant_anticipe, predHorizonS, inc, vff)
        } else getString(R.string.p3_ant_direct)
        val cadTxt = if (CADRAGE_ACTIVE) {
            val nac = if (gimbalPitch.isNaN()) "—" else getString(R.string.p3_cad_nac, gimbalPitch)
            getString(R.string.p3_cad_actif, ecartCapDeg, yaw, nac)
        } else getString(R.string.p3_cad_off)
        // Etat de la confirmation visuelle YOLO (ne pilote QUE le cadrage camera).
        val yoloTxt = when {
            !CONFIRM_YOLO -> getString(R.string.p3_yolo_off)
            yoloVoitureFraiche() && yoloNbVoit == 1 && yoloConf >= YOLO_CONF_MIN ->
                getString(R.string.p3_yolo_vue, yoloConf)
            yoloVoitureFraiche() && yoloNbVoit > 1 -> getString(R.string.p3_yolo_multi)
            else -> getString(R.string.p3_yolo_non_vue)
        }
        val modeTxt = if (verrouTranslation) getString(R.string.p3_mode_float) else getString(R.string.p3_mode_fix)
        val posNom = when (positionSuivi) {
            "devant"  -> getString(R.string.p3_pos_devant)
            "gauche"  -> getString(R.string.p3_pos_gauche)
            "droite"  -> getString(R.string.p3_pos_droite)
            "plongee" -> getString(R.string.p3_pos_plongee)
            else      -> getString(R.string.p3_pos_derriere)
        }
        val mouvTxt = when (val m = mouvementActif) {
            null -> "🎬 " + posNom
            GenerateurMouvement.TypeMouvement.TRAVELLING -> "🎬 TRAVELLING"
            else -> getString(R.string.p3_mouv_phase, m.name, (phaseMouv * 100).toInt())
        }
        majSuivi(getString(R.string.p3_suivi_bloc,
            modeTxt, mouvTxt, precision, autoAgeS, capTxt, antTxt, yoloTxt,
            distHoriz.toInt(), cibleAlt - dAlt, cadTxt, pitch, roll, thr))
    }

    /** Bascule le mode CAMERA (suivi vision, sans boitier RTK) on/off. */
    /** Active/desactive le simulateur DJI (test du suivi sans vol reel). */
    private fun basculerSimulateur() {
        if (pont.simulateurActif()) {
            pont.desactiverSimulateur { runOnUiThread { if (::btnSimPhase3.isInitialized) btnSimPhase3.text = getString(R.string.p3_btn_sim_off) } }
        } else {
            pont.activerSimulateur(SIM_LAT_P3, SIM_LON_P3) { ok -> runOnUiThread { if (::btnSimPhase3.isInitialized) btnSimPhase3.text = getString(if (ok) R.string.p3_btn_sim_on else R.string.p3_btn_sim_off) } }
        }
    }

    private fun basculerVision() {
        // Securite : en suivi de vehicule, la camera seule n'est pas permise (boitier obligatoire).
        if (profilSujet != ProfilSujetMobile.MARCHE) {
            txtEtat.text = getString(R.string.p3_suivi_obligatoire)
            return
        }
        suiviVision = !suiviVision
        gimbalVisionDeg = Double.NaN
        majBoutonVision()
        txtEtat.text = if (suiviVision) getString(R.string.p3_vision_on)
                       else getString(R.string.p3_vision_off)
    }

    private fun majBoutonVision() {
        if (!::btnVision.isInitialized) return
        btnVision.text = if (suiviVision) getString(R.string.p3_btn_vision_on)
                         else getString(R.string.p3_btn_vision_off)
        btnVision.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (suiviVision) 0xFF4A148C.toInt() else 0xFF6A1B9A.toInt())
    }

    // -- UN TICK DE SUIVI CAMERA (sujet SANS boitier RTK) --
    // Le materiel decide : Mini 3 -> pivot seulement ; Mini 4 Pro -> deplacement
    // borne UNIQUEMENT si les capteurs anti-obstacle sont reellement actifs. Au
    // moindre doute (detection perdue/ambigue) le drone TIENT sa position.
    private fun tickSuiviVision() {
        val modele = try { pont.modeleDrone() } catch (_: Throwable) { "" }
        val prof = CapacitesDrone.analyser(modele)
        val evitActif = try {
            lecteurPerception.perceptionDisponible() && lecteurPerception.evitementHorizontalActif()
        } catch (_: Throwable) { false }
        var mode = SuiviVisionYolo.modeAutorise(prof.evitement, evitActif)
        // Garde-fou global : deplacement camera coupe tant qu'il n'est pas valide en vol.
        if (!AUTORISER_DEPLACEMENT_VISION) mode = SuiviVisionYolo.ModeVision.CADRAGE_PIVOT

        val obs = SuiviVisionYolo.Observation(
            fraiche = yoloVoitureFraiche(),
            nbCibles = yoloNbVoit,
            confiance = yoloConf,
            cx = yoloCx, cy = yoloCy, hauteurBoite = yoloHauteur
        )
        val cmd = moteurVision.calculer(obs, mode)
        if (!cmd.suit) { hover(); majSuivi(getString(R.string.p3_vision_recherche)); return }

        // Avance seulement si le sujet est deja bien CENTRE en X (le nez pointe vers
        // lui) -> aucune avance dans une mauvaise direction avant d'avoir pivote.
        var avance = cmd.avanceMps
        if (abs(yoloCx - 0.5f) > VISION_CENTRE_AVANCE) avance = 0f
        val pitch = clampF(avance, -V_MAX_HORIZ, V_MAX_HORIZ)
        val yaw = clampF(cmd.yawDps, -YAW_MAX_DPS, YAW_MAX_DPS)
        // Repere CORPS : pitch = avance vers le nez (donc vers le sujet une fois cadre),
        // roll = 0 (aucun deplacement lateral), throttle = 0 (le drone tient son altitude).
        try { pont.envoyerVitesses(pitch, 0f, 0f, yaw, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {}

        // Nacelle : on accumule le petit ajustement, borne dans la plage nacelle.
        if (gimbalVisionDeg.isNaN()) gimbalVisionDeg = 0.0
        gimbalVisionDeg = (gimbalVisionDeg + cmd.gimbalDeltaDeg)
            .coerceIn(GIMBAL_PITCH_MIN.toDouble(), GIMBAL_PITCH_MAX.toDouble())
        if (dernierGimbalPitch.isNaN() || abs(gimbalVisionDeg - dernierGimbalPitch) >= GIMBAL_SEUIL_MAJ_DEG) {
            try { pont.orienterNacelle(gimbalVisionDeg.toFloat(), 0f, false) } catch (_: Exception) {}
            dernierGimbalPitch = gimbalVisionDeg
        }

        val deplTxt = if (cmd.deplacementAutorise && AUTORISER_DEPLACEMENT_VISION)
            getString(R.string.p3_vision_depl) else getString(R.string.p3_vision_pivot)
        majSuivi(getString(R.string.p3_vision_bloc, deplTxt, yaw, gimbalVisionDeg, pitch))
    }

    private fun hover() { try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {} }

    private fun clampF(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    // ── YOLO : demarrage de la detection voiture (confirmation + cadrage fin) ────
    // Modele leger (nano 320 / CPU) pour ne pas surcharger le telephone ni entrer
    // en concurrence GPU avec l'affichage. Si le modele ne charge pas, la couche
    // reste simplement inactive : on retombe sur le cadrage GNSS (aucun risque).
    private fun demarrerYolo() {
        if (!CONFIRM_YOLO || yoloSuivi != null) return
        val ok = try { yolo.loadModel(assets, 0, 0, 0) } catch (_: Throwable) { false }
        if (!ok) { android.util.Log.w("Phase3", "YOLO loadModel echec — confirmation desactivee"); return }
        val ys = YoloSuivi(
            yolo,
            onSujet = { trouve, cx, cy, _, fh ->
                yoloVoitureVue = trouve
                if (trouve) { yoloCx = cx; yoloCy = cy; yoloHauteur = fh; yoloVueMs = System.currentTimeMillis() }
            },
            onSuiviInfo = { conf, nb -> yoloConf = conf; yoloNbVoit = nb },
            // MODE SOCCER uniquement : la liste multi-joueurs complete alimente le tracker
            // (identites stables) puis le centre de groupe. N'affecte pas l'ancien suivi.
            onJoueurs = { personnes -> if (soccerMode2D) majMultiJoueursSoccer(personnes) }
        )
        ys.setClassesSuivies(classesSuivi)   // classes selon le profil (personne ou vehicule)
        ys.demarrer()
        yoloSuivi = ys
    }

    /**
     * MODE SOCCER : ingere la liste complete des personnes YOLO ([cx,cy,fw,fh,conf]) -> tracker
     * multi-joueurs (identites stables) -> centre du groupe principal. Met a jour le vrai
     * nbJoueurs, le centre d'action et la confiance moyenne pour le realisateur. Observation seule.
     */
    private fun majMultiJoueursSoccer(personnes: List<FloatArray>) {
        val now = System.currentTimeMillis()
        // TEST E-01 : diagnostic ecrit dans le fichier a chaque frame de detection recue.
        // Nous dit si YOLO tourne et combien de personnes il voit, meme si on n'entre jamais
        // dans le bloc d'emission 2D. SUPPRIMER apres le test (flag=false le desactive).
        if (TEST_E01_SIGNE_THROTTLE) {
            try {
                val f = java.io.File(getExternalFilesDir(null), "test_e01_signe.log")
                java.io.FileOutputStream(f, true).use {
                    it.write(("DIAG ts=$now personnes_yolo=${personnes.size} mode2D=$soccerMode2D\n")
                        .toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {}
        }
        val dets = personnes.mapNotNull { p ->
            if (p.size < 5) null
            else ca.cineflight.stage.sport.soccer.PlayerTracker.Detection(p[0], p[1], p[2], p[3], p[4])
        }
        val joueurs = soccerPlayerTracker.update(dets, now)
        val positions = joueurs.map { Pair(it.cx, it.cy) }
        val centre = ca.cineflight.stage.sport.soccer.ActionCenter.calculer(positions)
        if (centre.present) {
            soccerCentreCx = centre.x
            soccerCentreCy = centre.y
            soccerNbJoueurs = centre.taille
            // confiance moyenne du groupe (pertinence de l'observation).
            soccerConfMoyenne = if (joueurs.isNotEmpty())
                joueurs.map { it.conf }.average().toFloat() else 0f
        } else {
            soccerNbJoueurs = 0
            soccerConfMoyenne = 0f
        }
    }

    /** Une voiture est-elle vue par YOLO a l'instant (detection recente) ? */
    private fun yoloVoitureFraiche(): Boolean =
        yoloVoitureVue && (System.currentTimeMillis() - yoloVueMs) < YOLO_FRAIS_MS

    /**
     * MIROIR DE MOUVEMENT soccer (Phase 9B). Transforme la detection YOLO courante en
     * position d'action, la stabilise, calcule la VITESSE THEORIQUE de rail, et la
     * JOURNALISE avec la commande reellement envoyee. INVARIANT : rien n'est applique
     * au drone (soccer_motion_applied=false). Fail-open : jamais bloquant.
     *
     * NB Phase3 : une seule cible YOLO (yoloCx) -> on la traite comme l'action directe,
     * sans passer par la mediane multi-joueurs (format soccer complet viendra du flux
     * dedie). L'age et la confiance viennent des champs YOLO existants.
     */
    /**
     * EMISSION ARBITREE soccer (Phase 9D). Place l'arbitre dans le chemin d'emission,
     * mais la commande soccer n'est reellement envoyee QUE si le double verrou l'autorise
     * (SOCCER_RAIL_REAL_ENABLED == true ET plafond de vitesse > 0). Sinon, on renvoie la
     * commande [existante] inchangee.
     *
     * INVARIANT DEFENSIF : si l'emission reelle est active, le plafond de vitesse doit
     * etre >= 0 (jamais negatif). check() echoue tot en dev plutot que d'emettre un aberrant.
     *
     * @return la commande a reellement envoyer au pont.
     */
    /**
     * Position REELLE du drone sur le rail (passe E) : projette la position GNSS du
     * drone (pont.latitudeDrone/longitudeDrone) sur le segment de rail -> fraction [0,1].
     * Repli sur le centre si la position est indisponible (NaN). Fail-safe.
     */
    private fun positionDroneSurRail(): Float {
        return try {
            val lat = pont.latitudeDrone()
            val lon = pont.longitudeDrone()
            if (!lat.isFinite() || !lon.isFinite()) return SOCCER_RAIL_POS_REPLI
            ca.cineflight.stage.sport.soccer.RailProjection.fraction(
                ca.cineflight.stage.sport.soccer.RailPoint(lat, lon), soccerRail
            )
        } catch (_: Throwable) {
            SOCCER_RAIL_POS_REPLI
        }
    }

    /**
     * true si le point de DECOLLAGE est a <= SOCCER_DIST_MAX_M du rail (VLOS).
     * Decollage = profil (takeoff_zone) si present, sinon position reelle du drone.
     * Fail-closed : si aucun point de decollage connu, retourne false (bloque le soccer).
     */
    private fun estOperateurProcheDuRail(): Boolean {
        try {
            // 1) point de decollage du profil (RailPoint), sinon 2) position reelle du drone.
            val point = soccerProfil?.takeoffZone?.firstOrNull() ?: run {
                val lat = pont.latitudeDrone(); val lon = pont.longitudeDrone()
                if (!lat.isFinite() || !lon.isFinite()) return false   // inconnu -> bloque
                ca.cineflight.stage.sport.soccer.RailPoint(lat, lon)
            }
            val d = ca.cineflight.stage.sport.soccer.RailProjection.distanceM(point, soccerRail)
            return d <= SOCCER_DIST_MAX_M
        } catch (_: Throwable) {
            return false   // fail-closed
        }
    }

    private fun deciderEmissionSoccer(
        existante: ca.cineflight.stage.control.AssainisseurVitesse.Vitesses
    ): ca.cineflight.stage.control.AssainisseurVitesse.Vitesses {
        // Invariant defensif (spec 9D).
        check(!SOCCER_RAIL_REAL_ENABLED || SOCCER_MAX_SPEED_MPS >= 0f) {
            "Config soccer invalide : maxSpeed negatif avec emission reelle"
        }
        try {
            // Construit l'action stable courante (meme source qu'en miroir).
            val now = System.currentTimeMillis()
            val vue = yoloVoitureVue && yoloConf > 0f
            val estimate = if (vue) ca.cineflight.stage.sport.soccer.SoccerActionEstimate(
                positionNormalized = yoloCx.coerceIn(0f, 1f),
                confidence = yoloConf,
                source = ca.cineflight.stage.sport.soccer.SoccerActionEstimate.Source.PLAYERS,
                timestampMs = if (yoloVueMs > 0L) yoloVueMs else now,
            ) else null
            val stable = soccerTracker.update(estimate, now)
            val railPos = positionDroneSurRail()   // passe E : position GNSS reelle projetee
            val target = stable.positionNormalized ?: railPos
            val ageMs = if (yoloVueMs > 0L) now - yoloVueMs else Long.MAX_VALUE

            val output = soccerMotion.compute(
                ca.cineflight.stage.sport.soccer.RailControlInput(
                    currentPosition = railPos,
                    targetPosition = target,
                    actionAgeMs = ageMs,
                    confidence = stable.confidence,
                )
            )
            // Commande soccer en repere corps : la vitesse de rail est une translation
            // laterale (roll). pitch/throttle/yaw = 0 en V1 (altitude fixe, pas de yaw auto).
            val soccerCmd = ca.cineflight.stage.control.AssainisseurVitesse.Vitesses(
                pitch = 0f, roll = output.requestedVelocityMps, throttle = 0f, yaw = 0f
            )

            // CAPTURE ATOMIQUE (NC-T2-001) : un unique point de lecture des signaux bruts,
            // publie via la fabrique (snapshot immuable + generation). Principe FAIL-CLOSED :
            // ce qui n'est pas prouve vrai bloque le soccer. L'action rail est fiable si la
            // confiance stable atteint le seuil YOLO.
            val actionFiableRail = (stable.confidence >= YOLO_CONF_MIN)
            val safety = capturerSnapshotSecurite(actionFiable = actionFiableRail).snapshot
            val decision = ca.cineflight.stage.sport.soccer.FlightCommandArbiter.decide(
                existingCommand = existante,
                soccerCommand = soccerCmd,
                pilotCommand = existante,               // pas de reprise pilote distincte ici
                // Arme SEULEMENT si l'operateur a arme ET que le flag reel l'autorise.
                soccerModeArmed = (SOCCER_RAIL_REAL_ENABLED && soccerArme),
                safety = safety,
                // RAIL = deplacement HORIZONTAL (roll) : le gate obstacle RESTE une
                // condition dure sur ce chemin (hors perimetre de la demande SFOC actuelle).
                horizontalMotionRequested = true,
            )

            // DOUBLE VERROU + ARMEMENT : on n'applique la commande soccer QUE si le flag reel
            // est actif, que l'operateur a arme, et que l'arbitre a retenu SoccerRail.
            val appliqueSoccer = SOCCER_RAIL_REAL_ENABLED && soccerArme &&
                decision.source == ca.cineflight.stage.sport.soccer.FlightCommandArbiter.FlightCommandSource.SoccerRail
            val aEnvoyer = if (appliqueSoccer) decision.command else existante

            android.util.Log.i(
                ca.cineflight.stage.sport.soccer.SoccerRailEmissionLog.TAG,
                ca.cineflight.stage.sport.soccer.SoccerRailEmissionLog.ligne(
                    realEnabled = SOCCER_RAIL_REAL_ENABLED,
                    maxSpeedMps = SOCCER_MAX_SPEED_MPS,
                    winner = decision.source,
                    soccerCommand = soccerCmd,
                    soccerApplied = appliqueSoccer,
                )
            )
            return aEnvoyer
        } catch (_: Throwable) {
            // Fail-safe : au moindre probleme, on renvoie la commande existante inchangee.
            return existante
        }
    }

    /**
     * CAPTURE ATOMIQUE de l'instantane de securite (NC-T2-001). Lit TOUS les signaux bruts
     * en une seule passe, les emballe dans un RawSafetySample immuable, puis les publie via
     * la fabrique (snapshot immuable + generation). Un lecteur ne verra jamais un melange
     * d'etats de moments differents. FAIL-CLOSED : ce qui n'est pas prouve vrai bloque.
     *
     * @param actionFiable resultat deja calcule de la fiabilite d'action (contexte rail ou 2D).
     */
    private fun capturerSnapshotSecurite(actionFiable: Boolean): ca.cineflight.stage.sport.soccer.SafetySnapshotFactory.Published {
        // --- CAPTURE EN UNE PASSE (aucune logique metier entre les lectures) ---
        val cPilote = modeManuel
        val cUrgence = soccerArretUrgence
        val cVs = vsActif
        val cEnVol = enVol
        val cRtk = autoRtk.uppercase()
        val cBatt = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        val cOperateurProche = estOperateurProcheDuRail()
        // --- FIN DE CAPTURE : plus aucune variable partagee n'est relue apres ce point ---

        val sample = ca.cineflight.stage.sport.soccer.RawSafetySample(
            pilotOverride = cPilote,
            emergencyStop = cUrgence,
            // GATE OBSTACLE — BASELINE ALTITUDE_ONLY (audit v50, Option A) : ce champ est
            // HORS PERIMETRE du chemin d'emission altitude (l'arbitre l'ignore quand
            // horizontalMotionRequested=false ; preuve exhaustive dans les tests). AUCUN
            // credit d'evitement d'obstacles n'est revendique : les obstacles sont traites
            // par l'evaluation du site et les procedures. Reste false (fail-closed) pour le
            // chemin RAIL/horizontal, ou il demeure une condition dure (NC-T2-003).
            obstacleGateAllows = false,
            virtualStickAvailable = cVs,
            inFlightCompatible = cEnVol,
            railLoadedAndValid = true,
            dronePositionFresh = (cRtk == "FIX" || cRtk == "FLOAT"),
            actionFreshAndConfident = actionFiable,
            batteryOk = (cBatt >= SOCCER_BATT_MIN_PCT),
            corridorClear = true,
            operatorNearRail = cOperateurProche,
        )
        return soccerSnapshotFactory.publish(sample)
    }

    /**
     * EMISSION REELLE 2D (Essai 1 : ALTITUDE SEULE). Prend le throttle propose par le
     * controleur d'altitude, le fait passer par le MEME arbitre + le MEME SafetySnapshot
     * que le mode RAIL, applique le DOUBLE VERROU 2D, et n'emet REELLEMENT que si tout est
     * OK. roll/pitch/yaw = 0 (aucun mouvement horizontal). Retourne un libelle d'etat pour
     * l'affichage. Aucun second chemin de securite : l'arbitre est l'unique juge.
     *
     * Conditions cumulatives pour emettre : mode 2D actif + operateur arme + flag reel +
     * vMax>0 + SafetySnapshot valide + arbitre retient SoccerRail. Un seul manquant -> 0.
     */
    private fun emettre2DSoccer(throttleDemandeMps: Float, throttleSurveilleMps: Float): String {
        try {
            val nowNanos = System.nanoTime()
            // CAPTURE ATOMIQUE (NC-T2-001) : un seul point de lecture des signaux bruts.
            // L'action est fiable en 2D si la confiance suffit OU si des joueurs sont vus,
            // ET si la derniere detection est FRAICHE (garde d'age). CORRECTION SECURITE
            // (E-06/E-07) : sans cette garde, un flux video GELE laisse yoloConf/nbJoueurs
            // figes sur leur derniere valeur -> le systeme se croit voyant indefiniment.
            // La garde d'age force actionFiable=false des que la detection date de plus de
            // YOLO_FRAIS_MS, ce qui fait tomber le bit AC -> throttle bloque (fail-closed).
            val yoloFrais = (yoloVueMs > 0L && (System.currentTimeMillis() - yoloVueMs) < YOLO_FRAIS_MS)
            val actionFiable2D = yoloFrais && (yoloConf >= YOLO_CONF_MIN || soccerNbJoueurs > 0)
            // TEST E-06/E-07 : trace l'age YOLO et le bit AC (actionFreshAndConfident).
            // Quand la video/detection est perdue, actionFiable2D passe a false -> AC=0 ->
            // throttle_emis=0 (verifiable aussi via le champ snapshot du log SOCCER_2D_EMISSION).
            if (TEST_E06_09_SECU) {
                val ageYolo = if (yoloVueMs > 0L) System.currentTimeMillis() - yoloVueMs else -1L
                logSecuTest("E06 ts=${System.currentTimeMillis()} yolo_age_ms=$ageYolo yoloConf=$yoloConf nbJoueurs=$soccerNbJoueurs actionFiable=$actionFiable2D (AC)")
            }
            val pub = capturerSnapshotSecurite(actionFiable = actionFiable2D)
            val safety = pub.snapshot
            // DECISION : source UNIQUE du double verrou 2D + arbitre (Emission2DGuard, teste).
            // Le throttle passe a l'arbitre est celui DEJA surveille par le watchdog.
            val r = ca.cineflight.stage.sport.soccer.Emission2DGuard.decider(
                throttleMps = throttleSurveilleMps,
                flagReel = SOCCER_2D_REAL_ENABLED,
                operateurArme = soccerArme,
                vMaxMps = SOCCER_2D_MAX_VSPEED_MPS,
                safety = safety,
            )
            val throttleEmis = if (r.emettre) r.command.throttle else 0f
            if (r.emettre) {
                // EMISSION REELLE : throttle seul (roll/pitch/yaw = 0).
                pont.envoyerVitesses(0f, 0f, r.command.throttle, 0f,
                    ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
            }
            // JOURNALISATION HAUTE FREQUENCE (Phase 1.4) : trace generation + 11 conditions +
            // etat watchdog + les 3 throttles (demande/surveille/emis) + verdict guard.
            val ligneLog = ca.cineflight.stage.sport.soccer.Soccer2DEmissionLog.ligne(
                tsMs = System.currentTimeMillis(),
                generation = pub.generation,
                throttleDemandeMps = throttleDemandeMps,
                throttleSurveilleMps = throttleSurveilleMps,
                throttleEmisMps = throttleEmis,
                watchdogFrais = soccerWatchdog.cycleFrais(nowNanos),
                watchdogAgeMs = soccerWatchdog.ageMs(nowNanos),
                etat = r.etat,
                raison = r.raison,
                snapshot = safety,
                emis = r.emettre,
            )
            android.util.Log.i(ca.cineflight.stage.sport.soccer.Soccer2DEmissionLog.TAG, ligneLog)
            // TEST E-01 : ecrit aussi chaque ligne dans un fichier sur le telephone, pour
            // pouvoir lire les logs sans cable ADB (telephone occupe par la manette DJI).
            // Fichier : Android/data/ca.cineflight.solo/files/test_e01_signe.log
            if (TEST_E01_SIGNE_THROTTLE) {
                try {
                    val f = java.io.File(getExternalFilesDir(null), "test_e01_signe.log")
                    java.io.FileOutputStream(f, true).use {
                        it.write((ligneLog + "\n").toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Throwable) { /* fail-open : ne jamais casser la boucle pilote */ }
            }
            if (r.emettre) {
                return "ACTIVE throttle=%.2f m/s (%s)".format(r.command.throttle, r.raison)
            }
            return when (r.etat) {
                ca.cineflight.stage.sport.soccer.Emission2DGuard.Etat.INERTE_FLAG_OFF -> "INERTE (flag off)"
                ca.cineflight.stage.sport.soccer.Emission2DGuard.Etat.INERTE_NON_ARME -> "INERTE (non armé)"
                ca.cineflight.stage.sport.soccer.Emission2DGuard.Etat.INERTE_VMAX_ZERO -> "INERTE (vMax 0)"
                else -> "BLOQUÉ (${r.raison})"
            }
        } catch (_: Throwable) {
            return "INERTE (erreur → 0)"
        }
    }

    /**
     * Charge le profil SOCCER_RAIL depuis le serveur Web (le rail dessine dans preview3d).
     * Remplace le rail d'essai par le rail reel. Fail-safe : en cas d'absence/erreur, on
     * garde le rail d'essai et on l'indique dans le panneau. Aucune commande drone.
     */
    private fun chargerProfilSoccer() {
        lifecycleScope.launch {
            val res = ca.cineflight.stage.sport.soccer.SoccerRailClient.charger()
            val t = txtSoccer
            when (res) {
                is ca.cineflight.stage.sport.soccer.SoccerRailClient.Resultat.Ok -> {
                    soccerProfil = res.profile
                    soccerRail = res.profile.rail
                    if (t != null) t.text = "⚽ SOCCER — rail « ${res.profile.mode} » chargé " +
                        "(alt ${res.profile.altitudeAglM.toInt()} m, vmax ${res.profile.maxSpeedMps} m/s)"
                }
                is ca.cineflight.stage.sport.soccer.SoccerRailClient.Resultat.Absent -> {
                    if (t != null) t.text = "⚽ SOCCER — aucun rail défini sur le serveur (rail d'essai)."
                }
                is ca.cineflight.stage.sport.soccer.SoccerRailClient.Resultat.Erreur -> {
                    if (t != null) t.text = "⚽ SOCCER — serveur injoignable (${res.message}). Rail d'essai."
                }
            }
        }
    }

    /**
     * Liste les rails du serveur et laisse l'operateur en CHOISIR un (dialog). Le rail
     * choisi remplace le rail courant. Fail-safe : si la liste est vide/injoignable, on
     * l'indique. Aucune commande drone.
     */
    /** Bascule le mode soccer entre RAIL (drone sur le rail) et 2D (drone dans le terrain). */
    private fun basculerModeSoccer() {
        // Le mode 2D exige un terrain defini (>= 3 sommets) ; sinon on reste en rail.
        if (!soccerMode2D) {
            val terrain = soccerProfil?.terrain ?: emptyList()
            if (terrain.size < 3) {
                txtSoccer?.text = "⚽ Mode 2D indisponible : aucun terrain défini dans le profil."
                return
            }
        }
        soccerMode2D = !soccerMode2D
        soccerDirection.reset(); soccerAltitude.reset()
        soccer2DMotion.reset(); soccerPlanWidth.reset(); soccerPhase.reset(); soccerCadrage.reset(); soccerDirector.reset(); soccerLateralM = 0.0
        soccerPlayerTracker.reset(); soccerNbJoueurs = 0; soccerCentreCx = 0.5f; soccerCentreCy = 0.5f; soccerConfMoyenne = 0f
        val b = btnModeSoccer
        if (b != null) {
            b.text = if (soccerMode2D) "Mode : 2D TERRAIN" else "Mode : RAIL"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (soccerMode2D) 0xFF2E7D32.toInt() else 0xFF455A64.toInt())
        }
        txtSoccer?.text = if (soccerMode2D)
            "⚽ Mode 2D : le drone se place en retrait de l'action, dans le terrain."
        else "⚽ Mode RAIL : le drone reste sur le rail."
    }

    private fun choisirRailSoccer() {
        lifecycleScope.launch {
            val rails = ca.cineflight.stage.sport.soccer.SoccerRailClient.lister()
            if (rails.isEmpty()) {
                txtSoccer?.text = "⚽ SOCCER — aucun rail sur le serveur (ou injoignable)."
                return@launch
            }
            val libelles = rails.map { "${it.nom} · ${it.longueurM} m" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@Phase3Activity)
                .setTitle("Choisir le rail")
                .setItems(libelles) { _, i -> chargerRailParId(rails[i].id) }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    /** Charge un rail precis (par id) et remplace le rail courant. */
    private fun chargerRailParId(id: String) {
        lifecycleScope.launch {
            val res = ca.cineflight.stage.sport.soccer.SoccerRailClient.chargerParId(id)
            val t = txtSoccer
            when (res) {
                is ca.cineflight.stage.sport.soccer.SoccerRailClient.Resultat.Ok -> {
                    soccerProfil = res.profile
                    soccerRail = res.profile.rail
                    if (t != null) t.text = "⚽ SOCCER — rail chargé " +
                        "(alt ${res.profile.altitudeAglM.toInt()} m, vmax ${res.profile.maxSpeedMps} m/s)"
                }
                is ca.cineflight.stage.sport.soccer.SoccerRailClient.Resultat.Absent ->
                    if (t != null) t.text = "⚽ SOCCER — rail introuvable."
                is ca.cineflight.stage.sport.soccer.SoccerRailClient.Resultat.Erreur ->
                    if (t != null) t.text = "⚽ SOCCER — erreur (${res.message})."
            }
        }
    }

    private fun observerMiroirMouvementSoccer(commandSent: String) {
        try {
            val now = System.currentTimeMillis()
            val vue = yoloVoitureVue && yoloConf > 0f
            val estimate = if (vue) {
                ca.cineflight.stage.sport.soccer.SoccerActionEstimate(
                    positionNormalized = yoloCx.coerceIn(0f, 1f),
                    confidence = yoloConf,
                    source = ca.cineflight.stage.sport.soccer.SoccerActionEstimate.Source.PLAYERS,
                    timestampMs = if (yoloVueMs > 0L) yoloVueMs else now,
                )
            } else null

            val stable = soccerTracker.update(estimate, now)
            val actionPos = stable.positionNormalized
            val railPos = positionDroneSurRail()   // passe E : position GNSS reelle projetee
            val target = actionPos ?: railPos   // pas d'action -> on vise la position actuelle
            val ageMs = if (yoloVueMs > 0L) now - yoloVueMs else Long.MAX_VALUE

            val output = soccerMotion.compute(
                ca.cineflight.stage.sport.soccer.RailControlInput(
                    currentPosition = railPos,
                    targetPosition = target,
                    actionAgeMs = ageMs,
                    confidence = stable.confidence,
                )
            )
            android.util.Log.i(
                ca.cineflight.stage.sport.soccer.SoccerRailMirrorLog.TAG,
                ca.cineflight.stage.sport.soccer.SoccerRailMirrorLog.ligne(
                    actionPosition = actionPos,
                    railCurrent = railPos,
                    railTarget = target,
                    output = output,
                    commandSent = commandSent,
                )
            )

            // Panneau lisible a l'ecran (mode SOCCER). Observation seule.
            val t = txtSoccer
            if (t != null && !soccerMode2D) {
                val actionTxt = actionPos?.let { "%.2f".format(it) } ?: "—"
                val txt = "⚽ SOCCER — RAIL (observation)\n" +
                    "action=$actionTxt  rail=%.2f→%.2f\n".format(railPos, target) +
                    "vitesse théorique=%.2f m/s  (%s)".format(output.requestedVelocityMps, output.reason.name)
                runOnUiThread { t.text = txt }
            }

            // ── MODE 2D : position en RETRAIT dans le terrain + altitude adaptative.
            // Observation seule : on calcule et affiche ce que le drone VISERAIT, sans
            // commander (le double verrou + arbitre gouvernent toujours l'emission reelle).
            // Le mode 2D marche des qu'on a soit un groupe de joueurs suivis (multi-joueurs),
            // soit la cible mono-YOLO fraiche (repli).
            val multi = soccerNbJoueurs > 0
            // TEST E-01 : diag a l'entree de la boucle d'emission (thread pilote).
            if (TEST_E01_SIGNE_THROTTLE) {
                try {
                    val f = java.io.File(getExternalFilesDir(null), "test_e01_signe.log")
                    java.io.FileOutputStream(f, true).use {
                        it.write(("BOUCLE ts=${System.currentTimeMillis()} mode2D=$soccerMode2D nbJoueurs=$soccerNbJoueurs multi=$multi vue=$vue entre=${soccerMode2D && (multi || vue)}\n")
                            .toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Throwable) {}
            }
            if (soccerMode2D && (multi || vue)) {
                // CENTRE D'ACTION : le centre du groupe principal si multi-joueurs, sinon
                // la cible mono-YOLO (repli). C'est la vraie action collective.
                val centre = if (multi)
                    ca.cineflight.stage.sport.soccer.Point2D(
                        soccerCentreCx.coerceIn(0f, 1f), soccerCentreCy.coerceIn(0f, 1f))
                else
                    ca.cineflight.stage.sport.soccer.Point2D(
                        yoloCx.coerceIn(0f, 1f), yoloCy.coerceIn(0f, 1f))
                // nb de joueurs et confiance reels (repli 1 / yoloConf en mono-cible).
                val nbJoueurs = if (multi) soccerNbJoueurs else 1
                val confObs = if (multi) soccerConfMoyenne else yoloConf

                // 1) DIRECTION du jeu (lissee).
                val dir = soccerDirection.update(centre, now)

                // 2) ANTICIPATION : centre projete un peu en avance (borne).
                val anticipe = soccerAnticipation.anticiper(
                    centre, dir.dirX, dir.dirY, dir.vitesse, dir.fiable)

                // 3) POSITION EN RETRAIT 2D (derriere l'action anticipee).
                //    RETRAIT EN METRES REELS : on convertit via les dimensions reelles du
                //    terrain (profil lat/lon). Sans profil -> repli fraction par defaut.
                val dims = soccerProfil?.terrain?.let {
                    ca.cineflight.stage.sport.soccer.TerrainMetrics.dimensions(it)
                }
                val cible = if (dims != null && dims.valide) {
                    soccer2DPlanner.calculer(
                        anticipe, ca.cineflight.stage.sport.soccer.Point2D(dir.dirX, dir.dirY),
                        emptyList(), dims)
                } else {
                    soccer2DPlanner.calculer(
                        anticipe, ca.cineflight.stage.sport.soccer.Point2D(dir.dirX, dir.dirY),
                        emptyList())
                }

                // 4) CONTROLEUR 2D : vitesses vers la cible (bornees, accel limitee).
                //    Cible2D -> Point2D pour le controleur.
                val ciblePt = ca.cineflight.stage.sport.soccer.Point2D(cible.x, cible.y)
                val posDrone = ca.cineflight.stage.sport.soccer.Point2D(soccer2DPosX, soccer2DPosY)
                val ageMs = if (yoloVueMs > 0L) now - yoloVueMs else Long.MAX_VALUE
                val mv = soccer2DMotion.compute(posDrone, ciblePt, ageMs, yoloConf)

                // 5) CAMERA / NACELLE : viser le centre d'action (regles cinema).
                val aim = soccerCameraAim.calculer(centre.x, centre.y, dir.dirX)

                // 6) LARGEUR DE PLAN (etalement proxy V1 = vitesse ; certitude = conf).
                val etalement = kotlin.math.min(1f, dir.vitesse * 5f)
                val plan = soccerPlanWidth.maj(etalement, etalement, yoloConf)

                // 7) PHASE DE JEU -> plage d'altitude autorisee (table validee).
                val vitesseNorm = kotlin.math.min(1f, dir.vitesse * 5f)
                val phase = soccerPhase.maj(vitesseNorm, etalement)

                // 8) ALTITUDE PAR TAILLE DES JOUEURS (asservissement), bornee par la phase.
                //    yoloHauteur = hauteur de boite joueur dans l'image [0,1] (proxy taille).
                val alt = soccerAltitude.altitudeParTaille(yoloHauteur, phase.altMinM, phase.altMaxM)

                // 9) INDICE DE QUALITE DE CADRAGE (Phase 2) : signal a maximiser.
                //    nbJoueurs et confiance REELS (multi-joueurs) via le tracker + centre de
                //    groupe ; repli mono-cible (1 / yoloConf) si aucun joueur suivi.
                //    5e critere : confiance module le score final.
                val cadrage = soccerCadrage.calculer(
                    tailleJoueurMoyenne = yoloHauteur,
                    nbJoueurs = nbJoueurs,
                    actionCx = centre.x,
                    actionCy = centre.y,
                    dirX = dir.dirX,
                    confiance = confObs)

                // 10) REALISATEUR (Phase 2, observation) : recherche locale predictive de
                //     l'altitude qui MAXIMISE le score de cadrage (confiance predite par
                //     candidat incluse). Ne commande RIEN ; on affiche l'altitude "optimisee".
                //     Altitude courante = AGL reel si dispo, sinon l'altitude planifiee.
                val altReelle = try { pont.altitudeDrone() } catch (_: Throwable) { Double.NaN }
                val altCourante = if (altReelle.isFinite() && altReelle > 0.0) altReelle else alt
                //     GRILLE 2D : optimise altitude ET decalage lateral ensemble.
                val realise = soccerDirector.realiser2D(
                    nanosMonotone = System.nanoTime(),
                    altitudeActuelleM = altCourante,
                    lateralActuelM = soccerLateralM,
                    tailleObserveeImg = yoloHauteur,
                    nbJoueurs = nbJoueurs,
                    actionCx = centre.x,
                    actionCy = centre.y,
                    dirX = dir.dirX,
                    plageMinM = phase.altMinM,
                    plageMaxM = phase.altMaxM,
                    confianceObservee = confObs)
                soccerLateralM = realise.lateralM   // memorise pour la frame suivante

                // 11) EMISSION REELLE 2D (Essai 1 : ALTITUDE SEULE). Le throttle vise
                //     l'altitude optimisee ; roll/pitch/yaw = 0. La commande passe
                //     OBLIGATOIREMENT par l'arbitre + double verrou. Inerte par defaut
                //     (SOCCER_2D_REAL_ENABLED=false, vMax=0).
                val throttle2Dcalcule = soccerAltThrottle.throttle(
                    altitudeOptimiseeM = realise.altitudeM,
                    altitudeActuelleM = altCourante,
                    vMaxMps = SOCCER_2D_MAX_VSPEED_MPS)
                // TEST E-01 AU SOL SEULEMENT (hélices retirées) : force un throttle POSITIF
                // connu (+0.2 = montée) pour vérifier le SIGNE au log. En production
                // (flag=false) on utilise la valeur calculée normale, rien n'est modifié.
                val throttle2D = if (TEST_E01_SIGNE_THROTTLE) 0.2f else throttle2Dcalcule
                // WATCHDOG (Phase 1.2) : si la boucle de decision s'est figee (dernier battement
                // trop vieux), on force le throttle a 0 AVANT l'arbitre. Barriere independante
                // du double verrou : un cycle mort ne peut plus commander de mouvement.
                val throttle2DSurveille = soccerWatchdog.filtrerThrottle(throttle2D, System.nanoTime())
                // On transmet le throttle DEMANDE et le throttle SURVEILLE : le journal 1.4
                // trace les deux (plus l'emis) pour reconstituer la decision apres coup.
                val emis2D = emettre2DSoccer(throttle2D, throttle2DSurveille)

                if (t != null) {
                    val srcTxt = if (multi) "%d joueurs".format(nbJoueurs) else "mono-cible"
                    val txt2 = "⚽ SOCCER — 2D TERRAIN (observation) — %s\n".format(srcTxt) +
                        "action=(%.2f,%.2f) dir=(%.2f,%.2f) v=%.2f\n".format(centre.x, centre.y, dir.dirX, dir.dirY, dir.vitesse) +
                        "cible=(%.2f,%.2f) vitesse=(%.2f,%.2f) %s\n".format(cible.x, cible.y, mv.vx, mv.vy, mv.reason.name) +
                        "camera: yaw=%.0f°/s tilt=%.1f°  plan=%s\n".format(aim.yawDps, aim.tiltStepDeg, plan.name) +
                        "phase=%s  alt=%.0f m (taille=%.2f)\n".format(phase.name, alt, yoloHauteur) +
                        "cadrage=%.0f%% (T%.0f N%.0f L%.0f S%.0f)\n".format(
                            cadrage.scoreCadrage * 100f, cadrage.taille * 100f, cadrage.nombre * 100f,
                            cadrage.leadRoom * 100f, cadrage.stabilite * 100f) +
                        "confiance×%.2f → final=%.0f%%\n".format(cadrage.facteurConfiance, cadrage.total * 100f) +
                        "🎬 réalisateur: alt=%.1f m %s  latéral=%.1f m %s".format(
                            realise.altitudeM,
                            when (realise.sens) { 1 -> "↑"; -1 -> "↓"; else -> "=" },
                            realise.lateralM,
                            when (realise.sensLateral) { 1 -> "→"; -1 -> "←"; else -> "=" }) +
                        (if (realise.zoomActif) "  zoom=%.2f×".format(realise.zoom) else "") +
                        "\n🔓 émission 2D: %s".format(emis2D)
                    runOnUiThread { t.text = txt2 }
                }
            }

            // Overlay YOLO (si affiche) : dessine la detection courante sur la video.
            // La cible Phase3 est unique -> une seule boite, construite depuis cx/cy/hauteur.
            val ov = overlayYoloP3
            if (ov != null && overlayYoloVisible) {
                val boxes = if (vue) {
                    val h = if (yoloHauteur > 0f) yoloHauteur else 0.2f
                    val w = (h * 0.6f).coerceIn(0.03f, 0.5f)   // largeur estimee depuis la hauteur
                    listOf(RecepteurBoxes.Box(
                        x = (yoloCx - w / 2f).coerceIn(0f, 1f),
                        y = (yoloCy - h / 2f).coerceIn(0f, 1f),
                        w = w, h = h, conf = yoloConf, sel = true
                    ))
                } else emptyList()
                runOnUiThread { ov.majBoxes(boxes) }
            }
        } catch (_: Throwable) { /* observation seulement : jamais bloquant */ }
    }

    private fun majSuivi(t: String) { runOnUiThread { txtSuivi.text = t } }

    // ── MENU PARCOURS : choisir une trace enregistree sur le serveur ────────────
    private fun majParcoursLabel() {
        val t = traceChoisieTitre
        txtParcours.text = if (t == null) {
            getString(R.string.p3_parcours_aucun)
        } else {
            val n = traceChoisie?.points?.size
            val suffixe = if (n != null) getString(R.string.p3_parcours_points, n)
                          else getString(R.string.p3_parcours_chargement)
            getString(R.string.p3_parcours_titre, t, suffixe)
        }
    }

    private fun choisirParcours() {
        txtParcours.text = getString(R.string.p3_parcours_chargement_liste)
        lifecycleScope.launch {
            val traces = try {
                ca.cineflight.stage.cine.ClientTraces.lister()
            } catch (_: Exception) { emptyList() }

            if (traces.isEmpty()) {
                majParcoursLabel()
                androidx.appcompat.app.AlertDialog.Builder(this@Phase3Activity)
                    .setTitle(getString(R.string.p3_dlg_parcours_titre))
                    .setMessage(getString(R.string.p3_dlg_aucun_msg))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val labels = traces.map { t ->
                val ty = when (t.type) {
                    "corridor_route" -> getString(R.string.p3_type_route)
                    "corridor_suivi" -> getString(R.string.p3_type_suivi)
                    else -> t.type
                }
                getString(R.string.p3_trace_label, t.titre.ifBlank { getString(R.string.p3_sans_titre) }, ty, t.nbPoints)
            }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this@Phase3Activity)
                .setTitle(getString(R.string.p3_dlg_choisir_titre))
                .setItems(labels) { _, i ->
                    val t = traces[i]
                    traceChoisieId = t.id
                    traceChoisieTitre = t.titre.ifBlank { t.id }
                    traceChoisie = null
                    majParcoursLabel()
                    // Charger le detail (geometrie) pour validation + info points.
                    lifecycleScope.launch {
                        traceChoisie = try {
                            ca.cineflight.stage.cine.ClientTraces.charger(t.id)
                        } catch (_: Exception) { null }
                        majParcoursLabel()
                        txtEtat.text = if (traceChoisie != null)
                            getString(R.string.p3_parcours_charge, traceChoisieTitre ?: "")
                        else
                            getString(R.string.p3_parcours_illisible)
                    }
                }
                .setNeutralButton(getString(R.string.p3_btn_aucun)) { _, _ ->
                    traceChoisieId = null; traceChoisieTitre = null; traceChoisie = null
                    majParcoursLabel()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> majParcoursLabel() }
                .show()
        }
    }

    private fun pretAVoler(): Boolean {
        val c = try { pont.estConnecte() } catch (_: Throwable) { false }
        val g = try { pont.gpsValide() } catch (_: Throwable) { false }
        val b = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        return c && g && b > 40
    }

    /** Confirmation avant une commande de vol (bouton rouge si "danger"). */
    private fun confirmerVol(titre: String, message: String, labelOui: String, danger: Boolean, onOui: () -> Unit) {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(message)
            .setNegativeButton("Annuler", null)
            .setPositiveButton(labelOui) { _, _ -> onOui() }
            .show()
        if (danger) dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFD32F2F.toInt())
    }

    private fun decoller() {
        if (enVol) { txtEtat.text = getString(R.string.p3_deja_envol); return }
        if (!pretAVoler()) {
            txtEtat.text = getString(R.string.p3_decollage_bloque)
            return
        }
        txtEtat.text = getString(R.string.p3_decollage_activation)
        pont.activerVirtualStick(true); vsActif = true
        pont.decoller { ok ->
            runOnUiThread {
                enVol = ok
                txtEtat.text = if (ok) getString(R.string.p3_decollage_ok)
                               else getString(R.string.p3_decollage_echec)
            }
        }
    }

    private fun demarrerSuivi() {
        if (!enVol) { txtEtat.text = getString(R.string.p3_decolle_dabord); return }
        if (!suiviVision && !reseauOk) { txtEtat.text = getString(R.string.p3_voiture_non_captee); return }
        // FAIL-CLOSED : en mode RTK, on n'arme pas le suivi si le RTK n'est pas exploitable.
        if (!suiviVision) {
            val r = etatFeuRtk()
            if (r.feu == FeuRtk.ROUGE) { afficherBlocageRtk(r); return }
        }
        // Si on etait en MODE MANUEL (ou VirtualStick coupe), l'app reprend la main
        // AVANT de lancer le suivi -> sinon la commande n'aurait aucun effet.
        if (!vsActif) { try { pont.activerVirtualStick(true) } catch (_: Exception) {}; vsActif = true }
        if (modeManuel) { modeManuel = false; majBoutonManuel() }
        // ETAPE 2 : repartir d'un historique de prediction propre (pas de residu
        // d'une session precedente qui fausserait vitesse/cap au demarrage).
        try { predicteur.reinitialiser() } catch (_: Exception) {}
        predPret = false
        dernierGimbalPitch = Double.NaN   // ETAPE 3 : forcer un 1er envoi de nacelle
        gimbalVisionDeg = Double.NaN
        mouvementActif = null; positionSuivi = "derriere"; phaseMouv = 0.0   // on demarre en suivi simple (derriere)
        azimutCourantDeg = Double.NaN
        suiviActif = true
        txtEtat.text = getString(R.string.p3_suivi_actif)
    }

    /** Change le mouvement cinematographique EN DIRECT (null = suivi simple). */
    /** Change l'ANGLE du suivi simple EN DIRECT (derriere/devant/gauche/droite/plongee). */
    private fun choisirPosition(pos: String) {
        mouvementActif = null
        positionSuivi = pos
        phaseMouv = 0.0
        azimutCourantDeg = Double.NaN     // repart de l'azimut REEL -> arc sur par l'arriere
        dernierGimbalPitch = Double.NaN
        txtEtat.text = when (pos) {
            "devant"  -> getString(R.string.p3_pos_msg_devant)
            "gauche"  -> getString(R.string.p3_pos_msg_gauche)
            "droite"  -> getString(R.string.p3_pos_msg_droite)
            "plongee" -> getString(R.string.p3_pos_msg_plongee)
            else      -> getString(R.string.p3_pos_msg_derriere)
        }
    }

    private fun choisirMouvement(mv: GenerateurMouvement.TypeMouvement?) {
        mouvementActif = mv
        positionSuivi = "derriere"        // tout choix de mouvement repasse derriere
        azimutCourantDeg = Double.NaN     // recalage de l'arc au retour vers une position
        phaseMouv = 0.0
        dernierGimbalPitch = Double.NaN   // forcer un reajustement de nacelle
        txtEtat.text = when (mv) {
            null -> getString(R.string.p3_pos_msg_derriere)
            GenerateurMouvement.TypeMouvement.ORBITE     -> getString(R.string.p3_mouv_msg_orbite)
            GenerateurMouvement.TypeMouvement.TRAVELLING -> getString(R.string.p3_mouv_msg_travelling)
            GenerateurMouvement.TypeMouvement.REVEAL     -> getString(R.string.p3_mouv_msg_reveal)
            GenerateurMouvement.TypeMouvement.RAPPROCHE  -> getString(R.string.p3_mouv_msg_rapproche)
        }
    }

    private fun arreterSuivi() {
        suiviActif = false
        hover()
        txtEtat.text = getString(R.string.p3_suivi_arrete)
    }

    /** Bascule le pilotage : AUTO (l'app commande) <-> MANUEL (telecommande DJI). */
    private fun basculerModeManuel() {
        if (!modeManuel) {
            // -> MANUEL : l'app lache le drone, la telecommande DJI reprend la main.
            modeManuel = true
            suiviActif = false
            if (vsActif) { try { pont.activerVirtualStick(false) } catch (_: Exception) {}; vsActif = false }
            txtEtat.text = getString(R.string.p3_manuel_on)
        } else {
            // -> AUTO : l'app reprend la main. En vol, elle tient la position (hover).
            modeManuel = false
            if (enVol && !vsActif) { try { pont.activerVirtualStick(true) } catch (_: Exception) {}; vsActif = true }
            txtEtat.text = if (enVol)
                getString(R.string.p3_manuel_off_envol)
            else
                getString(R.string.p3_manuel_off_sol)
        }
        majBoutonManuel()
    }

    /** Met a jour le libelle + la couleur du bouton manuel selon l'etat courant. */
    private fun majBoutonManuel() {
        if (!::btnManuel.isInitialized) return
        if (modeManuel) {
            btnManuel.text = getString(R.string.p3_btn_reprendre)
            btnManuel.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
        } else {
            btnManuel.text = getString(R.string.p3_btn_manuel)
            btnManuel.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF5D4037.toInt())
        }
    }

    private fun atterrir() {
        suiviActif = false
        pont.atterrir { ok ->
            runOnUiThread {
                if (ok) { enVol = false; txtEtat.text = getString(R.string.p3_atterri) }
                else txtEtat.text = getString(R.string.p3_atterrissage_echec)
            }
        }
        lifecycleScope.launch { delay(4000); pont.activerVirtualStick(false); vsActif = false }
    }

    /**
     * Arme ou desarme le mode soccer (bouton operateur). L'armement demande une
     * confirmation ; le desarmement est immediat. Ne fait qu'ARMER l'intention : le
     * mouvement reste soumis au double verrou, a l'arbitre et a toutes ses conditions.
     */
    /** Affiche / masque l'overlay des detections YOLO sur la video (mode soccer). */
    private fun basculerOverlayYolo() {
        overlayYoloVisible = !overlayYoloVisible
        overlayYoloP3?.visibility =
            if (overlayYoloVisible) android.view.View.VISIBLE else android.view.View.GONE
        btnOverlayYolo?.text =
            if (overlayYoloVisible) "Masquer les détections" else "Afficher les détections"
    }

    private fun basculerArmementSoccer() {
        if (soccerArme) {
            soccerArme = false
            soccerWatchdogIndep.desarmerSurveillance()  // desarmement volontaire, pas une defaillance
            majBoutonSoccer()
            return
        }
        confirmerVol(
            "Armer le mode SOCCER ?",
            "Le drone pourra se déplacer sur le rail si toutes les conditions de sécurité sont réunies. Le pilote garde la priorité et l'arrêt d'urgence désarme immédiatement.",
            "Armer", true
        ) {
            soccerArretUrgence = false     // un nouvel armement leve un ancien arret d'urgence
            soccerArme = true
            // WATCHDOG INDEPENDANT (REQ-WDG-001) : la confirmation du dialog EST la
            // decision humaine explicite -> reset d'un eventuel latch, armement de la
            // surveillance, demarrage du thread B (idempotent).
            soccerWatchdogIndep.reset()
            soccerWatchdogIndep.armer()
            soccerWatchdogIndep.demarrer()
            majBoutonSoccer()
        }
    }

    private fun majBoutonSoccer() {
        val b = btnSoccer ?: return
        if (soccerArme) {
            b.text = "Mode SOCCER : ARMÉ"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
        } else {
            b.text = "Mode SOCCER : désarmé"
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF455A64.toInt())
        }
    }

    // TEST E-06..E-09 : écrit une ligne de diagnostic dans un fichier sur le téléphone
    // (lecture sans câble ADB). Fail-open : n'interrompt jamais le flux. Actif seulement
    // quand TEST_E06_09_SECU = true.
    private fun logSecuTest(ligne: String) {
        if (!TEST_E06_09_SECU) return
        try {
            val f = java.io.File(getExternalFilesDir(null), "test_secu_e0x.log")
            java.io.FileOutputStream(f, true).use {
                it.write((ligne + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {}
    }

    private fun arretUrgence() {
        suiviActif = false
        soccerArme = false                // SECURITE : l'arret d'urgence desarme le soccer
        soccerArretUrgence = true         // ...et pose le drapeau (l'arbitre -> commande neutre)
        soccerWatchdogIndep.desarmerSurveillance()  // mode desarme -> surveillance suspendue
        // TEST E-08 : trace l'instant precis de la cessation.
        logSecuTest("E08 ts=${System.currentTimeMillis()} emergencyStop=true soccerArme=false vsActif=false raison=arret_urgence")
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {}
        try { pont.activerVirtualStick(false) } catch (_: Exception) {}
        vsActif = false
        modeManuel = true                 // apres l'arret, c'est la telecommande qui commande
        majBoutonManuel()
        majBoutonSoccer()                 // reflete le desarmement soccer dans l'UI
        txtEtat.text = getString(R.string.p3_urgence_msg)
    }

    // ── ETAT RTK (feu) : partage par le badge (en vol) et le blocage au demarrage ──
    private enum class FeuRtk { VERT, JAUNE, ROUGE }
    private data class EtatFeuRtk(val feu: FeuRtk, val pastille: String, val titre: String, val detail: String)

    private fun etatFeuRtk(): EtatFeuRtk {
        val q = autoRtk.uppercase()
        val ageTxt = if (!autoAgeS.isFinite()) "—" else "%.1f s".format(autoAgeS)
        // Ligne de diagnostic technique, ajoutee au message quand l'info est dispo.
        val diag = buildString {
            if (autoSats >= 0) append(getString(R.string.p3_diag_sats, autoSats))
            if (autoHacc.isFinite()) append(getString(R.string.p3_diag_precision, autoHacc))
        }
        return when {
            !reseauOk || autoLat.isNaN() ->
                EtatFeuRtk(FeuRtk.ROUGE, "🔴", getString(R.string.p3_rtk_nocapte_t),
                    getString(R.string.p3_rtk_nocapte_d, diag))
            autoAgeS > RTK_AGE_MAX_S ->
                EtatFeuRtk(FeuRtk.ROUGE, "🔴", getString(R.string.p3_rtk_stale_t),
                    getString(R.string.p3_rtk_stale_d, ageTxt, diag))
            q == "FIX" ->
                EtatFeuRtk(FeuRtk.VERT, "🟢", getString(R.string.p3_rtk_pret_t),
                    getString(R.string.p3_rtk_pret_d, ageTxt, diag))
            q == "FLOAT" || (AUTORISER_SUIVI_GPS && q == "GPS") ->
                EtatFeuRtk(FeuRtk.JAUNE, "🟡", getString(R.string.p3_rtk_presque_t),
                    getString(R.string.p3_rtk_presque_d, ageTxt, diag))
            else ->
                EtatFeuRtk(FeuRtk.ROUGE, "🔴", getString(R.string.p3_rtk_faible_t),
                    getString(R.string.p3_rtk_faible_d, diag))
        }
    }

    private fun majBadgeRtk() {
        if (!::pastilleRtk.isInitialized) return
        if (suiviVision) { pastilleRtk.visibility = android.view.View.GONE; return }
        val r = etatFeuRtk()
        pastilleRtk.visibility = android.view.View.VISIBLE
        pastilleRtk.text = getString(R.string.p3_rtk_badge, r.pastille, r.titre)
        pastilleRtk.setBackgroundColor(when (r.feu) {
            FeuRtk.VERT  -> 0xFF1B5E20.toInt()
            FeuRtk.JAUNE -> 0xFF7A5900.toInt()
            FeuRtk.ROUGE -> 0xFF8B1A1A.toInt()
        })
    }

    private fun afficherDetailRtk() {
        val r = etatFeuRtk()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${r.pastille} ${r.titre}")
            .setMessage(r.detail)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun afficherBlocageRtk(r: EtatFeuRtk) {
        txtEtat.text = getString(R.string.p3_suivi_bloque)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.p3_suivi_impossible_t))
            .setMessage(r.detail + getString(R.string.p3_suivi_impossible_m))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun rafraichir() {
        val c = try { pont.estConnecte() } catch (_: Throwable) { false }
        val g = try { pont.gpsValide() } catch (_: Throwable) { false }
        val b = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        val alt = try { pont.altitudeDrone() } catch (_: Throwable) { Double.NaN }
        val autoTxt = if (reseauOk) getString(R.string.p3_auto_ok, autoAgeS) else getString(R.string.p3_auto_non)

        // ── Barre d'infos du drone (mise a jour en direct) ──────────────────────
        val cap = try { pont.capDroneDeg() } catch (_: Throwable) { Float.NaN }
        val lat = try { pont.latitudeDrone() } catch (_: Throwable) { Double.NaN }
        val lon = try { pont.longitudeDrone() } catch (_: Throwable) { Double.NaN }
        val modele = try { pont.modeleDrone() } catch (_: Throwable) { "" }
        val battTxt = if (b in 0..100) "$b %" else "—"
        val capTxt = if (cap.isNaN()) "—" else "%.0f°".format(cap)
        val gpsTxt = when {
            !c -> getString(R.string.p3_gps_horsligne)
            g -> getString(R.string.p3_gps_precis)
            else -> getString(R.string.p3_gps_faible)
        }
        val posTxt = if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) "—"
                     else "%.5f, %.5f".format(lat, lon)
        val nomDrone = if (modele.isNullOrBlank()) "Drone" else modele
        txtTelemetrie.text =
            "🔋 $battTxt  ·  ⛰ ${fmtAlt(alt)}  ·  📡 $gpsTxt  ·  🧭 $capTxt\n" +
            "📍 $posTxt  ·  🚁 $nomDrone"
        when {
            !c -> setVoyant(getString(R.string.p3_voyant_non_connecte), 0xFF8B1A1A.toInt())
            modeManuel && enVol -> setVoyant(getString(R.string.p3_voyant_manuel), 0xFF4E342E.toInt())
            suiviActif -> setVoyant(getString(R.string.p3_voyant_suivi, fmtAlt(alt), b, autoTxt), 0xFF1B5E20.toInt())
            enVol -> setVoyant(getString(R.string.p3_voyant_envol, fmtAlt(alt), b, autoTxt), 0xFF1B5E20.toInt())
            pretAVoler() -> setVoyant(getString(R.string.p3_voyant_pret, autoTxt), 0xFF1B5E20.toInt())
            !g -> setVoyant(getString(R.string.p3_voyant_gps), 0xFF8B1A1A.toInt())
            b in 0..40 -> setVoyant(getString(R.string.p3_voyant_batt, b), 0xFF8B1A1A.toInt())
            else -> setVoyant(getString(R.string.p3_voyant_verif, autoTxt), 0xFF7A5900.toInt())
        }
        majBadgeRtk()
    }

    private fun fmtAlt(a: Double) = if (a.isNaN()) "—" else "%.1f m".format(a)
    private fun setVoyant(t: String, c: Int) { voyant.text = t; voyant.setBackgroundColor(c) }

    override fun onPause() {
        super.onPause()
        // securite : ecran en arriere-plan -> on coupe le suivi et l'envoi.
        suiviActif = false
        cmdHoverSiPossible()
        if (vsActif && !enVol) { try { pont.activerVirtualStick(false) } catch (_: Exception) {}; vsActif = false }
    }

    private fun cmdHoverSiPossible() {
        if (vsActif && enVol) { try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {} }
    }

    override fun onStart() {
        super.onStart()
        try { lecteurPerception.demarrer() } catch (_: Throwable) {}
    }

    override fun onStop() {
        try { lecteurPerception.arreter() } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { flux?.arreter() } catch (_: Exception) {}       // libere la video live
        try { yoloSuivi?.arreter() } catch (_: Exception) {}  // stoppe la detection YOLO
        try { lecteurPerception.arreter() } catch (_: Exception) {}
        try { soccerWatchdogIndep.arreter() } catch (_: Exception) {}  // stoppe le thread B
    }

    /**
     * MISE EN SECURITE declenchee par le WATCHDOG INDEPENDANT (REQ-WDG-001).
     * S'execute sur le THREAD B (independant) : ne touche PAS l'UI directement — si le
     * fil UI est gele, les actions de securite ci-dessous s'executent quand meme.
     * Actions (miroir de arretUrgence(), sans UI) : desarmement + drapeau urgence +
     * commande neutre directe + demande de sortie Virtual Stick + journalisation.
     * L'EFFET physique de la cessation reste a caracteriser par E-03.
     */
    private fun mettreEnSecuriteDepuisWatchdogIndep(ageMs: Long) {
        soccerArme = false
        soccerArretUrgence = true
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Throwable) {}
        try { pont.activerVirtualStick(false) } catch (_: Throwable) {}
        vsActif = false
        logSecuTest("WDG_INDEP ts=${System.currentTimeMillis()} defaillance=battement_perime age_ms=$ageMs actions=desarme+urgence+neutre+sortieVS")
        android.util.Log.e("CineFlightWDG",
            "WATCHDOG INDEPENDANT declenche (age=$ageMs ms) : desarmement + neutralisation + sortie Virtual Stick demandes")
        // UI en meilleur effort seulement (peut ne jamais s'executer si le fil UI est mort).
        runOnUiThread {
            try {
                majBoutonSoccer()
                txtEtat.text = "⛔ WATCHDOG INDÉPENDANT : boucle d'émission figée (${ageMs} ms) — mode désarmé, commande neutralisée, sortie Virtual Stick demandée."
            } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
