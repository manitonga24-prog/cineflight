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

    /**
     * Espace restant sur la carte, en OCTETS ; -1 = inconnu.
     *
     * Il était déjà lu (`getStorageLeftCapacity`) mais jeté : seules les minutes de vidéo
     * en étaient tirées. Or c'est lui qui permet de refuser AVANT le décollage une capture
     * 3D de 96 photos qui ne tiendrait pas — une carte pleine au 40ᵉ cliché, c'est une
     * batterie et un déplacement perdus pour un jeu inexploitable.
     */
    @Volatile var octetsLibresCarte: Long = -1L
        private set

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
     * MSDK v5 : LA clé stockage est `KeyCameraStorageInfos` (doc officielle CameraKey) —
     * les 7 anciens noms candidats (KeyCameraSDCardIsInserted, KeySDCardIsInserted, etc.)
     * N'EXISTENT PAS en v5 : l'icône disquette affichait « absente » avec une carte SD
     * réellement présente (constaté 2026-07-25). Structure rendue :
     * CameraStorageInfos.getCameraStorageInfoList() -> List<CameraStorageInfo> avec
     * getStorageType() (SDCARD / mémoire interne), getStorageState() (insertion),
     * getStorageLeftCapacity() (Mo) et getAvailableVideoDuration() (secondes).
     * Parse par réflexion, best-effort, jamais bloquant ; sur échec on GARDE les
     * dernières valeurs connues. À appeler sur le FIL PRINCIPAL uniquement.
     */
    private fun majStockage() {
        val km = try { KeyManager.getInstance() } catch (_: Throwable) { return }
        try {
            val champ = CameraKey::class.java.getField("KeyCameraStorageInfos").get(null)
            val cle = creerCleCameraCiblee(champ) ?: creerCleReflexion(champ) ?: return
            val mg = km.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 } ?: return
            val infos = mg.invoke(km, cle) ?: return
            val liste = infos.javaClass.getMethod("getCameraStorageInfoList").invoke(infos) as? List<*> ?: return
            fun nomEnum(o: Any?, m: String): String? =
                try { (o?.javaClass?.getMethod(m)?.invoke(o) as? Enum<*>)?.name } catch (_: Throwable) { null }
            fun entier(o: Any?, m: String): Int? =
                try { (o?.javaClass?.getMethod(m)?.invoke(o) as? Number)?.toInt() } catch (_: Throwable) { null }
            // Entrée CARTE SD en priorité ; repli sur la première entrée (mémoire interne).
            val sd = liste.firstOrNull { nomEnum(it, "getStorageType")?.contains("SD") == true }
                ?: liste.firstOrNull() ?: return
            val etat = nomEnum(sd, "getStorageState") ?: ""
            val resteMo = entier(sd, "getStorageLeftCapacity") ?: -1
            // Présence : état d'insertion positif (INSERTED mais pas NOT_INSERTED),
            // sinon repli sur une capacité restante > 0 (une carte lue = une carte présente).
            sdPresente = (etat.contains("INSERT") && !etat.contains("NOT")) || resteMo > 0
            // Mio -> octets. On garde -1 quand la lecture a échoué : « inconnu » et
            // « zéro » ne veulent pas dire la même chose, et les confondre ferait refuser
            // tous les vols dès que le SDK ne répond pas.
            octetsLibresCarte = if (resteMo >= 0) resteMo.toLong() * 1024L * 1024L else -1L
            val secondes = entier(sd, "getAvailableVideoDuration") ?: -1
            sdMinutesRestantes = if (secondes >= 0) secondes / 60 else -1
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "stockage v5 indisponible: " + e.message)
        }
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

    private var dernierStockageMs = 0L
    override fun lireEtat(enVol: Boolean): EtatCockpit {
        majHome()
        // SUR LE FIL PRINCIPAL (surtout PAS de fil de fond : la concurrence SDK déconnecte le
        // drone, cf. CLAUDE.md). Mais THROTTLÉ : l'état carte SD change lentement, inutile de
        // refaire 7 getValue à chaque appel (~6/s). Une fois toutes les 2 s allège le fil UI
        // sans aucun accès concurrent au SDK.
        val now = System.currentTimeMillis()
        if (now - dernierStockageMs >= 2000L) { dernierStockageMs = now; majStockage() }
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
            // Durée d'enregistrement lue au DRONE (CameraKey.KeyRecordingTime, secondes) :
            // juste même si l'enregistrement a été lancé depuis la RADIOCOMMANDE. -1 si la
            // clé ne répond pas -> l'app affiche alors son propre chronomètre.
            secondesEnregistrement = if (enregistreEnCours()) lireSecondesEnregistrement() else -1,
            gimbalPitchDeg = gimbalPitch,
            carteSdPresente = sdPresente,
            minutesEnregRestantes = sdMinutesRestantes,
            rthEnCours = rthActif,
            modele = base.modeleDrone()
        )
    }

    /**
     * Durée d'enregistrement en cours, en SECONDES, lue au drone
     * (`CameraKey.KeyRecordingTime`, doc officielle CameraKey v5). -1 si indisponible.
     * Clé CIBLÉE (#506). À appeler sur le FIL PRINCIPAL (règle SDK mono-fil).
     */
    private fun lireSecondesEnregistrement(): Int {
        return try {
            val champ = CameraKey::class.java.getField("KeyRecordingTime").get(null)
            val cle = creerCleCameraCiblee(champ) ?: creerCleReflexion(champ) ?: return -1
            val km = KeyManager.getInstance()
            val mg = km.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 }
            (mg?.invoke(km, cle) as? Number)?.toInt() ?: -1
        } catch (_: Throwable) { -1 }
    }

    private val handlerPhoto = android.os.Handler(android.os.Looper.getMainLooper())

    /** Ancien contrat (tir-et-oublie) conservé : délègue à la version confirmée, sans callback. */
    override fun declencherPhoto() {
        declencherPhotoConfirmee { ok, detail ->
            if (!ok) Log.e("PontDjiReelCockpit", detail ?: "Échec photo")
        }
    }

    /**
     * Prend UNE photo et ne rend le résultat qu'APRÈS la réponse du SDK — commande ACCEPTÉE
     * (onSuccess) ou REFUSÉE (onFailure) — plus un court délai d'écriture du fichier.
     * L'ancien `declencherPhoto()` ne remontait RIEN à l'appelant : le panorama comptait donc
     * chaque photo comme prise, même refusée (carte pleine, caméra occupée, mauvais mode).
     * Ici, un refus est signalé et le panorama peut s'interrompre proprement.
     */
    fun declencherPhotoConfirmee(onFini: (Boolean, String?) -> Unit) {
        val termine = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finir(ok: Boolean, detail: String?) {
            if (termine.compareAndSet(false, true)) {
                if (ok) Log.i("PontDjiReelCockpit", "Photo confirmée (commande acceptée)")
                else Log.e("PontDjiReelCockpit", detail ?: "Échec photo")
                onFini(ok, detail)
            }
        }
        // Stoppe toute TÂCHE photo en cours ou résiduelle (PHOTO_PANORAMA / PHOTO_INTERVAL /
        // PHOTO_SUPER_RESOLUTION). Doc DJI v5 (CameraKey.KeyStopShootPhoto) : c'est le seul
        // moyen de sortir la caméra d'une tâche laissée par DJI Fly ; sinon KeyStartShootPhoto
        // répond cannot_start_task_on_weak_gps dès que le GPS est faible (au sol, intérieur).
        // Best-effort : l'échec (caméra déjà libre) est ignoré et on enchaîne.
        fun stopperTachePhoto(puis: () -> Unit) {
            try {
                KeyManager.getInstance().performAction(
                    KeyTools.createCameraKey(CameraKey.KeyStopShootPhoto,
                        dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                        dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT), null,
                    object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                        override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                            Log.i("PontDjiReelCockpit", "Tâche photo stoppée (KeyStopShootPhoto)")
                            puis()
                        }
                        override fun onFailure(error: dji.v5.common.error.IDJIError) { puis() }
                    })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "stop tâche photo ex: ${e.message}"); puis() }
        }
        // Bascule la caméra en PHOTO_NORMAL puis enchaîne après `delaiMs` de stabilisation
        // (sinon -511 si la caméra est en mode vidéo, ou tir refusé pendant la transition).
        // Mode obtenu par RÉFLEXION (nom d'enum incertain selon la version SDK).
        fun basculerModePhotoPuis(delaiMs: Long, puis: () -> Unit) {
            try {
                val clsMode = Class.forName("dji.sdk.keyvalue.value.camera.CameraMode")
                val photoMode = clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "PHOTO_NORMAL" }
                    ?: clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "PHOTO" }
                if (photoMode != null) {
                    KeyManager.getInstance().setValue(
                        KeyTools.createCameraKey(CameraKey.KeyCameraMode,
                            dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                            dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT),
                        photoMode as dji.sdk.keyvalue.value.camera.CameraMode,
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { handlerPhoto.postDelayed({ puis() }, delaiMs) }
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                Log.w("PontDjiReelCockpit", "mode photo échoué (${error.description()}), tentative directe")
                                puis()
                            }
                        })
                } else puis()
            } catch (e: Throwable) {
                Log.w("PontDjiReelCockpit", "bascule mode photo ex: ${e.message}")
                puis()
            }
        }
        // UNE seule récupération WEAK_GPS par prise (anti-boucle).
        val recuperationWeakGps = java.util.concurrent.atomic.AtomicBoolean(false)
        // TIR — VARIANTES DE CLÉ, dans l'ordre. Le ciblage objectif (#506) était censé être
        // obligatoire… mais CONSTAT 2026-07-25 : à 15-16 satellites, tir ciblé refusé -472
        // CANNOT_START_TASK_ON_WEAK_GPS, caméra pourtant en PHOTO_NORMAL (DIAG ciblé). Sur
        // un MONO-objectif (Mini 4 Pro), le ciblage LENS peut router l'action vers le
        // sous-système « tâche vision » (pano), qui exige le GPS. Variante 2 = clé NUE,
        // la forme canonique du support DJI (#537). Chaque refus est journalisé avec sa
        // variante -> le Logcat dira laquelle passe sur ce matériel.
        fun shoot(variante: Int = 0) {
            try {
                val cle = when (variante) {
                    0 -> KeyTools.createCameraKey(CameraKey.KeyStartShootPhoto,
                        dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                        dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT)
                    1 -> KeyTools.createCameraKey(CameraKey.KeyStartShootPhoto,
                        dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN,
                        dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_WIDE)
                    else -> KeyTools.createKey(CameraKey.KeyStartShootPhoto)
                }
                KeyManager.getInstance().performAction(cle, null,
                    object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                        override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                            // Commande ACCEPTÉE : on laisse ~1 s à la caméra pour ÉCRIRE le
                            // fichier avant d'avancer (sinon le panorama pivote pendant l'écriture).
                            Log.i("PontDjiReelCockpit", "Photo ACCEPTÉE (variante=$variante)")
                            handlerPhoto.postDelayed({ finir(true, null) }, 1000L)
                        }
                        override fun onFailure(error: dji.v5.common.error.IDJIError) {
                            if (variante < 2) {
                                Log.w("PontDjiReelCockpit", "Photo refusée variante=$variante " +
                                    "(${error.errorCode()}) -> variante ${variante + 1}")
                                shoot(variante + 1)
                                return
                            }
                            // description() est souvent null : on compose CODE + hint, ET on
                            // ajoute le contexte des blocages RÉELS (carte SD) pour diagnostiquer.
                            val base = try {
                                val parts = listOf(
                                    error.errorCode(),
                                    error.description(),
                                    try { error.hint() } catch (_: Throwable) { null }
                                ).filter { !it.isNullOrBlank() }
                                if (parts.isEmpty()) error.toString() else parts.joinToString(" · ")
                            } catch (_: Throwable) { error.toString() }
                            // RÉCUPÉRATION : cannot_start_task_on_weak_gps = la caméra est
                            // restée dans un mode TÂCHE (panorama/QuickShot). On stoppe la
                            // tâche, on force PHOTO_NORMAL, et on retire UNE fois.
                            if (base.contains("WEAK_GPS", ignoreCase = true) &&
                                recuperationWeakGps.compareAndSet(false, true)) {
                                Log.w("PontDjiReelCockpit",
                                    "WEAK_GPS -> stop tâche + PHOTO_NORMAL + nouvel essai")
                                stopperTachePhoto { basculerModePhotoPuis(400L) { shoot() } }
                                return
                            }
                            try { majStockage() } catch (_: Throwable) {}
                            // DIAGNOSTIC WEAK_GPS : lectures CIBLÉES (la lecture non ciblée
                            // peut rendre le cache SDK, pas l'état réel de la caméra — #506).
                            if (base.contains("WEAK_GPS", ignoreCase = true)) {
                                handlerPhoto.post {
                                    Log.e("PontDjiReelCockpit", "DIAG WEAK_GPS : mode_cible=" +
                                        (lireCameraBrutCible("KeyCameraMode") ?: "?") +
                                        " pano_en_cours=" +
                                        (lireCameraBrutCible("KeyIsShootingPhotoPanorama") ?: "?") +
                                        " mode_pano=" +
                                        (lireCameraBrutCible("KeyPhotoPanoramaMode") ?: "?") +
                                        " satellites=" +
                                        this@PontDjiReelCockpit.base.nbSatellitesActuel())
                                }
                            }
                            val ctx = "carte SD=" + if (sdPresente) "présente" else "ABSENTE/inconnue"
                            // ⚠ HISTORIQUE DU DIAGNOSTIC (2026-07-25) : d'abord attribué au
                            // GPS (satellites=0 en intérieur)… RÉFUTÉ ensuite : -472 aussi à
                            // 15-16 satellites, home point capturé, caméra PHOTO_NORMAL au
                            // DIAG ciblé. Le libellé WEAK_GPS du firmware ne décrit donc PAS
                            // la vraie condition. Piste en cours : la FORME de la clé (les
                            // 3 variantes ci-dessus). Croiser avec DJI Fly au même endroit.
                            // REMÈDE ÉTABLI 2026-07-25 : -472 persistant = état caméra rémanent
                            // que le MSDK ne peut ni voir ni réparer. UNE ouverture de DJI Fly
                            // (photo test) réinitialise la caméra. Cause probable : coupure
                            // brutale pendant une opération caméra (essais E-03, surchauffe).
                            val hintGps = if (base.contains("WEAK_GPS", ignoreCase = true))
                                " | état caméra rémanent probable : ouvrir DJI Fly, prendre une photo test, fermer, puis revenir dans CineFlight"
                            else ""
                            val info = "$base | $ctx$hintGps"
                            Log.e("PontDjiReelCockpit", "Échec KeyStartShootPhoto : $info")
                            finir(false, info)
                        }
                    })
            } catch (e: Throwable) { finir(false, "Exception photo : ${e.message}") }
        }
        // CHEMIN NOMINAL. En MSDK v5, le mode de prise EST KeyCameraMode (PHOTO_NORMAL,
        // PHOTO_PANORAMA, PHOTO_INTERVAL...). Les clés KeyPhotoShootMode / KeyShootPhotoMode /
        // KeyCameraFlatMode n'existent PAS en v5 (vérifié doc officielle CameraKey) :
        // l'ancienne boucle de candidats par réflexion ne faisait RIEN — supprimée.
        // Séquence : lire le mode (diagnostic Logcat) -> si mode tâche ou inconnu, stopper
        // la tâche -> forcer PHOTO_NORMAL -> tirer. Une photo UNIQUE n'exige pas de GPS.
        val modeActuel = lireCameraEnum("KeyCameraMode")
        if (modeActuel == "PHOTO_NORMAL") {
            basculerModePhotoPuis(0L) { shoot() }   // déjà en photo simple : aucun délai
        } else {
            stopperTachePhoto { basculerModePhotoPuis(400L) { shoot() } }
        }
    }

    override fun reglerGimbalPitch(pitchDeg: Float) {
        gimbalPitch = pitchDeg
        orienterNacelle(pitchDeg, 0f, false)   // délégué à base
    }

    // ── VERROUILLAGE D'EXPOSITION (panorama, 2026-07-26) ────────────────────────────
    // Constat terrain : jointures VISIBLES sur un panorama assemblé — une moitié nettement
    // plus claire que l'autre. Cause : l'exposition AUTOMATIQUE se réajuste à chaque
    // rotation (ciel d'un côté, sous-bois de l'autre), donc chaque photo a une luminosité
    // différente et l'assembleur ne peut pas fondre les jointures.
    // REMÈDE (pratique standard des apps pano) : figer ISO + vitesse sur les valeurs
    // MESURÉES avant la séquence, puis restaurer l'automatique à la fin.
    @Volatile private var expoVerrouillee = false

    /**
     * Fige l'exposition sur les valeurs COURANTES (lues à la caméra) pour toute la durée
     * du panorama. Best-effort : si la lecture échoue, on ne verrouille pas plutôt que de
     * figer une valeur inventée (une exposition fausse est pire qu'une exposition qui varie).
     * @return true si le verrouillage a réellement été appliqué.
     */
    /**
     * Fige la BALANCE DES BLANCS pour la durée d'un panorama.
     *
     * POURQUOI C'EST LE VERROUILLAGE LE PLUS IMPORTANT. Sur ce point, toutes les sources
     * s'accordent : en automatique, la balance dérive d'une image à l'autre et chaque
     * direction prend une teinte différente. Or un écart de COULEUR se rattrape très mal au
     * raccord, alors qu'un écart de LUMINOSITÉ, Hugin sait l'égaliser par optimisation
     * photométrique. On verrouille donc ce que le logiciel ne sait pas réparer.
     *
     * On fige la balance TELLE QU'ELLE EST — on n'impose pas une température arbitraire.
     * Celle que la caméra a choisie au premier cliché convient à la scène ; la figer suffit,
     * et forcer 5500 K trahirait les lumières de fin de journée.
     *
     * ⚠ Liste blanche de noms de clé, comme partout ailleurs. Si rien ne correspond, on ne
     * force RIEN et on le DIT : une balance qu'on croit figée et qui dérive donnerait un
     * panorama en camaïeu sans que personne comprenne pourquoi.
     */
    fun verrouillerBalanceBlancs(): String {
        // 1) Lire la valeur courante : c'est elle qu'on veut conserver.
        val actuel = lireCameraBrutCible("KeyWhiteBalance")
            ?: lireCameraBrutCible("KeyCameraWhiteBalance")
        // 2) Trouver la constante de mode « manuel / personnalisé » du SDK.
        val clsNoms = listOf(
            "dji.sdk.keyvalue.value.camera.WhiteBalancePreset",
            "dji.sdk.keyvalue.value.camera.CameraWhiteBalancePreset",
        )
        var manuel: Any? = null
        for (n in clsNoms) {
            val cls = try { Class.forName(n) } catch (_: Throwable) { continue }
            val consts = cls.enumConstants ?: continue
            manuel = consts.firstOrNull {
                val s = (it as Enum<*>).name.uppercase()
                s.contains("MANUAL") || s.contains("CUSTOM")
            }
            if (manuel != null) break
            Log.w("PontDjiReelCockpit", "$n sans constante manuelle : " +
                consts.joinToString { (it as Enum<*>).name })
        }
        if (manuel == null) return "balance des blancs NON figée : mode manuel introuvable dans ce SDK"
        for (nomCle in listOf("KeyWhiteBalance", "KeyCameraWhiteBalance")) {
            val champ = try { CameraKey::class.java.getField(nomCle).get(null) } catch (_: Throwable) { continue }
            val cle = creerCleCameraCiblee(champ) ?: continue
            try {
                val km = KeyManager.getInstance()
                val ms = km.javaClass.methods.firstOrNull {
                    it.name == "setValue" && it.parameterTypes.size == 3
                } ?: continue
                ms.invoke(km, cle, manuel, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i("PontDjiReelCockpit", "balance des blancs FIGÉE (était $actuel)")
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        Log.w("PontDjiReelCockpit", "balance des blancs refusée : ${error.description()}")
                    }
                })
                return "balance des blancs figée (était ${actuel ?: "?"}, clé $nomCle)"
            } catch (e: Throwable) {
                Log.w("PontDjiReelCockpit", "balance des blancs : ${e.message}")
            }
        }
        return "balance des blancs NON figée : aucune clé exploitable dans ce SDK"
    }

    /**
     * Fige ISO et vitesse pour toute la séquence. Rend une description de ce qui a été
     * TENTÉ ; le résultat RÉEL part au journal une seconde plus tard.
     *
     * ⚠⚠ DÉFAUT CORRIGÉ (2026-07-28) — L'ANCIENNE VERSION DÉCLARAIT SANS VÉRIFIER.
     * Elle posait `expoVerrouillee = true` juste après avoir DEMANDÉ le mode manuel, sans
     * jamais relire ce que la caméra avait retenu, et son résultat était jeté par un
     * `try { } catch { }` chez l'appelant. C'était donc un état d'INTENTION, pas de fait —
     * la même famille de défaut que « armé sans détecteur » ou « mode soccer pré-armé ».
     *
     * MESURE QUI L'A RÉVÉLÉ : sur le panorama `eca54f3db18e`, les expositions estimées par
     * Hugin s'étalent sur **2,48 EV**, en oscillant avec l'azimut — clair dos au soleil,
     * sombre face à lui. C'est la signature d'une exposition restée AUTOMATIQUE. Le verrou
     * n'avait jamais tenu, et personne ne pouvait le savoir : rien n'était journalisé.
     * Conséquence visible : des rectangles de luminosité dans le panorama fini.
     */
    fun verrouillerExposition(): String {
        if (expoVerrouillee) return "exposition : déjà verrouillée"
        val isoBrut = lireIsoBrut()          // ex. "ISO_400"
        val shutBrut = lireShutterBrut()     // ex. "SHUTTER_1_120"
        val iso = isoBrut?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val shut = shutBrut?.removePrefix("SHUTTER_")?.replace('_', '/')
        if (iso == null || shut.isNullOrBlank()) {
            // ⚠ CE REFUS SE DIT MAINTENANT. Il n'allait qu'au Logcat, effacé en quelques
            // heures : le panorama sortait avec des jointures visibles et rien n'expliquait
            // pourquoi, des semaines plus tard.
            val m = "!! exposition NON verrouillée (lecture impossible : iso=$isoBrut" +
                    " vitesse=$shutBrut) — les jointures seront visibles"
            Log.w("PontDjiReelCockpit", m)
            return m
        }
        reglerModeExpo("MANUAL")
        handlerPhoto.postDelayed({
            try { reglerIso(iso); reglerShutter(shut) } catch (_: Throwable) {}
        }, 300L)   // laisse le mode MANUAL s'appliquer avant de poser les valeurs
        // ⚠ VÉRIFICATION DIFFÉRÉE, SUR LE FIL PRINCIPAL. On relit ce que la caméra a
        // RÉELLEMENT retenu : une demande acceptée par le SDK n'est pas un réglage appliqué,
        // ce projet en a fait l'expérience assez souvent. Le fil principal est imposé —
        // lire les clés depuis un fil de fond avait déstabilisé la liaison le 2026-07-24.
        handlerPhoto.postDelayed({
            val mode = try { lireModeExpoBrut() } catch (_: Throwable) { null }
            val iso2 = try { lireIsoBrut() } catch (_: Throwable) { null }
            val sh2 = try { lireShutterBrut() } catch (_: Throwable) { null }
            val tenu = mode?.contains("MANUAL", ignoreCase = true) == true
            val m = if (tenu)
                "exposition VERROUILLÉE et vérifiée : mode=$mode iso=$iso2 vitesse=$sh2"
            else
                "!! EXPOSITION NON TENUE : mode=$mode (attendu MANUAL) iso=$iso2" +
                " vitesse=$sh2 — l'auto-exposition va dériver et les jointures seront visibles"
            Log.i("PontDjiReelCockpit", m)
            try { JournalVol.evenement(m) } catch (_: Throwable) {}
            if (!tenu) try { JournalVol.anomalie("EXPOSITION_NON_TENUE mode=$mode") } catch (_: Throwable) {}
        }, 1200L)
        expoVerrouillee = true
        val m = "exposition demandée en MANUEL : ISO $iso, vitesse $shut (vérification dans 1 s)"
        Log.i("PontDjiReelCockpit", m)
        return m
    }

    /** Restaure l'exposition AUTOMATIQUE (fin ou annulation du panorama). Idempotent. */
    fun deverrouillerExposition() {
        if (!expoVerrouillee) return
        expoVerrouillee = false
        try { reglerModeExpo("AUTO") } catch (_: Throwable) {}
        Log.i("PontDjiReelCockpit", "exposition rendue à l'automatique")
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
    /**
     * Comme [creerCleReflexion] mais CIBLÉE composant/objectif (LEFT_OR_MAIN + objectif par
     * défaut) — obligatoire sur Mini 4 Pro (#506) sinon les réglages caméra sont ignorés.
     * Cherche KeyTools.createCameraKey(keyInfo, ComponentIndexType, CameraLensType) par réflexion.
     */
    private fun creerCleCameraCiblee(champ: Any?): Any? {
        if (champ == null) return null
        return try {
            val comp = dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN
            val lens = dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_DEFAULT
            var res: Any? = null
            for (m in KeyTools::class.java.methods) {
                if (m.name == "createCameraKey" && m.parameterTypes.size == 3 &&
                    m.parameterTypes[0].isAssignableFrom(champ.javaClass)) {
                    res = m.invoke(null, champ, comp, lens); break
                }
            }
            res
        } catch (_: Throwable) { null }
    }

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

            // 3) Appliquer via setValue(cle, valeur, callback) par reflexion.
            // ⚠ CIBLAGE OBLIGATOIRE (Mini 4 Pro, #506) : clé ciblée composant/objectif EN
            // PRIORITÉ ; sinon le SDK IGNORE le réglage en silence (mode de prise inchangé
            // -> panorama persistant -> cannot_start_task_on_weak_gps). Repli non ciblé.
            val champKey = dji.sdk.keyvalue.key.CameraKey::class.java.getField(keyName).get(null)
            val cle = creerCleCameraCiblee(champKey) ?: creerCleReflexion(champKey)
                ?: run { Log.w("PontDjiReelCockpit", "$keyName: createKey echoue"); return }
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
    /**
     * Lit une CameraKey CIBLÉE (LEFT_OR_MAIN + LENS_DEFAULT) et rend la valeur brute (ou null).
     * DIAGNOSTIC : la lecture NON ciblée (lireCameraEnum) peut rendre le cache SDK au lieu de
     * l'état réel de la caméra sur Mini 4 Pro (#506). À appeler sur le FIL PRINCIPAL.
     */
    /**
     * Règle le format d'enregistrement des photos : JPEG, DNG (RAW), ou les deux.
     *
     * @param mode 0 = JPEG seul · 1 = DNG seul · 2 = DNG + JPEG.
     * @return un texte décrivant ce qui a RÉELLEMENT été fait — appliqué, déjà bon, ou
     *   impossible. Jamais un silence : c'est un réglage qui décide de ce qui sera écrit
     *   sur la carte, et le pilote doit pouvoir le vérifier après coup.
     *
     * ⚠ LISTE BLANCHE stricte, comme pour toute clé caméra de ce projet. Si la clé ou la
     * constante n'existe pas dans ce MSDK, on ne force RIEN et on le dit.
     */
    fun reglerFormatPhoto(mode: Int): String {
        val cherche = when (mode) {
            0 -> listOf("JPEG")
            1 -> listOf("RAW", "DNG")
            else -> listOf("RAW_JPEG", "JPEG_RAW", "RAWJPEG")
        }
        val clsNoms = listOf(
            "dji.sdk.keyvalue.value.camera.PhotoFileFormat",
            "dji.sdk.keyvalue.value.camera.PhotoStorageFormat",
        )
        var valeur: Any? = null
        for (n in clsNoms) {
            val cls = try { Class.forName(n) } catch (_: Throwable) { continue }
            val consts = cls.enumConstants ?: continue
            // Pour « les deux », on exige un libellé contenant RAW **et** JPEG : un simple
            // « contient RAW » attraperait le RAW seul et écrirait le mauvais format.
            valeur = if (mode == 2)
                consts.firstOrNull { val s = (it as Enum<*>).name.uppercase()
                    s.contains("RAW") && s.contains("JPEG") }
            else consts.firstOrNull { c ->
                val s = (c as Enum<*>).name.uppercase()
                cherche.any { s == it || s.startsWith(it) } &&
                    !(mode == 1 && s.contains("JPEG")) && !(mode == 0 && s.contains("RAW"))
            }
            if (valeur != null) break
            Log.w("PontDjiReelCockpit", "$n : aucune constante pour mode=$mode " +
                "(disponibles : " + consts.joinToString { (it as Enum<*>).name } + ")")
        }
        if (valeur == null) return "format photo NON réglé : constante introuvable dans ce SDK"
        for (nomCle in listOf("KeyPhotoFileFormat", "KeyPhotoStorageFormat")) {
            val champ = try { CameraKey::class.java.getField(nomCle).get(null) } catch (_: Throwable) { continue }
            val cle = creerCleCameraCiblee(champ) ?: continue
            try {
                val km = KeyManager.getInstance()
                val ms = km.javaClass.methods.firstOrNull {
                    it.name == "setValue" && it.parameterTypes.size == 3
                } ?: continue
                ms.invoke(km, cle, valeur, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i("PontDjiReelCockpit", "format photo = ${(valeur as Enum<*>).name}")
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        Log.w("PontDjiReelCockpit", "format photo refusé : ${error.description()}")
                    }
                })
                return "format photo demandé : ${(valeur as Enum<*>).name} (clé $nomCle)"
            } catch (e: Throwable) {
                Log.w("PontDjiReelCockpit", "format photo : ${e.message}")
            }
        }
        return "format photo NON réglé : aucune clé exploitable dans ce SDK"
    }

    /**
     * Résumé des réglages RÉELLEMENT appliqués dans la caméra, lus au drone.
     *
     * POURQUOI. Ces réglages viennent de la caméra, pas de l'app : ils survivent aux
     * redémarrages et à un passage par une autre application. Les photos du 2026-07-27
     * sortaient en 16:9 — un quart de la hauteur du capteur jeté — depuis des semaines,
     * sans que rien ne l'affiche nulle part. Un pilote ne peut pas corriger ce qu'il ne
     * voit pas.
     *
     * ⚠ On lit, on n'écrit RIEN ici. Et une clé absente n'est pas une erreur : chaque
     * drone expose ce qu'il veut. Ce qui manque est simplement omis du résumé, jamais
     * remplacé par une valeur supposée.
     */
    fun resumeReglagesCamera(): Map<String, String> {
        val res = LinkedHashMap<String, String>()
        // Libellé -> noms de clé acceptés, du plus précis au plus général.
        val aLire = listOf(
            "format" to listOf("KeyPhotoRatio", "KeyPhotoAspectRatio"),
            "taille photo" to listOf("KeyPhotoSize", "KeyPhotoFileFormat"),
            "vidéo" to listOf("KeyVideoResolutionFrameRate"),
            "mode" to listOf("KeyCameraMode"),
            "couleur" to listOf("KeyCameraColor", "KeyVideoColorMode"),
        )
        for ((libelle, noms) in aLire) {
            for (n in noms) {
                val v = lireCameraBrutCible(n)
                if (!v.isNullOrBlank()) { res[libelle] = lisible(v); break }
            }
        }
        return res
    }

    /** Rend un nom d'énum SDK présentable : `RATIO_16_COLON_9` -> `16:9`. */
    private fun lisible(brut: String): String {
        var s = brut.removePrefix("RATIO_").removePrefix("RESOLUTION_")
            .removePrefix("RATE_").removePrefix("PHOTO_").removePrefix("SIZE_")
        s = s.replace("_COLON_", ":").replace("FPS", " im/s").replace('_', ' ')
        return s.trim()
    }

    private fun lireCameraBrutCible(keyName: String): String? {
        return try {
            val champ = dji.sdk.keyvalue.key.CameraKey::class.java.getField(keyName).get(null)
            val cle = creerCleCameraCiblee(champ) ?: return null
            val km = KeyManager.getInstance()
            val mg = km.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 }
            val v = mg?.invoke(km, cle) ?: return null
            (v as? Enum<*>)?.name ?: v.toString()
        } catch (_: Throwable) { null }
    }

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

