package ca.cineflight.stage.control

/**
 * Telemetrie — modèle de données du cockpit + interface étendue du pont DJI.
 *
 * Ce fichier regroupe TOUT ce qu'un cockpit complet doit montrer/commander en
 * plus du pilotage de base (déjà couvert par PiloteDrone.PontDji). On le sépare
 * pour ne pas alourdir PontDji : l'interface PontCockpit hérite de PontDji et
 * ajoute la télémétrie riche + le contrôle caméra/gimbal/RTH.
 *
 * ⚠ ADAPTATION SDK (MSDK 5.10.0) : les NOMS DE CLÉS ci-dessous suivent le sample
 * officiel DJI. Certains varient légèrement selon la version ; Android Studio
 * proposera l'import/clé correcte (Alt+Entrée). Chaque clé incertaine est
 * annotée. Si une clé manque sur Mini 3, le getter renvoie une valeur neutre
 * (NaN / -1) et le cockpit affiche "—" sans planter.
 */

/** Instantané complet de l'état du drone, lu à chaque rafraîchissement UI. */
data class EtatCockpit(
    // --- vol de base ---
    val connecte: Boolean = false,
    val enVol: Boolean = false,
    val batteriePct: Int = -1,
    val altitudeAgl: Double = Double.NaN,   // m, relative au décollage
    val capDeg: Float = Float.NaN,          // yaw, degrés
    // --- position ---
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    val satellites: Int = 0,
    val gpsValide: Boolean = false,
    // --- dynamique ---
    val distanceDecollageM: Double = Double.NaN,  // distance horizontale au décollage
    val vitesseHorizM: Double = Double.NaN,        // m/s
    val vitesseVertM: Double = Double.NaN,         // m/s (+ = montée)
    // --- liaisons ---
    val signalRcPct: Int = -1,    // qualité downlink/uplink RC 0..100
    val signalVideoPct: Int = -1, // qualité flux vidéo 0..100
    // --- caméra / nacelle ---
    val enregistre: Boolean = false,
    /** Durée d'enregistrement en cours (s) lue au DRONE ; -1 = inconnue (repli chrono app). */
    val secondesEnregistrement: Int = -1,
    val gimbalPitchDeg: Float = Float.NaN,
    // --- stockage (carte SD du drone) ---
    val carteSdPresente: Boolean = false,   // true si une carte SD est insérée et utilisable
    val minutesEnregRestantes: Int = -1,    // minutes de vidéo encore enregistrables ; -1 = inconnu
    // --- RTH ---
    val rthEnCours: Boolean = false,
    val modele: String = ""
)

/**
 * PontCockpit — interface étendue. PontDjiReel et PontDjiSimule l'implémentent.
 * Hérite de PiloteDrone.PontDji : tout le pilotage existant reste valable.
 */
interface PontCockpit : PiloteDrone.PontDji {
    /** Lit un instantané complet (appelé ~2 Hz par l'UI). */
    fun lireEtat(enVol: Boolean): EtatCockpit

    // --- commandes cockpit (pilotage manuel = sticks physiques ; ici on n'ajoute
    //     QUE des commandes annexes : caméra, gimbal, RTH) ---
    fun declencherPhoto()
    fun reglerGimbalPitch(pitchDeg: Float)   // tilt absolu (négatif = vers le bas)
    fun lancerRth(onFini: (Boolean) -> Unit)
    fun annulerRth(onFini: (Boolean) -> Unit)
    // réglages caméra (best effort : Mini 3 peut ne pas tout exposer)
    fun reglerIso(iso: Int)
    fun reglerEv(ev: Float)
    fun reglerResolutionFps(resNom: String, fps: Int)
    fun reglerShutter(shutterNom: String)   // ex "1/120", "1/500" (mode manuel)
    fun reglerWb(wbNom: String)             // ex "AUTO", "SUNNY", "CLOUDY"
    fun reglerModeExpo(modeNom: String)     // "AUTO", "MANUAL", "SHUTTER", "APERTURE"
    // Lecture de l'etat REEL de la camera (nom d'enum brut, null si non lisible).
    fun lireEvBrut(): String?
    fun lireIsoBrut(): String?
    fun lireModeExpoBrut(): String?
    fun lireShutterBrut(): String?
    fun lireWbBrut(): String?
}

