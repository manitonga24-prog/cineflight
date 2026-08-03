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
    private lateinit var btnCadrage: Button   // distance de cadrage réglable en direct
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
    // Recul DERRIÈRE le sujet, le long de son cap : suit le réglage de distance de cadrage.
    private val OFFSET_RECUL_M   get() = distanceCadrageM
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
    // ⚠ ARMÉ POUR LA CAMPAGNE E-03 (2026-07-22). Fournit le contexte d'émission au sol :
    // sans throttle NON NUL, la persistance ne peut pas être mesurée (E03-10 / FS3).
    // → REMIS À false LE 2026-08-03, campagne au banc terminée (11 scénarios sur 16
    //   qualifiés ; les 5 restants exigent le vol et n'ont pas besoin de ce drapeau).
    //   Il était resté armé depuis le 22 juillet : c'est LE drapeau qui force un throttle
    //   de +0,2 m/s en permanence — une montée continue dès l'armement du mode 2D.
    private val TEST_E01_SIGNE_THROTTLE = false   // ← test de SIGNE au SOL (+0.2 forcé). false = production.

    // ══ VOL RÉEL 2D — throttle CALCULÉ et BORNÉ (asservissement d'altitude) ══════════════
    // DÉCOUPLE l'émission 2D du test de signe au sol. Le test de signe force +0.2 (montée
    // continue) : DANGEREUX en vol. Le vol réel utilise le throttle calculé par
    // l'asservissement d'altitude, borné à une petite vitesse verticale (voir vMax).
    // ⚠ true UNIQUEMENT pour un essai en vol, hélices en place, APRÈS montée progressive
    // (stationnaire -> micro-mouvements bornés -> scénarios). Défaut false = production sûre.
    // Si les deux drapeaux sont true, le VOL RÉEL l'emporte (jamais de +0.2 en vol).
    private val SOCCER_2D_VOL_REEL = false

    /** Contexte d'émission 2D actif : test de signe AU SOL (+0.2) OU vol réel (throttle calculé).
     *  Active le profil personne, la boucle 2D, le miroir d'observation et l'émission réelle. */
    private val SOCCER_2D_EMISSION_ACTIVE get() = TEST_E01_SIGNE_THROTTLE || SOCCER_2D_VOL_REEL

    // ══ SUIVI ATHLÈTE (source ATHLETE_PHONE) — étape 5 de la spec d'intégration ══════════
    // Quand true : le poller réseau lit la position du SUJET depuis CineFlight Athlete
    // (GET /api/v1/subjects/{id}/latest) AU LIEU du RTK voiture, la passe par
    // AdaptateurSuiviAthlete (précision/âge/saut GPS), remplit les MÊMES variables
    // autoLat/autoLon/autoCap/autoAgeS et nourrit le prédicteur existant. tickSuivi est
    // INCHANGÉ (§18 : « seule la source change »). Profil forcé PERSONNE (MARCHE).
    // ⚠ SIMULATEUR DJI, hélices retirées — jamais en vol direct tant que non qualifié.
    // Défaut false = production. Le subject_id vient des prefs (écran Athlète le mémorise).
    // ⚠ MIS À true POUR L'ESSAI OBSERVATION (2026-07-24) — À REMETTRE À false APRÈS.
    // → REMIS À false LE 2026-08-03. Il était resté true avec OBSERVER à false, c'est-à-dire
    //   le suivi athlète armé en VOL RÉEL sans le garde-fou d'observation. Une combinaison
    //   que personne n'avait choisie : chaque drapeau avait été bougé pour un essai précis,
    //   et c'est leur CONJONCTION, jamais relue, qui armait le vol.
    private val SUIVI_ATHLETE_SIMU = false

    // MODE OBSERVATION (défaut true) : quand SUIVI_ATHLETE_SIMU est actif, le suivi CALCULE
    // et AFFICHE la commande qu'il enverrait, mais N'ACTIVE JAMAIS le Virtual Stick et
    // N'ENVOIE RIEN à l'aéronef. Les moteurs ne tournent pas → HÉLICES PEUVENT RESTER EN PLACE,
    // rien ne bouge. C'est le seul mode sûr tant que les hélices sont montées. Passer à false
    // UNIQUEMENT hélices retirées + simulateur DJI, pour l'essai Virtual Stick réel.
    // ⚠ MIS À false POUR L'ESSAI EN VOL RÉEL (2026-07-24) — le drone SUIT pour de vrai (VS actif).
    // → REMIS À true LE 2026-08-03 : l'observation est la valeur SÛRE, celle qui calcule et
    //   affiche sans jamais commander. C'est aussi ce que CLAUDE.md annonçait depuis le
    //   début (« défaut true ») alors que le code disait le contraire — l'écart est corrigé
    //   dans le sens du document, pas l'inverse.
    private val SUIVI_ATHLETE_OBSERVER = true
    /** Observation athlète active (calcule/affiche, n'envoie rien, VS jamais activé). */
    private val athleteObs get() = SUIVI_ATHLETE_SIMU && SUIVI_ATHLETE_OBSERVER

    // ══════════════════════════════════════════════════════════════════════════════
    // ⚠️ ESSAI E-03 AU BANC — HÉLICES RETIRÉES ⚠️ (caractérisation cessation Virtual Stick)
    // Quand true : (1) le simulateur DJI est activé au démarrage (drone posé = vol simulé),
    // (2) un panneau de déclencheurs E-03 est affiché (un bouton par famille de stimulus),
    // (3) chaque événement (T0..T6) est journalisé via EssaiE03Log dans un fichier.
    // NE JAMAIS voler avec ce drapeau actif. Émission réelle : réutilise le double verrou
    // E-01 (throttle borné), UNIQUEMENT au banc, hélices retirées.
    // ⚠ ARMÉ POUR LA CAMPAGNE E-03 (2026-07-22). À REMETTRE À false À LA FIN.
    // → REMIS À false LE 2026-08-03. Les essais AU BANC sont clos : 11 scénarios sur 16
    //   qualifiés, les 5 restants (10, 12, FS1-3) exigent le vol et passent par d'autres
    //   chemins. Le panneau de déclencheurs disparaît de l'écran, ce qui est voulu : un
    //   bouton d'essai visible en vol est un bouton qu'on finit par presser.
    private val TEST_E03_CESSATION_VS = false   // ← false = production.
    // SIMULATEUR DJI — INTERRUPTEUR SÉPARÉ (colonne « Env. » du tableau 36 du dossier).
    // Le simulateur valide l'OUTIL d'essai ; la chaîne RÉELLE valide la SÉCURITÉ.
    // Seuls E03-01/02/03/04 sont marqués « Simu+réel » : les 12 autres scénarios EXIGENT
    // le simulateur INACTIF, sinon le résultat est irrecevable. Ce drapeau doit donc
    // pouvoir être coupé SANS désactiver l'essai lui-même — d'où sa séparation.
    // La non-conformité est en outre détectée et journalisée (EssaiE03Config.nonConformiteEnv).
    // ⚠ SESSION 1 : true pour E03-01/02/03/04 (« Simu+réel »).
    // AVANT les 12 autres scénarios (« RÉEL »), REPASSER À false ET RECOMPILER,
    // sinon l'application refuse la répétition (ENV_NON_CONFORME) — c'est voulu.
    // SESSION 2 (2026-07-22) : passé à false pour les 12 scénarios « RÉEL ».
    private val TEST_E03_SIMULATEUR = false  // ← false = chaîne réelle (par défaut).
    // Version MSDK ÉPINGLÉE dans app/build.gradle (com.dji:dji-sdk-v5-aircraft).
    // Sert de repli traçable si la lecture à l'exécution échoue. À METTRE À JOUR si la
    // dépendance change — tout changement de configuration invalide les résultats E-03 (§381).
    private val MSDK_VERSION_BUILD = "5.18.0 (build.gradle)"
    // FENÊTRE D'OBSERVATION après un stimulus E-03, avant d'écrire la ligne de synthèse.
    // Doit couvrir le pire cas attendu : timeout du watchdog indépendant (500 ms) + marge
    // pour l'aller-retour SDK et le retour à zéro de la télémétrie. Trop court = tous les
    // scénarios rendraient FAIL par chronologie, pas par défaut de sécurité.
    private val E03_FENETRE_OBSERVATION_MS = 3000L
    /**
     * FENÊTRE D'OBSERVATION POUR UN STIMULUS PHYSIQUE (débrancher, éteindre, tuer).
     *
     * 3 secondes suffisent quand le stimulus est un appui logiciel. Elles sont hors de
     * portée quand l'opérateur doit saisir un câble et l'arracher : le 2026-07-22, trois
     * tentatives E03-07 d'affilée ont vu l'événement tomber APRÈS la fermeture de la
     * fenêtre (900 ms, 3,2 s et 3,9 s de retard). L'essai n'était pas sévère, il était
     * inexécutable.
     *
     * Allonger la fenêtre n'affaiblit aucun critère : la persistance se mesure de T0 à la
     * dernière commande non nulle observée, jamais sur la durée de la fenêtre.
     */
    private val E03_FENETRE_PHYSIQUE_MS = 15_000L

    // ── SURVEILLANCE THERMIQUE DU TÉLÉPHONE (E-03 scénario 13) ──────────────────
    //
    // POURQUOI. Un téléphone qui surchauffe est bridé par Android : la boucle pilote
    // ralentit, et à l'extrême le système tue l'application. Jusqu'ici l'application
    // n'observait AUCUNE température d'appareil — les seules températures connues venaient
    // de la météo, pour juger si le DRONE peut voler. La surchauffe du téléphone, qui est
    // pourtant ce qui met la boucle de commande en danger, n'était pas surveillée.
    //
    // SEUIL RETENU : `THERMAL_STATUS_SEVERE`. En dessous (LIGHT, MODERATE), le bridage ne
    // gêne pas une boucle à 10 Hz — déclencher là poserait des mises en sécurité inutiles.
    // À partir de SEVERE, Android annonce un bridage lourd du processeur.
    //
    // ⚠ CE SEUIL N'EST PAS CRITIQUE, et c'est important pour le dossier : le watchdog
    // indépendant détecte DÉJÀ un fil d'émission ralenti ou gelé, quelle qu'en soit la
    // cause. Cette surveillance thermique est une détection PRÉCOCE et spécifique à la
    // cause — une défense en profondeur, pas la protection principale. Si le seuil est trop
    // haut, le watchdog rattrape la conséquence ; on ne fait donc pas reposer une décision
    // de sécurité sur une valeur arbitraire.
    //
    // API 29 minimum (`PowerManager.addThermalStatusListener`). En dessous, la surveillance
    // n'existe pas et le journal le dit — on ne laisse pas croire qu'elle veille.
    private val E03_SEUIL_THERMIQUE = 3   // android.os.PowerManager.THERMAL_STATUS_SEVERE

    /** Dernier niveau thermique connu. -1 = aucun relevé encore reçu. */
    @Volatile private var niveauThermiqueDernier: Int = -1

    private var ecouteurThermique: android.os.PowerManager.OnThermalStatusChangedListener? = null

    private fun libelleThermique(n: Int): String = when (n) {
        0 -> "AUCUN"; 1 -> "LEGER"; 2 -> "MODERE"; 3 -> "SEVERE"
        4 -> "CRITIQUE"; 5 -> "URGENCE"; 6 -> "ARRET_IMMINENT"; else -> "INCONNU_$n"
    }

    /**
     * Branche la surveillance thermique. Idempotent. Sans effet sous API 29 — et la ligne
     * de journal le CONSIGNE, pour qu'un lecteur ne suppose pas une surveillance absente.
     */
    private fun brancherSurveillanceThermique() {
        if (ecouteurThermique != null) return
        if (android.os.Build.VERSION.SDK_INT < 29) {
            if (TEST_E03_CESSATION_VS) {
                logE03("E03 THERMIQUE surveillance=INDISPONIBLE api=${android.os.Build.VERSION.SDK_INT} " +
                       "requis=29 consequence=scenario_13_non_mesurable_sur_cet_appareil")
            }
            return
        }
        val pm = try {
            getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        } catch (_: Throwable) { null } ?: return
        val l = android.os.PowerManager.OnThermalStatusChangedListener { niveau ->
            // TRANSITION, PAS ÉTAT — même règle que la perte d'aéronef. On ne réagit qu'à
            // l'ENTRÉE dans la zone sévère. Sans cela, un appareil déjà chaud à l'ouverture
            // de l'écran déclencherait un arrêt d'urgence avant toute action de l'opérateur,
            // et chaque rappel répété du système en déclencherait un nouveau.
            val avant = niveauThermiqueDernier
            val entreeEnZoneSevere = avant < E03_SEUIL_THERMIQUE && niveau >= E03_SEUIL_THERMIQUE
            niveauThermiqueDernier = niveau
            if (TEST_E03_CESSATION_VS) {
                logE03("E03 THERMIQUE niveau=$niveau libelle=${libelleThermique(niveau)} " +
                       "precedent=${if (avant < 0) "inconnu" else libelleThermique(avant)} " +
                       "seuil=${libelleThermique(E03_SEUIL_THERMIQUE)} " +
                       "transition=${if (entreeEnZoneSevere) "ENTREE_ZONE_SEVERE" else "aucune"} " +
                       "soccerArme_avant=$soccerArme " +
                       "effet=${if (entreeEnZoneSevere && soccerArme) "arret_urgence_desarme_le_soccer" else "aucun"} " +
                       "ts=${System.currentTimeMillis()}")
            }
            // On n'agit que si l'automatisme est armé : sans mode armé, il n'y a aucune
            // commande à faire cesser, et couper serait une mise en sécurité sans objet.
            if (entreeEnZoneSevere && soccerArme) {
                if (TEST_E03_CESSATION_VS) e03Capteur.marquerT1()
                runOnUiThread { try { arretUrgence() } catch (_: Throwable) {} }
            }
        }
        try {
            pm.addThermalStatusListener(mainExecutor, l)
            ecouteurThermique = l
            if (TEST_E03_CESSATION_VS) {
                logE03("E03 THERMIQUE surveillance=BRANCHEE niveau_initial=" +
                       "${try { libelleThermique(pm.currentThermalStatus) } catch (_: Throwable) { "inconnu" }} " +
                       "seuil=${libelleThermique(E03_SEUIL_THERMIQUE)}")
            }
        } catch (_: Throwable) { ecouteurThermique = null }
    }

    /** Retire la surveillance thermique (fin de premier plan). Idempotent. */
    private fun debrancherSurveillanceThermique() {
        val l = ecouteurThermique ?: return
        ecouteurThermique = null
        if (android.os.Build.VERSION.SDK_INT < 29) return
        try {
            (getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.removeThermalStatusListener(l)
        } catch (_: Throwable) {}
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // ESSAI E03-05 — CRASH DU PROCESSUS (mesure par handler de dernier recours)
    // ══════════════════════════════════════════════════════════════════════════════
    // PROBLÈME STRUCTUREL : un process tué ne peut plus écrire son journal. L'instrumentation
    // T0..T5 + fenêtre d'observation ne peut donc PAS se mesurer elle-même pour ce scénario
    // (la coroutine de synthèse meurt avec le process). On mesure autrement.
    //
    // CE QUE E03-05 PROUVE, ET RIEN DE PLUS :
    //  - l'émetteur de commandes (boucle pilote + SDK DJI) vit DANS ce process → quand le
    //    process meurt, l'émission cesse INSTANTANÉMENT : aucune commande ne peut survivre à
    //    la mort du process. La persistance après crash est NULLE PAR CONSTRUCTION, pas par
    //    une mesure de délai — il n'y a plus personne pour émettre.
    //  - le marqueur final consigne DEPUIS COMBIEN DE TEMPS l'aéronef était commandé au moment
    //    du crash (dernière commande non nulle), ce qui atteste que la commande coulait bien
    //    juste avant, et que c'est BIEN le crash qui l'a interrompue.
    // CE QUE E03-05 NE COUVRE PAS : la protection de l'aéronef APRÈS la mort de l'app, qui est
    // le timeout Virtual Stick du DRONE (firmware DJI). Cela relève d'un essai EN VOL, pas du
    // banc — à écrire ainsi au dossier.

    /** Nanos monotone de la DERNIÈRE commande NON NULLE réellement émise (-1 = aucune). */
    private val e03DerniereEmissionNonNulleNanos = java.util.concurrent.atomic.AtomicLong(-1L)

    /** Handler de crash présent AVANT le nôtre, pour le CHAÎNER (laisser le process mourir). */
    @Volatile private var e03HandlerCrashPrecedent: Thread.UncaughtExceptionHandler? = null

    /**
     * Installe un `UncaughtExceptionHandler` qui, sur un crash NON intercepté, écrit une
     * dernière ligne `E03 CRASH_PROCESS` PUIS chaîne au handler précédent — le process meurt
     * donc normalement, on n'avale RIEN. Idempotent. Actif seulement en build d'essai.
     */
    private fun installerHandlerCrashE03() {
        if (e03HandlerCrashPrecedent != null) return
        e03HandlerCrashPrecedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val derN = e03DerniereEmissionNonNulleNanos.get()
                val depuisMs = if (derN > 0L) (System.nanoTime() - derN) / 1_000_000 else -1L
                val ligne = "E03 CRASH_PROCESS thread=${thread.name} " +
                    "exception=${ex.javaClass.simpleName} " +
                    "derniere_emission_non_nulle_il_y_a_ms=$depuisMs " +
                    "persistance_apres_crash=NULLE_PAR_CONSTRUCTION_emetteur_in_process " +
                    "note=le_process_et_le_SDK_DJI_meurent_ensemble_aucune_commande_ne_survit " +
                    "ts=${System.currentTimeMillis()}\n"
                java.io.FileOutputStream(java.io.File(getExternalFilesDir(null), "essai_e03.log"), true)
                    .use { it.write(ligne.toByteArray(Charsets.UTF_8)); it.flush() }
            } catch (_: Throwable) { /* on meurt de toute façon : best-effort */ }
            // CHAÎNE OBLIGATOIRE : laisser ART tuer le process. Ne JAMAIS avaler le crash.
            e03HandlerCrashPrecedent?.uncaughtException(thread, ex)
        }
    }
    // ══════════════════════════════════════════════════════════════════════════════
    // ⚠️⚠️ MODE BANC E-03 — DÉROGATION DE SÉCURITÉ DÉLIBÉRÉE, HÉLICES RETIRÉES ⚠️⚠️
    // ══════════════════════════════════════════════════════════════════════════════
    // POURQUOI CE MODE EXISTE
    // L'arbitre exige `inFlightCompatible=true` pour autoriser la moindre émission. C'est
    // correct en exploitation : on n'envoie pas de commande à un aéronef au sol. Mais le
    // §378 impose que E-03 soit un essai AU BANC, hélices retirées — donc JAMAIS en vol.
    // Sans dérogation, l'arbitre bloque tout, aucune commande n'est émise, et l'essai ne
    // peut produire AUCUNE mesure (constat de terrain 2026-07-22 : `emis=true` absent de
    // tout le journal, raison="soccer bloque : virtual_stick_indisponible").
    //
    // CE QUE LA DÉROGATION FAIT, EXACTEMENT
    //   1. autorise `activerVirtualStick(true)` sans décollage ;
    //   2. force le bit IF (inFlightCompatible) du SafetySnapshot ;
    //   3. empêche les points qui coupent le VS quand `enVol==false` de le redésactiver.
    // Elle NE touche à AUCUN autre bit : PO, EM, OG, RV, PF, AC, BO, CC, ON restent juges.
    //
    // CE QU'ELLE NE PEUT PAS FAIRE — À NE PAS MASQUER DANS LE DOSSIER
    // Hélices retirées, les moteurs ne tournent pas : la persistance PHYSIQUE (T6) n'est
    // PAS mesurable au banc. Seuls T0..T5 et la persistance DE COMMANDE le sont. Le
    // journal porte `banc=OUI` sur CHAQUE ligne pour qu'aucune mesure de banc ne puisse
    // être présentée comme une mesure en vol.
    //
    // VERROUS
    //   - inopérant si TEST_E03_CESSATION_VS == false ;
    //   - armement REFUSÉ si l'aéronef est réellement en vol (`enVol==true`) ;
    //   - désarmement automatique si l'aéronef décolle pendant l'essai ;
    //   - armement MANUEL par bouton : jamais actif au simple lancement de l'écran.
    // ⚠ REMETTRE À false APRÈS LA CAMPAGNE. Ne JAMAIS committer à true.
    private val TEST_E03_BANC = false   // ← false = production / VOL RÉEL (dérogation banc retirée, 2026-07-23).
    // ══════════════════════════════════════════════════════════════════════════════
    // DIAGNOSTIC — génération de charge pour la campagne anti-faux-positif.
    // Le resserrement du budget watchdog (500→350 ms) rapproche le seuil de la gigue
    // d'ordonnancement Android. Il faut démontrer que NI le watchdog de cycle NI le
    // watchdog indépendant ne déclenchent sous charge. Ces boutons produisent une charge
    // REPRODUCTIBLE ; ils ne remplacent pas les manipulations réelles (rotation, arrière-
    // plan, verrouillage, app lourde, changement de réseau, session longue), qui seules
    // sont représentatives.
    // ⚠ Drapeau de DIAGNOSTIC : jamais à true en production. 5e drapeau à remettre à false.
    private val TEST_E03_STRESS = false   // ← false = production (campagne de charge terminée, 2026-07-23).
    private val E03_STRESS_DUREE_MS = 30_000L
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
        if (SOCCER_2D_EMISSION_ACTIVE) ProfilSujetMobile.MARCHE
        else if (SUIVI_ATHLETE_SIMU) ProfilSujetMobile.MARCHE   // athlète = personne
        else if (intent?.getStringExtra("PROFIL_SUJET") == "MARCHE") ProfilSujetMobile.MARCHE
        else ProfilSujetMobile.AUTO
    }

    // ── SUIVI ATHLÈTE (étape 5) : source ATHLETE_PHONE au lieu du RTK voiture ────────────
    private val adaptateurAthlete = ca.cineflight.stage.athlete.AdaptateurSuiviAthlete()
    private val evaluateurAthlete = ca.cineflight.stage.athlete.EvaluateurSourceAthlete()
    // UN SEUL détecteur d'immobilité inertielle (CoreMotion) : le même verdict alimente
    // l'évaluateur ET l'adaptateur — deux instances divergeraient (hystérésis séparées).
    private val immobiliteAthlete = ca.cineflight.stage.athlete.ImmobiliteInertielle()
    @Volatile private var athleteEtat = ca.cineflight.stage.athlete.EvaluateurSourceAthlete.Etat.PERDU
    /** Dernière raison de l'adaptateur athlète (ok / precision_… / saut_… / donnee_perimee_…). */
    @Volatile private var athleteRaison = "en attente"
    /** Vitesse mini (m/s) pour juger le cap athlète FIABLE. En dessous : cap gelé (anti-360). */
    private val CAP_ATHLETE_VITESSE_MIN = 0.5
    // Horodatage du dernier log CF_SuiviAthlete (instrumentation ~1 Hz du suivi athlète).
    private var dernierLogAthleteMs = 0L
    // CAP DÉRIVÉ DE LA TRAJECTOIRE : comble l'absence de cap des DEUX sources (boîtier RTK
    // et iPhone athlète). Sans lui, les angles derrière/devant/gauche/droite sont inopérants.
    private val capDeplacement = ca.cineflight.stage.control.CapParDeplacement()
    // Journal PERSISTANT du vol (singleton : un aéronef, un vol à la fois).
    private val journalVol get() = ca.cineflight.stage.control.JournalVol
    private val URL_ATHLETE_BASE = "https://cineflight.ca"
    /** subject_id mémorisé par l'écran Athlète (prefs). Lu À CHAQUE FOIS (pas de cache lazy :
     *  sinon ouvrir Phase 3 avant de choisir l'athlète le figerait à vide). Vide -> suivi refusé. */
    private val athleteSubjectId: String
        get() = getSharedPreferences("cineflight", MODE_PRIVATE).getString("athlete_subject_id", "") ?: ""
    // Mode ATHLÈTE : fenêtres temporelles adaptées à la cadence iPhone (~2 s/POST). Avec la
    // config RTK voiture (ageMax=0,5 s...), la prédiction était inopérante (predPret jamais
    // vrai) et le drone suivait la position brute par bonds de 2 s. Voir ProfilSujetMobile.
    private val predicteur by lazy {
        DiagnosticPredictionRtk(
            if (SUIVI_ATHLETE_SIMU) profilSujet.configurationPredictionAthlete()
            else profilSujet.configurationPrediction())
    }
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
    // MIGRATION SafetyLimits (v54) : seuils de securite consommes depuis la SOURCE UNIQUE
    // (ca.cineflight.stage.control.SafetyLimits). Ne PAS redeclarer de litteraux ici —
    // garde anti-reintroduction : SafetyLimitsSourceGuardTest.
    private val YOLO_CONF_MIN      = ca.cineflight.stage.control.SafetyLimits.YOLO_CONF_MIN
    private val YOLO_FRAIS_MS      = ca.cineflight.stage.control.SafetyLimits.YOLO_FRAIS_MS
    private val GAIN_YAW_YOLO      = 40f               // deg/s par unite d'ecart image (borne ↓)
    private val YAW_YOLO_MAX_DPS   = 10f               // le nudge yaw YOLO ne depasse jamais ca

    // ── SPORT SOCCER — MIROIR DE MOUVEMENT (Phase 9B) ────────────────────────────
    // false = comportement identique a aujourd'hui : le pipeline ne tourne pas.
    // true  = on OBSERVE seulement (log SOCCER_RAIL_MIRROR, soccer_motion_applied=false).
    // Ce flag est SEPARE du flag d'emission reelle (jamais actives implicitement ensemble).
    // Active par le mode SOCCER (extra MODE_SOCCER) : observation a l'ecran, aucun mouvement.
    // TEST E-01 : ouvre le miroir de mouvement (sinon observerMiroirMouvementSoccer, qui
    // contient le bloc d'emission 2D, n'est JAMAIS appele). Flag=false -> false comme avant.
    @Volatile private var SOCCER_RAIL_MIRROR_ENABLED = SOCCER_2D_EMISSION_ACTIVE
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
    private val SOCCER_2D_REAL_ENABLED = SOCCER_2D_EMISSION_ACTIVE
    // BORNE de vitesse verticale : vol réel = petite vitesse PRUDENTE (0.5 m/s) pour les
    // premiers essais ; test de signe au sol = 0.2 ; sinon inerte (0).
    private val SOCCER_2D_MAX_VSPEED_MPS = when {
        SOCCER_2D_VOL_REEL -> 0.5f
        TEST_E01_SIGNE_THROTTLE -> 0.2f
        else -> 0f
    }
    private val SOCCER_BATT_MIN_PCT = ca.cineflight.stage.control.SafetyLimits.BATTERIE_MIN_PCT  // source unique (v54)
    private val SOCCER_DIST_MAX_M = ca.cineflight.stage.control.SafetyLimits.DIST_OPERATEUR_MAX_M // source unique (v54)
    // ARMEMENT runtime : le mode soccer n'est "arme" que si l'operateur l'a active ET
    // que le flag reel l'autorise. Toute reprise pilote / arret d'urgence le desarme.
    // TEST E-01 : auto-arme le mode soccer quand le flag de test est actif (le bouton
    // d'armement n'est visible que si SOCCER_RAIL_REAL_ENABLED, hors scope du test 2D).
    // En production (flag=false) -> false comme avant. REMETTRE le flag a false apres le test.
    /**
     * MODE SOCCER ARMÉ — TOUJOURS false au démarrage.
     *
     * DÉFAUT CORRIGÉ (2026-07-22). Ce champ valait `TEST_E01_SIGNE_THROTTLE`, donc `true`
     * dès l'ouverture de l'écran. Trois conséquences, toutes constatées au banc :
     *
     *  1. Le bouton est CRÉÉ avec le libellé « Mode SOCCER : désarmé » et `majBoutonSoccer()`
     *     n'était pas appelé à la construction : l'écran affichait « désarmé » alors que
     *     l'état interne disait « armé ». Les deux se contredisaient dès la première seconde.
     *  2. `basculerArmementSoccer()` teste `if (soccerArme)` en tête et se contente
     *     d'afficher « maintenir 3 s pour désarmer ». Un appui sur un bouton annoncé
     *     « désarmé » ne pouvait donc PAS armer — sans dialogue, sans trace au journal.
     *     Relevé du 2026-07-22 : aucune ligne `SOCCER_ARME` sur une session de 20 minutes,
     *     et `etait_arme=NON` sur tous les désarmements de cycle de vie.
     *  3. `soccerArme=true` sans que le thread du watchdog indépendant ait jamais démarré,
     *     et sans épinglage ni maintien d'écran (tous deux posés à l'armement) : exactement
     *     l'état menteur « armé sans détecteur » corrigé par ailleurs, réintroduit ici par
     *     une valeur initiale.
     *
     * RÈGLE : le mode automatique s'arme par une décision HUMAINE, jamais par un drapeau de
     * compilation. La série d'essai le réarme explicitement (`e03RearmerSoccerAuto`), donc
     * rien ne dépend de cette pré-initialisation.
     */
    @Volatile private var soccerArme = false
    @Volatile private var soccerArretUrgence = false

    /**
     * Raison du dernier désarmement provoqué par le CYCLE DE VIE (sortie du premier plan),
     * en attente d'être annoncée au pilote à son retour. null = rien à annoncer.
     *
     * Le message survit à l'aller-retour arrière-plan : sans lui, le pilote retrouverait un
     * bouton « désarmé » sans savoir pourquoi et pourrait croire à une fausse manœuvre.
     */
    @Volatile private var soccerDesarmeParCycleVie: String? = null
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
    // ── DISTANCE DE CADRAGE réglable (2026-07-25) ───────────────────────────────────
    // Constat terrain : « le 360 autour de moi est très éloigné ». La distance venait du
    // PROFIL mémorisé (VELO = 20 m) sans que le pilote puisse la changer sur place.
    // Elle est désormais un RÉGLAGE explicite, mémorisé, borné par le plancher de sécurité
    // du noyau (jamais sous distanceMinM : 3,5 m personne / 8 m véhicule).
    private val PALIERS_PERSONNE = doubleArrayOf(4.0, 6.0, 9.0, 14.0)
    private val PALIERS_VEHICULE = doubleArrayOf(12.0, 20.0, 30.0, 45.0)
    private val paliersCadrage get() =
        if (profilSujet == ProfilSujetMobile.MARCHE) PALIERS_PERSONNE else PALIERS_VEHICULE
    private val CLE_CADRAGE = "cadrage_distance_idx"
    // ⚠ NE PAS lire les préférences à l'INITIALISATION DU CHAMP : le contexte de
    // l'Activity n'est pas encore prêt -> crash à l'ouverture de l'écran (constaté
    // 2026-07-25). Lecture PARESSEUSE, au premier accès (donc après onCreate).
    private var idxCadrageCache: Int = -1
    private var idxCadrage: Int
        get() {
            if (idxCadrageCache < 0) {
                idxCadrageCache = try {
                    getSharedPreferences("cineflight", MODE_PRIVATE).getInt(CLE_CADRAGE, 0)
                } catch (_: Throwable) { 0 }.coerceIn(0, 3)
            }
            return idxCadrageCache
        }
        set(v) { idxCadrageCache = v.coerceIn(0, 3) }
    /** Distance drone-sujet demandée (m), bornée au plancher de sécurité du profil. */
    private val distanceCadrageM: Double get() {
        val plancher = if (profilSujet == ProfilSujetMobile.MARCHE) 3.5 else 8.0
        return paliersCadrage[idxCadrage.coerceIn(0, paliersCadrage.size - 1)]
            .coerceAtLeast(plancher)
    }

    private val DIST_MOUV_M  get() = distanceCadrageM             // rayon d'orbite / mouvements

    // ── LIMITES DE SECURITE ─────────────────────────────────────────────────────
    private val V_MAX_HORIZ = 2.0f          // m/s horizontal max — réduit pour les 1ers essais de suivi (était 4.0)
    private val V_MAX_VERT   = 2.0f         // m/s vertical max
    private val GAIN_P       = 0.6f         // gain proportionnel (vitesse = P * ecart)
    private val ZONE_MORTE_M = 1.0          // en-deca, on ne bouge pas (anti-oscillation)
    private val RTK_AGE_MAX_S = ca.cineflight.stage.control.SafetyLimits.RTK_AGE_MAX_S   // source unique (v54) ; au-dela : commande neutralisee/bloquee (E-03)
    private val DIST_MAX_M   = ca.cineflight.stage.control.SafetyLimits.DIST_DECROCHAGE_M // source unique (v54)
    private val ALT_MAX_M    = ca.cineflight.stage.control.SafetyLimits.ALT_MAX_M         // source unique (v54)

    // Serveur relais RTK (meme hote que CineFlight)
    private val URL_RTK = "https://cineflight.ca/api/rtk/sujet"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ESSAI E03-05 : handler de crash de dernier recours (écrit le marqueur avant la mort
        // du process, puis chaîne). Uniquement en build d'essai. Aucun effet en production.
        if (TEST_E03_CESSATION_VS) try { installerHandlerCrashE03() } catch (_: Throwable) {}

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        racine.addView(TextView(this).apply {
            // TITRE selon le MODE réel : soccer, suivi de PERSONNE (profil MARCHE) ou de
            // VÉHICULE. Avant, l'écran de suivi personne affichait « Suivre le véhicule » car
            // le titre ne distinguait que soccer / non-soccer.
            text = when {
                intent?.getBooleanExtra("MODE_SOCCER", false) == true -> getString(R.string.p3_titre_soccer)
                profilSujet == ProfilSujetMobile.MARCHE -> getString(R.string.p3_titre_personne)
                else -> getString(R.string.p3_titre)
            }
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
        // SIMULATEUR — RÉSERVÉ AU MODE DÉVELOPPEUR (build debug ou préférence explicite).
        // Cet écran sert au VRAI suivi : un bouton de simulation au milieu des commandes
        // normales laissait croire que la simulation est une étape obligatoire ou un mode de
        // vol ordinaire. Il reste dans le code pour les tests, mais invisible pour
        // l'utilisateur final (et toujours caché en mode soccer).
        val simulateurAutorise = ca.cineflight.stage.BuildConfig.DEBUG ||
            getSharedPreferences("cineflight", MODE_PRIVATE).getBoolean("mode_developpeur", false)
        btnSimPhase3 = bouton(getString(R.string.p3_btn_sim_off), 0xFF455A64.toInt()) { basculerSimulateur() }
        racine.addView(btnSimPhase3.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
            visibility = if (simulateurAutorise && !estModeSoccer)
                android.view.View.VISIBLE else android.view.View.GONE
        })
        // TEST AXES HORIZONTAUX EN SIMULATEUR (2026-07-25). Vol réel du jour : au lieu de
        // suivre, le drone ORBITAIT autour du sujet (r≈25 m = 2 m/s ÷ 4,5°/s) — signature
        // exacte d'une commande « avant » exécutée EN LATÉRAL, le piège pitch/roll connu du
        // Virtual Stick DJI (INVERSER_ROLL_PITCH existe pour ça, jamais vérifié : E-01 n'a
        // testé que le throttle, les axes horizontaux sont invérifiables au sol). Ce test
        // mesure la sémantique RÉELLE dans le simulateur : hélices retirées, zéro risque.
        racine.addView(bouton("🧭 TEST AXES (SIMULATEUR)", 0xFF6A1B9A.toInt()) { testAxesSimulateur() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
            visibility = if (simulateurAutorise && !estModeSoccer)
                android.view.View.VISIBLE else android.view.View.GONE
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
            // Ouvre l'ecran Live (cle synchronisee auto). Le direct se lance ensuite
            // avec le bouton "Demarrer le direct".
            startActivity(android.content.Intent(this, LiveStreamActivity::class.java))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) }
        })

        // 🎬 ANGLE / MOUVEMENT : change en direct pendant le suivi. (Vehicule seulement.)
        // Boutons plus HAUTS + peu de marge interne pour que le texte (icône + mot, jusqu'à
        // 2 lignes) reste lisible dans le panneau étroit. Poids égal -> largeur uniforme.
        fun poidsBtn() = LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        fun boutonPos(txt: String, pos: String, couleur: Int) =
            bouton(txt, couleur) { choisirPosition(pos) }.apply {
                textSize = 13f; maxLines = 2; setPadding(dp(2), dp(4), dp(2), dp(4)); layoutParams = poidsBtn()
            }
        fun boutonMouv(txt: String, mv: GenerateurMouvement.TypeMouvement, couleur: Int) =
            bouton(txt, couleur) { choisirMouvement(mv) }.apply {
                textSize = 13f; maxLines = 2; setPadding(dp(2), dp(4), dp(2), dp(4)); layoutParams = poidsBtn()
            }
        fun sousTitreSection(cle: Int, hautDp: Int) {
            racine.addView(TextView(this).apply {
                text = getString(cle); setTextColor(0xFFB0BEC5.toInt()); textSize = 12f
                setPadding(dp(2), dp(hautDp), 0, dp(4))
                if (estModeSoccer) visibility = GONE
            })
        }
        fun rangeeBoutons(vararg vues: android.view.View) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            vues.forEach { r.addView(it) }
            if (estModeSoccer) r.visibility = GONE
            racine.addView(r, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        // ══ SECTION 1 — ANGLE DE DÉPART : OÙ le drone se place par rapport au sujet. ══
        sousTitreSection(R.string.p3_section_angle, 14)
        rangeeBoutons(
            boutonPos(getString(R.string.p3_angle_derriere), "derriere", 0xFF37474F.toInt()),
            boutonPos(getString(R.string.p3_angle_devant), "devant", 0xFF4E342E.toInt()),
            boutonPos(getString(R.string.p3_angle_gauche), "gauche", 0xFF37474F.toInt()))
        rangeeBoutons(
            boutonPos(getString(R.string.p3_angle_droite), "droite", 0xFF37474F.toInt()),
            boutonPos(getString(R.string.p3_angle_plongee), "plongee", 0xFF4527A0.toInt()))
        // ══ DISTANCE DE CADRAGE : réglable EN DIRECT (le 360 était trop éloigné, 2026-07-25).
        // Un seul bouton qui cycle les paliers : lisible d'un coup d'œil, utilisable en vol.
        btnCadrage = bouton("", 0xFF00838F.toInt()) { cyclerDistanceCadrage() }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
            if (estModeSoccer) visibility = GONE
        }
        racine.addView(btnCadrage)
        majBoutonCadrage()

        // ══ SECTION 2 — MOUVEMENT DU PLAN : CE QUE le drone fait pendant l'enregistrement. ══
        sousTitreSection(R.string.p3_section_mouvement, 12)
        rangeeBoutons(
            boutonMouv(getString(R.string.p3_mouv_orbite), GenerateurMouvement.TypeMouvement.ORBITE, 0xFF00695C.toInt()),
            boutonMouv(getString(R.string.p3_mouv_reveal), GenerateurMouvement.TypeMouvement.REVEAL, 0xFF00695C.toInt()))
        rangeeBoutons(
            boutonMouv(getString(R.string.p3_mouv_rapproche), GenerateurMouvement.TypeMouvement.RAPPROCHE, 0xFF00695C.toInt()),
            boutonMouv(getString(R.string.p3_mouv_travelling), GenerateurMouvement.TypeMouvement.TRAVELLING, 0xFF00695C.toInt()))

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

        // RTH (2026-07-25) : demandé pour les 3 modes de suivi (boîtier, voiture, vision).
        // Séquence sûre : suivi coupé -> commande neutre -> VS rendu -> KeyStartGoHome.
        racine.addView(bouton(getString(R.string.p3_btn_rth), 0xFFEF6C00.toInt()) {
            confirmerVol(getString(R.string.p3_rth_titre), getString(R.string.p3_rth_msg),
                getString(R.string.p3_rth_oui), false) { lancerRthSuivi() }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
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

        // BOUTON MODE SOCCER (armer/desarmer). Visible si l'un OU l'autre des modes
        // automatiques est compilé actif.
        //
        // DÉFAUT CORRIGÉ (2026-07-22) : la condition ne testait que SOCCER_RAIL_REAL_ENABLED
        // (mode RAIL, resté à `false`). Or l'essai E-03 se déroule en mode 2D, gouverné par
        // SOCCER_2D_REAL_ENABLED. Le bouton n'était donc JAMAIS créé : `btnSoccer` restait
        // null, `majBoutonSoccer()` sortait immédiatement, et il n'existait AUCUN moyen
        // d'armer le mode à la main. Le défaut est passé inaperçu parce que `soccerArme`
        // était pré-armé par un drapeau de test et que la série d'essai réarme toute seule —
        // deux béquilles qui masquaient l'absence de commande manuelle.
        //
        // Conséquence au dossier : le chemin d'armement MANUEL n'a jamais pu être exercé.
        // C'est précisément la réserve « démontré par le chemin automatique seulement ».
        // ⚠ AUSSI gaté sur estModeSoccer : ce bouton d'armement « Mode SOCCER » n'a de sens
        // qu'en mode soccer. Sans ce garde, il fuitait dans le suivi personne/véhicule
        // (gaté seulement sur les drapeaux de test, restés true pour la campagne E-03).
        if ((SOCCER_RAIL_REAL_ENABLED || SOCCER_2D_REAL_ENABLED) && estModeSoccer) {
            btnSoccer = bouton("Mode SOCCER : désarmé", 0xFF455A64.toInt()) { basculerArmementSoccer() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
                // ── DÉSARMEMENT PAR APPUI MAINTENU 3 s ────────────────────────────────
                // L'écran étant épinglé pendant l'armement, le pilote ne doit pas avoir à
                // chercher une combinaison Android sous stress : la sortie volontaire est
                // ICI, sur le bouton qu'il regarde déjà. Trois secondes, parce qu'un appui
                // bref pendant un vol automatisé ne doit pas pouvoir désarmer par accident.
                setOnTouchListener { v, ev ->
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            if (soccerArme) {
                                appuiDesarmementDebutMs = System.currentTimeMillis()
                                v.postDelayed(runnableDesarmementLong, 3000L)
                                try { txtEtat.text = "Maintenir… désarmement dans 3 s" } catch (_: Throwable) {}
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.removeCallbacks(runnableDesarmementLong)
                            val tenu = System.currentTimeMillis() - appuiDesarmementDebutMs
                            if (soccerArme && appuiDesarmementDebutMs > 0L && tenu < 3000L) {
                                try { txtEtat.text = "Appui trop court (${tenu} ms) — maintenir 3 s pour désarmer" } catch (_: Throwable) {}
                            }
                            appuiDesarmementDebutMs = 0L
                        }
                    }
                    false   // on ne consomme pas : l'appui simple reste géré par le clic
                }
            }
            racine.addView(btnSoccer)
            // LE BOUTON REFLÈTE L'ÉTAT RÉEL, DÈS LA CRÉATION. Sans cet appel, le libellé
            // posé à la construction (« désarmé ») pouvait contredire `soccerArme` et le
            // pilote se fiait à un affichage faux. Un indicateur de sécurité se dérive de
            // l'état, il ne se recopie pas à la main.
            majBoutonSoccer()
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
        // ── PANNEAU E-03 (banc, hélices retirées) : un bouton par scénario ───────────
        // Affiché UNIQUEMENT quand TEST_E03_CESSATION_VS = true. Chaque bouton déclenche
        // un stimulus et journalise T0..T6 + persistance dans essai_e03.log.
        if (TEST_E03_CESSATION_VS && estModeSoccer) {
            racine.addView(TextView(this).apply {
                text = "🧪 ESSAI E-03 — BANC, HÉLICES RETIRÉES"
                setTextColor(0xFFFFCC00.toInt()); textSize = 15f
                setPadding(0, dp(16), 0, dp(4))
            })
            // ARMEMENT DU BANC — préalable OBLIGATOIRE aux scénarios.
            // Sans lui l'arbitre bloque (bit IF=0, aéronef au sol) et aucune commande
            // n'est émise : tous les scénarios rendraient une persistance de 0 sans
            // signification. Bouton SÉPARÉ et explicite : la dérogation ne doit jamais
            // s'appliquer par le seul fait d'ouvrir l'écran.
            if (TEST_E03_BANC) {
                racine.addView(TextView(this).apply {
                    text = "Étape 1 — armer le banc. VÉRIFIER D'ABORD que les hélices " +
                           "sont retirées. Le banc mesure T0→T5 ; T6 (effet physique) " +
                           "n'est pas mesurable moteurs à l'arrêt."
                    setTextColor(0xFFFF7043.toInt()); textSize = 12f
                    setPadding(0, dp(2), 0, dp(6))
                })
                val btnBanc = bouton("🔒 ARMER LE BANC", 0xFFB71C1C.toInt()) {
                    e03BasculerBanc()
                }.apply {
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { bottomMargin = dp(6) }
                }
                e03BtnBanc = btnBanc
                racine.addView(btnBanc)

                // SÉRIE AUTOMATIQUE : évite 5 réarmements manuels par scénario (§378 exige
                // N≥5). N'affecte QUE les scénarios à stimulus logiciel ; ceux qui exigent
                // un geste physique restent unitaires, sans quoi on journaliserait un
                // stimulus qui n'a pas eu lieu.
                // Libellé COURT et bouton HAUT : le panneau vit dans une colonne étroite,
                // un texte long y est tronqué et devient illisible (retour terrain).
                val btnSerie = bouton("RÉPÉTITIONS : ×1", 0xFF455A64.toInt()) {
                    e03Repetitions = if (e03Repetitions == 1) 5 else 1
                    e03BtnSerie?.text = if (e03Repetitions == 5) "RÉPÉTITIONS : ×5 AUTO"
                                        else "RÉPÉTITIONS : ×1"
                    e03BtnSerie?.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            if (e03Repetitions == 5) 0xFF00897B.toInt() else 0xFF455A64.toInt())
                    logE03("E03 SERIE_MODE repetitions=$e03Repetitions")
                }.apply {
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { bottomMargin = dp(10) }
                }
                e03BtnSerie = btnSerie
                racine.addView(btnSerie)

                // ── CHARGE DE DIAGNOSTIC (campagne anti-faux-positif) ──────────────
                if (TEST_E03_STRESS) {
                    racine.addView(TextView(this).apply {
                        text = "Charge de diagnostic — 30 s. Surveiller l'apparition de " +
                               "WDG_INDEP avec contexte=HORS_FENETRE. Aucun déclenchement " +
                               "attendu : la charge ralentit la boucle, elle ne la gèle pas."
                        setTextColor(0xFF90A4AE.toInt()); textSize = 12f
                        setPadding(0, dp(6), 0, dp(4))
                    })
                    val charges = listOf(
                        "⚡ CPU 30 s" to ca.cineflight.stage.diag.StressBanc.Mode.CPU,
                        "🧠 Mémoire/GC 30 s" to ca.cineflight.stage.diag.StressBanc.Mode.MEMOIRE,
                        "⚡🧠 CPU + GC 30 s" to ca.cineflight.stage.diag.StressBanc.Mode.CPU_ET_MEMOIRE,
                    )
                    for ((libelle, mode) in charges) {
                        racine.addView(bouton(libelle, 0xFF5D4037.toInt()) {
                            e03LancerStress(mode)
                        }.apply {
                            textSize = 13f
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) }
                        })
                    }
                }
            }
            val scenarios: List<Pair<String, ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario>> = listOf(
                "01 Arrêt normal" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_01_ARRET_NORMAL,
                "02 Zéro maintenu" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_02_ZERO_MAINTENU,
                "03 Gel thread émission" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_03_GEL_THREAD,
                "04 Exception" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_04_EXCEPTION,
                "05 Crash processus" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_05_CRASH_PROCESS,
                "06 Arrière-plan" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_06_ARRIERE_PLAN,
                "07 Déconnexion USB" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_07_DECONNEXION_USB,
                "08 Perte MSDK" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_08_PERTE_MSDK,
                "09 Perte RC" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_09_PERTE_RC,
                "10 Cmd verticale +puis cessation" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_10_DERNIERE_CMD_POSITIVE,
                "11 Sortie VS explicite" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_11_SORTIE_VS_EXPLICITE,
                "12 Batterie faible station" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_12_BATTERIE_STATION,
                "13 Surcharge thermique" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_13_SURCHARGE_THERMIQUE,
                "FS1 Perte RC (failsafe)" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_FS1_PERTE_RC,
                "FS2 Perte station (failsafe)" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_FS2_PERTE_STATION,
                "FS3 Cmd persistante (failsafe)" to ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_FS3_CMD_PERSISTANTE,
            )
            for ((libelle, sc) in scenarios) {
                racine.addView(bouton(libelle, 0xFF6A1B9A.toInt()) {
                    // Les scénarios à stimulus LOGICIEL passent TOUJOURS par la séquence
                    // automatique, y compris pour une seule répétition. Raison : chaque
                    // scénario désarme le mode soccer (c'est son objet), et en tir manuel
                    // l'opérateur devait le réarmer entre deux appuis. Une seule fois oublié
                    // et la répétition part à vide — 4 mesures perdues le 2026-07-22.
                    // La séquence remet les préconditions avant chaque stimulus.
                    if (sc in E03_SCENARIOS_AUTOMATISABLES) {
                        e03LancerSerie(sc, e03Repetitions)
                    } else {
                        if (e03Repetitions > 1) {
                            txtEtat.text = "Stimulus physique requis — répétition unitaire"
                            logE03("E03 SERIE non_applicable scenario=${sc.name} " +
                                   "cause=stimulus_physique_requis mode=unitaire")
                        }
                        e03Declencher(sc)
                    }
                }.apply {
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4) }
                })
            }
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
                    // ÉTAT DE VOL RÉEL : `enVol` suit désormais le SDK (KeyIsFlying). Un
                    // décollage AUX MANCHES DE LA RC met donc enVol=true — sinon « Démarrer le
                    // suivi » restait bloqué sur « décolle d'abord » après un décollage manuel.
                    try { pont.obsEnVol = { vol -> enVol = vol } } catch (_: Throwable) {}
                    // SECURITE E-09 (correction, ACTIVE EN PRODUCTION) : détecte la perte de
                    // liaison radiocommande et déclenche l'arrêt d'urgence (coupe l'émission
                    // soccer + désarme). En complément du failsafe DJI natif. Le log fichier
                    // n'est écrit que sous TEST_E06_09_SECU (logSecuTest est gardé par le flag).
                    try {
                        pont.obsConnexionRc = { connecte ->
                            logSecuTest("E09 ts=${System.currentTimeMillis()} rc_connecte=$connecte estConnecte=${try { pont.estConnecte() } catch (_: Throwable) { false }} soccerArme=$soccerArme")
                            // TRAÇABILITÉ E-03 : c'est CET observateur qui désarme le soccer
                            // (arretUrgence pose soccerArme=false), et l'essai s'arrête net sur
                            // raison="non arme" sans que le journal E-03 en dise la cause. On la
                            // consigne donc là où on la cherche. La ligne dit aussi combien de
                            // fois l'événement se répète : des rafales identiques en quelques
                            // millisecondes trahiraient un abonnement multiple aux clés DJI,
                            // pas une vraie perte de liaison.
                            if (TEST_E03_CESSATION_VS) {
                                e03CptConnexionRc += 1
                                logE03("E03 RC_CONNEXION connecte=$connecte occurrence=$e03CptConnexionRc " +
                                       "estConnecte=${try { pont.estConnecte() } catch (_: Throwable) { false }} " +
                                       "soccerArme_avant=$soccerArme " +
                                       "effet=${if (!connecte) "arret_urgence_desarme_le_soccer" else "aucun"} " +
                                       "ts=${System.currentTimeMillis()}")
                            }
                            rcConnecteDernier = connecte
                            // ACTION IMMÉDIATE — décision revue le 2026-07-22.
                            //
                            // Un premier relevé (`connecte=false` avec `estConnecte=true`) m'avait
                            // fait conclure à un faux négatif de la clé DJI, et j'avais introduit
                            // une corroboration différée de 400 ms. Le relevé suivant a montré que
                            // la liaison du contrôleur de vol tombait bel et bien, 400 ms APRÈS :
                            // la clé RC ne mentait pas, elle DEVANÇAIT — c'est le premier maillon
                            // à lâcher. La corroboration ne supprimait donc pas un faux positif,
                            // elle retardait une vraie détection de 400 ms, sur un budget de
                            // persistance de 500 ms (§378). Mauvais échange pour une sécurité.
                            //
                            // On agit donc SANS DÉLAI, comme avant. La vérification différée est
                            // conservée en OBSERVATION PASSIVE : elle alimente le journal sans
                            // peser sur la décision, et accumulera au fil de la campagne la preuve
                            // que la clé RC devance (ou non) la perte du contrôleur de vol.
                            if (!connecte) {
                                runOnUiThread { try { arretUrgence() } catch (_: Throwable) {} }
                                if (TEST_E03_CESSATION_VS) lifecycleScope.launch {
                                    kotlinx.coroutines.delay(RC_PERTE_CONFIRMATION_MS)
                                    logE03("E03 RC_PERTE_OBSERVATION apres_ms=$RC_PERTE_CONFIRMATION_MS " +
                                           "rc_toujours_absente=${!rcConnecteDernier} " +
                                           "fc_aussi_perdu=${!(try { pont.estConnecte() } catch (_: Throwable) { false })} " +
                                           "note=observation_seule_l_arret_a_deja_eu_lieu ts=${System.currentTimeMillis()}")
                                }
                            }
                        }

                        // ── PERTE DU CONTRÔLEUR DE VOL (E-03 scénario 08) ──────────────
                        //
                        // DÉFAUT CORRIGÉ (2026-07-22). `PontDjiReel` écoutait déjà
                        // `FlightControllerKey.KeyConnection` et exposait `obsConnexionDrone`,
                        // mais AUCUN abonné n'était branché dans cet écran : la perte de
                        // l'aéronef mettait le voyant au rouge et rien d'autre.
                        //
                        // Constaté au banc : drone éteint, radiocommande toujours allumée →
                        // `detection_survenue=false`, aucun jalon marqué, le mode soccer
                        // restait ARMÉ et la boucle continuait d'émettre vers un aéronef
                        // absent. Ce n'est pas dangereux en soi — il n'y a plus personne pour
                        // exécuter la commande — mais l'application affirmait un état
                        // (« automatisme armé ») que la réalité ne portait plus. C'est la même
                        // famille de défaut que « armé sans détecteur » : un état qui ment.
                        //
                        // La liaison radiocommande n'est PAS un substitut : elle peut rester
                        // établie alors que l'aéronef a disparu (RC branchée au téléphone,
                        // drone hors tension ou hors de portée). Les deux maillons doivent
                        // donc être surveillés séparément.
                        //
                        // SYMÉTRIE VOULUE avec la perte RC : action immédiate, arrêt d'urgence
                        // idempotent (les doublons du SDK sont absorbés), et compteur
                        // d'occurrences au journal — une rafale identique en quelques
                        // millisecondes trahirait un abonnement multiple, pas une vraie perte.
                        try {
                            pont.obsConnexionDrone = { connecte ->
                                // TRANSITION, PAS ÉTAT. On ne réagit qu'à un passage
                                // PRÉSENT → ABSENT. À l'ouverture de l'écran, la clé DJI
                                // délivre l'état courant : si le drone n'est pas encore sous
                                // tension, elle rapporte `false` — ce qui n'est pas une perte,
                                // c'est une absence initiale. Déclencher là-dessus poserait un
                                // arrêt d'urgence à chaque ouverture de Phase 3 sans aéronef,
                                // polluerait le journal et verrouillerait le latch avant même
                                // que l'opérateur ait touché à quoi que ce soit.
                                // `null` = état encore inconnu.
                                val perteReelle = (droneConnecteDernier == true) && !connecte
                                droneConnecteDernier = connecte
                                if (TEST_E03_CESSATION_VS) {
                                    e03CptConnexionDrone += 1
                                    logE03("E03 DRONE_CONNEXION connecte=$connecte " +
                                           "occurrence=$e03CptConnexionDrone " +
                                           "transition=${if (perteReelle) "PRESENT_VERS_ABSENT" else "aucune"} " +
                                           "rc_connectee=$rcConnecteDernier " +
                                           "soccerArme_avant=$soccerArme " +
                                           "effet=${if (perteReelle) "arret_urgence_desarme_le_soccer" else "aucun"} " +
                                           "ts=${System.currentTimeMillis()}")
                                }
                                if (perteReelle) {
                                    // T1 = détection de la perte de l'aéronef. Marqué AVANT
                                    // l'arrêt d'urgence pour que l'ordre des jalons reflète la
                                    // chaîne réelle (détection → désarmement → neutre → VS).
                                    if (TEST_E03_CESSATION_VS) e03Capteur.marquerT1()
                                    runOnUiThread { try { arretUrgence() } catch (_: Throwable) {} }
                                }
                            }
                        } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                    try { flux?.demarrer() } catch (_: Exception) {}   // relie la video live
                    try { demarrerYolo() } catch (_: Exception) {}     // confirmation + cadrage fin
                    try { lecteurPerception.demarrer() } catch (_: Exception) {}  // etat capteurs (mode camera)
                    // ESSAI E-03 AU BANC : le simulateur DJI n'est activé QUE si son
                    // interrupteur dédié est armé (colonne « Env. » du tableau 36). Les
                    // scénarios « RÉEL » exigent TEST_E03_SIMULATEUR=false.
                    if (TEST_E03_CESSATION_VS) {
                        try { e03BrancherObservateurs() } catch (_: Throwable) {}
                        // En-tête de configuration : écrit en TÂCHE DE FOND car il calcule
                        // le SHA-256 de l'APK (lecture disque de plusieurs dizaines de Mo).
                        // Sur le fil UI, cela risquerait un ANR au démarrage de l'écran.
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                e03HashApk()   // remplit le cache (lecture disque)
                                // ATTENTE ACTIVE COURTE : getValue() lit le CACHE du SDK.
                                // Juste après l'enregistrement, les SN/firmware ne sont pas
                                // encore poussés et seraient consignés VIDES.
                                // On teste la disponibilité de l'IDENTITÉ SDK uniquement —
                                // PAS EssaiE03Config.complete(), qui inclut les champs
                                // manuels (câble, port, SN téléphone) toujours vides et
                                // ferait donc attendre le délai maximal à chaque démarrage.
                                var id = ca.cineflight.stage.control.SondeIdentiteDji.lire()
                                var essais = 0
                                while (!id.complete() && essais < 8) {
                                    kotlinx.coroutines.delay(1000)
                                    essais++
                                    id = ca.cineflight.stage.control.SondeIdentiteDji.lire()
                                }
                                val cfg = e03Config()
                                logE03(cfg.ligneEntete(System.currentTimeMillis()))
                                e03IdentiteSdkConsignee = id.complete()
                                logE03("E03 CONFIG_LECTURE essais=$essais " +
                                       "identite_sdk=${if (id.complete()) "COMPLETE" else "PARTIELLE"} " +
                                       "note=cable_port_sn_telephone_a_consigner_au_cahier")
                            } catch (_: Throwable) {}
                        }
                        if (TEST_E03_SIMULATEUR) {
                            try { e03ActiverSimulateur() } catch (_: Throwable) {}
                        } else {
                            logE03("E03 simulateur=NON_DEMANDE env=CHAINE_REELLE ts=${System.currentTimeMillis()}")
                        }
                    }
                }
            }
        }

        // Les deux boucles vivent dans une fonction dediee : elles doivent pouvoir etre
        // ARRETEES a onStop() puis RELANCEES a onStart(). Les laisser inline dans onCreate
        // rendait leur redemarrage impossible — l'ecran revenait au premier plan sans plus
        // jamais emettre.
        demarrerBoucles()

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
                    // CAP DÉRIVÉ DU DÉPLACEMENT (2026-07-25) : le boîtier n'envoie PAS de
                    // cap (heading_deg=-- sur tout le vol du 25/07) -> capEffectif restait
                    // NaN -> les 4 angles de prise de vue donnaient la MÊME position.
                    // On reconstruit le cap depuis la trajectoire GPS ; il ne SUPPLANTE
                    // jamais un cap fourni par la source, il ne fait que combler son absence.
                    if (!autoCap.isFinite()) {
                        val capDer = capDeplacement.mettreAJour(autoLat, autoLon, System.currentTimeMillis())
                        if (capDer.isFinite()) {
                            autoCap = capDer
                            dernierCapValideDeg = capDer
                        }
                    } else {
                        capDeplacement.mettreAJour(autoLat, autoLon, System.currentTimeMillis())
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

    // ── RESEAU (mode ATHLÈTE) : GET /api/v1/subjects/{id}/latest ────────────────
    // Remplit les MÊMES champs que lirePositionAuto (autoLat/autoLon/autoCap/autoAgeS +
    // prédicteur) pour que tickSuivi fonctionne SANS changement (§18). Fail-closed : toute
    // donnée non exploitable (perte, périmée, imprécise, saut GPS) -> reseauOk=false ->
    // tickSuivi passe en HOVER. L'adaptateur porte les garde-fous (§10/§16).
    private fun lirePositionAthlete() {
        val sid = athleteSubjectId
        if (sid.isBlank()) {
            reseauOk = false; autoAgeS = 999.0; autoRtk = "LOST"
            athleteEtat = ca.cineflight.stage.athlete.EvaluateurSourceAthlete.Etat.PERDU
            athleteRaison = "aucun athlète sélectionné (écran Athlète)"
            majPredictionIndispo("ATHLETE_SANS_ID"); return
        }
        val t = ca.cineflight.stage.athlete.ClientAthlete.lireDerniere(URL_ATHLETE_BASE, sid)
        // IMMOBILITÉ INERTIELLE (CoreMotion) : à l'arrêt le GPS iPhone dérive au-dessus du
        // seuil de 8 m ; l'inertiel confirme l'immobilité et l'adaptateur sert alors
        // l'ANCRE (dernière position acceptée) au lieu du point qui dérive.
        val immobileInertiel = immobiliteAthlete.evaluer(t)
        athleteEtat = evaluateurAthlete.evaluer(t, immobileInertiel)   // état affiché au pilote (PRÊT/…)
        val d = adaptateurAthlete.adapter(t, System.currentTimeMillis(), immobileInertiel)
        athleteRaison = d.raison
        // INSTRUMENTATION LOGCAT (~1 Hz) : le vol d'essai du 2026-07-25 n'était PAS
        // analysable — la ligne OBS ne va qu'à l'écran. Une ligne par seconde suffit
        // pour reconstituer predPret/raison/âge/vitesse après coup.
        val nowLog = System.currentTimeMillis()
        if (nowLog - dernierLogAthleteMs >= 1000L) {
            dernierLogAthleteMs = nowLog
            android.util.Log.i("CF_SuiviAthlete",
                "autorise=${d.autoriser} raison=${d.raison} etat=$athleteEtat" +
                " age=${"%.2f".format(t.ageS())}s prec=${"%.1f".format(t.precisionHM)}m" +
                " vit=${"%.2f".format(t.vitesseMps)}m/s immobile=$immobileInertiel" +
                " predPret=$predPret predV=${"%.2f".format(predVitesseMps)}")
        }
        if (!d.autoriser) {
            reseauOk = false
            autoAgeS = if (t.ageS().isFinite()) t.ageS() else 999.0
            autoRtk = "LOST"                                 // -> tickSuivi HOVER (fail-closed)
            majPredictionIndispo("ATHLETE_${d.raison}")
            return
        }
        autoLat = d.lat; autoLon = d.lon
        // CAP athlète : fiable UNIQUEMENT si le sujet BOUGE. Un iPhone immobile renvoie un
        // course_deg quasi aléatoire → le point « derrière » tournerait autour du sujet et le
        // drone ferait un 360. En dessous du seuil de marche, on GÈLE le cap : autoCap=NaN →
        // tickSuivi retombe sur dernierCapValideDeg (figé) → offset FIXE, pas d'orbite.
        val bouge = t.vitesseMps.isFinite() && t.vitesseMps >= CAP_ATHLETE_VITESSE_MIN
        if (bouge && d.capDeg.isFinite()) {
            autoCap = d.capDeg
            dernierCapValideDeg = d.capDeg
        } else if (bouge) {
            // L'iPhone n'envoie AUCUN cap (relevé 2026-07-25) : on le dérive de la
            // trajectoire GPS, sinon les angles de prise de vue sont tous identiques.
            val capDer = capDeplacement.mettreAJour(d.lat, d.lon, System.currentTimeMillis())
            autoCap = capDer
            if (capDer.isFinite()) dernierCapValideDeg = capDer
        } else {
            autoCap = Double.NaN            // immobile : cap gelé (anti-orbite), repli sur le dernier valide
        }
        autoAgeS = d.ageS
        // FIX = suivi COMPLET (translation). L'adaptateur a DÉJÀ fait le tri qualité (précision
        // ≤8 m, âge ≤3 s, saut). La doctrine FLOAT verrouillerait toute translation -> le drone
        // ne suivrait pas. La prudence vient du profil personne + du plafond athlète (2 m/s).
        autoRtk = "FIX"
        autoVitesseKmh = if (t.vitesseMps.isFinite()) t.vitesseMps * 3.6 else Double.NaN
        reseauOk = true
        // nourrir le prédicteur EXISTANT (§14) : timestamp dérivé de l'âge de réception.
        val tsMs = System.currentTimeMillis() - (autoAgeS.coerceAtLeast(0.0) * 1000.0).toLong()
        // fiable = true SEULEMENT si le sujet bouge : immobile, on ne veut pas que le
        // prédicteur produise un cap/anticipation à partir du bruit GPS (source du 360).
        val etat = predicteur.mettreAJour(
            lat = autoLat, lon = autoLon, ageS = autoAgeS,
            fiable = bouge, qualiteRtk = if (bouge) "FLOAT" else "GPS", timestampMs = tsMs
        )
        majPrediction(etat)
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
        // Seuil ATHLÈTE = 3 s (§16 ; l'iPhone pousse ~toutes les 2 s, donc 2 s était trop
        // serré et forçait un HOVER permanent). Voiture RTK = RTK_AGE_MAX_S (2 s) inchangé.
        val ageMaxSuivi = if (SUIVI_ATHLETE_SIMU) 3.0 else RTK_AGE_MAX_S
        if (!reseauOk || autoLat.isNaN() || autoLon.isNaN() || autoAgeS > ageMaxSuivi) {
            hover()
            // En mode athlète : afficher la VRAIE raison du blocage (précision/saut/perte),
            // pas le message « position voiture » qui n'a aucun sens ici.
            if (SUIVI_ATHLETE_SIMU) majSuivi("OBS athlète — pas de suivi : $athleteRaison (âge %.1f s)".format(autoAgeS))
            else majSuivi(getString(R.string.p3_suivi_hover_age, autoAgeS))
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
        // Plafond horizontal : athlète = conservateur (GPS ~3-5 m, personne) -> V_MAX_HORIZ (2 m/s) ;
        // sinon adapté au drone (voiture RTK). §10 : limiter la vitesse.
        val vMaxH = if (SUIVI_ATHLETE_SIMU) V_MAX_HORIZ.toDouble() else vMaxHorizSelonDrone()
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

        // JOURNAL DE VOL PERSISTANT (2026-07-26) : tout ce qu'il faut pour rejouer le
        // raisonnement du suivi APRÈS COUP — positions drone ET cible, distance, cap du
        // sujet, angle et cadrage demandés, les 4 axes commandés (roll compris), état de
        // la source et de la prédiction. Le logcat s'écrase en quelques heures ; ce fichier
        // survit (adb pull .../files/vols/). ~1 Hz, écriture sur fil de fond.
        try {
            journalVol.etat(
                droneLat = dLat, droneLon = dLon, droneAltM = dAlt, droneCapDeg = pont.capDroneDeg().toDouble(),
                cibleLat = cibleLat, cibleLon = cibleLon, distanceM = distCar,
                capSujetDeg = capEffectif, angle = mouvementActif?.name ?: positionSuivi,
                cadrageM = distanceCadrageM,
                pitch = aEnvoyer.pitch, roll = aEnvoyer.roll,
                throttle = aEnvoyer.throttle, yaw = aEnvoyer.yaw,
                sourceEtat = if (SUIVI_ATHLETE_SIMU) athleteEtat.toString() else autoRtk,
                sourceAgeS = autoAgeS, sourcePrecisionM = autoHacc,
                predictionPrete = predPret,
                raison = if (SUIVI_ATHLETE_SIMU) athleteRaison else "-",
                batteriePct = try { pont.batteriePourcent() } catch (_: Throwable) { -1 },
                satellites = try { pont.nbSatellitesActuel() } catch (_: Throwable) { -1 },
            )
        } catch (_: Throwable) {}

        // MODE OBSERVATION ATHLÈTE : on N'ENVOIE RIEN (VS jamais activé), on AFFICHE seulement
        // la commande calculée. Les moteurs ne tournent pas -> hélices peuvent rester en place.
        if (athleteObs) {
            // Étape 6 : montre si la CAMÉRA (YOLO) corrige le cadrage à cet instant (fusion).
            val yoloTxt = if (yoloCadre) "OUI %.0f%%".format(yoloConf * 100f) else "non"
            majSuivi(("OBS athlète — av=%.2f lat=%.2f vert=%.2f yaw=%.0f | dist=%.1fm " +
                "yolo(cadrage)=%s état=%s (rien envoyé)")
                .format(aEnvoyer.pitch, aEnvoyer.roll, aEnvoyer.throttle, aEnvoyer.yaw, distCar, yoloTxt, athleteEtat))
        } else {
            try {
                pont.envoyerVitesses(
                    aEnvoyer.pitch, aEnvoyer.roll, aEnvoyer.throttle, aEnvoyer.yaw,
                    ca.cineflight.stage.control.CommandOrigin.AUTOMATIC
                )
            } catch (_: Exception) {}
        }

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
    /**
     * TEST AXES HORIZONTAUX — SIMULATEUR SEULEMENT (2026-07-25).
     * Mesure la sémantique RÉELLE du Virtual Stick : commande « pitch +1 m/s » pendant 4 s,
     * puis compare la DIRECTION du déplacement simulé au CAP du drone.
     *   écart ≈ 0°   -> pitch = AVANT  -> INVERSER_ROLL_PITCH=false CORRECT
     *   écart ≈ ±90° -> pitch = LATÉRAL -> sémantique INVERSÉE -> passer INVERSER_ROLL_PITCH=true
     *   écart ≈ 180° -> pitch = ARRIÈRE -> signe à inverser
     * REFUSE hors simulateur (aucun risque d'orbite réelle). Exige d'avoir DÉCOLLÉ (sim).
     * Verdict au Logcat (tag TestAxes) ET à l'écran.
     */
    private fun testAxesSimulateur() {
        if (!pont.simulateurActif()) { txtEtat.text = "TEST AXES : active d'abord le SIMULATEUR."; return }
        if (!enVol) { txtEtat.text = "TEST AXES : DÉCOLLE (simulateur) d'abord."; return }
        lifecycleScope.launch(Dispatchers.Main) {
            val lat0 = pont.latitudeDrone(); val lon0 = pont.longitudeDrone(); val cap0 = pont.capDroneDeg()
            if (lat0.isNaN() || lon0.isNaN() || cap0.isNaN()) {
                txtEtat.text = "TEST AXES : position/cap simulés absents."; return@launch
            }
            if (!vsActif) {
                try { pont.activerVirtualStick(true) } catch (_: Exception) {}
                vsActif = true
                kotlinx.coroutines.delay(800)
            }
            android.util.Log.i("TestAxes", "DEBUT cap0=${"%.0f".format(cap0)} lat0=$lat0 lon0=$lon0 pitch=+1.0 4s")
            txtEtat.text = "TEST AXES : pitch +1 m/s pendant 4 s…"
            repeat(40) {
                try { pont.envoyerVitesses(1f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {}
                kotlinx.coroutines.delay(100)
            }
            repeat(5) {   // retour au neutre
                try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {}
                kotlinx.coroutines.delay(100)
            }
            val lat1 = pont.latitudeDrone(); val lon1 = pont.longitudeDrone()
            val dN = (lat1 - lat0) * 111_320.0
            val dE = (lon1 - lon0) * 111_320.0 * cos(Math.toRadians(lat0))
            val dist = hypot(dE, dN)
            val capMouv = Math.toDegrees(kotlin.math.atan2(dE, dN))   // 0=N, 90=E (boussole)
            val ecart = ca.cineflight.stage.cine.PanoramaStateMachine
                .ecartAngulaire(cap0, capMouv.toFloat())
            val verdict = when {
                dist < 1.0 -> "IMMOBILE (${"%.1f".format(dist)} m) — non concluant (VS accordé ? en vol sim ?)"
                kotlin.math.abs(ecart) <= 30f -> "PITCH=AVANT ✓ — INVERSER_ROLL_PITCH=false CORRECT"
                kotlin.math.abs(kotlin.math.abs(ecart) - 90f) <= 30f ->
                    "PITCH=LATÉRAL ⚠ SÉMANTIQUE INVERSÉE — passer INVERSER_ROLL_PITCH=true (cause de l'ORBITE)"
                kotlin.math.abs(ecart) >= 150f -> "PITCH=ARRIÈRE ⚠ signe inversé"
                else -> "AMBIGU (écart ${"%.0f".format(ecart)}°) — répéter le test"
            }
            android.util.Log.i("TestAxes", "FIN dist=${"%.1f".format(dist)}m cap_mouvement=${"%.0f".format(capMouv)}" +
                " cap0=${"%.0f".format(cap0)} ecart=${"%.0f".format(ecart)} -> $verdict")
            txtEtat.text = "TEST AXES : $verdict"
        }
    }

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
        if (SOCCER_2D_EMISSION_ACTIVE) {
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
    private fun capturerSnapshotSecurite(actionFiable: Boolean, mode2D: Boolean = false): ca.cineflight.stage.sport.soccer.SafetySnapshotFactory.Published {
        // --- CAPTURE EN UNE PASSE (aucune logique metier entre les lectures) ---
        val cPilote = modeManuel
        val cUrgence = soccerArretUrgence
        val cVs = vsActif
        // DÉROGATION DE BANC (E-03, §378) : hélices retirées, l'aéronef n'est PAS en vol,
        // et l'arbitre refuserait donc toute émission — rendant l'essai impossible. Le
        // mode banc, armé MANUELLEMENT et REFUSÉ si l'aéronef vole réellement, force ce
        // seul bit. Tous les autres bits restent évalués normalement, et chaque ligne du
        // journal porte `banc=OUI` pour que la nature de la mesure ne soit jamais perdue.
        val cEnVol = enVol || (TEST_E03_CESSATION_VS && TEST_E03_BANC && e03BancArme)
        val cRtk = autoRtk.uppercase()
        val cBatt = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        val cOperateurProche = estOperateurProcheDuRail()
        // --- FIN DE CAPTURE : plus aucune variable partagee n'est relue apres ce point ---

        // ── DÉROGATION DE BANC — PÉRIMÈTRE EXACT ────────────────────────────────────
        // Audit 2026-07-22 : forcer le seul bit IF ne suffisait pas. DEUX autres conditions
        // sont STRUCTURELLEMENT insatisfiables au banc, quoi que fasse l'opérateur :
        //
        //   PF `dronePositionFresh` — vaut FIX/FLOAT du RTK de la VOITURE, lu sur le réseau
        //      (lirePositionAuto). Au banc il n'y a pas de voiture : autoRtk reste "—",
        //      donc PF=0 pour toujours et l'arbitre bloque avant même d'examiner le reste.
        //   ON `operatorNearRail` — distance opérateur↔rail calculée depuis le profil et le
        //      GPS ; sans profil chargé ni fix GPS (essai en intérieur), fail-closed à 0.
        //
        // Ces trois bits (IF, PF, ON) sont des PRÉCONDITIONS DE SITE. Ils ne disent rien de
        // la cessation Virtual Stick, qui est le seul objet de E-03, et ils sont couverts
        // par d'autres essais. On les neutralise donc explicitement, et EUX SEULS.
        //
        // RESTENT JUGES, sans exception : PO (priorité pilote), EM (arrêt d'urgence),
        // VS (Virtual Stick), AC (détection YOLO fraîche et confiante), BO (batterie),
        // RV, CC, OG. Un essai qui les contournerait ne prouverait plus rien.
        val derog = TEST_E03_CESSATION_VS && TEST_E03_BANC && e03BancArme
        // FRAÎCHEUR DE POSITION — SOURCE SELON LE MODE (découplage 2026-07-23, réserve §5).
        //   RAIL  : la position vient du RTK de la VOITURE (le drone suit le rail/la voiture).
        //   2D    : le drone suit les JOUEURS par vision, PAS la voiture → la fraîcheur vient
        //           de la position PROPRE du drone (GPS valide + seuil satellites du vol auto).
        //           Sans ce découplage, l'absence de voiture bloquait TOUTE émission 2D en vol.
        val cPositionFraiche = if (mode2D) {
            // `gpsValide()` garantit déjà lat/lon valides + fix de base ; on impose EN PLUS le
            // seuil du vol automatique (SAT_MIN_AUTO=14) via nbSatellitesActuel().
            val gpsOk = try { pont.gpsValide() } catch (_: Throwable) { false }
            val sats = try { pont.nbSatellitesActuel() } catch (_: Throwable) { 0 }
            ca.cineflight.stage.sport.soccer.SafetyPosition2D.positionFraiche(
                gpsOk, sats, ca.cineflight.stage.MainActivity.SAT_MIN_AUTO) || derog
        } else {
            (cRtk == "FIX" || cRtk == "FLOAT") || derog
        }
        val cOperateurOk = cOperateurProche || derog

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
            dronePositionFresh = cPositionFraiche,
            actionFreshAndConfident = actionFiable,
            batteryOk = (cBatt >= SOCCER_BATT_MIN_PCT),
            corridorClear = true,
            operatorNearRail = cOperateurOk,
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
            val pub = capturerSnapshotSecurite(actionFiable = actionFiable2D, mode2D = true)
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
            // ── VERROU D'AUTORITÉ — dernière barrière avant l'aéronef ──────────────
            // INVARIANT : une seule autorité de commande pilote par aéronef. Si CETTE
            // instance ne détient pas l'autorité, elle n'émet RIEN, quoi qu'ait décidé
            // l'arbitre. C'est une barrière de dernier recours contre les producteurs
            // concurrents (relevé du 2026-07-22 : 6 arrêts d'urgence pour un événement).
            // Elle ne corrige pas la cause — le cycle de vie s'en charge — elle borne la
            // conséquence si la cause resurgit.
            val autorite = ca.cineflight.stage.control.AutoriteCommandeDrone.detient(instanceId)
            if (!autorite && r.emettre) {
                logSecuTest("AUTORITE_REFUSEE instance=$instanceId " +
                            "proprietaire=${ca.cineflight.stage.control.AutoriteCommandeDrone.proprietaireActuel()} " +
                            "throttle_bloque=${r.command.throttle} ts=${System.currentTimeMillis()}")
            }
            val throttleEmis = if (r.emettre && autorite) r.command.throttle else 0f
            // ESSAI E-03 : POINT D'ÉMISSION UNIQUE — T0 et persistance de COMMANDE.
            //
            // T0 est marqué à CHAQUE cycle, y compris quand le guard bloque l'émission.
            // C'est VOULU : la fiche définit T0 comme « dernier heartbeat / dernière
            // commande émise ». La sémantique HEARTBEAT est celle qui rend E03-03 (gel du
            // fil d'émission) mesurable — le délai de détection T1-T0 doit alors valoir
            // environ le timeout du watchdog (500 ms). Marquer T0 uniquement lors d'une
            // émission réelle fausserait ce scénario, où justement plus rien n'est émis.
            //
            // La persistance, elle, n'est alimentée que par un throttle NON NUL réellement
            // émis (observerThrottleEmis ignore les zéros) : c'est la commande partie au
            // drone qui peut persister, pas le battement de la boucle.
            // Hors essai, aucun coût : le flag court-circuite les deux appels.
            if (TEST_E03_CESSATION_VS) {
                e03Capteur.marquerT0()
                e03Capteur.observerThrottleEmis(throttleEmis)
                // E03-05 : mémorise l'instant de la dernière commande NON NULLE réellement
                // partie. Le marqueur de crash s'en sert pour dire depuis combien de temps
                // l'aéronef était commandé quand le process est mort.
                if (throttleEmis != 0f) e03DerniereEmissionNonNulleNanos.set(nowNanos)
            }
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
            if (SOCCER_2D_EMISSION_ACTIVE) {
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
            if (SOCCER_2D_EMISSION_ACTIVE) {
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
                // E03-02 « ZÉRO MAINTENU » : le scénario envoie 10 zéros, mais la boucle
                // pilote réécrivait +0.2 à 10 Hz juste après — l'application contredisait
                // donc son propre stimulus, et le journal l'a montré
                // (persist_commande_ms≈2998, soit toute la fenêtre d'observation).
                // Le drapeau impose le zéro pendant la fenêtre : sans lui, ce scénario ne
                // mesure pas ce que son nom annonce.
                val throttle2D = when {
                    e03ForcerZero -> 0f                       // scénario « zéro maintenu »
                    // VOL RÉEL PRIORITAIRE : asservissement d'altitude (borné par vMax). JAMAIS
                    // le +0.2 forcé en vol — celui-ci est réservé au test de SIGNE au sol.
                    SOCCER_2D_VOL_REEL -> throttle2Dcalcule
                    TEST_E01_SIGNE_THROTTLE -> 0.2f           // TEST DE SIGNE AU SOL, hélices retirées
                    else -> throttle2Dcalcule
                }
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
        // FILET : `enVol` suit le SDK via obsEnVol, mais si l'aéronef vient de décoller à la RC
        // et que l'observateur n'a pas encore propagé, on relit l'état RÉEL du SDK au moment
        // du clic. Évite le faux « décolle d'abord » sur un aéronef déjà en l'air.
        if (!enVol && try { pont.estEnVolReel() } catch (_: Throwable) { false }) enVol = true
        // OBSERVATION athlète : pas besoin d'être en vol (rien n'est envoyé, on affiche l'intention).
        if (!enVol && !athleteObs) { txtEtat.text = getString(R.string.p3_decolle_dabord); return }
        if (!suiviVision && !reseauOk) {
            // §16 : « Démarrer » refusé tant que la source n'est pas fraîche et exploitable.
            txtEtat.text = if (SUIVI_ATHLETE_SIMU)
                "Athlète non prêt — position fraîche requise (état : $athleteEtat)."
            else getString(R.string.p3_voiture_non_captee)
            return
        }
        // FAIL-CLOSED : en mode RTK, on n'arme pas le suivi si le RTK n'est pas exploitable.
        // En mode athlète, le GPS téléphone n'est pas du RTK : la doctrine RTK ne s'applique
        // pas ; c'est l'adaptateur (précision/âge/saut) qui a déjà décidé via reseauOk.
        if (!suiviVision && !SUIVI_ATHLETE_SIMU) {
            val r = etatFeuRtk()
            if (r.feu == FeuRtk.ROUGE) { afficherBlocageRtk(r); return }
        }
        // Si on etait en MODE MANUEL (ou VirtualStick coupe), l'app reprend la main
        // AVANT de lancer le suivi -> sinon la commande n'aurait aucun effet.
        // OBSERVATION athlète : NE PAS activer le Virtual Stick (sécurité hélices en place).
        if (!vsActif && !athleteObs) { try { pont.activerVirtualStick(true) } catch (_: Exception) {}; vsActif = true }
        if (modeManuel) { modeManuel = false; majBoutonManuel() }
        // ETAPE 2 : repartir d'un historique de prediction propre (pas de residu
        // d'une session precedente qui fausserait vitesse/cap au demarrage).
        try { predicteur.reinitialiser() } catch (_: Exception) {}
        try { capDeplacement.reinitialiser() } catch (_: Exception) {}   // aucun cap hérité d'un vol précédent
        predPret = false
        dernierGimbalPitch = Double.NaN   // ETAPE 3 : forcer un 1er envoi de nacelle
        gimbalVisionDeg = Double.NaN
        mouvementActif = null; positionSuivi = "derriere"; phaseMouv = 0.0   // on demarre en suivi simple (derriere)
        azimutCourantDeg = Double.NaN
        suiviActif = true
        // ENREGISTREMENT VIDÉO AUTO (2026-07-25) : un suivi qui démarre est un plan qu'on
        // veut filmer — le vol boîtier du jour n'a RIEN enregistré. Pas en OBSERVATION
        // (athleteObs : rien ne vole), pas si déjà en cours (lancé à la RC par exemple).
        if (!athleteObs && enVol) {
            try {
                if (!pont.enregistreEnCours()) {
                    pont.demarrerEnregistrement()
                    android.util.Log.i("Phase3", "Enregistrement vidéo démarré avec le suivi")
                }
            } catch (_: Exception) {}
        }
        // JOURNAL DE VOL : un fichier par session de suivi, avec la configuration EXACTE.
        // Sans cet en-tête, on ne sait pas après coup avec quels réglages le vol a eu lieu.
        try {
            journalVol.demarrer(
                ctx = this,
                mode = when {
                    SUIVI_ATHLETE_SIMU -> "ATHLETE"
                    suiviVision -> "VISION"
                    else -> "BOITIER"
                },
                entete = "profil=$profilSujet cadrage=${distanceCadrageM}m angle=$positionSuivi" +
                    " vMaxH=${if (SUIVI_ATHLETE_SIMU) V_MAX_HORIZ.toDouble() else vMaxHorizSelonDrone()}" +
                    " altCible=${OFFSET_HAUTEUR_M}m inverserRollPitch=" +
                    "${ca.cineflight.stage.control.PontDjiReel.INVERSER_ROLL_PITCH}" +
                    " anticipation=$ANTICIPATION_ACTIVE observation=$athleteObs" +
                    " drone=${pont.modeleDrone()}")
        } catch (_: Throwable) {}

        // SIGNAL VISUEL « MODE AUTOMATIQUE ARMÉ » (2026-07-25) : LED AVANT allumée dès que
        // le suivi est actif. Deux usages : les personnes autour voient que l'appareil est
        // en mode automatique ; le SUJET FILMÉ, souvent loin, sait qu'il est encore suivi.
        // ⚠ NE TOUCHE PAS aux feux de navigation (réglementaires, laissés au firmware).
        if (!athleteObs) try { ca.cineflight.stage.control.BaliseLeds.signalEtatVol(true) } catch (_: Throwable) {}
        txtEtat.text = getString(R.string.p3_suivi_actif)
    }

    /** Change le mouvement cinematographique EN DIRECT (null = suivi simple). */
    /**
     * DISTANCE DE CADRAGE — cycle les paliers (plus proche → plus large → retour).
     * Applicable EN VOL : la cible se déplace, le drone rejoint la nouvelle distance à
     * vitesse bornée comme tout le reste (aucun mouvement brusque). Mémorisé pour la
     * prochaine session. Le plancher de sécurité du noyau reste prioritaire.
     */
    private fun cyclerDistanceCadrage() {
        idxCadrage = (idxCadrage + 1) % paliersCadrage.size
        getSharedPreferences("cineflight", MODE_PRIVATE).edit()
            .putInt(CLE_CADRAGE, idxCadrage).apply()
        majBoutonCadrage()
        azimutCourantDeg = Double.NaN          // recale l'arc sur la nouvelle distance
        dernierGimbalPitch = Double.NaN        // force un réajustement de nacelle
        try { journalVol.evenement("cadrage -> ${distanceCadrageM} m") } catch (_: Throwable) {}
        txtEtat.text = getString(R.string.p3_cadrage_change, distanceCadrageM)
        android.util.Log.i("Phase3", "distance de cadrage = ${distanceCadrageM} m (palier $idxCadrage)")
    }

    private fun majBoutonCadrage() {
        if (!::btnCadrage.isInitialized) return
        val libelle = when (idxCadrage) {
            0 -> getString(R.string.p3_cadrage_tres_proche)
            1 -> getString(R.string.p3_cadrage_proche)
            2 -> getString(R.string.p3_cadrage_moyen)
            else -> getString(R.string.p3_cadrage_large)
        }
        btnCadrage.text = getString(R.string.p3_cadrage_btn, libelle, distanceCadrageM)
    }

    /** Change l'ANGLE du suivi simple EN DIRECT (derriere/devant/gauche/droite/plongee). */
    private fun choisirPosition(pos: String) {
        mouvementActif = null
        positionSuivi = pos
        try { journalVol.evenement("angle -> $pos") } catch (_: Throwable) {}
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
        try { ca.cineflight.stage.control.BaliseLeds.signalEtatVol(false) } catch (_: Throwable) {}
        try { journalVol.terminer("suivi arrêté par le pilote") } catch (_: Throwable) {}
        txtEtat.text = getString(R.string.p3_suivi_arrete)
    }

    /**
     * RETOUR MAISON depuis un mode de suivi (boîtier / voiture / vision, 2026-07-25).
     * ORDRE IMPOSÉ : (1) suivi coupé, (2) commande NEUTRE, (3) Virtual Stick RENDU au
     * firmware — sinon l'app et le RTH se disputent l'autorité —, (4) KeyStartGoHome.
     * Annulation : bouger un stick de la RC (comportement DJI standard).
     */
    private fun lancerRthSuivi() {
        suiviActif = false
        hover()                                                       // neutre immédiat
        try { ca.cineflight.stage.control.BaliseLeds.signalEtatVol(false) } catch (_: Throwable) {}
        if (vsActif) { try { pont.activerVirtualStick(false) } catch (_: Exception) {}; vsActif = false }
        try { journalVol.terminer("RTH demandé par le pilote") } catch (_: Throwable) {}
        txtEtat.text = getString(R.string.p3_rth_encours)
        // Petit délai : laisser l'acquittement de la sortie VS arriver avant le GoHome.
        lifecycleScope.launch {
            delay(600)
            pont.lancerRth { ok ->
                runOnUiThread {
                    txtEtat.text = if (ok) getString(R.string.p3_rth_actif)
                    else getString(R.string.p3_rth_echec)
                }
            }
        }
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

    /**
     * ÉPINGLAGE DE L'ÉCRAN pendant que le mode soccer est armé.
     *
     * PROTECTION D'ERGONOMIE, PAS DE SÉCURITÉ. Elle évite qu'un geste distrait envoie
     * l'écran en arrière-plan pendant un vol automatisé. Elle ne garantit RIEN : Android
     * peut toujours arrêter l'écran (appel entrant, mémoire basse, extinction, arrêt
     * système), et l'utilisateur peut sortir volontairement en maintenant Retour + Aperçu.
     *
     * La vraie protection reste ailleurs, et ne doit jamais dépendre de cet appel :
     * autorité de commande unique, arrêt de la boucle à onStop, désinscription des
     * écouteurs DJI, réaction sûre à la perte du premier plan.
     *
     * L'échec est journalisé, jamais propagé : un épinglage refusé ne doit pas empêcher
     * d'armer, encore moins de désarmer.
     */
    private fun epinglerEcran(actif: Boolean) {
        try {
            if (actif) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startLockTask()
                journalCycleVie("EPINGLAGE actif=OUI")
            } else {
                stopLockTask()
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                journalCycleVie("EPINGLAGE actif=NON")
            }
        } catch (e: Throwable) {
            journalCycleVie("EPINGLAGE_ECHEC actif=$actif cause=${e.javaClass.simpleName}")
        }
    }

    /**
     * DÉSARMEMENT DU MODE SOCCER — ordre imposé.
     *
     * Les commandes cessent AVANT que l'écran soit libéré. L'inverse laisserait une
     * fenêtre, si courte soit-elle, où le pilote peut quitter l'écran alors que la
     * commande automatisée est encore en vigueur.
     *
     * NOTE — écart assumé par rapport à la séquence proposée : on n'arrête PAS ici la
     * boucle pilote ni les écouteurs DJI. Ils servent AUSSI au vol manuel et au mode rail,
     * qui doivent rester opérants après un désarmement du soccer. Leur arrêt reste lié au
     * cycle de vie (onStop), là où il a un sens. De même, l'autorité de commande reste
     * attachée à l'écran vivant, pas à l'armement : la libérer ici bloquerait le stationnaire
     * et la reprise manuelle, c'est-à-dire précisément ce dont le pilote a besoin après un
     * désarmement.
     */
    private fun desarmerModeSoccer(raison: String) {
        // 1) LES COMMANDES D'ABORD.
        soccerArme = false
        soccerWatchdogIndep.desarmerSurveillance()  // desarmement volontaire, pas une defaillance
        try {
            pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
        } catch (_: Throwable) {}
        // 2) L'ÉCRAN ENSUITE.
        epinglerEcran(false)
        majBoutonSoccer()
        journalCycleVie("SOCCER_DESARME raison=$raison")
        try { txtEtat.text = "Mode SOCCER désarmé — contrôle automatique inactif" } catch (_: Throwable) {}
    }

    private fun basculerArmementSoccer() {
        if (soccerArme) {
            // Le désarmement passe par l'appui long (voir le bouton) : un simple appui ne
            // doit pas pouvoir désarmer par inadvertance pendant un vol.
            //
            // TRACE OBLIGATOIRE — un appui qui ne produit RIEN doit laisser une marque. Sans
            // elle (relevé du 2026-07-22), un opérateur pouvait appuyer plusieurs fois sur un
            // bouton annoncé « désarmé » pendant que le code le croyait armé : aucun dialogue,
            // aucune ligne, et 20 minutes d'essai perdues sans indice au journal.
            // Le champ `detecteur` révèle immédiatement l'incohérence « armé sans watchdog ».
            if (TEST_E03_CESSATION_VS) {
                logE03("E03 SOCCER_APPUI_SANS_EFFET etat_interne=ARME " +
                       "detecteur=${if (try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }) "EN_SERVICE" else "HORS_SERVICE"} " +
                       "action_attendue=appui_maintenu_3s_pour_desarmer ts=${System.currentTimeMillis()}")
            }
            try {
                txtEtat.text = "Maintenir « Mode SOCCER » 3 s pour désarmer"
            } catch (_: Throwable) {}
            return
        }
        // AUTORITÉ DE COMMANDE — vérifiée AVANT d'armer. Si un autre écran la détient,
        // on refuse : deux producteurs de commandes vers un même aéronef est le défaut
        // que ce verrou existe pour empêcher.
        if (!ca.cineflight.stage.control.AutoriteCommandeDrone.acquerir(instanceId)) {
            val proprio = ca.cineflight.stage.control.AutoriteCommandeDrone.proprietaireActuel()
            journalCycleVie("ARMEMENT_REFUSE cause=autorite_detenue_par=$proprio")
            try { txtEtat.text = "⛔ Armement refusé : un autre écran contrôle déjà le drone" } catch (_: Throwable) {}
            return
        }
        confirmerVol(
            "Armer le mode SOCCER ?",
            "Le drone pourra se déplacer sur le rail si toutes les conditions de sécurité sont réunies. Le pilote garde la priorité et l'arrêt d'urgence désarme immédiatement.\n\nL'écran sera épinglé pendant l'armement. Pour désarmer : maintenir le bouton SOCCER 3 secondes.",
            "Armer", true
        ) {
            // TRACE D'ARMEMENT — indispensable au diagnostic.
            // Sans elle, un journal montrant `soccerArme_avant=false` au moment d'un
            // événement ne permet pas de distinguer « l'opérateur n'a pas armé » de
            // « l'armement a échoué ». Constaté le 2026-07-22 : contre-essai non concluant
            // faute de savoir laquelle des deux situations s'était produite.
            val latchAvant = soccerArretUrgence
            soccerArretUrgence = false     // un nouvel armement leve un ancien arret d'urgence
            soccerArme = true
            epinglerEcran(true)
            // WATCHDOG INDEPENDANT (REQ-WDG-001) : la confirmation du dialog EST la
            // decision humaine explicite -> reset d'un eventuel latch, armement de la
            // surveillance, demarrage du thread B (idempotent).
            soccerWatchdogIndep.reset()
            soccerWatchdogIndep.armer()
            soccerWatchdogIndep.demarrer()
            // TRACE ÉCRITE APRÈS LE DÉMARRAGE DU DÉTECTEUR — pas avant. Le champ
            // `detecteur` doit rendre compte de l'état qui suivra l'armement ; journalisé
            // en amont il aurait consigné « hors service » à chaque fois, ce qui n'aurait
            // rien voulu dire. Sans ce champ (relevé du 2026-07-22), une session de 15 min
            // sans déclenchement ne prouvait pas que le détecteur surveillait pendant ce
            // temps — l'absence d'alarme et l'absence de détecteur se ressemblent trop.
            if (TEST_E03_CESSATION_VS) {
                logE03("E03 SOCCER_ARME instance=$instanceId latch_urgence_avant=" +
                       "${if (latchAvant) "POSE" else "LIBRE"} latch_apres=LIBRE " +
                       "mode_manuel=${if (modeManuel) "OUI" else "NON"} " +
                       "detecteur=${if (try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }) "EN_SERVICE" else "HORS_SERVICE"} " +
                       "vs=${if (vsActif) "OUI" else "NON"} banc=${if (e03BancArme) "OUI" else "NON"} " +
                       "ts=${System.currentTimeMillis()}")
            }
            // TRAÇABILITÉ (v54) : identifiant de la configuration de sécurité qui gouverne
            // cet armement (empreinte des seuils SafetyLimits) — exigence du dossier.
            logSecuTest("SAFETYLIMITS ts=${System.currentTimeMillis()} config_id=${ca.cineflight.stage.control.SafetyLimits.CONFIG_ID} armement=soccer")
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

    // ══════════════════════════════════════════════════════════════════════════════
    // ESSAI E-03 — infrastructure au banc (drapeau TEST_E03_CESSATION_VS)
    // ══════════════════════════════════════════════════════════════════════════════
    private val e03Log = ca.cineflight.stage.sport.soccer.EssaiE03Log()
    /** CAPTEUR des mesures T0..T6 + persistance (classe pure, testée en JVM). */
    private val e03Capteur = ca.cineflight.stage.sport.soccer.EssaiE03Capteur()
    // Point de vol simulé (mêmes coordonnées que le CONOPS ; le simulateur ne bouge pas
    // le drone physiquement, hélices retirées).
    private val E03_LAT = 45.50189
    private val E03_LON = -73.56739
    @Volatile private var e03Compteur = 0
    /** true une fois les observateurs E-03 branchés (idempotence). */
    @Volatile private var e03ObservateursBranches = false
    /**
     * GEL DES BATTEMENTS (scénario E03-03 uniquement). Quand true, la boucle pilote cesse
     * d'alimenter le watchdog INDÉPENDANT : c'est le seul moyen de le mettre réellement en
     * situation de détecter un fil d'émission figé. Levé automatiquement à la fin de la
     * fenêtre d'observation. N'a AUCUN effet hors TEST_E03_CESSATION_VS.
     */
    @Volatile private var e03GelBattements = false

    /** Nombre d'événements de connexion RC reçus — révèle un abonnement multiple. */
    @Volatile private var e03CptConnexionRc = 0

    /**
     * Nombre d'événements de connexion de l'AÉRONEF reçus (E-03 scénario 08).
     *
     * Compteur SÉPARÉ de celui de la radiocommande : les deux maillons tombent
     * indépendamment, et les confondre empêcherait de distinguer « le drone a disparu »
     * de « la radiocommande a disparu » — deux pannes différentes, deux réactions à
     * tracer distinctement au dossier.
     */
    @Volatile private var e03CptConnexionDrone = 0

    /**
     * Dernier état connu de la liaison AÉRONEF. `null` tant qu'aucun événement n'est arrivé.
     *
     * Sert à distinguer une PERTE (présent → absent) d'une ABSENCE INITIALE (écran ouvert
     * avant la mise sous tension du drone). Seule la première est une défaillance.
     */
    @Volatile private var droneConnecteDernier: Boolean? = null

    /**
     * ZÉRO IMPOSÉ (scénario E03-02 uniquement). Quand true, la boucle pilote commande 0
     * au lieu du throttle d'essai : c'est le seul moyen que « zéro maintenu » signifie
     * réellement zéro maintenu. Levé en fin de fenêtre d'observation.
     */
    @Volatile private var e03ForcerZero = false

    /** Dernier état de liaison RC annoncé par le SDK (vrai par défaut : on ne présume pas une panne). */
    @Volatile private var rcConnecteDernier = true

    /**
     * Délai de CONFIRMATION d'une perte de radiocommande avant mise en sécurité.
     * Assez court pour rester dans l'enveloppe de réaction attendue (§378), assez long
     * pour absorber un faux négatif ponctuel de la clé DJI (mesuré le 2026-07-22).
     */
    private val RC_PERTE_CONFIRMATION_MS = 400L

    /**
     * Simulateur DJI déjà activé dans cette session. `enableSimulator` ÉCHOUE quand le
     * simulateur tourne déjà — c'est ce qui a produit six `simulateur=ECHEC` consécutifs
     * alors qu'il était bel et bien actif depuis l'ouverture de l'écran. On ne le
     * réactive donc pas, au lieu d'interpréter un refus légitime comme une panne.
     */
    @Volatile private var e03SimulateurActif = false

    /**
     * MODE BANC ARMÉ (voir TEST_E03_BANC). Faux au lancement de l'écran : il faut une
     * action explicite de l'opérateur. Tant qu'il est faux, l'application se comporte
     * EXACTEMENT comme en production.
     */
    @Volatile private var e03BancArme = false

    /** Libellé du bouton d'armement du banc, tenu à jour après chaque bascule. */
    private var e03BtnBanc: android.widget.Button? = null

    /**
     * ARME ou DÉSARME le mode banc.
     *
     * REFUS si l'aéronef est en vol : la dérogation n'a de sens qu'au sol, hélices
     * retirées. L'autoriser en vol reviendrait à court-circuiter le bit IF sur un aéronef
     * qui vole — exactement ce que l'arbitre est là pour empêcher.
     *
     * À l'armement on demande le Virtual Stick au SDK. Le résultat (accepté / refusé) est
     * journalisé par les observateurs déjà branchés : c'est LUI qui dira si le Mini 4 Pro
     * accepte le VS au sol, question restée ouverte jusqu'ici.
     */
    private fun e03BasculerBanc() {
        if (!TEST_E03_CESSATION_VS || !TEST_E03_BANC) return
        // txtEtat est lateinit : on ne laisse JAMAIS un défaut d'affichage faire tomber
        // l'écran pendant un essai — le journal, lui, garde la trace dans tous les cas.
        fun etat(msg: String) { try { txtEtat.text = msg } catch (_: Throwable) {} }
        if (!e03BancArme) {
            if (enVol) {
                etat("BANC REFUSÉ : aéronef en vol")
                logE03("E03 BANC refus=aeronef_en_vol consequence=derogation_non_armee")
                return
            }
            e03BancArme = true
            // L'identité matérielle est souvent servie par le SDK APRÈS l'ouverture de
            // l'écran. On la reprend ici : l'en-tête doit être complet avant toute mesure.
            e03ReviserConfigSiIdentiteArrivee()
            logE03("E03 BANC arme=OUI helices=RETIREES_DECLARE " +
                   "derogation_bits=IF,PF,ON juges=PO,EM,VS,AC,BO,RV,CC,OG " +
                   "portee=T0_a_T5_uniquement " +
                   "non_mesurable=T6_persistance_physique_moteurs_a_l_arret")
            // ORDRE IMPOSÉ : simulateur D'ABORD, Virtual Stick ENSUITE. Constat 2026-07-22 :
            // le SDK a accordé le VS (accorde=OUI) dans la seule session où le simulateur
            // était ACTIF, et l'a refusé (accorde=NON) dans celle où il avait échoué.
            // Demander le VS avant que le simulateur soit prêt revient donc à le demander
            // pour rien. Voir e03PreparerBanc().
            e03PreparerBanc(essaisRestants = 5)
            etat("⚠ BANC ARMÉ — préparation…")
        } else {
            e03BancArme = false
            try { pont.activerVirtualStick(false) } catch (_: Throwable) {}
            vsActif = false
            logE03("E03 BANC arme=NON vs_relache=OUI")
            etat("Banc désarmé")
        }
        e03BtnBanc?.text = if (e03BancArme) "🔓 BANC ARMÉ" else "🔒 ARMER LE BANC"
        e03BtnBanc?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (e03BancArme) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt())
    }

    /**
     * Lance une charge de diagnostic et encadre la fenêtre dans le journal.
     *
     * La charge ne prouve rien à elle seule : ce sont les lignes `WDG_INDEP` (ou leur
     * ABSENCE) entre STRESS DEBUT et STRESS FIN qui constituent la preuve. On journalise
     * donc les bornes explicitement, pour qu'un relevé postérieur puisse les corréler sans
     * avoir à deviner quand la charge tournait.
     *
     * Un déclenchement pendant la fenêtre n'est PAS automatiquement un défaut : il peut
     * signaler une vraie perte de cycle sous charge extrême. C'est l'analyse qui tranche,
     * pas l'étiquette — d'où le libellé neutre côté journal.
     */
    private fun e03LancerStress(mode: ca.cineflight.stage.diag.StressBanc.Mode) {
        if (!TEST_E03_STRESS) return
        if (ca.cineflight.stage.diag.StressBanc.actif()) {
            txtEtat.text = "Charge déjà en cours"
            return
        }
        // ── PRÉCONDITION : LE DÉTECTEUR DOIT ÊTRE EN SERVICE ──────────────────────
        // Un essai anti-faux-positif ne vaut QUE si le watchdog surveille pendant la charge.
        // Sans cela, l'absence de WDG_INDEP ne prouve rien — c'est vérifier un détecteur de
        // fumée en l'ayant débranché (relevé du 2026-07-22 : 3 charges avec `soccer_arme=NON`).
        //
        // Le banc doit être armé — c'est la décision humaine, elle ne s'automatise pas. Le
        // mode soccer, lui, est réarmé ICI comme la série le fait déjà : chaque scénario le
        // désarme, et exiger un réarmement manuel entre chaque charge n'apportait aucune
        // sécurité supplémentaire — seulement 8 refus consécutifs et aucune mesure.
        if (!e03BancArme) {
            logE03("E03 STRESS REFUS mode=${mode.name} cause=banc_non_arme " +
                   "action=armer_le_banc_helices_retirees ts=${System.currentTimeMillis()}")
            txtEtat.text = "⛔ Armer le banc d'abord"
            return
        }
        // ÉTAT DE SANTÉ, PAS INTENTION : `estEnService()` vérifie que le thread porteur est
        // VIVANT. `surveillanceArmee()` seul rendait true sur un détecteur mort après un
        // passage en arrière-plan — la charge se serait déroulée sans surveillance.
        if (!soccerArme || !(try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false })) {
            logE03("E03 STRESS PREPARATION detecteur=hors_service action=rearmement_automatique " +
                   "arme=${try { soccerWatchdogIndep.surveillanceArmee() } catch (_: Throwable) { false }} " +
                   "en_service=${try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }} " +
                   "ts=${System.currentTimeMillis()}")
            e03RearmerSoccerAuto(0)
        }
        val nbFils = Runtime.getRuntime().availableProcessors()
        // Laisse la boucle pilote publier quelques battements avant de charger : démarrer la
        // charge sur un watchdog tout juste armé mesurerait le démarrage, pas la robustesse.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            val enService = soccerArme &&
                (try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false })
            if (!enService) {
                logE03("E03 STRESS REFUS mode=${mode.name} cause=detecteur_toujours_hors_service " +
                       "apres_rearmement=OUI consequence=absence_de_declenchement_ne_prouverait_rien " +
                       "ts=${System.currentTimeMillis()}")
                runOnUiThread { try { txtEtat.text = "⛔ Détecteur hors service — voir le journal" } catch (_: Throwable) {} }
                return@launch
            }
            e03DemarrerStress(mode, nbFils)
        }
    }

    /** Démarre effectivement la charge, détecteur vérifié en service. */
    private fun e03DemarrerStress(mode: ca.cineflight.stage.diag.StressBanc.Mode, nbFils: Int) {
        logE03("E03 " + ca.cineflight.stage.diag.StressBanc.descriptionDemarrage(
            mode, E03_STRESS_DUREE_MS, nbFils) +
            " timeout_ms=${ca.cineflight.stage.control.SafetyLimits.WATCHDOG_TIMEOUT_MS}" +
            " periode_ms=${ca.cineflight.stage.control.SafetyLimits.WATCHDOG_INDEP_PERIODE_MS}" +
            // MESURÉ, PAS AFFIRMÉ. Ces trois champs étaient écrits en dur : le journal
            // affirmait « EN_SERVICE » sans jamais l'avoir constaté. Une preuve d'essai ne
            // se déclare pas, elle se relève.
            " soccer_arme=${if (soccerArme) "OUI" else "NON"}" +
            " surveillance_watchdog=${if (try { soccerWatchdogIndep.surveillanceArmee() } catch (_: Throwable) { false }) "ARMEE" else "DESARMEE"}" +
            " detecteur=${if (try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }) "EN_SERVICE" else "HORS_SERVICE"}" +
            " ts=${System.currentTimeMillis()}")
        val lance = ca.cineflight.stage.diag.StressBanc.demarrer(
            mode = mode, dureeMs = E03_STRESS_DUREE_MS, nbFilsCpu = nbFils,
        ) { compteRendu ->
            // ÉTAT DU DÉTECTEUR EN FIN DE CHARGE : s'il s'est désarmé entre-temps (un
            // déclenchement pose le latch et coupe la surveillance), la fenêtre n'a PAS été
            // surveillée jusqu'au bout et la preuve est partielle. Le dire ici évite de
            // conclure à tort à une absence de faux positif.
            val encoreArme = try {
                soccerArme && soccerWatchdogIndep.estEnService()
            } catch (_: Throwable) { false }
            logE03("E03 $compteRendu " +
                   "detecteur_en_fin=${if (encoreArme) "TOUJOURS_EN_SERVICE" else "DESARME_EN_COURS_preuve_partielle"} " +
                   "ts=${System.currentTimeMillis()}")
            runOnUiThread { try { txtEtat.text = "Charge terminée — relire le journal" } catch (_: Throwable) {} }
        }
        txtEtat.text = if (lance) "⚡ Charge ${mode.name} — 30 s" else "Charge refusée"
    }

    // ── SÉRIE AUTOMATIQUE DE RÉPÉTITIONS ────────────────────────────────────────────
    /**
     * Scénarios dont le stimulus est ENTIÈREMENT logiciel, donc répétables sans geste
     * humain. Les autres (débrancher l'USB, éteindre la RC, tuer le processus…) exigent
     * une action physique : les « automatiser » reviendrait à journaliser un stimulus qui
     * n'a pas eu lieu. Ils restent en déclenchement unitaire, c'est délibéré.
     */
    private val E03_SCENARIOS_AUTOMATISABLES: Set<ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario> =
        setOf(
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_01_ARRET_NORMAL,
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_02_ZERO_MAINTENU,
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_03_GEL_THREAD,
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_04_EXCEPTION,
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_11_SORTIE_VS_EXPLICITE,
        )

    // Scénarios PHYSIQUES où une commande NON NULLE doit couler au moment du stimulus pour
    // que la persistance soit mesurable. On les distingue des pertes de liaison (07/08/09) :
    // là, la commande cesse d'elle-même dès que la perception meurt — l'absence de commande
    // est le comportement ATTENDU, pas une erreur d'opérateur. Pour CEUX-CI au contraire,
    // une perception fraîche est indispensable ; sans elle l'arbitre fail-closed bloque
    // l'émission et la répétition rend NUL après 15 s d'attente inutile. On refuse donc tout
    // de suite, avec un retour à l'écran, au lieu de laisser l'opérateur découvrir le NUL en
    // fin de fenêtre (relevé 2026-07-22 : E03-13 répété en vain, mire non accrochée).
    private val E03_SCENARIOS_COMMANDE_ATTENDUE: Set<ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario> =
        setOf(
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_06_ARRIERE_PLAN,
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_13_SURCHARGE_THERMIQUE,
            // E03-05 (crash) : le marqueur n'a de sens que si l'aéronef était RÉELLEMENT
            // commandé au moment du crash. Sans commande vivante, on refuse — sinon on
            // « prouverait » une cessation qui n'avait rien à cesser.
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_05_CRASH_PROCESS,
        )

    /** Nombre de répétitions déclenchées par un appui (1 = unitaire, 5 = série §378). */
    @Volatile private var e03Repetitions = 1
    /** Une série est en cours : empêche tout chevauchement. */
    @Volatile private var e03SerieEnCours = false
    private var e03BtnSerie: android.widget.Button? = null

    /** Délai entre la fin d'une répétition et le réarmement de la suivante. */
    private val E03_PAUSE_ENTRE_REPETITIONS_MS = 800L
    /** Temps laissé à la boucle pour émettre un throttle non nul avant le stimulus. */
    private val E03_DELAI_AVANT_STIMULUS_MS = 3000L

    /**
     * RÉARMEMENT AUTOMATIQUE DU MODE SOCCER entre deux répétitions d'une série.
     *
     * En usage normal, l'armement passe par une confirmation humaine — c'est un garde-fou
     * voulu. Ici il est contourné, et il faut être clair sur le prix : pendant une série,
     * l'opérateur n'est plus consulté à chaque cycle. Ce contournement est acceptable
     * UNIQUEMENT parce que la série ne peut démarrer qu'au banc, hélices retirées, après
     * un armement manuel explicite du banc, et qu'elle s'interrompt au premier écart.
     * Chaque réarmement est journalisé : rien n'est implicite dans la preuve.
     */
    private fun e03RearmerSoccerAuto(rep: Int) {
        soccerArretUrgence = false
        modeManuel = false
        soccerArme = true
        try {
            soccerWatchdogIndep.reset(); soccerWatchdogIndep.armer(); soccerWatchdogIndep.demarrer()
            // BATTEMENT IMMÉDIAT après armement. Sans lui, le thread B peut évaluer l'âge
            // avant que la boucle pilote ait publié son premier battement et déclencher
            // aussitôt — observé le 2026-07-22 avec `WDG_INDEP declenche age_ms=-3`, qui a
            // désarmé le soccer et interrompu la série E03-02 à 0/5. Un âge négatif n'est
            // pas une défaillance à détecter, c'est une mesure faite trop tôt.
            soccerWatchdogIndep.battement()
        } catch (_: Throwable) {}
        if (e03BancArme && !vsActif) {
            try { pont.activerVirtualStick(true) } catch (_: Throwable) {}
            vsActif = true
        }
        runOnUiThread { try { majBoutonSoccer(); majBoutonManuel() } catch (_: Throwable) {} }
        // RELEVÉ, pas déclaré : ces champs étaient écrits en dur (« soccer_arme=OUI »). Si le
        // réarmement échouait, le journal l'affirmait quand même. On lit l'état réel.
        logE03("E03 SERIE_REARMEMENT rep=$rep " +
               "soccer_arme=${if (soccerArme) "OUI" else "NON"} " +
               "mode_manuel=${if (modeManuel) "OUI" else "NON"} " +
               "urgence_latch=${if (soccerArretUrgence) "OUI" else "NON"} " +
               "detecteur=${if (try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }) "EN_SERVICE" else "HORS_SERVICE"} " +
               "vs=${if (vsActif) "OUI" else "NON"} " +
               "note=confirmation_humaine_contournee_pendant_la_serie_banc_uniquement")
    }

    /**
     * Enchaîne [n] répétitions d'un scénario automatisable.
     *
     * ARRÊT IMMÉDIAT si une précondition tombe (banc désarmé, aéronef en vol). Mieux vaut
     * une série incomplète qu'une série dont les dernières répétitions ne mesurent rien.
     */
    private fun e03LancerSerie(s: ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario, n: Int) {
        if (e03SerieEnCours) {
            txtEtat.text = "Série déjà en cours"
            return
        }
        if (!e03BancArme) {
            txtEtat.text = "⛔ Armer le banc d'abord"
            logE03("E03 SERIE refus=banc_non_arme scenario=${s.name}")
            return
        }
        e03SerieEnCours = true
        // Dernier filet avant de mesurer : si l'identité SDK est arrivée entre-temps,
        // l'en-tête est révisé maintenant, pas après coup.
        e03ReviserConfigSiIdentiteArrivee()
        logE03("E03 SERIE DEBUT scenario=${s.name} repetitions=$n " +
               "ts=${System.currentTimeMillis()}")
        lifecycleScope.launch {
            var faites = 0
            try {
                for (i in 1..n) {
                    if (!e03BancArme || enVol) {
                        logE03("E03 SERIE INTERRUPTION apres=$faites/$n " +
                               "cause=${if (enVol) "aeronef_en_vol" else "banc_desarme"}")
                        break
                    }
                    e03RearmerSoccerAuto(i)
                    runOnUiThread {
                        try { txtEtat.text = "🧪 Série ${s.name} — répétition $i/$n" } catch (_: Throwable) {}
                    }
                    // Laisse la boucle pilote émettre un throttle non nul : sans commande
                    // en vigueur, il n'y a aucune persistance à mesurer.
                    kotlinx.coroutines.delay(E03_DELAI_AVANT_STIMULUS_MS)
                    if (!soccerArme) {
                        logE03("E03 SERIE INTERRUPTION apres=$faites/$n cause=soccer_desarme_avant_stimulus")
                        break
                    }
                    // Une répétition REFUSÉE (environnement non conforme, précondition
                    // manquante) ne compte pas, et la répéter 4 fois de plus ne ferait
                    // qu'empiler des refus identiques — vu le 2026-07-22 : E03-11 refusé
                    // 5 fois pour la même cause, avec un compteur affichant « 5/5 faites ».
                    if (!e03Declencher(s)) {
                        logE03("E03 SERIE INTERRUPTION apres=$faites/$n cause=repetition_refusee " +
                               "note=corriger_la_cause_avant_de_relancer")
                        break
                    }
                    faites += 1
                    // Fenêtre d'observation + écriture de la synthèse + marge.
                    kotlinx.coroutines.delay(E03_FENETRE_OBSERVATION_MS + E03_PAUSE_ENTRE_REPETITIONS_MS)
                }
            } finally {
                e03SerieEnCours = false
                logE03("E03 SERIE FIN scenario=${s.name} repetitions_faites=$faites/$n " +
                       "ts=${System.currentTimeMillis()}")
                runOnUiThread {
                    try { txtEtat.text = "Série terminée : $faites/$n répétitions" } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * PRÉPARATION DU BANC : simulateur DJI, PUIS Virtual Stick.
     *
     * POURQUOI DES RÉESSAIS. `enableSimulator` était appelé à l'ouverture de l'écran, donc
     * avant que la liaison SDK soit établie : il échouait (`simulateur=ECHEC`), toujours
     * en compagnie de `identite_sdk=PARTIELLE` — même cause, SDK pas encore prêt. On
     * réessaie ici, déclenché par une action opérateur qui suppose tout branché.
     *
     * SI LE SIMULATEUR RESTE INDISPONIBLE, on demande quand même le Virtual Stick : le
     * refus éventuel du SDK est une DONNÉE d'essai (`VS_ACTIVATION accorde=NON`), pas un
     * échec à masquer. Le journal dira alors que les commandes partent sans être suivies
     * d'effet, et la portée de la mesure s'en trouve réduite — explicitement.
     */
    private fun e03PreparerBanc(essaisRestants: Int) {
        if (!TEST_E03_SIMULATEUR) {
            logE03("E03 BANC simulateur=NON_DEMANDE env=CHAINE_REELLE vs_demande=OUI")
            e03DemanderVsBanc()
            return
        }
        if (e03SimulateurActif) {
            logE03("E03 BANC simulateur=DEJA_ACTIF reactivation=INUTILE vs_demande=OUI")
            e03DemanderVsBanc()
            return
        }
        try {
            pont.activerSimulateur(E03_LAT, E03_LON) { ok ->
                if (ok) e03SimulateurActif = true
                logE03("E03 BANC simulateur=${if (ok) "ACTIF" else "ECHEC"} " +
                       "essais_restants=$essaisRestants ts=${System.currentTimeMillis()}")
                when {
                    ok -> e03DemanderVsBanc()
                    essaisRestants > 0 -> lifecycleScope.launch {
                        kotlinx.coroutines.delay(1000)
                        if (e03BancArme) e03PreparerBanc(essaisRestants - 1)
                    }
                    else -> {
                        logE03("E03 BANC simulateur=INDISPONIBLE apres_reessais=OUI " +
                               "consequence=vs_demande_quand_meme_resultat_journalise")
                        e03DemanderVsBanc()
                    }
                }
            }
        } catch (_: Throwable) {
            logE03("E03 BANC simulateur=EXCEPTION consequence=vs_demande_quand_meme")
            e03DemanderVsBanc()
        }
    }

    /** Demande le Virtual Stick au banc. La réponse du SDK est journalisée par les observateurs. */
    private fun e03DemanderVsBanc() {
        try { pont.activerVirtualStick(true) } catch (_: Throwable) {}
        vsActif = true
        runOnUiThread {
            try { txtEtat.text = "⚠ BANC ARMÉ — hélices retirées obligatoire" } catch (_: Throwable) {}
        }
    }

    /**
     * VERROU INVERSE : si l'aéronef décolle alors que le banc est armé, on désarme
     * immédiatement. Appelé depuis la boucle pilote, donc réévalué en continu.
     */
    private fun e03SurveillerVolPendantBanc() {
        if (e03BancArme && enVol) {
            e03BancArme = false
            logE03("E03 BANC desarme_auto=OUI cause=aeronef_en_vol " +
                   "consequence=mesures_suivantes_non_recevables_au_banc")
            runOnUiThread {
                try {
                    e03BtnBanc?.text = "🔒 ARMER LE BANC"
                    txtEtat.text = "BANC DÉSARMÉ : aéronef en vol"
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * CONFIGURATION FIGÉE de l'essai (§381). Les champs lisibles automatiquement sont
     * renseignés ici ; les champs MATÉRIELS (SN et firmware de la RC, type de câble,
     * SN/firmware du drone) doivent être complétés par l'opérateur AVANT la campagne —
     * tant qu'ils sont vides, l'en-tête du journal porte `complete=NON` et liste les
     * manquants, ce qui signale de lui-même que la campagne n'est pas recevable.
     */
    private fun e03Config(): ca.cineflight.stage.sport.soccer.EssaiE03Config {
        // Identité matérielle lue au SDK (SN + firmware du drone et de la RC). Les champs
        // que le SDK ne sert pas restent vides et sont signalés manquants dans l'en-tête.
        val id = try {
            ca.cineflight.stage.control.SondeIdentiteDji.lire()
        } catch (_: Throwable) {
            ca.cineflight.stage.control.SondeIdentiteDji.Identite()
        }
        return ca.cineflight.stage.sport.soccer.EssaiE03Config(
            // ── Lus au SDK (vides si non servis : saisie manuelle au cahier d'essai) ──
            rcNumeroSerie = id.rcNumeroSerie,
            rcFirmware = id.rcFirmware,
            droneNumeroSerie = id.droneNumeroSerie,
            droneFirmware = id.droneFirmware,
            // ── IMPOSSIBLES à lire : propriétés physiques, aucun capteur. Saisie manuelle. ──
            cableType = "",
            portUtilise = "",
            // Hash APK : calculé automatiquement (mis en cache). Voir e03HashApk().
            apkHash = e03HashApkCache ?: "",
            // ── Renseignés automatiquement ──
            telModele = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            // SN du téléphone : NON lisible par l'application (Build.getSerial() exige
            // l'API 26 + READ_PHONE_STATE et reste refusé aux apps non privilégiées).
            // Consigné MANUELLEMENT par l'opérateur — l'en-tête le signale comme manquant
            // tant qu'il n'est pas renseigné.
            telNumeroSerie = "",
            androidVersion = android.os.Build.VERSION.RELEASE ?: "",
            appVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (_: Throwable) { "" },
            // Version MSDK : lue par RÉFLEXION (l'API exacte varie selon les versions du
            // SDK et n'est utilisée nulle part ailleurs dans le projet — une référence
            // directe ferait courir un risque de compilation). Repli sur la version
            // ÉPINGLÉE dans app/build.gradle, qui fait foi pour la traçabilité.
            msdkVersion = e03LireVersionMsdk(),
            droneModele = try { pont.modeleDrone() } catch (_: Throwable) { "" },
            configId = ca.cineflight.stage.control.SafetyLimits.CONFIG_ID,
            simulateurActif = TEST_E03_SIMULATEUR,
        )
    }

    /** Hash SHA-256 de l'APK, calculé une seule fois (opération disque non triviale). */
    @Volatile private var e03HashApkCache: String? = null

    /** true si l'en-tête déjà écrit portait une identité SDK COMPLÈTE. */
    @Volatile private var e03IdentiteSdkConsignee = false

    /**
     * RÉVISION DE L'EN-TÊTE DE CONFIGURATION — supprime une contrainte de procédure.
     *
     * L'en-tête §381 est écrit à l'ouverture de l'écran, après une attente de 8 s au plus
     * sur les numéros de série servis par le SDK. Si le drone ou la radiocommande ne sont
     * pas encore vus à ce moment-là, l'en-tête part avec `drone_sn=A_CONSIGNER` et
     * `identite_sdk=PARTIELLE` — et il n'était JAMAIS corrigé ensuite. L'opérateur devait
     * donc quitter l'écran, tout rebrancher, effacer le journal et recommencer. Constaté
     * deux fois le 2026-07-22, dont une campagne complète à refaire.
     *
     * Une exigence de traçabilité ne doit pas dépendre de l'ordre dans lequel on allume le
     * matériel. On relit donc la sonde au moment où l'identité compte VRAIMENT — armement
     * du banc, démarrage d'une série — et si elle est devenue complète, on réécrit
     * l'en-tête intégral.
     *
     * L'en-tête initial est CONSERVÉ, la révision est explicite (`E03 CONFIG_REVISION`) :
     * on ne réécrit pas l'histoire, on la complète. La dernière ligne `E03 CONFIG` fait foi.
     * Idempotent : sans effet une fois l'identité consignée.
     */
    private fun e03ReviserConfigSiIdentiteArrivee() {
        if (!TEST_E03_CESSATION_VS || e03IdentiteSdkConsignee) return
        val id = try {
            ca.cineflight.stage.control.SondeIdentiteDji.lire()
        } catch (_: Throwable) { return }
        if (!id.complete()) return
        e03IdentiteSdkConsignee = true
        logE03("E03 CONFIG_REVISION raison=identite_sdk_disponible_apres_ouverture_de_l_ecran " +
               "portee=en_tete_initial_conserve_la_ligne_CONFIG_suivante_fait_foi " +
               "ts=${System.currentTimeMillis()}")
        logE03(e03Config().ligneEntete(System.currentTimeMillis()))
        logE03("E03 CONFIG_LECTURE essais=revision identite_sdk=COMPLETE " +
               "note=cable_port_sn_telephone_a_consigner_au_cahier")
    }

    /**
     * HASH SHA-256 DE L'APK EN COURS D'EXÉCUTION (§381 : « application + hash APK »).
     *
     * Lit le fichier APK réellement installé (`sourceDir`) et en calcule l'empreinte. Cela
     * identifie sans ambiguïté le binaire utilisé pendant la campagne — une saisie manuelle
     * serait à la fois pénible et sujette à erreur, alors que c'est précisément la donnée
     * qui rend les résultats rattachables à une version.
     *
     * ⚠ À appeler HORS du fil UI : sur une APK de plusieurs dizaines de Mo, la lecture peut
     * prendre ~1 s. Le résultat est mis en cache.
     */
    private fun e03HashApk(): String {
        e03HashApkCache?.let { return it }
        val h = try {
            val chemin = packageManager.getApplicationInfo(packageName, 0).sourceDir
            val md = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(chemin).use { fis ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            android.util.Log.w("CineFlightE03", "Hash APK indisponible: ${e.message}")
            ""
        }
        e03HashApkCache = h
        return h
    }

    /**
     * Version du MSDK pour la traçabilité E-03. Tente plusieurs accesseurs par réflexion
     * (les noms varient selon les versions) ; à défaut, rend la version ÉPINGLÉE dans
     * app/build.gradle, suffixée pour indiquer qu'elle vient de la déclaration de build
     * et non d'une lecture à l'exécution.
     */
    private fun e03LireVersionMsdk(): String {
        return try {
            val cls = Class.forName("dji.v5.manager.SDKManager")
            val inst = cls.getMethod("getInstance").invoke(null)
            val noms = listOf("getSDKVersion", "getSdkVersion", "sdkVersion")
            for (n in noms) {
                try {
                    val v = cls.getMethod(n).invoke(inst)?.toString()
                    if (!v.isNullOrBlank()) return v
                } catch (_: Throwable) {}
            }
            MSDK_VERSION_BUILD
        } catch (_: Throwable) {
            MSDK_VERSION_BUILD
        }
    }

    /**
     * Branche les observateurs qui alimentent le capteur E-03. Idempotent.
     * - vitesse verticale RÉELLE (télémétrie) -> persistance physique + T6 automatique ;
     * - acquittement SDK de la sortie Virtual Stick -> T5.
     * Aucun effet hors essai : appelé uniquement sous TEST_E03_CESSATION_VS.
     */
    private fun e03BrancherObservateurs() {
        if (e03ObservateursBranches) return
        e03ObservateursBranches = true
        try {
            pont.obsVitesseVerticale = { v -> e03Capteur.observerVitesseReelle(v) }
        } catch (_: Throwable) {}
        try {
            pont.obsVsDesactivationConfirmee = { ok ->
                e03Capteur.marquerT5()
                logE03("E03 T5 acquittement_sortie_vs ok=$ok ts=${System.currentTimeMillis()}")
            }
        } catch (_: Throwable) {}
        // ACQUITTEMENT D'ACTIVATION DU VS — indispensable au banc.
        // `vsActif` est un drapeau APPLICATIF : il dit que l'app a DEMANDÉ le Virtual
        // Stick, pas que le SDK l'a accordé. Si DJI refuse (aéronef au sol, autorité non
        // rendue…), les commandes partent mais l'aéronef les ignore : la persistance
        // mesurée porterait alors sur une chaîne interrompue. On journalise donc la
        // réponse RÉELLE du SDK, pour que la lecture du journal ne puisse pas se tromper.
        try {
            pont.obsVsEnableAccepte = {
                logE03("E03 VS_ACTIVATION accorde=OUI par=SDK_DJI ts=${System.currentTimeMillis()} " +
                       "portee=commandes_effectivement_transmises")
            }
        } catch (_: Throwable) {}
        try {
            pont.obsVsEnableRefuse = {
                logE03("E03 VS_ACTIVATION accorde=NON par=SDK_DJI ts=${System.currentTimeMillis()} " +
                       "consequence=commandes_emises_mais_IGNOREES_par_l_aeronef " +
                       "portee=mesure_limitee_a_la_chaine_applicative")
            }
        } catch (_: Throwable) {}
        logE03("E03 observateurs=branches vitesse_verticale+acquittement_vs+activation_vs " +
               "ts=${System.currentTimeMillis()}")
    }

    /** Écrit une ligne E-03 dans son propre fichier (indépendant du log E06-09). */
    private fun logE03(ligne: String) {
        try {
            val f = java.io.File(getExternalFilesDir(null), "essai_e03.log")
            java.io.FileOutputStream(f, true).use { it.write((ligne + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {}
        android.util.Log.i("CineFlightE03", ligne)
    }

    /** Active le simulateur DJI pour l'essai (drone connecté, hélices retirées). */
    private fun e03ActiverSimulateur() {
        pont.activerSimulateur(E03_LAT, E03_LON) { ok ->
            if (ok) e03SimulateurActif = true
            logE03("E03 simulateur=${if (ok) "ACTIF" else "ECHEC"} ts=${System.currentTimeMillis()}")
            runOnUiThread {
                try { txtEtat.text = if (ok) "🧪 E-03 : simulateur DJI ACTIF (banc, hélices retirées)" else "🧪 E-03 : échec activation simulateur" } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Déclenche un scénario E-03. Chaque appel : (1) consigne T0 (dernière commande),
     * (2) provoque le stimulus, (3) laisse le watchdog / la logique réagir, puis
     * (4) journalise une ligne EssaiE03Log avec les horodatages capturés.
     * La MESURE FINE de T1..T6 et de la persistance se lit ensuite dans les journaux
     * corrélés (WDG_INDEP, E08, télémétrie) ; cette méthode pose le cadre et le verdict.
     */
    /** @return true si la répétition a été LANCÉE, false si elle a été refusée. */
    private fun e03Declencher(s: ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario): Boolean {
        e03Compteur += 1
        val rep = e03Compteur
        val cfg = ca.cineflight.stage.control.SafetyLimits.CONFIG_ID

        // (1) CONFORMITÉ D'ENVIRONNEMENT (tableau 36) : un scénario « RÉEL » exécuté sous
        // simulateur ne prouve RIEN. On le journalise et on REFUSE de produire une ligne
        // de résultat, plutôt que de laisser une mesure trompeuse entrer au dossier.
        val nc = e03Config().nonConformiteEnv(s)
        if (nc != null) {
            logE03("E03 REFUS scenario=${s.name} rep=$rep $nc ts=${System.currentTimeMillis()}")
            runOnUiThread {
                try { txtEtat.text = "⛔ E-03 : scénario ${s.name} exige la chaîne RÉELLE (simulateur actif) — répétition refusée." } catch (_: Throwable) {}
            }
            return false
        }

        // (1 bis) BANC ARMÉ ? Au sol et sans dérogation, l'arbitre bloque toute émission
        // (bit IF=0) : aucune commande ne part, la persistance mesurée vaut 0 et le verdict
        // serait un PASS vide de sens. On REFUSE plutôt que de produire cette fausse preuve.
        if (TEST_E03_BANC && !e03BancArme && !enVol) {
            logE03("E03 REFUS scenario=${s.name} rep=$rep cause=banc_non_arme " +
                   "consequence=aucune_commande_emise_persistance_sans_signification " +
                   "action=armer_le_banc_helices_retirees ts=${System.currentTimeMillis()}")
            runOnUiThread {
                try { txtEtat.text = "⛔ Armer le banc d'abord (hélices retirées)" } catch (_: Throwable) {}
            }
            return false
        }

        // (1 ter) MODE SOCCER ARMÉ ? Sans lui, l'arbitre rend « non arme » et AUCUNE
        // commande n'est émise : `v_commandee=0.0`, `MESURE_SANS_OBJET`, répétition nulle.
        // Le piège est sournois — le verdict peut afficher PASS alors que rien n'a été
        // exercé (constaté 2026-07-22 : 4 répétitions perdues de cette façon). On refuse
        // AVANT le stimulus, au lieu de laisser une ligne trompeuse entrer au journal.
        if (!soccerArme) {
            logE03("E03 REFUS scenario=${s.name} rep=$rep cause=soccer_non_arme " +
                   "consequence=aucune_commande_non_nulle_persistance_non_exercee " +
                   "action=armer_le_mode_soccer_avant_le_stimulus ts=${System.currentTimeMillis()}")
            runOnUiThread {
                try { txtEtat.text = "⛔ Armer le mode SOCCER d'abord" } catch (_: Throwable) {}
            }
            return false
        }

        // (1 quater) PERCEPTION VIVANTE ? Pour les scénarios physiques où la persistance se
        // mesure (E03-06, E03-13), une commande NON NULLE doit couler AU MOMENT du stimulus.
        // Sans perception fraîche, l'arbitre fail-closed bloque l'émission → v_commandee=0 →
        // répétition NULLE, mais découverte seulement au bout de 15 s. On applique ici la
        // MÊME condition que le point d'émission (yoloFrais + conf|joueurs) et on refuse
        // immédiatement avec un message clair. On ne truque RIEN : si la mire n'est pas
        // accrochée, il n'y a réellement rien à faire cesser — on épargne juste à l'opérateur
        // une fenêtre perdue. Exclut volontairement les pertes de liaison (07/08/09).
        if (s in E03_SCENARIOS_COMMANDE_ATTENDUE) {
            val ageYolo = if (yoloVueMs > 0L) System.currentTimeMillis() - yoloVueMs else -1L
            val yoloFrais = (yoloVueMs > 0L && ageYolo < YOLO_FRAIS_MS)
            val perceptionVivante = yoloFrais && (yoloConf >= YOLO_CONF_MIN || soccerNbJoueurs > 0)
            if (!perceptionVivante) {
                logE03("E03 REFUS scenario=${s.name} rep=$rep cause=perception_non_fraiche " +
                       "yolo_age_ms=$ageYolo joueurs_vus=$soccerNbJoueurs conf=$yoloConf " +
                       "consequence=aucune_commande_non_nulle_la_repetition_serait_NULLE " +
                       "action=accrocher_la_mire_avant_le_stimulus ts=${System.currentTimeMillis()}")
                runOnUiThread {
                    try { txtEtat.text = "⛔ Mire non accrochée — vise la cible puis recommence" } catch (_: Throwable) {}
                }
                return false
            }
        }

        // (1 quinquies) THERMIQUE DÉJÀ AU SEUIL ? La détection E03-13 repose sur une
        // TRANSITION sous→au-dessus du seuil (même garde que la perte d'aéronef, pour ne pas
        // déclencher un arrêt d'urgence à chaque ouverture d'écran quand l'appareil est déjà
        // chaud). Conséquence : si le niveau est DÉJÀ à SEVERE au moment du stimulus —
        // typiquement un override adb laissé verrouillé d'un tir précédent —, AUCUNE
        // transition ne peut survenir, et le tir rend NUL sans qu'il y ait le moindre défaut
        // de sécurité. On le détecte et on refuse tout de suite, en indiquant le reset requis
        // (relevé 2026-07-22 : niveau_initial=SEVERE, override non remis à zéro entre tirs).
        if (s == ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_13_SURCHARGE_THERMIQUE) {
            val niveauThermiqueActuel = try {
                (getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
                    ?.currentThermalStatus ?: -1
            } catch (_: Throwable) { -1 }
            if (niveauThermiqueActuel >= E03_SEUIL_THERMIQUE) {
                logE03("E03 REFUS scenario=${s.name} rep=$rep cause=thermique_deja_au_seuil " +
                       "niveau=${libelleThermique(niveauThermiqueActuel)} " +
                       "consequence=aucune_transition_possible_le_tir_serait_NUL " +
                       "action=cmd_thermalservice_reset_avant_le_stimulus ts=${System.currentTimeMillis()}")
                runOnUiThread {
                    try { txtEtat.text = "⛔ Thermique déjà à SEVERE — fais 'reset' puis recommence" } catch (_: Throwable) {}
                }
                return false
            }
        }

        // (2) DÉBUT D'OBSERVATION : efface T1..T6. On NE réinitialise PAS les vitesses —
        // la dernière commande non nulle émise AVANT le stimulus est justement la donnée
        // dont on mesure la persistance.
        //
        // DEUX RÉGIMES, parce que le stimulus n'a pas la même nature :
        //  - LOGICIEL (E03-01..04, 11) : l'appui EST le stimulus → T0 est figé maintenant.
        //  - PHYSIQUE (USB, RC, kill…) : l'appui annonce seulement l'intention ; l'événement
        //    arrive quelques secondes plus tard. Figer T0 ici comptait le temps de réaction
        //    de l'opérateur comme de la persistance logicielle (relevé E03-07 :
        //    persist_ms=1561 → FAIL, sans aucun défaut de sécurité). T0 continue donc de
        //    suivre le heartbeat jusqu'à la DÉTECTION réelle, qui le fige.
        val stimulusPhysique = s !in E03_SCENARIOS_AUTOMATISABLES
        if (stimulusPhysique) e03Capteur.armerObservationDifferee()
        else e03Capteur.demarrerObservation()
        // FENÊTRE D'OBSERVATION. Courte pour un stimulus logiciel (la chaîne réagit en
        // dizaines de ms) ; longue pour un stimulus physique, où il faut laisser à
        // l'opérateur le temps matériel de débrancher, d'éteindre ou de tuer le processus.
        // Allonger la fenêtre n'assouplit AUCUN critère : la persistance se mesure de T0 à
        // la dernière commande non nulle, pas sur la durée de la fenêtre. Une fenêtre trop
        // courte ne rendait pas l'essai sévère, elle le rendait impossible (relevé du
        // 2026-07-22 : trois tentatives E03-07, l'événement tombant chaque fois après la
        // fermeture).
        val fenetreMs = if (stimulusPhysique) E03_FENETRE_PHYSIQUE_MS else E03_FENETRE_OBSERVATION_MS
        logE03("E03 DEBUT scenario=${s.name} rep=$rep config_id=$cfg " +
               "simulateur=${if (TEST_E03_SIMULATEUR) "ACTIF" else "INACTIF"} " +
               "banc=${if (e03BancArme) "OUI" else "NON"} en_vol_reel=${if (enVol) "OUI" else "NON"} " +
               // PRÉREQUIS D'ÉMISSION, relevés AU MOMENT DU STIMULUS. Sans eux, une
               // persistance de 0 se lit comme un succès alors qu'aucune commande n'a pu
               // partir. Les trois doivent être vrais, sinon la répétition ne mesure rien.
               "soccer_arme=${if (soccerArme) "OUI" else "NON"} mode2D=${if (soccerMode2D) "OUI" else "NON"} " +
               "joueurs_vus=$soccerNbJoueurs mode_manuel=${if (modeManuel) "OUI" else "NON"} " +
               "urgence_latch=${if (soccerArretUrgence) "OUI" else "NON"} " +
               // ÉTAT DE SANTÉ DU DÉTECTEUR au moment du stimulus. E03-03 repose ENTIÈREMENT
               // sur le watchdog indépendant : si son thread porteur est mort, le scénario ne
               // peut rien détecter et l'absence de déclenchement n'aurait aucune valeur.
               "detecteur=${if (try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }) "EN_SERVICE" else "HORS_SERVICE"} " +
               // PÉRIMÈTRE DE LA DÉROGATION, avec les valeurs RÉELLES des bits neutralisés.
               // Le dossier doit pouvoir lire ce qui a été forcé ET ce qui aurait été mesuré :
               // sans cela, un lecteur ne peut pas distinguer une condition satisfaite d'une
               // condition contournée.
               (if (e03BancArme) " derogation_bits=IF,PF,ON reels[IF]=${if (enVol) "1" else "0"} " +
                                 "reels[PF]=${if (autoRtk.uppercase() == "FIX" || autoRtk.uppercase() == "FLOAT") "1" else "0"} " +
                                 "rtk=${autoRtk} " +
                                 "reels[ON]=${if (try { estOperateurProcheDuRail() } catch (_: Throwable) { false }) "1" else "0"}"
                else " derogation_bits=aucune") + " " +
               "t0_fige=${e03Capteur.t0Fige()} ts=${System.currentTimeMillis()}")

        // (3) STIMULUS. Les scénarios automatisables marquent leurs propres T ; les
        // scénarios à stimulus PHYSIQUE (USB, kill, RC…) sont horodatés ici en T1 : le
        // déclencheur humain EST l'événement de détection pour ces cas.
        when (s) {
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_03_GEL_THREAD -> {
                // GEL RÉEL des battements — sans cela le watchdog ne déclencherait JAMAIS
                // et le scénario ne prouverait rien. On ne gèle PAS le fil UI (l'app doit
                // rester pilotable) : on coupe la SOURCE de battements, ce qui place le
                // watchdog indépendant dans la situation exacte qu'il doit détecter.
                // C'est lui, depuis le thread B, qui marquera T1..T4 via
                // mettreEnSecuriteDepuisWatchdogIndep(). Le drapeau est levé automatiquement
                // à la fin de la fenêtre d'observation.
                e03GelBattements = true
                logE03("E03 stimulus=gel_battements_REEL scenario=${s.name} rep=$rep " +
                       "attendu=detection_par_watchdog_independant_sous_500ms")
            }
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_11_SORTIE_VS_EXPLICITE -> {
                e03Capteur.marquerT1()                       // l'action EST l'événement
                try { pont.activerVirtualStick(false) } catch (_: Throwable) {}
                e03Capteur.marquerT4()                       // sortie VS demandée
                vsActif = false
                logE03("E03 stimulus=sortie_vs scenario=${s.name} rep=$rep")
            }
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_01_ARRET_NORMAL -> {
                e03Capteur.marquerT1()
                soccerArme = false
                e03Capteur.marquerT2()                       // désarmement applicatif
                soccerWatchdogIndep.desarmerSurveillance()
                try {
                    pont.envoyerVitesses(0f, 0f, 0f, 0f,
                        ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
                    e03Capteur.marquerT3()                   // commande neutre demandée
                } catch (_: Throwable) {}
                try {
                    pont.activerVirtualStick(false)
                    e03Capteur.marquerT4()                   // sortie VS demandée
                    vsActif = false
                } catch (_: Throwable) {}
                logE03("E03 stimulus=arret_normal scenario=${s.name} rep=$rep")
            }
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_02_ZERO_MAINTENU -> {
                // Envoi répété de zéro : on vérifie l'absence d'emballement sous zéro.
                // Le drapeau empêche la boucle pilote de réécrire +0.2 juste derrière.
                e03ForcerZero = true
                e03Capteur.marquerT1()
                repeat(10) {
                    try {
                        pont.envoyerVitesses(0f, 0f, 0f, 0f,
                            ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
                    } catch (_: Throwable) {}
                }
                e03Capteur.marquerT3()
                logE03("E03 stimulus=zero_maintenu n=10 scenario=${s.name} rep=$rep")
            }
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_04_EXCEPTION -> {
                // Exception dans la boucle : la chaîne doit se mettre en sécurité
                // (fail-closed) sans emporter le processus.
                e03Capteur.marquerT1()
                try {
                    throw IllegalStateException("E-03 : exception provoquee (essai au banc)")
                } catch (e: Throwable) {
                    logE03("E03 stimulus=exception capturee=${e.javaClass.simpleName} " +
                           "scenario=${s.name} rep=$rep")
                    try { arretUrgence(); e03Capteur.marquerT2() } catch (_: Throwable) {}
                }
            }
            ca.cineflight.stage.sport.soccer.EssaiE03Log.Scenario.E03_05_CRASH_PROCESS -> {
                // CRASH RÉEL du processus. À la DIFFÉRENCE de E03-04, on n'INTERCEPTE PAS :
                // l'exception part sur un thread dédié, atteint le handler par défaut (le nôtre,
                // installé à onCreate), qui écrit `E03 CRASH_PROCESS` puis laisse ART tuer le
                // process. La synthèse retardée NE s'écrira jamais (le process est mort) — c'est
                // ATTENDU : le marqueur CRASH_PROCESS tient lieu de résultat. La persistance
                // après crash est nulle par construction (émetteur DJI in-process, il meurt avec).
                val derN = e03DerniereEmissionNonNulleNanos.get()
                val depuisMs = if (derN > 0L) (System.nanoTime() - derN) / 1_000_000 else -1L
                logE03("E03 stimulus=crash_process scenario=${s.name} rep=$rep " +
                       "derniere_emission_non_nulle_il_y_a_ms=$depuisMs " +
                       "note=exception_NON_interceptee_va_tuer_le_process_marqueur_CRASH_PROCESS_a_suivre")
                Thread({
                    throw RuntimeException("E-03-05 : crash processus provoque (essai au banc, helices retirees)")
                }, "E03-05-crash").start()
            }
            else -> {
                // Stimulus PHYSIQUE : l'opérateur agit (débrancher, éteindre, kill…).
                //
                // ON NE MARQUE PLUS T1 ICI. L'appui n'est pas une détection : c'est une
                // annonce. Marquer T1 au bouton figeait l'instant de détection AVANT que
                // l'événement ait eu lieu, et rendait la mesure inexploitable — T1 valait
                // le temps de réaction de l'opérateur, pas celui de la chaîne.
                // T1 sera marqué par le VRAI détecteur (perte RC, watchdog, exception),
                // et c'est lui qui figera T0.
                logE03("E03 stimulus=manuel scenario=${s.name} rep=$rep " +
                       "fenetre_ms=$fenetreMs t0_suit_le_heartbeat=OUI " +
                       "action=provoquer_le_stimulus_physique_MAINTENANT " +
                       "note=T0_sera_fige_a_la_detection_reelle_T1_T6_marques_par_la_chaine")
            }
        }

        // (4) SYNTHÈSE. Écrite APRÈS un court délai pour laisser la chaîne réagir : sans
        // cela, T2..T6 seraient systématiquement absents et TOUT scénario rendrait FAIL
        // pour une raison de chronologie, pas de sécurité. Le délai est journalisé.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(fenetreMs)
            // AU BANC, la télémétrie est écartée : moteurs à l'arrêt, elle ne mesure que du
            // bruit. Voir construireMesures(banc).
            val mesures = e03Capteur.construireMesures(banc = e03BancArme)
            logE03(e03Log.ligne(s, rep, mesures, cfg))
            // Marquages BRUTS : lève l'ambiguïté quand T0 manque (tous les Tx relatifs
            // s'affichent alors « - » même s'ils ont été atteints).
            logE03(e03Capteur.ligneMarquages())
            logE03(e03Capteur.ligneSources(e03Log, banc = e03BancArme))
            // Au banc, l'absence d'effet physique est ATTENDUE : le motif rendu décrit une
            // limite du protocole (MESURE_BANC) et la répétition reste recevable. Ailleurs,
            // un motif dénonce un défaut et la répétition doit être rejouée sans être comptée.
            e03Capteur.incoherenceMesure(banc = e03BancArme)?.let {
                val recevable = it.startsWith("MESURE_BANC")
                val etiquette = if (recevable) "LIMITE" else "INCOHERENCE"
                val suite = if (recevable) "" else " consequence=repetition_a_rejouer_NON_COMPTABILISEE"
                logE03("E03 $etiquette scenario=${s.name} rep=$rep $it$suite")
            }
            logE03("E03 FIN scenario=${s.name} rep=$rep fenetre_ms=$fenetreMs " +
                   "stimulus=${if (stimulusPhysique) "PHYSIQUE" else "LOGICIEL"} " +
                   "t0_fige=${e03Capteur.t0Fige()} " +
                   "detection_survenue=${!e03Capteur.observationDiffereeEnAttente()} " +
                   "banc=${if (e03BancArme) "OUI" else "NON"} ts=${System.currentTimeMillis()}")
            // Remise en état pour la répétition suivante : on relève le gel des battements
            // (sinon le watchdog resterait aveugle) et on déverrouille T0 pour qu'il
            // resuive le heartbeat. Fait APRÈS l'écriture des mesures, jamais avant.
            e03GelBattements = false
            e03ForcerZero = false
            e03Capteur.reinitialiser()
            // RÉTABLISSEMENT DU VIRTUAL STICK entre deux répétitions.
            // La plupart des scénarios coupent le VS — c'est précisément ce qu'ils mesurent.
            // Sans rétablissement, la répétition suivante partirait avec VS=0 et l'arbitre
            // bloquerait : on mesurerait l'absence de commande, pas la cessation.
            // On ne rétablit QUE le VS, et seulement si le banc est encore armé. Le
            // réarmement du mode SOCCER reste MANUEL : c'est une décision humaine explicite,
            // et l'automatiser reviendrait à retirer l'opérateur de la boucle.
            val vsARetablir = e03BancArme && !vsActif
            if (vsARetablir) {
                try { pont.activerVirtualStick(true) } catch (_: Throwable) {}
                vsActif = true
            }
            logE03("E03 REARME gel_battements=false zero_impose=false t0_fige=${e03Capteur.t0Fige()} " +
                   "vs_retabli=${if (vsARetablir) "OUI" else "NON_deja_actif_ou_banc_desarme"} " +
                   "soccer_arme=${if (soccerArme) "OUI" else "NON_a_rearmer_manuellement"} " +
                   "pret_pour=rep${rep + 1}")
        }
        return true
    }

    /**
     * ARRÊT D'URGENCE — IDEMPOTENT.
     *
     * Un système DÉJÀ en arrêt d'urgence n'a pas à s'arrêter une seconde fois. Rejouer la
     * séquence n'apporte aucune sécurité supplémentaire : le mode est désarmé, le latch est
     * posé, le Virtual Stick est coupé, la commande neutre est partie. En revanche elle
     * ajoute des appels SDK inutiles et, surtout, elle DUPLIQUE la trace — ce qui rend le
     * journal trompeur : on ne distingue plus « deux événements » de « un événement livré
     * deux fois ».
     *
     * Constat au banc (2026-07-22) : un seul débranchement de radiocommande produisait deux
     * arrêts d'urgence, le second avec `soccerArme_avant=false` — donc sur un système déjà
     * sûr. La cause exacte de la double livraison (double abonnement ou redondance du SDK)
     * n'a pas besoin d'être tranchée pour que cette garde soit correcte : quelle qu'elle
     * soit, la deuxième exécution est sans objet.
     *
     * ⚠ Le latch n'est levé QUE par un réarmement humain explicite. La garde ne peut donc
     * pas masquer un second événement survenu APRÈS un retour à l'état armé.
     */
    private fun arretUrgence() {
        if (soccerArretUrgence) {
            if (TEST_E03_CESSATION_VS) {
                logE03("E03 ARRET_URGENCE_IGNORE cause=deja_en_arret_urgence " +
                       "soccer_arme=${if (soccerArme) "OUI" else "NON"} " +
                       "vs=${if (vsActif) "OUI" else "NON"} " +
                       "note=systeme_deja_sur_seconde_execution_sans_objet " +
                       "ts=${System.currentTimeMillis()}")
            }
            return
        }
        // ESSAI E-03 : T1 = détection/déclenchement de l'arrêt d'urgence (scénarios
        // E03-04 exception, E03-08 perte MSDK, E03-09 perte RC qui passent par ici).
        if (TEST_E03_CESSATION_VS) e03Capteur.marquerT1()
        suiviActif = false
        soccerArme = false                // SECURITE : l'arret d'urgence desarme le soccer
        // L'écran est libéré APRÈS le désarmement — jamais avant. Un arrêt d'urgence qui
        // laisserait l'écran épinglé enfermerait le pilote dans un écran devenu inerte.
        try { epinglerEcran(false) } catch (_: Throwable) {}
        soccerArretUrgence = true         // ...et pose le drapeau (l'arbitre -> commande neutre)
        soccerWatchdogIndep.desarmerSurveillance()  // mode desarme -> surveillance suspendue
        if (TEST_E03_CESSATION_VS) e03Capteur.marquerT2()   // T2 = désarmement applicatif
        // TEST E-08 : trace l'instant precis de la cessation.
        logSecuTest("E08 ts=${System.currentTimeMillis()} emergencyStop=true soccerArme=false vsActif=false raison=arret_urgence")
        // ESSAI E-03 : même trace dans le journal de l'essai (scénarios 04/08/09 passent
        // par l'arrêt d'urgence). Journal autoportant, indépendant de TEST_E06_09_SECU.
        if (TEST_E03_CESSATION_VS) {
            logE03("E03 ARRET_URGENCE emergencyStop=true soccerArme=false vsActif=false " +
                   "raison=arret_urgence ts=${System.currentTimeMillis()}")
        }
        try {
            pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
            if (TEST_E03_CESSATION_VS) e03Capteur.marquerT3()   // T3 = commande neutre demandée
        } catch (_: Exception) {}
        try {
            pont.activerVirtualStick(false)
            if (TEST_E03_CESSATION_VS) e03Capteur.marquerT4()   // T4 = sortie VS demandée
        } catch (_: Exception) {}
        vsActif = false
        modeManuel = true                 // apres l'arret, c'est la telecommande qui commande
        majBoutonManuel()
        majBoutonSoccer()                 // reflete le desarmement soccer dans l'UI
        txtEtat.text = getString(R.string.p3_urgence_msg)
    }

    // ── ETAT RTK (feu) : partage par le badge (en vol) et le blocage au demarrage ──
    private enum class FeuRtk { VERT, JAUNE, ROUGE }
    private data class EtatFeuRtk(val feu: FeuRtk, val pastille: String, val titre: String, val detail: String)

    // ── Badge en mode ATHLÈTE : libellé « Athlète » + ANTI-CLIGNOTEMENT ──────────────
    // Basé sur l'état de l'évaluateur (PRÊT/DÉGRADÉ/PERDU). Une DÉGRADATION doit PERSISTER
    // (grâce de 1,5 s) avant d'assombrir le badge ; une amélioration passe tout de suite.
    // N'affecte QUE l'affichage — le suivi reste fail-closed (reseauOk immédiat).
    private var athleteFeuAffiche = FeuRtk.ROUGE
    private var athleteFeuBrutDernier = FeuRtk.ROUGE
    private var athleteFeuBrutDepuisMs = 0L
    private val ATHLETE_FEU_GRACE_MS = 1500L
    private fun rangFeu(f: FeuRtk) = when (f) { FeuRtk.VERT -> 2; FeuRtk.JAUNE -> 1; FeuRtk.ROUGE -> 0 }
    private fun feuAthleteStable(brut: FeuRtk): FeuRtk {
        val now = System.currentTimeMillis()
        if (brut != athleteFeuBrutDernier) { athleteFeuBrutDernier = brut; athleteFeuBrutDepuisMs = now }
        if (rangFeu(brut) >= rangFeu(athleteFeuAffiche) || now - athleteFeuBrutDepuisMs >= ATHLETE_FEU_GRACE_MS)
            athleteFeuAffiche = brut
        return athleteFeuAffiche
    }
    private fun etatFeuAthlete(): EtatFeuRtk {
        val brut = when (athleteEtat) {
            ca.cineflight.stage.athlete.EvaluateurSourceAthlete.Etat.PRET -> FeuRtk.VERT
            ca.cineflight.stage.athlete.EvaluateurSourceAthlete.Etat.DEGRADE,
            ca.cineflight.stage.athlete.EvaluateurSourceAthlete.Etat.REPRISE -> FeuRtk.JAUNE
            ca.cineflight.stage.athlete.EvaluateurSourceAthlete.Etat.PERDU -> FeuRtk.ROUGE
        }
        val feu = feuAthleteStable(brut)
        val ageTxt = if (!autoAgeS.isFinite()) "—" else "%.1f s".format(autoAgeS)
        val (past, titre) = when (feu) {
            FeuRtk.VERT  -> "🟢" to "CineFlight Athlète — position précise"
            FeuRtk.JAUNE -> "🟡" to "CineFlight Athlète — position moyenne"
            FeuRtk.ROUGE -> "🔴" to "CineFlight Athlète — position perdue"
        }
        return EtatFeuRtk(feu, past, titre, "Source : iPhone CineFlight Athlète · âge $ageTxt · $athleteRaison")
    }

    private fun etatFeuRtk(): EtatFeuRtk {
        if (SUIVI_ATHLETE_SIMU) return etatFeuAthlete()   // badge « Athlète » stabilisé
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

    // ── CYCLE DE VIE ET UNICITÉ DE L'AUTORITÉ DE COMMANDE ───────────────────────────
    /**
     * Identité de CETTE instance d'écran. Journalisée à chaque transition de cycle de vie,
     * et utilisée comme clé du verrou d'autorité.
     *
     * Relevé du 2026-07-22 : un même événement de perte de radiocommande a produit SIX
     * arrêts d'urgence, avec des compteurs d'occurrence distincts — donc PLUSIEURS
     * producteurs actifs en même temps. Sans identité par instance, impossible de savoir
     * lesquels, ni combien. C'est cette trace qui permettra de trancher.
     */
    private val instanceId: String = "P3-" + Integer.toHexString(System.identityHashCode(this))

    /** Garde d'idempotence : la boucle pilote ne s'arrête qu'une fois. */
    private val boucleActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Instant du début d'appui sur le bouton SOCCER (0 = aucun appui en cours). */
    @Volatile private var appuiDesarmementDebutMs = 0L

    /**
     * Désarmement déclenché par un appui maintenu 3 s. Isolé dans un Runnable nommé pour
     * pouvoir être ANNULÉ si le doigt se lève avant l'échéance — sans quoi un appui bref
     * désarmerait quand même, avec 3 s de retard.
     */
    private val runnableDesarmementLong = Runnable {
        if (soccerArme) {
            appuiDesarmementDebutMs = 0L
            desarmerModeSoccer("appui_maintenu_3s")
        }
    }

    private fun journalCycleVie(etape: String) {
        val msg = "CYCLE_VIE instance=$instanceId task=$taskId etape=$etape " +
                  "autorite=${ca.cineflight.stage.control.AutoriteCommandeDrone.proprietaireActuel()} " +
                  "boucle_active=${boucleActive.get()} ts=${System.currentTimeMillis()}"
        android.util.Log.i("Phase3Lifecycle", msg)
        if (TEST_E03_CESSATION_VS) logE03("E03 $msg")
    }

    /**
     * ARRÊT DE LA BOUCLE PILOTE — idempotent.
     *
     * DÉFAUT CORRIGÉ : la boucle vivait dans `lifecycleScope`, qui n'est annulé qu'à
     * `onDestroy()`. Or Android ne garantit PAS que `onDestroy()` soit appelé rapidement.
     * Un écran passé en arrière-plan continuait donc d'émettre à 10 Hz vers l'aéronef,
     * indéfiniment. Pour une boucle qui commande un drone, l'arrêt doit avoir lieu dès que
     * l'écran quitte le premier plan — pas « un jour, quand le système voudra bien ».
     */
    /**
     * DÉMARRE le poller réseau et la boucle pilote. Appelée à la création ET à chaque
     * retour au premier plan.
     *
     * IDEMPOTENTE : si les boucles tournent déjà, l'appel est sans effet. Sans cette
     * garde, un aller-retour rapide en arrière-plan empilerait plusieurs boucles pilotes
     * — exactement le défaut de producteurs concurrents qu'on cherche à supprimer.
     */
    private fun demarrerBoucles() {
        if (!boucleActive.compareAndSet(false, true)) return
        journalCycleVie("BOUCLES_DEMARREES")
        // ── POLLER RESEAU : recupere la position RTK de l'auto (~5 Hz) ──────────
        jobReseau = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (SUIVI_ATHLETE_SIMU) lirePositionAthlete() else lirePositionAuto()
                delay(200)   // 5 Hz
            }
        }

        // ── BOUCLE PILOTE : envoie la commande de suivi (ou hover) a ~10 Hz ─────
        jobPilote = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                // VERROU INVERSE DU BANC : réévalué à chaque tour. Si l'aéronef décolle
                // pendant qu'une dérogation de banc est armée, elle tombe immédiatement.
                if (TEST_E03_CESSATION_VS && TEST_E03_BANC) e03SurveillerVolPendantBanc()
                // ══ TEST E-01 AU SOL — HÉLICES RETIRÉES ══════════════════════════════════
                // Appelle DIRECTEMENT le pipeline soccer 2D (observerMiroirMouvementSoccer),
                // en sautant tickSuivi() qui exige un contexte voiture RTK absent en mode soccer.
                // But : observer le SIGNE du throttle au sol. NE JAMAIS voler avec ce build
                // (throttle force a +0.2 = montee continue). Flag=false -> code normal.
                if (SOCCER_2D_EMISSION_ACTIVE && soccerMode2D) {
                    try { observerMiroirMouvementSoccer(commandSent = "TEST_E01_SOL") } catch (_: Throwable) {}
                    soccerWatchdog.battement(System.nanoTime())
                    // ESSAI E-03 (scénario 03) : le gel volontaire COUPE ce battement, pour
                    // placer le watchdog indépendant en situation réelle de détection.
                    if (!e03GelBattements) soccerWatchdogIndep.battement()
                    delay(100)
                    continue
                }
                // ═════════════════════════════════════════════════════════════════════════
                if (athleteObs) {
                    // OBSERVATION athlète : le suivi calcule et AFFICHE, sans VS ni vol requis,
                    // sans rien envoyer (le garde dans tickSuivi bloque l'émission).
                    if (suiviActif) tickSuivi()
                } else if (vsActif) {
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
                // ESSAI E-03 (scénario 03) : le gel volontaire coupe ce battement pour
                // reproduire EXACTEMENT la défaillance que le watchdog doit détecter.
                if (!e03GelBattements) soccerWatchdogIndep.battement()
                delay(100)   // 10 Hz
            }
        }
    }

    private fun arreterBouclePilote(raison: String) {
        if (!boucleActive.compareAndSet(true, false)) return
        try { jobPilote?.cancel() } catch (_: Throwable) {}
        jobPilote = null
        try { jobReseau?.cancel() } catch (_: Throwable) {}
        jobReseau = null
        suiviActif = false
        journalCycleVie("BOUCLE_ARRETEE raison=$raison")
    }

    override fun onPause() {
        super.onPause()
        journalCycleVie("PAUSE")
        // ESSAI E-03 SCÉNARIO 06 « PASSAGE EN ARRIÈRE-PLAN ».
        //
        // T1 = DÉTECTION. C'est ICI, et pas plus tard, que l'application apprend qu'elle
        // quitte le premier plan : onPause est le premier rappel du cycle de vie à survenir.
        // Le marquer dans onStop daterait la détection de plusieurs centaines de ms trop
        // tard, et surtout APRÈS la sortie du Virtual Stick faite juste en dessous — ce qui
        // produirait des jalons dans le désordre.
        //
        // Ne marque QUE si le mode automatique était armé : une mise en veille alors que
        // rien n'est armé n'est pas une cessation, il n'y a rien à faire cesser.
        if (TEST_E03_CESSATION_VS && soccerArme) e03Capteur.marquerT1()
        // securite : ecran en arriere-plan -> on coupe le suivi et l'envoi.
        suiviActif = false
        cmdHoverSiPossible()
        if (vsActif && !enVol) {
            try { pont.activerVirtualStick(false) } catch (_: Exception) {}
            vsActif = false
            // T4 = sortie Virtual Stick demandée au SDK.
            if (TEST_E03_CESSATION_VS) e03Capteur.marquerT4()
        }
    }

    private fun cmdHoverSiPossible() {
        if (vsActif && enVol) { try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC) } catch (_: Exception) {} }
    }

    override fun onStart() {
        super.onStart()
        // ACQUISITION DE L'AUTORITÉ. Si un autre écran la détient encore, celui-ci
        // n'émettra rien — le point d'émission le vérifie. Mieux vaut un écran muet
        // qu'un second flux de commandes vers le même aéronef.
        val obtenue = ca.cineflight.stage.control.AutoriteCommandeDrone.acquerir(instanceId)
        journalCycleVie("START autorite_obtenue=$obtenue")
        if (!obtenue) {
            try {
                txtEtat.text = "⛔ Autorité de commande détenue par un autre écran — émission bloquée"
            } catch (_: Throwable) {}
        }
        try { lecteurPerception.demarrer() } catch (_: Throwable) {}
        // Les écouteurs DJI ont été retirés à onStop() : on les rétablit pour CETTE instance.
        try { pont.initialiserListeners() } catch (_: Throwable) {}
        try { brancherSurveillanceThermique() } catch (_: Throwable) {}
        // Relance des boucles arrêtées à onStop(). Idempotent.
        demarrerBoucles()
        // AUCUN RÉARMEMENT AUTOMATIQUE. Le mode soccer a été désarmé par la sortie du
        // premier plan ; on le DIT au pilote au lieu de le reprendre en silence. Le
        // watchdog indépendant reste volontairement arrêté : il ne repart qu'à l'armement.
        soccerDesarmeParCycleVie?.let { raison ->
            soccerDesarmeParCycleVie = null
            journalCycleVie("REPRISE_SANS_REARMEMENT raison_desarmement=$raison soccer_arme=NON")
            try {
                txtEtat.text = "Mode SOCCER désarmé\n" +
                    "Raison : application passée en arrière-plan\n" +
                    "Réarmement manuel requis"
            } catch (_: Throwable) {}
            try { majBoutonSoccer() } catch (_: Throwable) {}
        }
    }

    override fun onResume() {
        super.onResume()
        journalCycleVie("RESUME")
    }

    override fun onStop() {
        journalCycleVie("STOP")
        desarmerSoccerCycleVie("ecran_hors_premier_plan")
        super.onStop()
    }

    /**
     * DÉSARMEMENT COMPLET LIÉ AU CYCLE DE VIE — l'écran quitte le premier plan.
     *
     * DÉFAUT CORRIGÉ (2026-07-22). `onStop` tuait le thread B du watchdog indépendant
     * (`arreter()`) mais laissait `soccerArme = true`, et `onStart` ne relançait jamais ce
     * thread. Après un simple aller-retour en arrière-plan — appel entrant, verrouillage
     * d'écran, notification plein écran — la boucle pilote redémarrait et recommençait à
     * commander l'aéronef, tandis que la protection contre le gel du fil d'émission
     * (REQ-WDG-001) était HORS SERVICE sans que rien ne le signale : ni le bouton, ni le
     * journal, ni le garde-fou de la campagne de charge, qui interrogeaient tous une
     * variable d'intention (`surveillanceArmee`) au lieu d'un état de santé.
     *
     * Le piège était double : un essai « arrière-plan / premier plan » cherchant des
     * déclenchements intempestifs n'en aurait trouvé AUCUN, et cette absence aurait été
     * portée au dossier comme une preuve d'absence de faux positif.
     *
     * DÉCISION (Christian, 2026-07-22) : une perte du premier plan INTERROMPT l'automatisme.
     * Le retour dans l'application ne reprend JAMAIS un mode armé — le réarmement est un
     * geste humain délibéré, avec la personne dans le champ. Afficher « armé » alors que la
     * boucle est arrêtée, les écouteurs retirés et l'autorité rendue serait un mensonge sur
     * la disponibilité réelle du système.
     *
     * ORDRE IMPOSÉ : les commandes cessent AVANT que l'écran et les ressources soient
     * libérés. IDEMPOTENT — appelable depuis onStop puis onDestroy sans effet de bord.
     */
    private fun desarmerSoccerCycleVie(raison: String) {
        val etaitArme = soccerArme
        // 1) LES COMMANDES D'ABORD — l'automatisme cesse avant tout le reste.
        soccerArme = false
        // ESSAI E-03 SCÉNARIO 06 : T2 = désarmement applicatif effectué.
        if (TEST_E03_CESSATION_VS && etaitArme) e03Capteur.marquerT2()
        suiviActif = false
        try { soccerWatchdogIndep.desarmerSurveillance() } catch (_: Throwable) {}
        try {
            pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
            // T3 = commande neutre demandée au pont.
            if (TEST_E03_CESSATION_VS && etaitArme) e03Capteur.marquerT3()
        } catch (_: Throwable) {}
        // 2) L'ÉCRAN — le laisser épinglé enfermerait l'appareil dans un écran inerte.
        try { epinglerEcran(false) } catch (_: Throwable) {}
        // 3) LES RESSOURCES.
        arreterBouclePilote("onStop")
        // Sans ce retrait, un écran en arrière-plan continue de réagir aux événements de
        // l'aéronef — c'est l'origine des arrêts d'urgence multipliés observés au banc.
        try { pont.libererEcouteurs() } catch (_: Throwable) {}
        try { debrancherSurveillanceThermique() } catch (_: Throwable) {}
        try { soccerWatchdogIndep.arreter() } catch (_: Throwable) {}
        try { lecteurPerception.arreter() } catch (_: Throwable) {}
        // L'autorité est rendue : un autre écran pourra légitimement la prendre.
        ca.cineflight.stage.control.AutoriteCommandeDrone.liberer(instanceId)
        // 4) L'ÉTAT AFFICHÉ REDEVIENT FIDÈLE, et la raison survit au retour au premier plan.
        if (etaitArme) soccerDesarmeParCycleVie = raison
        try { majBoutonSoccer() } catch (_: Throwable) {}
        journalCycleVie(
            "SOCCER_DESARME_CYCLE_VIE raison=$raison etait_arme=${if (etaitArme) "OUI" else "NON"} " +
            "detecteur_en_service=${try { soccerWatchdogIndep.estEnService() } catch (_: Throwable) { false }}"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        journalCycleVie("DESTROY")
        arreterBouclePilote("onDestroy")
        try { pont.libererEcouteurs() } catch (_: Throwable) {}
        try { flux?.arreter() } catch (_: Exception) {}       // libere la video live
        try { yoloSuivi?.arreter() } catch (_: Exception) {}  // stoppe la detection YOLO
        try { lecteurPerception.arreter() } catch (_: Exception) {}
        try { soccerWatchdogIndep.arreter() } catch (_: Exception) {}  // stoppe le thread B
        ca.cineflight.stage.control.AutoriteCommandeDrone.liberer(instanceId)
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
        // ESSAI E-03 : T1 = DÉTECTION par le watchdog indépendant. C'est l'horodatage
        // central du scénario E03-03 (gel du fil d'émission). Marqué en TOUT PREMIER,
        // avant les actions, pour ne pas inclure leur durée dans le délai de détection.
        if (TEST_E03_CESSATION_VS) e03Capteur.marquerT1()
        soccerArme = false
        soccerArretUrgence = true
        if (TEST_E03_CESSATION_VS) e03Capteur.marquerT2()   // T2 = désarmement applicatif
        try {
            pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.AUTOMATIC)
            if (TEST_E03_CESSATION_VS) e03Capteur.marquerT3()   // T3 = commande neutre demandée
        } catch (_: Throwable) {}
        try {
            pont.activerVirtualStick(false)
            if (TEST_E03_CESSATION_VS) e03Capteur.marquerT4()   // T4 = sortie VS demandée
        } catch (_: Throwable) {}
        vsActif = false
        logSecuTest("WDG_INDEP ts=${System.currentTimeMillis()} defaillance=battement_perime age_ms=$ageMs actions=desarme+urgence+neutre+sortieVS")
        // ESSAI E-03 : la MEME ligne est écrite dans essai_e03.log. Sans cela, la preuve de
        // détection du scénario E03-03 (âge du battement périmé) partirait uniquement dans
        // test_secu_e0x.log, qui est conditionné par TEST_E06_09_SECU — un drapeau SANS
        // rapport avec E-03. Le journal de l'essai doit être autoportant.
        if (TEST_E03_CESSATION_VS) {
            // CONTEXTE — distingue un déclenchement ATTENDU d'un déclenchement INATTENDU.
            // Pendant une fenêtre d'observation E-03, T0 est figé : le déclenchement est le
            // résultat voulu du stimulus. Hors fenêtre, rien ne le justifie a priori.
            // Le libellé CONSTATE sans conclure : « inattendu » n'est pas « faux positif ».
            // Un déclenchement hors fenêtre peut aussi révéler une vraie perte de cycle —
            // trancher demande d'analyser la charge et l'ordonnancement du moment, pas de
            // lire une étiquette. Le budget resserré à 350 ms rend cette distinction
            // d'autant plus importante à ne pas préjuger.
            val fenetreE03 = e03Capteur.t0Fige()
            logE03("E03 WDG_INDEP declenche age_ms=$ageMs " +
                   "contexte=${if (fenetreE03) "FENETRE_E03_DECLENCHEMENT_ATTENDU" else "HORS_FENETRE_E03_DECLENCHEMENT_INATTENDU"} " +
                   "timeout_ms=${ca.cineflight.stage.control.SafetyLimits.WATCHDOG_TIMEOUT_MS} " +
                   "periode_ms=${ca.cineflight.stage.control.SafetyLimits.WATCHDOG_INDEP_PERIODE_MS} " +
                   // Anomalies d'horloge AMPLES cumulées : 0 attendu. Une valeur non nulle
                   // signalerait un problème de source de temps, distinct d'un simple
                   // entrelacement de lectures (celui-ci est désormais ramené à zéro).
                   "anomalies_horloge=${try { soccerWatchdogIndep.anomaliesHorloge() } catch (_: Throwable) { -1 }} " +
                   "actions=desarme+urgence+neutre+sortieVS ts=${System.currentTimeMillis()}")
        }
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
