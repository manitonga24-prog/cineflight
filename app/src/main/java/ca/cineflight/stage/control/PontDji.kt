package ca.cineflight.stage.control

import android.util.Log
import android.os.Handler
import android.os.Looper
import kotlin.math.cos

// --- Imports DJI Mobile SDK v5 (vol réel) ---
// NOTE : ces imports correspondent à la structure de paquets MSDK v5 vue dans
// l'échantillon officiel DJI. Si ta version diffère, Android Studio te
// proposera l'import correct (Alt+Entrée). Les noms de CLÉS (KeyAircraftLocation3D,
// etc.) sont stables depuis MSDK 5.0.
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.sdk.keyvalue.value.common.LocationCoordinate2D

/**
 * PontDjiReel — implémentation RÉELLE des appels DJI Mobile SDK v5.
 *
 * ⚠ FICHIER À AJUSTER SUR MATÉRIEL. Les signatures exactes du MSDK v5 évoluent
 * d'une version à l'autre ; ce fichier donne la structure correcte et les
 * appels attendus, mais c'est ici que tu adapteras aux types précis de TA
 * version du SDK (imports DJI, noms de champs de VirtualStickFlightControlParam,
 * énumérations de coordinate/control system). Le RESTE de l'app ne dépend pas du
 * SDK : seul ce fichier le touche (grâce à l'interface PiloteDrone.PontDji).
 *
 * APPELS MSDK v5 UTILISÉS (à confirmer/adapter selon ta version) :
 *
 *   // activer le mode Virtual Stick
 *   VirtualStickManager.getInstance().setVirtualStickModeEnabled(
 *       enabled, object : CommonCallbacks.CompletionCallback { ... })
 *
 *   // envoyer une commande de VITESSE
 *   val param = VirtualStickFlightControlParam().apply {
 *       rollPitchControlMode  = RollPitchControlMode.VELOCITY
 *       yawControlMode        = YawControlMode.ANGULAR_VELOCITY
 *       verticalControlMode   = VerticalControlMode.VELOCITY
 *       rollPitchCoordinateSystem = FlightCoordinateSystem.BODY  // repère drone
 *       pitch = pitchMps.toDouble()
 *       roll  = rollMps.toDouble()
 *       yaw   = yawDps.toDouble()
 *       verticalThrottle = throttleMps.toDouble()
 *   }
 *   VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
 *
 *   // décollage / atterrissage : via les actions de vol
 *   KeyManager / FlightControllerKey.KeyStartTakeoff  (action)
 *   KeyManager / FlightControllerKey.KeyStartAutoLanding (action)
 *
 *   // cap du drone (yaw) et batterie : via KeyManager
 *   FlightControllerKey.KeyAircraftAttitude -> yaw
 *   BatteryKey.KeyChargeRemainingInPercent
 *
 *   // --- GPS (vol extérieur / Explorer) ---
 *   FlightControllerKey.KeyAircraftLocation3D -> { latitude, longitude, altitude }
 *      (selon la version : KeyAircraftLocation pour lat/lon, KeyAltitude pour
 *       l'altitude relative au décollage)
 *   FlightControllerKey.KeyGPSSatelliteCount  -> nb de satellites (qualité fix)
 *   FlightControllerKey.KeyGPSSignalLevel      -> niveau de signal (0..5)
 *
 * NOTE COORDINATE SYSTEM : on demande le repère BODY (drone) et on fournit
 * pitch/roll DÉJÀ tournés par TraductionAxes selon le cap. (Alternative : si tu
 * choisis le repère GROUND du SDK, ne PAS tourner dans TraductionAxes — mais on
 * garde BODY pour maîtriser nous-mêmes la rotation, plus prévisible.)
 *
 * NOTE GPS / ALTITUDE : KeyAltitude du MSDK renvoie l'altitude RELATIVE au point
 * de décollage (AGL), c'est exactement ce qu'on veut. La latitude/longitude est
 * en degrés décimaux WGS84. On considère le fix VALIDE si le nb de satellites
 * >= SAT_MIN ET la lat/lon ne sont pas nulles/NaN.
 */
class PontDjiReel(private val obstacleGateWiring: ObstacleGateWiring) : PiloteDrone.PontDji {

    // Le cap courant et la batterie sont mis à jour par des listeners SDK
    // (KeyManager.listen). On les stocke ici pour un accès synchrone par tick.
    @Volatile private var capDeg: Float = 0f
    @Volatile private var batterie: Int = -1
    @Volatile private var connecte: Boolean = false
    @Volatile private var modele: String = ""
    // Température batterie (°C) — diagnostic surchauffe. NaN = pas encore reçue.
    @Volatile private var battTempC: Double = Double.NaN
    private var battTempDernierLogC: Int = Int.MIN_VALUE
    /** Température batterie du drone (°C), NaN si inconnue. */
    fun temperatureBatterieC(): Double = battTempC

    // ObstacleSafetyGate — ÉTAPE 1, MODE MIROIR. Le cablage est desormais OBLIGATOIRE et TYPE
    // (obstacleGateWiring, injecte au constructeur) : Off = aucun gate, Mirror = observe/logue.
    //  - mirrorSafetyState : etat du gate propre au MIROIR, TOTALEMENT separe de tout etat
    //    "live" (il n'y en a pas encore). Ne bloque jamais rien.
    @Volatile private var mirrorSafetyState = ca.cineflight.stage.control.ObstacleSafetyGate.SafetyState()

    // Journal d'observation en vol (tags PERCEPTION_SAMPLE / GATE_MIRROR). session_id fixe par
    // instance de pont (un run), seq incremente a chaque tick miroir -> chaque ligne est unique
    // et correlable. Aucun effet sur le vol.
    private val gateSessionId: String =
        "S" + java.lang.Long.toHexString(System.currentTimeMillis())
    @Volatile private var gateSeq: Long = 0L

    // --- GPS : poussé par les listeners, lu par EmetteurGps (option A) ---
    @Volatile private var lat: Double = Double.NaN
    @Volatile private var lon: Double = Double.NaN
    @Volatile private var altAgl: Double = Double.NaN
    @Volatile protected var nbSatellites: Int = 0

    // Handler UI : sert a re-etablir le Virtual Stick APRES un decollage (le
    // startTakeoff DJI reprend la main et efface le mode VS).
    private val handlerPont = Handler(Looper.getMainLooper())
    // Re-activation VS post-decollage en attente (annulable si STOP/atterrissage).
    private var reactivationVs: Runnable? = null
    // true seulement apres onSuccess de enableVirtualStick (activation REELLEMENT confirmee).
    @Volatile var vsActifConfirme: Boolean = false
        private set
    @Volatile private var premiereCmdLoggee: Boolean = false
    private var cptLogAlt: Int = 0

    // --- HOOKS D'OBSERVATION (Phase 2 vocal). Nuls par defaut : PontDji ne depend
    // PAS de la voix. Un observateur externe peut les brancher. Appeles avec ?.invoke()
    // et jamais attendus -> aucun impact sur le pilotage. ---
    var obsVsEnableAccepte: (() -> Unit)? = null
    var obsVsEnableRefuse: (() -> Unit)? = null
    var obsVsConfirme: ((Boolean) -> Unit)? = null
    var obsVsDesactivationVoulue: (() -> Unit)? = null
    // ESSAI E-03 : ACQUITTEMENT SDK de la SORTIE Virtual Stick (T5 de la fiche).
    // obsVsDesactivationVoulue signale la DEMANDE (T4) ; ce hook-ci signale la REPONSE
    // du SDK (onSuccess de disableVirtualStick). Les deux sont distincts : c'est
    // precisement l'ecart T4->T5 que l'essai doit mesurer.
    var obsVsDesactivationConfirmee: ((Boolean) -> Unit)? = null
    // Lot 2C : decollage / atterrissage (etat reel via callback SDK).
    var obsTakeoffActive: (() -> Unit)? = null
    var obsTakeoffRefuse: (() -> Unit)? = null
    var obsLandingActive: (() -> Unit)? = null
    var obsLandingRefuse: (() -> Unit)? = null
    // Phase 3 : etats critiques (valeurs brutes ; la logique de seuil est cote observateur).
    var obsBatterie: ((Int) -> Unit)? = null
    var obsConnexionDrone: ((Boolean) -> Unit)? = null
    var obsConnexionRc: ((Boolean) -> Unit)? = null
    /** État de vol RÉEL de l'aéronef (SDK KeyIsFlying). true = en l'air, quelle que soit la
     *  façon de décoller — bouton de l'app OU manches de la RC. Corrige le suivi de `enVol`
     *  côté écran, qui autrement ne connaît QUE le décollage via le bouton de l'app. */
    var obsEnVol: ((Boolean) -> Unit)? = null
    var obsGpsSatellites: ((Int) -> Unit)? = null
    // Phase 3B : camera / enregistrement (etat reel via callback SDK).
    var obsRecordStarted: (() -> Unit)? = null
    var obsRecordStopped: (() -> Unit)? = null
    var obsRecordFailed: (() -> Unit)? = null
    // ESSAI E-03 : VITESSE VERTICALE REELLE de l'aeronef (m/s, + = montee).
    // Alimente la mesure de PERSISTANCE PHYSIQUE, critere central de l'essai E-03
    // (dossier 378 : « la derniere commande verticale ne persiste pas au-dela d'un
    // delai mesure et accepte »). SANS cet observateur, seule la persistance de
    // COMMANDE est mesurable — insuffisant pour E03-10 et E03-FS3.
    // Nul par defaut : aucun impact sur le pilotage quand personne n'observe.
    var obsVitesseVerticale: ((Float) -> Unit)? = null

    companion object {
        // Seuil de satellites pour considérer le fix exploitable pour piloter.
        // 8+ donne une précision horizontale ~1-3 m sur Mini ; en dessous, on
        // ne se fie PAS au GPS (gpsValide() = false -> le contrôleur Python doit
        // refuser de piloter / rester en hover).
        const val SAT_MIN = 8
        // INVERSION roll/pitch (cf. doc MSDK v5) :
        //  false = param.pitch<-pitch, param.roll<-roll (convention directe)
        //  true  = param.pitch<-roll, param.roll<-pitch (inversion signalee par DJI)
        // ⚠ BASCULÉ à true le 2026-07-25 suite au VOL RÉEL : commande « avant 2 m/s »
        // exécutée EN LATÉRAL -> le drone ORBITAIT autour du sujet (r≈25 m = 2,0 m/s ÷
        // 4,5°/s, signature géométrique sans autre explication : la rotation
        // TraductionAxes est vérifiée correcte). C'est le piège pitch/roll documenté du
        // Virtual Stick DJI en mode vitesse/BODY. Conforme à la consigne ci-dessous qui
        // l'avait prévu : « Si Avant fait dériver le drone de CÔTÉ -> basculer ».
        // CONTRE-VÉRIFICATION : bouton « 🧭 TEST AXES (SIMULATEUR) » (Phase 3, mode dev)
        // — doit rendre « PITCH=AVANT ✓ » avec cette valeur avant tout vol de suivi.
        const val INVERSER_ROLL_PITCH = true
    }

    /**
     * À appeler une fois au démarrage (après enregistrement SDK réussi) pour
     * Branche les listeners SDK (cap, batterie, connexion, GPS 3D, satellites).
     * À appeler une fois au démarrage, APRÈS l'enregistrement SDK réussi et la
     * connexion de l'appareil. Les valeurs sont poussées en continu par le SDK
     * et stockées dans les champs @Volatile, lus de façon synchrone par tick et
     * par EmetteurGps.
     *
     * ⚠ ADAPTATION SDK : les NOMS DE CLÉS (KeyAircraftLocation3D, KeyAircraftAttitude,
     * KeyGPSSatelliteCount, KeyChargeRemainingInPercent, KeyConnection) sont stables
     * depuis MSDK 5.0. Si ta version expose des types légèrement différents pour les
     * valeurs (ex. structure de l'attitude ou de la location), Android Studio le
     * signalera et l'ajustement est minime.
     */
    /**
     * RETIRE tous les écouteurs DJI enregistrés par CE pont.
     *
     * Nécessaire dès qu'un écran quitte le premier plan : sans cela, une instance en
     * arrière-plan continue de réagir aux événements de l'aéronef. Constaté au banc le
     * 2026-07-22 — un seul événement de perte de radiocommande a déclenché SIX arrêts
     * d'urgence, signe que plusieurs jeux d'écouteurs restaient vivants.
     *
     * Idempotent : appeler plusieurs fois est sans effet supplémentaire.
     */
    fun libererEcouteurs() {
        try { KeyManager.getInstance().cancelListen(this) } catch (_: Throwable) {}
        ecouteursActifs.set(false)
        Log.i("PontDjiReel", "Ecouteurs DJI liberes pour ce pont")
    }

    /**
     * VRAI dès que les écouteurs de ce pont sont enregistrés. Garde d'unicité.
     *
     * DÉFAUT CORRIGÉ (2026-07-22) : `initialiserListeners()` était appelé depuis DEUX
     * chemins — le rappel d'enregistrement du SDK et `onStart()` de l'écran. Le
     * `cancelListen(this)` en tête ne suffit pas si les deux appels se chevauchent : on se
     * retrouvait avec DEUX abonnements sur la même clé, et donc DEUX réactions à un seul
     * événement. Mesuré au banc : un débranchement de radiocommande produisait deux arrêts
     * d'urgence (compteur d'occurrence 3 puis 4 à 17 ms d'intervalle, même instance).
     *
     * La garde rend l'enregistrement IDEMPOTENT : un second appel ne fait rien tant que
     * [libererEcouteurs] n'a pas été appelé. C'est le seul moyen de garantir « un écouteur
     * par clé et par pont », indépendamment de l'ordre d'appel.
     */
    private val ecouteursActifs = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true si ce pont a des écouteurs DJI enregistrés. Diagnostic. */
    fun ecouteursEnregistres(): Boolean = ecouteursActifs.get()

    fun initialiserListeners() {
        if (!ecouteursActifs.compareAndSet(false, true)) {
            Log.i("PontDjiReel", "Ecouteurs DJI DEJA enregistres — second appel ignore (unicite)")
            return
        }
        val km = KeyManager.getInstance()

        // Idempotence : si on (ré)abonne après une reconnexion, on retire d'abord
        // les listeners déjà associés à ce holder pour éviter les doublons.
        try { km.cancelListen(this) } catch (_: Throwable) {}

        // --- CAP (yaw) via l'attitude de l'appareil ---
        // KeyAircraftAttitude renvoie un objet attitude (pitch/roll/yaw en deg).
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this
        ) { _, attitude ->
            if (attitude != null) {
                capDeg = attitude.yaw.toFloat()
            }
        }

        // --- BATTERIE (pourcentage restant) ---
        km.listen(
            KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent), this
        ) { _, pct ->
            if (pct != null) batterie = pct
            if (pct != null) { try { obsBatterie?.invoke(pct) } catch (_: Throwable) {} }
        }

        // --- TEMPÉRATURE BATTERIE (diagnostic surchauffe au sol, 2026-07-24) ---
        // Hypothèse : déconnexions régulières après ~4-5 min au sol = protection thermique.
        // `listen` uniquement (le SDK pousse sur son propre fil — AUCUN accès concurrent).
        // Clé optionnelle selon version/modèle -> try/catch, valeur tolérante (Int ou Double).
        try {
            km.listen(
                KeyTools.createKey(BatteryKey.KeyBatteryTemperature), this
            ) { _, t ->
                val v = when (t) {
                    is Double -> t
                    is Int -> t.toDouble()
                    is Number -> t.toDouble()
                    else -> Double.NaN
                }
                if (v.isFinite()) {
                    battTempC = v
                    // Trace périodique légère : une ligne par degré franchi.
                    val d = v.toInt()
                    if (d != battTempDernierLogC) {
                        battTempDernierLogC = d
                        Log.i("PontDjiReel", "TEMP batterie=${d}°C connecte=$connecte")
                    }
                }
            }
        } catch (e: Throwable) { Log.w("PontDjiReel", "KeyBatteryTemperature indispo: " + e.message) }

        // --- CONNEXION du flight controller (= connexion appareil) ---
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyConnection), this
        ) { _, c ->
            connecte = (c == true)
            // Diagnostic surchauffe : consigner la température AU MOMENT de la déconnexion.
            if (c != true) Log.e("PontDjiReel",
                "DECONNEXION drone — temp_batterie=${if (battTempC.isFinite()) "%.0f°C".format(battTempC) else "?"}")
            try { obsConnexionDrone?.invoke(c == true) } catch (_: Throwable) {}
        }

        // --- ÉTAT DE VOL RÉEL (aéronef en l'air) : KeyIsFlying. INDÉPENDANT de la façon de
        // décoller (bouton app OU manches RC). Sans ça, l'écran ne « sait » que l'aéronef vole
        // que s'il a décollé via le bouton de l'app -> le suivi refusait de s'armer après un
        // décollage à la RC. Cle optionnelle selon la version SDK -> try/catch. ---
        try {
            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyIsFlying), this
            ) { _, f ->
                enVolReel = (f == true)
                try { obsEnVol?.invoke(f == true) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "Cle FlightControllerKey.KeyIsFlying indisponible: " + e.message)
        }

        // --- ÉTAT D'ENREGISTREMENT RÉEL (KeyIsRecording) : SOURCE DE VÉRITÉ. Se met à jour
        // même si l'enregistrement est basculé par la RADIOCOMMANDE. Détection de CHANGEMENT
        // pour ne pas doubler les événements avec le onSuccess de KeyStartRecord/KeyStopRecord.
        // Cle optionnelle selon la version SDK -> try/catch. ---
        try {
            km.listen(
                KeyTools.createCameraKey(dji.sdk.keyvalue.key.CameraKey.KeyIsRecording,
                    dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                    dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT), this
            ) { _, rec ->
                val actif = (rec == true)
                val avant = enregistre
                enregistre = actif
                if (actif != avant) {
                    try { if (actif) obsRecordStarted?.invoke() else obsRecordStopped?.invoke() } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "Cle CameraKey.KeyIsRecording indisponible: " + e.message)
        }

        // --- CONNEXION de la TELECOMMANDE (Phase 3). Cle optionnelle selon la version
        // du SDK : on l'entoure d'un try/catch pour ne jamais casser l'init si absente. ---
        try {
            km.listen(
                KeyTools.createKey(dji.sdk.keyvalue.key.RemoteControllerKey.KeyConnection), this
            ) { _, rc ->
                try { obsConnexionRc?.invoke(rc == true) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "Cle RemoteControllerKey.KeyConnection indisponible: " + e.message)
        }

        // --- GPS : POSITION 3D en WGS84 (lat/lon + altitude RELATIVE au décollage) ---
        // KeyAircraftLocation3D fournit latitude, longitude et altitude. L'altitude est
        // l'altitude relative au point de décollage (AGL). ⚠ RESTAURÉ à l'écouteur UNIQUE
        // d'origine (2026-07-24) : l'ajout de KeyAircraftLocation(2D)+KeyAltitude coïncidait
        // avec des DÉCONNEXIONS du drone au fix GPS. Retirés le temps de trouver une lecture
        // de position/altitude sûre (probablement un getValue périodique, pas un abonnement).
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this
        ) { _, loc ->
            if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                altAgl = loc.altitude
            }
        }

        // --- GPS : nombre de satellites (qualité du fix) ---
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount), this
        ) { _, n ->
            if (n != null) nbSatellites = n
            if (n != null) { try { obsGpsSatellites?.invoke(n) } catch (_: Throwable) {} }
        }

        // --- VITESSE 3D REELLE (essai E-03 : persistance PHYSIQUE) ---
        // KeyAircraftVelocity -> Velocity3D {x,y,z} en N-E-D (m/s) : x=Nord, y=Est, z=BAS.
        // La vitesse verticale « + = montee » vaut donc -z (meme convention que
        // PontCockpitImpl, ou cette cle est deja utilisee et compile en MSDK 5.18.0).
        // Cle optionnelle selon modele/firmware -> try/catch pour ne JAMAIS casser l'init.
        try {
            km.listen(
                KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this
            ) { _, v ->
                if (v != null) {
                    val vVerticale = (-v.z).toFloat()   // NED : z vers le bas
                    try { obsVitesseVerticale?.invoke(vVerticale) } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "Cle KeyAircraftVelocity indisponible: " + e.message)
        }

        // --- MODELE du drone (type de produit) ---
        try {
            val cleType = KeyTools.createKey(dji.sdk.keyvalue.key.ProductKey.KeyProductType)
            km.listen(cleType, this) { _, t ->
                if (t != null) modele = t.toString()
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "Cle ProductType indisponible: " + e.message)
        }
        // MSDK v5 : le VirtualStickManager DOIT etre initialise une fois avant que
        // enableVirtualStick + sendVirtualStickAdvancedParam aient un effet. Sans ce
        // init(), le mode s'active en apparence mais les commandes de vitesse sont
        // IGNOREES (le drone decolle/atterrit mais ne bouge pas). Oubli classique v5.
        try {
            VirtualStickManager.getInstance().init()
            Log.i("PontDjiReel", "VirtualStickManager.init() OK")
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "VirtualStickManager.init() indisponible: " + e.message)
        }
        Log.i("PontDjiReel", "Listeners SDK câblés (cap, batterie, connexion, GPS 3D, satellites)")
    }

    override fun activerVirtualStick(actif: Boolean) {
        val vsm = VirtualStickManager.getInstance()
        if (actif) {
            // Active le mode Virtual Stick avancé (nécessaire pour les commandes
            // de vitesse avec sendVirtualStickAdvancedParam).
            Log.i("PontDjiReel", "Virtual Stick : enableVirtualStick DEMANDE...")
            vsm.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    // setVirtualStickAdvancedModeEnabled est void (pas d'erreur remontee
                    // par le SDK v5) : on log qu'on l'appelle pour tracer la sequence.
                    vsm.setVirtualStickAdvancedModeEnabled(true)
                    vsActifConfirme = true
                    Log.i("PontDjiReel", "Virtual Stick ACTIF CONFIRME (onSuccess) + mode avancé demandé")
                    try { obsVsEnableAccepte?.invoke() } catch (_: Throwable) {}
                    try { obsVsConfirme?.invoke(true) } catch (_: Throwable) {}
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    vsActifConfirme = false
                    Log.e("PontDjiReel", "ECHEC activation Virtual Stick (le drone ne bougera PAS): $error")
                    try { obsVsEnableRefuse?.invoke() } catch (_: Throwable) {}
                }
            })
        } else {
            // Annule une eventuelle re-activation VS programmee (decollage recent) :
            // si l'utilisateur STOP dans la fenetre de 1,5 s, on ne veut PAS que le
            // Virtual Stick se ré-active pendant l'atterrissage (coherence "main rendue").
            reactivationVs?.let { handlerPont.removeCallbacks(it); reactivationVs = null }
            essaisReactivationVs = 0   // stoppe la boucle de reessais post-decollage
            val etaitConfirme = vsActifConfirme
            vsActifConfirme = false
            premiereCmdLoggee = false
            try { obsVsDesactivationVoulue?.invoke() } catch (_: Throwable) {}
            if (etaitConfirme) { try { obsVsConfirme?.invoke(false) } catch (_: Throwable) {} }
            vsm.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i("PontDjiReel", "Virtual Stick désactivé")
                    // T5 (essai E-03) : acquittement SDK de la sortie Virtual Stick.
                    try { obsVsDesactivationConfirmee?.invoke(true) } catch (_: Throwable) {}
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReel", "Échec désactivation Virtual Stick: $error")
                    // Echec consigne aussi : un T5 en echec est une donnee d'essai.
                    try { obsVsDesactivationConfirmee?.invoke(false) } catch (_: Throwable) {}
                }
            })
        }
    }

    override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                 origin: CommandOrigin) {
        // Construit la commande de VITESSE et l'envoie au drone.
        // Les valeurs sont déjà en m/s (lin.) et deg/s (yaw), déjà tournées selon le cap
        // par TraductionAxes.
        //
        // OBSTACLE SAFETY GATE — ÉTAPE 0 (appliquée réellement, jamais en miroir) :
        // assainissement NaN/∞ → 0 + clamp universel AVANT le SDK. C'est le seul point que
        // TOUS les chemins traversent (y compris Phase3/PremierVol qui contournent
        // PiloteDrone). Un NaN/∞ ou une vitesse aberrante ne peut donc plus atteindre le
        // drone, quel que soit l'appelant. Les bornes sont ≥ aux commandes légitimes, donc
        // aucun mouvement normal n'est bridé. Le GATE obstacle (étapes 1-3) n'est PAS ici :
        // il viendra plus tard, d'abord en mode miroir.
        val s = AssainisseurVitesse.assainir(pitch, roll, throttle, yaw)
        val cmdAssainie = ObstacleSafetyGate.Vitesses(s.pitch, s.roll, s.throttle, s.yaw)
        //
        // OBSTACLE SAFETY GATE — ÉTAPE 1/2, MODE MIROIR. Le cablage est TYPE (obstacleGateWiring) :
        //  - Off    : la commande assainie part telle quelle au SDK, aucun log miroir.
        //  - Mirror : l'ORCHESTRATION (extraite dans OrchestrationMiroir, pure/testable) CALCULE
        //    cmdGate via le gate mais renvoie l'invariant miroir cmdAEnvoyer == cmdAssainie. On ne
        //    fait ici que journaliser ; ce qui part au SDK reste 's'. Aucun etat "live", aucun
        //    blocage. Entierement blinde : le miroir ne perturbe jamais le vol.
        val cmdAEnvoyer: ObstacleSafetyGate.Vitesses = when (val w = obstacleGateWiring) {
            is ObstacleGateWiring.Off -> cmdAssainie
            is ObstacleGateWiring.Mirror -> {
                try {
                    val seq = ++gateSeq
                    val perception = w.snapshotProvider()   // UNE seule lecture atomique
                    val snapV = perception?.vertical
                    val snapH = perception?.horizontal
                    val nowMono = perception?.nowMs ?: -1L
                    val anaH = ObstacleSafetyGate.analyserHorizontal(snapH)
                    val rawH = snapH?.distancesMm
                    val all60000 = (rawH != null && rawH.isNotEmpty() && rawH.all { it >= ObstacleSafetyGate.SENTINELLE_MM })
                    Log.i("PERCEPTION_SAMPLE",
                        "session_id=$gateSessionId seq=$seq source=${w.source} timestamp_monotone_ms=$nowMono" +
                        " upward_mm=${snapV?.upwardMm ?: "null"} upward_age_ms=${snapV?.upwardAgeMs ?: "null"}" +
                        " downward_mm=${snapV?.downwardMm ?: "null"} downward_age_ms=${snapV?.downwardAgeMs ?: "null"}" +
                        " horizontal_age_ms=${snapH?.ageMs ?: "null"} nb_secteurs=${rawH?.size ?: 0}" +
                        " horizontal_raw=${rawH ?: "null"} horizontal_min_mm=${anaH.minMm ?: "null"}" +
                        " filtered_count=${anaH.nbFiltres}/${anaH.nbTotal} all_60000=$all60000 reason=\"${anaH.raison}\"")
                    val decision = OrchestrationMiroir.deciderMiroir(
                        cmdAssainie = cmdAssainie, snapshot = perception, origin = origin, mirrorState = mirrorSafetyState)
                    mirrorSafetyState = decision.newMirrorState
                    val g = decision.cmdGate
                    val env = decision.cmdAEnvoyer
                    val sendEqualsSanitized = (env == cmdAssainie)
                    Log.i("GATE_MIRROR",
                        "session_id=$gateSessionId seq=$seq source=${w.source} origin=$origin actions=${decision.actions}" +
                        " cmd_brute=($pitch,$roll,$throttle,$yaw)" +
                        " cmd_assainie=(${cmdAssainie.pitch},${cmdAssainie.roll},${cmdAssainie.throttle},${cmdAssainie.yaw})" +
                        " cmd_gate=(${g.pitch},${g.roll},${g.throttle},${g.yaw})" +
                        " cmd_a_envoyer=(${env.pitch},${env.roll},${env.throttle},${env.yaw})" +
                        " mirror_state=(up=${decision.newMirrorState.upSuspended},down=${decision.newMirrorState.downSuspended},horiz=${decision.newMirrorState.horizSuspended})" +
                        " gate_reel_actif=false send_equals_sanitized=$sendEqualsSanitized")
                    if (!sendEqualsSanitized) Log.e("GATE_MIRROR_VIOLATION", "session_id=$gateSessionId seq=$seq cmd_a_envoyer != cmd_assainie (MIROIR)")
                } catch (_: Throwable) { /* le miroir ne doit jamais perturber le vol */ }
                cmdAssainie   // MIROIR STRICT : on envoie toujours l'assainie
            }
        }
        //
        // INVARIANT : en Off comme en Mirror, cmdAEnvoyer.{pitch,roll,throttle,yaw} == cmdAssainie
        // == s.{pitch,roll,throttle,yaw} (miroir strict, aucun gate reel). Le SDK est donc alimente
        // a partir de 's', numeriquement egal a cmdAEnvoyer dans les deux branches.
        //
        // ⚠ ADAPTATION SDK : selon ta version, VirtualStickFlightControlParam
        // expose soit des setters, soit des champs directs. Le repère choisi est BODY
        // (drone) : on fournit pitch/roll déjà tournés. Modes en VITESSE.
        // Le SDK est alimente par cmdAEnvoyer (== cmdAssainie en Off/Mirror) : flux explicite.
        // MAPPING SDK (extrait dans MappingSdkVirtualStick, pur/testable — test "faux drone").
        val map = MappingSdkVirtualStick.versParam(
            pitch = cmdAEnvoyer.pitch, roll = cmdAEnvoyer.roll,
            throttle = cmdAEnvoyer.throttle, yaw = cmdAEnvoyer.yaw,
            inverserRollPitch = INVERSER_ROLL_PITCH,
        )
        val pPitch: Double = map.pitch
        val pRoll: Double = map.roll
        val param = VirtualStickFlightControlParam().apply {
            this.pitch = map.pitch                                   // avant/arriere (m/s)
            this.roll = map.roll                                     // gauche/droite (m/s)
            this.yaw = map.yaw                                       // rotation (deg/s)
            this.verticalThrottle = map.verticalThrottle            // montee/descente (m/s)
            this.verticalControlMode = VerticalControlMode.VELOCITY
            this.rollPitchControlMode = RollPitchControlMode.VELOCITY
            this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
            this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        }
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
        // DIAGNOSTIC ALTITUDE : ~1x/seconde, affiche l'altitude reelle pour VOIR si le
        // drone monte vraiment quand on envoie une commande (throttle) — au lieu de
        // deviner a l'ecran. Si l'altitude augmente -> le mouvement fonctionne.
        cptLogAlt++
        if (cptLogAlt >= 15) {   // boucle ~15 Hz -> environ 1 log/sec
            cptLogAlt = 0
            // ⚠ ROLL AJOUTÉ (2026-07-26) : son absence a empêché de diagnostiquer directement
            // l'orbite du 25/07 — il a fallu déduire le défaut d'axes de la géométrie du vol.
            // On journalise ce qui part RÉELLEMENT au SDK (après mapping/inversion).
            Log.i("PontDjiReel", "ALT=${"%.2f".format(altitudeDrone())}m | cmd throttle=${s.throttle}" +
                " yaw=${s.yaw} pitch=$pPitch roll=$pRoll | cap=${"%.0f".format(capDroneDeg())}" +
                " lat=${"%.6f".format(latitudeDrone())} lon=${"%.6f".format(longitudeDrone())}" +
                " sat=$nbSatellites | vsActif=$vsActifConfirme")
        }
        // DIAGNOSTIC : trace la 1ere commande de MOUVEMENT envoyee (non nulle), pour
        // confirmer que le flux tourne. Si on voit "cmd envoyee" mais VS non confirme,
        // le drone ignorera la commande -> la cause est l'activation, pas l'envoi.
        val bouge = pPitch != 0.0 || pRoll != 0.0 || s.throttle != 0f || s.yaw != 0f
        if (bouge && !premiereCmdLoggee) {
            premiereCmdLoggee = true
            Log.i("PontDjiReel", "1ere commande MOUVEMENT envoyee (throttle=${s.throttle} yaw=${s.yaw} pitch=$pPitch roll=$pRoll) — vsActifConfirme=$vsActifConfirme")
            try { JournalVol.evenement("1re commande de mouvement envoyée" +
                " (thr=${s.throttle} yaw=${s.yaw} pitch=$pPitch roll=$pRoll) vsConfirmé=$vsActifConfirme") } catch (_: Throwable) {}
            if (!vsActifConfirme) {
                Log.w("PontDjiReel", "ATTENTION: commande envoyee mais Virtual Stick PAS confirme actif -> le drone risque d'IGNORER")
                // ⚠ CAS CRITIQUE : l'app croit piloter, le drone n'obéit pas. Sans cette
                // trace, le vol paraît « sans commande » alors que l'app en envoyait.
                try { JournalVol.anomalie(
                    "commandes envoyées SANS Virtual Stick confirmé — l'aéronef peut les IGNORER") } catch (_: Throwable) {}
            }
        }
    }

    override fun decoller(onFini: (Boolean) -> Unit) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartTakeoff),
            null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    Log.i("PontDjiReel", "Décollage OK")
                    try { obsTakeoffActive?.invoke() } catch (_: Throwable) {}
                    // DJI refuse le Virtual Stick TANT QUE le decollage auto n'est pas
                    // fini (erreur CONTROL_AUTH_TAKING_OFF -36870). La duree du decollage
                    // varie (Mini 4 Pro > 1,5 s), un delai fixe ne suffit pas. On REESSAIE
                    // l'activation en boucle jusqu'a ce que DJI accepte (autorite rendue).
                    demarrerReactivationVsApresDecollage()
                    onFini(true)
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    try { obsTakeoffRefuse?.invoke() } catch (_: Throwable) {}
                    Log.e("PontDjiReel", "Échec décollage: $error"); onFini(false)
                }
            })
    }

    // Nombre de tentatives restantes pour re-etablir le VS apres un decollage.
    // ~20 essais x 600 ms = 12 s : couvre largement la duree d'un decollage auto.
    private var essaisReactivationVs: Int = 0

    /** Re-etablit le Virtual Stick APRES le decollage, avec REESSAIS : DJI refuse
     *  (CONTROL_AUTH_TAKING_OFF) tant que la montee auto de decollage n'est pas finie.
     *  On retente toutes les 600 ms jusqu'a succes (autorite rendue) ou epuisement. */
    private fun demarrerReactivationVsApresDecollage() {
        reactivationVs?.let { handlerPont.removeCallbacks(it) }
        essaisReactivationVs = 20
        essayerActiverVs()
    }

    private fun essayerActiverVs() {
        if (vsActifConfirme) { Log.i("PontDjiReel", "VS deja actif, arret des reessais"); return }
        if (essaisReactivationVs <= 0) {
            Log.e("PontDjiReel", "VS non active apres tous les reessais (decollage trop long ?)")
            return
        }
        essaisReactivationVs -= 1
        val vsm = VirtualStickManager.getInstance()
        Log.i("PontDjiReel", "Reessai activation VS (restants=$essaisReactivationVs)")
        vsm.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                vsm.setVirtualStickAdvancedModeEnabled(true)
                vsActifConfirme = true
                Log.i("PontDjiReel", "Virtual Stick ACTIF CONFIRME apres decollage (mode avancé)")
                try { obsVsEnableAccepte?.invoke() } catch (_: Throwable) {}
                try { obsVsConfirme?.invoke(true) } catch (_: Throwable) {}
            }
            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                // Encore en decollage / autorite pas rendue : on reprogramme un essai.
                Log.w("PontDjiReel", "VS refuse (${error.errorCode()}), nouvel essai dans 600ms")
                val r = Runnable { essayerActiverVs() }
                reactivationVs = r
                handlerPont.postDelayed(r, 600)
            }
        })
    }

    override fun atterrir(onFini: (Boolean) -> Unit) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding),
            null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    try { obsLandingActive?.invoke() } catch (_: Throwable) {}
                    Log.i("PontDjiReel", "Atterrissage OK"); onFini(true)
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    try { obsLandingRefuse?.invoke() } catch (_: Throwable) {}
                    Log.e("PontDjiReel", "Échec atterrissage: $error"); onFini(false)
                }
            })
    }

    override fun capDroneDeg(): Float = capDeg
    override fun batteriePourcent(): Int = batterie
    override fun estConnecte(): Boolean = connecte

    // --- GPS ---
    override fun latitudeDrone(): Double = lat
    override fun longitudeDrone(): Double = lon
    override fun altitudeDrone(): Double = altAgl
    override fun virtualStickConfirmeActif(): Boolean = vsActifConfirme
    override fun gpsValide(): Boolean =
        nbSatellites >= SAT_MIN && !lat.isNaN() && !lon.isNaN()

    fun nbSatellitesActuel(): Int = nbSatellites

    @Volatile private var enVolReel: Boolean = false
    /** État de vol RÉEL lu au SDK (KeyIsFlying) : vrai que le décollage vienne de l'app OU de la RC. */
    fun estEnVolReel(): Boolean = enVolReel

    // --- CAMÉRA / NACELLE ---
    @Volatile private var enregistre: Boolean = false

    override fun orienterNacelle(pitchDeg: Float, yawDeg: Float, yawAbsolu: Boolean) {
        // Rotation de la nacelle en mode ANGLE. GimbalAngleRotation prend les
        // angles cible (pitch/roll/yaw) + le mode (absolu/relatif) + une durée.
        //
        // ⚠ ADAPTATION SDK : la structure exacte de GimbalAngleRotation varie un
        // peu selon la version (champs pitch/roll/yaw, mode, duration). Le sample
        // officiel construit un GimbalAngleRotation puis appelle KeyRotateByAngle.
        // Les noms de champs sont à confirmer via l'autocomplétion (comme pour
        // les modes Virtual Stick).
        val rotation = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation().apply {
            this.pitch = pitchDeg.toDouble()
            this.yaw = yawDeg.toDouble()
            this.roll = 0.0
            // mode angulaire : absolu (repère monde) ou relatif (nez du drone)
            // this.mode = if (yawAbsolu) GimbalAngleRotationMode.ABSOLUTE_ANGLE
            //             else GimbalAngleRotationMode.RELATIVE_ANGLE
            // this.duration = 0.0   // 0 = aussi vite que possible
        }
        KeyManager.getInstance().performAction(
            KeyTools.createKey(dji.sdk.keyvalue.key.GimbalKey.KeyRotateByAngle),
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {}
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReel", "Échec orientation nacelle: $error")
                }
            })
    }

    /**
     * RETOUR MAISON (RTH) — FlightControllerKey.KeyStartGoHome (2026-07-25, demandé pour
     * les modes de suivi Phase 3). L'appelant doit AVOIR CESSÉ d'émettre des commandes
     * Virtual Stick AVANT (suivi arrêté + VS coupé) : sinon l'app et le firmware se
     * disputent l'autorité. L'annulation se fait aux STICKS de la RC (pas de KeyStop stable).
     */
    fun lancerRth(onFini: (Boolean) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartGoHome), null,
                object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        Log.i("PontDjiReel", "RTH lancé")
                        try { JournalVol.evenement("RTH accepté par le SDK") } catch (_: Throwable) {}
                        onFini(true)
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        Log.e("PontDjiReel", "Échec RTH: ${error.errorCode()}")
                        try { JournalVol.anomalie("RTH REFUSÉ : ${error.errorCode()}") } catch (_: Throwable) {}
                        onFini(false)
                    }
                })
        } catch (e: Throwable) { Log.e("PontDjiReel", "RTH ex: ${e.message}"); onFini(false) }
    }

    override fun demarrerEnregistrement() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        // L'action d'enregistrement elle-même (ciblée LEFT_OR_MAIN + objectif par défaut).
        // UN réessai différé : la transition PHOTO->VIDEO peut être encore en cours quand
        // le premier StartRecord part (la caméra met ~0,5-1 s à changer de mode).
        fun lancer(dejaReessaye: Boolean = false) {
            KeyManager.getInstance().performAction(
                KeyTools.createCameraKey(dji.sdk.keyvalue.key.CameraKey.KeyStartRecord,
                    dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                    dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT),
                null,
                object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        enregistre = true; Log.i("PontDjiReel", "Enregistrement démarré")
                        try { obsRecordStarted?.invoke() } catch (_: Throwable) {}
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        if (!dejaReessaye) {
                            Log.w("PontDjiReel", "StartRecord refusé (${error.errorCode()}) -> réessai dans 800 ms")
                            h.postDelayed({ lancer(true) }, 800L)
                            return
                        }
                        // CODE + description + hint (description() souvent null, comme la photo).
                        val detail = try {
                            listOf(error.errorCode(), error.description(),
                                try { error.hint() } catch (_: Throwable) { null })
                                .filter { !it.isNullOrBlank() }
                                .joinToString(" · ").ifEmpty { error.toString() }
                        } catch (_: Throwable) { error.toString() }
                        try { obsRecordFailed?.invoke() } catch (_: Throwable) {}
                        Log.e("PontDjiReel", "Échec démarrage enregistrement: $detail")
                    }
                })
        }
        // BASCULE MODE VIDÉO d'abord : sinon « CAN NOT RECORD » si la caméra est restée en
        // mode PHOTO (le chemin photo force PHOTO_NORMAL à chaque prise). Symétrique du code
        // photo, AVEC le même délai de stabilisation de 500 ms après la bascule — lancer
        // l'enregistrement pendant la transition rend « CAN NOT RECORD ». Mode obtenu par
        // RÉFLEXION (nom d'enum incertain selon la version SDK). Si la bascule échoue, on
        // tente quand même l'enregistrement (la caméra était peut-être déjà en vidéo).
        try {
            val clsMode = Class.forName("dji.sdk.keyvalue.value.camera.CameraMode")
            val videoMode = clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "VIDEO_NORMAL" }
                ?: clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "VIDEO" }
            if (videoMode != null) {
                KeyManager.getInstance().setValue(
                    KeyTools.createCameraKey(dji.sdk.keyvalue.key.CameraKey.KeyCameraMode,
                        dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                        dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT),
                    videoMode as dji.sdk.keyvalue.value.camera.CameraMode,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() { h.postDelayed({ lancer() }, 500L) }
                        override fun onFailure(error: dji.v5.common.error.IDJIError) {
                            Log.w("PontDjiReel", "bascule mode vidéo échouée (${error.description()}), tentative directe")
                            lancer()
                        }
                    })
            } else lancer()
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "bascule mode vidéo ex: ${e.message}")
            lancer()
        }
    }

    override fun arreterEnregistrement() {
        KeyManager.getInstance().performAction(
            KeyTools.createCameraKey(dji.sdk.keyvalue.key.CameraKey.KeyStopRecord,
                dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT),
            null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    enregistre = false; Log.i("PontDjiReel", "Enregistrement arrêté")
                    try { obsRecordStopped?.invoke() } catch (_: Throwable) {}
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    try { obsRecordFailed?.invoke() } catch (_: Throwable) {}
                    Log.e("PontDjiReel", "Échec arrêt enregistrement: $error")
                }
            })
    }

    override fun enregistreEnCours(): Boolean = enregistre
    override fun modeleDrone(): String = if (modele.isNotEmpty()) modele else "Drone connecte"

    // ---- Simulateur DJI (test sans vol reel : moteurs virtuels, aucun deplacement) ----
    /** Active le simulateur du SDK. Le drone doit etre connecte (helices enlevees). */
    fun activerSimulateur(lat: Double, lon: Double, onFini: (Boolean) -> Unit) {
        try {
            val coord = LocationCoordinate2D(lat, lon)
            val settings = InitializationSettings.createInstance(coord, 12)  // 12 satellites simules
            SimulatorManager.getInstance().enableSimulator(settings, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { Log.i("PontDjiReel", "Simulateur active"); onFini(true) }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReel", "Echec activation simulateur: $error"); onFini(false)
                }
            })
        } catch (e: Throwable) { Log.e("PontDjiReel", "Simulateur indisponible: ${e.message}"); onFini(false) }
    }
    /** Desactive le simulateur. */
    fun desactiverSimulateur(onFini: (Boolean) -> Unit) {
        try {
            SimulatorManager.getInstance().disableSimulator(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { onFini(true) }
                override fun onFailure(error: dji.v5.common.error.IDJIError) { onFini(false) }
            })
        } catch (_: Throwable) { onFini(false) }
    }
    /** Le simulateur est-il actif ? */
    fun simulateurActif(): Boolean = try { SimulatorManager.getInstance().isSimulatorEnabled } catch (_: Throwable) { false }
}

/**
 * PontDjiSimule — pour tester l'app SANS drone (et sans le SDK). Simule un drone
 * qui décolle, tient un cap, "consomme" les vitesses, ET dérive en position GPS
 * selon ces vitesses. Utile pour valider toute la chaîne réception UDP -> pilote
 * -> commandes -> ré-émission GPS, à l'écran, sur le téléphone, avant d'avoir le
 * drone en main.
 *
 * Le point de départ est Bordeaux (45.5455, -73.6868) — le dock de référence.
 * La dérive intègre les vitesses (vx = Est+, vy = Nord+, vz = haut+) en degrés.
 */
class PontDjiSimule(
    latDepart: Double = 45.5455,
    lonDepart: Double = -73.6868
) : PiloteDrone.PontDji {
    @Volatile var dernierPitch = 0f; @Volatile var dernierRoll = 0f
    @Volatile var dernierThrottle = 0f; @Volatile var dernierYaw = 0f
    @Volatile private var cap = 0f
    @Volatile private var enVol = false

    // Position GPS simulée
    @Volatile private var lat = latDepart
    @Volatile private var lon = lonDepart
    @Volatile private var altAgl = 0.0

    // Constantes de conversion mètres -> degrés (approx. locale)
    private val mParDegLat = 111_320.0
    private val mParDegLon = 111_320.0 * cos(Math.toRadians(latDepart))

    override fun activerVirtualStick(actif: Boolean) {}

    override fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                                 origin: CommandOrigin) {
        dernierPitch = pitch; dernierRoll = roll
        dernierThrottle = throttle; dernierYaw = yaw
        // intègre le yaw pour faire évoluer le cap (réaliste)
        cap = ((cap + yaw * (1f / 15f)) % 360f + 360f) % 360f
        // intègre les vitesses pour faire dériver la position GPS simulée.
        // On reçoit ici des commandes en repère BODY (pitch/roll), mais pour la
        // simulation on traite pitch~avant(Nord), roll~droite(Est) approx., et
        // throttle~vertical. dt = 1/15 s (cadence pilote).
        val dt = 1.0 / 15.0
        if (enVol) {
            lat += (pitch * dt) / mParDegLat          // avancée Nord
            lon += (roll  * dt) / mParDegLon          // déplacement Est
            altAgl = (altAgl + throttle * dt).coerceAtLeast(0.0)
        }
    }

    override fun decoller(onFini: (Boolean) -> Unit) { enVol = true; altAgl = 1.2; onFini(true) }
    override fun atterrir(onFini: (Boolean) -> Unit) { enVol = false; altAgl = 0.0; onFini(true) }
    override fun capDroneDeg(): Float = cap
    override fun batteriePourcent(): Int = 85
    override fun estConnecte(): Boolean = true

    // --- GPS simulé ---
    override fun latitudeDrone(): Double = lat
    override fun longitudeDrone(): Double = lon
    override fun altitudeDrone(): Double = altAgl
    override fun gpsValide(): Boolean = true   // simulateur : toujours "bon fix"

    // --- Caméra / nacelle simulées ---
    @Volatile var nacellePitch = 0f; @Volatile var nacelleYaw = 0f
    @Volatile private var enregistre = false
    override fun orienterNacelle(pitchDeg: Float, yawDeg: Float, yawAbsolu: Boolean) {
        nacellePitch = pitchDeg; nacelleYaw = yawDeg
    }
    override fun demarrerEnregistrement() { enregistre = true }
    override fun arreterEnregistrement() { enregistre = false }
    override fun enregistreEnCours(): Boolean = enregistre
    override fun modeleDrone(): String = "Simulateur"
}

