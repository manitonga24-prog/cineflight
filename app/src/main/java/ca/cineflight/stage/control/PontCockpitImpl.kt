package ca.cineflight.stage.control

import android.util.Log
import kotlin.math.cos
import kotlin.math.hypot

// --- Imports DJI MSDK v5 ---
// Clés CONFIRMÉES dans la doc officielle MSDK v5 (api-reference-v5) :
//   FlightControllerKey.KeyAircraftVelocity  -> Velocity3D {x,y,z} (N-E-D)
//   FlightControllerKey.KeyStartGoHome       -> action RTH
//   FlightControllerKey.KeyAircraftLocation3D-> position 3D (déjà dans PontDjiReel)
//   CameraKey.KeyStartShootPhoto             -> action photo (standard caméra)
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.common.callback.CommonCallbacks

/**
 * Ponts cockpit en COMPOSITION (pas héritage) : on ENVELOPPE un PontDji existant
 * et on lui délègue tout le pilotage de base via `by base`. Le cockpit n'ajoute
 * QUE la télémétrie riche + caméra/gimbal/RTH. Aucune modif dans PontDji.kt.
 *
 * NOTES SUR LES CLÉS (vérifiées dans la doc officielle MSDK v5) :
 *   - VITESSE : KeyAircraftVelocity existe, retourne Velocity3D{x,y,z} en N-E-D.
 *   - RTH : KeyStartGoHome existe (action). Il n'y a PAS de KeyStopGoHome stable ;
 *     l'annulation RTH se fait en reprenant le contrôle (sticks) — donc on n'expose
 *     QUE le lancement, et l'annulation se fait à la télécommande.
 *   - HOME : pas de getter KeyHomeLocation fiable ; on CAPTURE le point de
 *     décollage nous-mêmes (première position valide après décollage) pour
 *     calculer la distance horizontale. Plus robuste, zéro clé incertaine.
 *   - SIGNAUX RC/VIDÉO : pas de clé confirmée dans FlightControllerKey ; ces
 *     champs restent à -1 ("—") jusqu'à câblage via AirLinkKey sur matériel.
 */

// ─────────────────────────────────────────────────────────────────────────────
// COCKPIT RÉEL — enveloppe un PontDjiReel
// ─────────────────────────────────────────────────────────────────────────────
class PontDjiReelCockpit(
    private val base: PontDjiReel = PontDjiReel(ObstacleGateWiring.Off)
) : PontCockpit, PiloteDrone.PontDji by base {

    @Volatile private var vH: Double = Double.NaN
    @Volatile private var vV: Double = Double.NaN
    @Volatile private var signalRc: Int = -1     // câblage AirLink ultérieur
    @Volatile private var signalVideo: Int = -1  // câblage AirLink ultérieur
    @Volatile private var gimbalPitch: Float = Float.NaN
    @Volatile private var rthActif: Boolean = false

    // Hooks d'observation vocale (Phase 2 Lot 2B). Nuls par defaut : ne depend pas de la voix.
    var obsRthActive: (() -> Unit)? = null
    var obsRthRefuse: (() -> Unit)? = null
    var obsRthAnnule: (() -> Unit)? = null
    // Hooks Phase 3A. Nuls par defaut : le pont ne depend pas de la voix.
    var obsHomeConfirme: (() -> Unit)? = null              // point maison capture
    var obsSignalCommande: ((Int) -> Unit)? = null         // 0..100, -1 = inconnu
    var obsSignalVideo: ((Int) -> Unit)? = null            // 0..100, -1 = inconnu
    @Volatile private var sdPresente: Boolean = false        // carte SD insérée + utilisable
    @Volatile private var sdMinutesRestantes: Int = -1       // minutes vidéo restantes ; -1 = inconnu

    // Point de décollage capturé nous-mêmes (pas de clé home incertaine).
    @Volatile private var latHome: Double = Double.NaN
    @Volatile private var lonHome: Double = Double.NaN
    @Volatile private var homeCapture = false

    fun initialiserTout() {
        base.initialiserListeners()
        initialiserListenersCockpit()
    }

    /** Expose le pont reel de base pour BRANCHER les hooks d'observation vocale
     *  (Phase 2). Lecture seule cote observation : ne modifie jamais le pilotage. */
    fun basePourObservation(): PontDjiReel = base

    private fun initialiserListenersCockpit() {
        val km = KeyManager.getInstance()

        // Idempotence : retire les listeners cockpit déjà associés à ce holder
        // avant de réabonner (cas d'une reconnexion du drone).
        try { km.cancelListen(this) } catch (_: Throwable) {}

        // VITESSE — KeyAircraftVelocity -> Velocity3D {x,y,z} en N-E-D (m/s).
        // x = Nord, y = Est, z = Bas. Vitesse horizontale = hypot(x,y).
        // Vitesse verticale (+ = montée) = -z (car z pointe vers le bas en NED).
        km.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this
        ) { _, v ->
            if (v != null) {
                vH = hypot(v.x, v.y)
                vV = -v.z
            }
        }

        // --- QUALITE DE LIAISON RADIO (AirLink). MSDK v5 expose KeySignalQuality
        // (pourcentage global de qualite de la liaison), PAS de downlink/uplink separes.
        // On la mappe sur le signal de COMMANDE (le plus critique). Cle optionnelle selon
        // le modele/firmware -> try/catch pour ne JAMAIS casser l'init. Met a jour
        // signalRc (0..100) et notifie le hook d'observation vocale. ---
        try {
            km.listen(
                KeyTools.createKey(dji.sdk.keyvalue.key.AirLinkKey.KeySignalQuality), this
            ) { _, q ->
                if (q != null) {
                    val pct = (q as? Int) ?: q.toString().toIntOrNull() ?: -1
                    if (pct in 0..100) { signalRc = pct; try { obsSignalCommande?.invoke(pct) } catch (_: Throwable) {} }
                }
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "AirLink KeySignalQuality indisponible: " + e.message)
        }

        Log.i("PontDjiReelCockpit", "Listeners cockpit câblés (vitesse 3D)")
    }

    /** Capture le point de décollage à la 1re position valide (appelé par lireEtat). */
    private fun majHome() {
        if (homeCapture) return
        val la = latitudeDrone(); val lo = longitudeDrone()
        if (gpsValide() && !la.isNaN() && !lo.isNaN()) {
            latHome = la; lonHome = lo; homeCapture = true
            Log.i("PontDjiReelCockpit", "Point de décollage capturé: $la, $lo")
            try { obsHomeConfirme?.invoke() } catch (_: Throwable) {}
        }
    }

    /**
     * Lit l'état de la carte SD du drone (présence + temps vidéo restant).
     * MSDK 5.10 : les noms de clés stockage varient selon la version, donc on
     * tente plusieurs clés connues par réflexion. Si aucune ne répond (ex. Mini 3
     * qui n'expose pas la clé), on laisse les valeurs neutres (false / -1) et le
     * cockpit affiche "—" sans planter. Lecture best-effort, jamais bloquante.
     */
    private fun majStockage() {
        val km = try { KeyManager.getInstance() } catch (_: Throwable) { return }

        // --- présence carte SD : on essaie les noms de clés connus ---
        val nomsClesPresence = listOf(
            "KeyCameraSDCardIsInserted", "KeySDCardIsInserted",
            "KeyCameraStorageState", "KeySDCardInsertState"
        )
        var presence: Boolean? = null
        for (nom in nomsClesPresence) {
            presence = lireBoolCamera(km, nom) ?: continue
            break
        }

        // --- temps vidéo restant (secondes) : noms connus ---
        val nomsClesTemps = listOf(
            "KeyCameraSDCardAvailableRecordingTimeInSeconds",
            "KeySDCardAvailableRecordingTimeInSeconds",
            "KeyCameraVideoRecordRemainTime"
        )
        var secondes: Int? = null
        for (nom in nomsClesTemps) {
            secondes = lireIntCamera(km, nom) ?: continue
            break
        }

        // Si on a un temps valide, la carte est forcément présente.
        sdPresente = presence ?: (secondes != null && secondes >= 0)
        sdMinutesRestantes = if (secondes != null && secondes >= 0) secondes / 60 else -1
    }

    /** Lit une clé CameraKey booléenne par réflexion ; null si indisponible. */
    private fun lireBoolCamera(km: Any, nomCle: String): Boolean? {
        return try {
            val champ = CameraKey::class.java.getField(nomCle).get(null)
            val cle = creerCleReflexion(champ) ?: return null
            val getValue = km.javaClass.getMethod("getValue", cle.javaClass.superclass ?: cle.javaClass)
            (getValue.invoke(km, cle) as? Boolean)
        } catch (_: Throwable) { null }
    }

    /** Lit une clé CameraKey entière par réflexion ; null si indisponible. */
    private fun lireIntCamera(km: Any, nomCle: String): Int? {
        return try {
            val champ = CameraKey::class.java.getField(nomCle).get(null)
            val cle = creerCleReflexion(champ) ?: return null
            val getValue = km.javaClass.getMethod("getValue", cle.javaClass.superclass ?: cle.javaClass)
            (getValue.invoke(km, cle) as? Number)?.toInt()
        } catch (_: Throwable) { null }
    }

    /**
     * Appelle KeyTools.createKey(...) par réflexion, en testant chaque surcharge
     * disponible. Evite les contraintes de typage générique a la compilation
     * (DJIKey vs DJIActionKeyInfo selon la version MSDK). Retourne la clé ou null.
     */
    private fun creerCleReflexion(champ: Any?): Any? {
        if (champ == null) return null
        return try {
            val cls = KeyTools::class.java
            for (m in cls.methods) {
                if (m.name == "createKey" && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0].isAssignableFrom(champ.javaClass)) {
                    return m.invoke(null, champ)
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    private fun distanceHome(): Double {
        val la = latitudeDrone(); val lo = longitudeDrone()
        if (la.isNaN() || lo.isNaN() || latHome.isNaN() || lonHome.isNaN()) return Double.NaN
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(latHome))
        return hypot((lo - lonHome) * mLon, (la - latHome) * mLat)
    }

    override fun lireEtat(enVol: Boolean): EtatCockpit {
        majHome()
        majStockage()
        return EtatCockpit(
            connecte = estConnecte(),
            enVol = enVol,
            batteriePct = batteriePourcent(),
            altitudeAgl = altitudeDrone(),
            capDeg = capDroneDeg(),
            latitude = latitudeDrone(),
            longitude = longitudeDrone(),
            satellites = base.nbSatellitesActuel(),
            gpsValide = gpsValide(),
            distanceDecollageM = distanceHome(),
            vitesseHorizM = vH,
            vitesseVertM = vV,
            signalRcPct = signalRc,
            signalVideoPct = signalVideo,
            enregistre = enregistreEnCours(),
            gimbalPitchDeg = gimbalPitch,
            carteSdPresente = sdPresente,
            minutesEnregRestantes = sdMinutesRestantes,
            rthEnCours = rthActif,
            modele = base.modeleDrone()
        )
    }

    override fun declencherPhoto() {
        // bascule mode PHOTO avant le shoot (sinon -511 si la camera est en mode video,
        // ce qui arrive apres un reglage resolution/fps qui force VIDEO_NORMAL).
        try {
            val clsMode = Class.forName("dji.sdk.keyvalue.value.camera.CameraMode")
            val photoMode = clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "PHOTO_NORMAL" }
                ?: clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "PHOTO" }
            if (photoMode != null) {
                val cleMode = KeyTools.createKey(CameraKey.KeyCameraMode)
                KeyManager.getInstance().setValue(cleMode, photoMode as dji.sdk.keyvalue.value.camera.CameraMode,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() { shootPhotoMaintenant() }
                        override fun onFailure(error: dji.v5.common.error.IDJIError) {
                            Log.w("PontDjiReelCockpit", "mode photo echoue (${error.description()}), tentative directe")
                            shootPhotoMaintenant()
                        }
                    })
            } else {
                shootPhotoMaintenant()
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "bascule mode photo ex: ${e.message}")
            shootPhotoMaintenant()
        }
    }

    private fun shootPhotoMaintenant() {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartShootPhoto), null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    Log.i("PontDjiReelCockpit", "Photo prise")
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReelCockpit", "Échec photo: $error")
                }
            })
    }

    override fun reglerGimbalPitch(pitchDeg: Float) {
        gimbalPitch = pitchDeg
        orienterNacelle(pitchDeg, 0f, false)   // délégué à base
    }

    override fun lancerRth(onFini: (Boolean) -> Unit) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartGoHome), null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    rthActif = true; Log.i("PontDjiReelCockpit", "RTH lancé")
                    try { obsRthActive?.invoke() } catch (_: Throwable) {}
                    onFini(true)
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReelCockpit", "Échec RTH: $error")
                    try { obsRthRefuse?.invoke() } catch (_: Throwable) {}
                    onFini(false)
                }
            })
    }

    override fun annulerRth(onFini: (Boolean) -> Unit) {
        // Pas de KeyStopGoHome stable en MSDK v5 : l'annulation du RTH se fait en
        // reprenant le contrôle aux STICKS de la RC-N1 (le pilote bouge un stick).
        // On se contente de refléter l'état ; l'action physique est manuelle.
        val etaitActif = rthActif
        rthActif = false
        Log.i("PontDjiReelCockpit", "Annulation RTH : reprendre le contrôle aux sticks RC")
        if (etaitActif) { try { obsRthAnnule?.invoke() } catch (_: Throwable) {} }
        onFini(true)
    }

    // ===================== FONDATION : reglage camera generique =====================
    /**
     * Regle un parametre camera de type enum, MULTI-DRONES, par reflexion.
     *  - Resout la classe enum du SDK (enumClassName) ; si absente -> log + abandon propre.
     *  - Si rangeKeyName fourni : lit la PLAGE reellement supportee par le drone connecte
     *    et ne choisit QUE parmi les valeurs supportees (ex : ISO 6400 sur un drone, pas l'autre).
     *  - Choix : nom exact (choisirNom) ; repli (repliContient) ; defaut (defautContient).
     *  - Applique via setValue(cle, valeur, callback) trouve par reflexion (3 args).
     *  Aucun crash si le drone ne supporte pas : tout est try/catch + logs.
     */
    private fun reglerCameraEnum(
        keyName: String,
        enumClassName: String,
        choisirNom: String,
        repliContient: String? = null,
        defautContient: String? = null,
        rangeKeyName: String? = null
    ) {
        try {
            val cls = Class.forName(enumClassName)
            val toutes = cls.enumConstants ?: run {
                Log.w("PontDjiReelCockpit", "$keyName: enum $enumClassName sans constantes"); return
            }
            Log.i("PontDjiReelCockpit", "$keyName dispo: " + toutes.joinToString { (it as Enum<*>).name })

            // 1) Restreindre a la plage supportee par CE drone, si demandee
            var candidates: List<Any> = toutes.toList()
            if (rangeKeyName != null) {
                try {
                    val champRange = dji.sdk.keyvalue.key.CameraKey::class.java.getField(rangeKeyName).get(null)
                    val cleRange = creerCleReflexion(champRange)
                    val plage: List<*>? = if (cleRange != null) { try { val km0 = KeyManager.getInstance(); val mg = km0.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 }; mg?.invoke(km0, cleRange) as? List<*> } catch (_: Throwable) { null } } else null
                    if (plage != null && plage.isNotEmpty()) {
                        candidates = plage.filterNotNull()
                        Log.i("PontDjiReelCockpit", "$keyName supporte: " + candidates.joinToString { (it as Enum<*>).name })
                    }
                } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "$keyName plage indispo: " + e.message) }
            }

            // 2) Choisir : nom exact -> repli contient -> defaut contient -> premier
            var choisi: Any? = candidates.firstOrNull { (it as Enum<*>).name == choisirNom }
            if (choisi == null && repliContient != null)
                choisi = candidates.firstOrNull { (it as Enum<*>).name.contains(repliContient) }
            if (choisi == null && defautContient != null)
                choisi = candidates.firstOrNull { (it as Enum<*>).name.contains(defautContient) }
            if (choisi == null) {
                Log.w("PontDjiReelCockpit", "$keyName: aucune valeur pour \"$choisirNom\""); return
            }

            // 3) Appliquer via setValue(cle, valeur, callback) par reflexion
            val champKey = dji.sdk.keyvalue.key.CameraKey::class.java.getField(keyName).get(null)
            val cle = creerCleReflexion(champKey) ?: run { Log.w("PontDjiReelCockpit", "$keyName: createKey echoue"); return }
            val km = KeyManager.getInstance()
            val m = km.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 3 }
            if (m == null) { Log.w("PontDjiReelCockpit", "$keyName: setValue(3 args) introuvable"); return }
            val cbType = m.parameterTypes[2]
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { _, methode, args ->
                when (methode.name) {
                    "onSuccess" -> Log.i("PontDjiReelCockpit", "$keyName OK: " + (choisi as Enum<*>).name)
                    "onFailure" -> Log.w("PontDjiReelCockpit", "$keyName echec: " + (args?.getOrNull(0)?.toString() ?: ""))
                }
                null
            }
            m.invoke(km, cle, choisi, cb)
            Log.i("PontDjiReelCockpit", "$keyName: envoye " + (choisi as Enum<*>).name)
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "$keyName non applique: " + e.message)
        }
    }

    // ===================== Lecture etat camera reel =====================
    /** Lit la valeur actuelle d'une CameraKey et retourne le nom d'enum brut (ou null). */
    private fun lireCameraEnum(keyName: String): String? {
        return try {
            val champKey = dji.sdk.keyvalue.key.CameraKey::class.java.getField(keyName).get(null)
            val cle = creerCleReflexion(champKey) ?: return null
            val km = KeyManager.getInstance()
            val mg = km.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 }
            val v = mg?.invoke(km, cle) ?: return null
            val nom = (v as? Enum<*>)?.name
            Log.i("PontDjiReelCockpit", "$keyName lu: " + (nom ?: v.toString()))
            nom
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "$keyName lecture echec: " + e.message); null
        }
    }

    override fun lireEvBrut(): String? = lireCameraEnum("KeyExposureCompensation")
    override fun lireIsoBrut(): String? = lireCameraEnum("KeyISO")
    override fun lireModeExpoBrut(): String? = lireCameraEnum("KeyExposureMode")
    override fun lireShutterBrut(): String? = lireCameraEnum("KeyShutterSpeed")
    override fun lireWbBrut(): String? = lireCameraEnum("KeyWhiteBalance")

    // ===================== Reglages camera reels (via reglerCameraEnum) =====================
    override fun reglerIso(iso: Int) {
        // CameraISO : noms ISO_100, ISO_200, ISO_400... AUTO si iso<=0. Plage selon drone.
        val nom = if (iso <= 0) "AUTO" else "ISO_$iso"
        reglerCameraEnum("KeyISO", "dji.sdk.keyvalue.value.camera.CameraISO",
            choisirNom = nom, repliContient = if (iso <= 0) "AUTO" else "$iso",
            defautContient = "AUTO", rangeKeyName = "KeyISORange")
    }

    override fun reglerShutter(shutterNom: String) {
        // "1/120" -> SHUTTER_1_120 ; "2" -> SHUTTER_2 (selon convention enum CameraShutterSpeed).
        val frac = shutterNom.trim().replace("/", "_")
        val nom = "SHUTTER_$frac"
        reglerCameraEnum("KeyShutterSpeed", "dji.sdk.keyvalue.value.camera.CameraShutterSpeed",
            choisirNom = nom, repliContient = frac,
            defautContient = null, rangeKeyName = "KeyShutterSpeedRange")
    }

    override fun reglerWb(wbNom: String) {
        // "AUTO","SUNNY","CLOUDY","INCANDESCENT"... -> enum CameraWhiteBalanceMode (nom direct).
        val nom = wbNom.trim().uppercase()
        reglerCameraEnum("KeyWhiteBalance", "dji.sdk.keyvalue.value.camera.WhiteBalanceMode",
            choisirNom = nom, repliContient = nom, defautContient = "AUTO")
    }

    override fun reglerModeExpo(modeNom: String) {
        // "AUTO","MANUAL","SHUTTER","APERTURE" -> CameraExposureMode (PROGRAM/MANUAL/SHUTTER_PRIORITY/APERTURE_PRIORITY).
        val cible = when (modeNom.trim().uppercase()) {
            "MANUAL" -> "MANUAL"
            "SHUTTER" -> "SHUTTER_PRIORITY"
            "APERTURE" -> "APERTURE_PRIORITY"
            else -> "PROGRAM"
        }
        reglerCameraEnum("KeyExposureMode", "dji.sdk.keyvalue.value.camera.CameraExposureMode",
            choisirNom = cible, repliContient = cible, defautContient = "PROGRAM")
    }
    override fun reglerEv(ev: Float) {
        // EV via CameraKey.KeyExposureCompensation (enum CameraExposureCompensation).
        // La camera doit etre en mode PROGRAM. On resout l'enum par son nom
        // (N_x_x = negatif, P_x_x = positif, N_0_0 = zero) via valueOf, dans un
        // try/catch : si le nom exact differe selon la version SDK, pas de crash.
        try {
            val cls = Class.forName("dji.sdk.keyvalue.value.camera.CameraExposureCompensation")
            val constantes = cls.enumConstants  // toutes les valeurs reelles de CETTE version SDK
            // 1) LOG : lister tous les noms disponibles (a lire une fois dans Logcat)
            Log.i("PontDjiReelCockpit", "EV dispo: " + constantes.joinToString { (it as Enum<*>).name })
            // 2) choisir la constante dont le nom correspond le mieux a ev
            val cible = nomEvCible(ev)
            var choisi: Any? = constantes.firstOrNull { (it as Enum<*>).name == cible }
            if (choisi == null) {
                // repli : cherche une constante contenant la valeur (ex "0_3", "1_0")
                val frac = nomFractionEv(ev)
                choisi = constantes.firstOrNull { (it as Enum<*>).name.contains(frac) && (ev >= 0) == !(it).toString().startsWith("N") }
            }
            if (choisi == null) choisi = constantes.firstOrNull { (it as Enum<*>).name.contains("0_0") }  // 0 par defaut
            if (choisi != null) {
                val km = dji.v5.manager.KeyManager.getInstance()
                val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyExposureCompensation)
                // setValue(cle, valeur, callback) : 3 arguments attendus par le SDK
                val m = km.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 3 }
                if (m != null) {
                    val cbType = m.parameterTypes[2]
                    val cb = java.lang.reflect.Proxy.newProxyInstance(
                        cbType.classLoader, arrayOf(cbType)
                    ) { _, methode, args ->
                        when (methode.name) {
                            "onSuccess" -> Log.i("PontDjiReelCockpit", "reglerEv OK: " + (choisi as Enum<*>).name)
                            "onFailure" -> Log.w("PontDjiReelCockpit", "reglerEv echec drone: " + (args?.getOrNull(0)?.toString() ?: ""))
                        }
                        null
                    }
                    m.invoke(km, cle, choisi, cb)
                    Log.i("PontDjiReelCockpit", "reglerEv: envoye " + (choisi as Enum<*>).name + " (ev=$ev)")
                } else Log.w("PontDjiReelCockpit", "reglerEv: setValue(3 args) introuvable")
            } else {
                Log.w("PontDjiReelCockpit", "reglerEv: aucune constante trouvee pour ev=$ev")
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "reglerEv($ev) non applique: " + e.message)
        }
    }

    /** Convertit une valeur EV (-2.0..+2.0) vers le nom d'enum DJI (pas de 1/3 EV). */
    private fun convertirEv(ev: Float): String {
        // arrondi au tiers d'EV le plus proche
        val pas = Math.round(ev / 0.3f)            // ... -2,-1,0,1,2 ...
        val v = pas * 0.3f
        if (Math.abs(v) < 0.05f) return "N_0_0"
        val ent = Math.abs(v).toInt()
        val dec = Math.round((Math.abs(v) - ent) * 10).toInt()
        val signe = if (v < 0) "N" else "P"
        return signe + "_" + ent + "_" + dec
    }

    private fun nomFractionEv(ev: Float): String {
        // format DJI : "0P3", "1P0", "2P0" (P = virgule decimale)
        val a = Math.abs(ev)
        val ent = a.toInt()
        val dec = Math.round((a - ent) * 10).toInt()
        return ent.toString() + "P" + dec
    }
    private fun nomEvCible(ev: Float): String {
        // ex : 0 -> NEG_0EV ; +0.3 -> POS_0P3EV ; -1.0 -> NEG_1P0EV
        if (Math.abs(ev) < 0.05f) return "NEG_0EV"
        return (if (ev < 0) "NEG_" else "POS_") + nomFractionEv(ev) + "EV"
    }

    override fun reglerResolutionFps(resNom: String, fps: Int) {
        // Applique resolution + fps sur le drone reel via CameraKey.KeyVideoResolutionFrameRate.
        // PRO multi-drones : on lit la PLAGE reellement supportee par le drone connecte
        // (KeyVideoResolutionFrameRateRange) et on choisit la valeur supportee la plus proche
        // de la demande. Ainsi aucun reglage n'est refuse : 60fps sur Mini 3, 200fps sur Mini 4 Pro.
        try {
            // resolution demandee -> nom d'enum VideoResolution
            val resCible = when (resNom) {
                "4K" -> "RESOLUTION_3840x2160"
                "2.7K" -> "RESOLUTION_2688x1512"
                else -> "RESOLUTION_1920x1080"   // FHD
            }
            val fpsCible = "RATE_${fps}FPS"

            val clsRes = Class.forName("dji.sdk.keyvalue.value.camera.VideoResolution")
            val clsFps = Class.forName("dji.sdk.keyvalue.value.camera.VideoFrameRate")
            val clsVRF = Class.forName("dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate")

            // 1) Mettre la camera en mode VIDEO_NORMAL (requis avant de regler res/fps)
            try {
                val clsMode = Class.forName("dji.sdk.keyvalue.value.camera.CameraMode")
                val videoNormal = clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "VIDEO_NORMAL" }
                if (videoNormal != null) {
                    val cleMode = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyCameraMode)
                    KeyManager.getInstance().setValue(cleMode, videoNormal as dji.sdk.keyvalue.value.camera.CameraMode,
                        object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {}
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {}
                        })
                }
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "mode video: " + e.message) }

            // 2) Lire la plage REELLE supportee par le drone connecte
            val cleRange = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyVideoResolutionFrameRateRange)
            val plage = KeyManager.getInstance().getValue(cleRange) as? List<*>

            // helper : extraire res+fps d'un objet VideoResolutionFrameRate
            fun nomRes(o: Any?): String? = try { clsVRF.getMethod("getResolution").invoke(o)?.let { (it as Enum<*>).name } } catch (e: Throwable) { null }
            fun nomFps(o: Any?): String? = try { clsVRF.getMethod("getFrameRate").invoke(o)?.let { (it as Enum<*>).name } } catch (e: Throwable) { null }
            fun fpsNum(nom: String?): Int = nom?.let { Regex("RATE_(\\d+)FPS").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

            // 3) Choisir la meilleure correspondance dans la plage reelle
            var choix: Any? = null
            if (plage != null && plage.isNotEmpty()) {
                // d'abord match exact res+fps
                choix = plage.firstOrNull { nomRes(it) == resCible && nomFps(it) == fpsCible }
                // sinon : meme resolution, fps supporte le plus proche (<=) du demande
                if (choix == null) {
                    val memeRes = plage.filter { nomRes(it) == resCible && fpsNum(nomFps(it)) > 0 }
                    choix = memeRes.minByOrNull { kotlin.math.abs(fpsNum(nomFps(it)) - fps) }
                }
                // sinon : n'importe quelle combinaison au fps demande
                if (choix == null) {
                    choix = plage.filter { fpsNum(nomFps(it)) > 0 }.minByOrNull { kotlin.math.abs(fpsNum(nomFps(it)) - fps) }
                }
            }

            // 4) Construire l'objet a appliquer (depuis la plage si trouve, sinon construit a la demande)
            val objVRF: Any = choix ?: run {
                val resVal = clsRes.enumConstants?.firstOrNull { (it as Enum<*>).name == resCible }
                val fpsVal = clsFps.enumConstants?.firstOrNull { (it as Enum<*>).name == fpsCible }
                    ?: clsFps.enumConstants?.firstOrNull { (it as Enum<*>).name == "RATE_30FPS" }
                clsVRF.getConstructor(clsRes, clsFps).newInstance(resVal, fpsVal)
            }

            // 5) Appliquer
            val cleSet = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyVideoResolutionFrameRate)
            @Suppress("UNCHECKED_CAST")
            KeyManager.getInstance().setValue(cleSet, objVRF as dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate,
                object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i("PontDjiReelCockpit", "Resolution/fps applique: ${nomRes(objVRF)} @ ${nomFps(objVRF)}")
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        Log.w("PontDjiReelCockpit", "set res/fps echoue: " + error.description())
                    }
                })
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "reglerResolutionFps non applique: " + e.message)
        }
    }

}


// COCKPIT SIMULE - enveloppe un PontDjiSimule (test sans drone)
class PontDjiSimuleCockpit(
    latDepart: Double = 45.5455,
    lonDepart: Double = -73.6868,
    private val base: PontDjiSimule = PontDjiSimule(latDepart, lonDepart)
) : PontCockpit, PiloteDrone.PontDji by base {

    private val latHome = latDepart
    private val lonHome = lonDepart
    @Volatile private var gimbalPitchSim = 0f
    @Volatile private var rthSim = false

    override fun lireEtat(enVol: Boolean): EtatCockpit {
        val vh = hypot(base.dernierPitch.toDouble(), base.dernierRoll.toDouble())
        val vv = base.dernierThrottle.toDouble()
        val la = latitudeDrone(); val lo = longitudeDrone()
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(latHome))
        val dist = hypot((lo - lonHome) * mLon, (la - latHome) * mLat)
        return EtatCockpit(
            connecte = true, enVol = enVol, batteriePct = 85,
            altitudeAgl = altitudeDrone(), capDeg = capDroneDeg(),
            latitude = la, longitude = lo, satellites = 13, gpsValide = true,
            distanceDecollageM = dist, vitesseHorizM = vh, vitesseVertM = vv,
            signalRcPct = 96, signalVideoPct = 92,
            enregistre = enregistreEnCours(),
            gimbalPitchDeg = gimbalPitchSim, rthEnCours = rthSim,
            carteSdPresente = true, minutesEnregRestantes = 32
        )
    }

    override fun declencherPhoto() {}
    override fun reglerGimbalPitch(pitchDeg: Float) { gimbalPitchSim = pitchDeg }
    override fun lancerRth(onFini: (Boolean) -> Unit) { rthSim = true; onFini(true) }
    override fun annulerRth(onFini: (Boolean) -> Unit) { rthSim = false; onFini(true) }
    override fun reglerIso(iso: Int) {}
    override fun reglerEv(ev: Float) {}
    override fun reglerResolutionFps(resNom: String, fps: Int) {}
    override fun reglerShutter(shutterNom: String) {}
    override fun reglerWb(wbNom: String) {}
    override fun reglerModeExpo(modeNom: String) {}
    override fun lireEvBrut(): String? = null
    override fun lireIsoBrut(): String? = null
    override fun lireModeExpoBrut(): String? = null
    override fun lireShutterBrut(): String? = null
    override fun lireWbBrut(): String? = null
}

