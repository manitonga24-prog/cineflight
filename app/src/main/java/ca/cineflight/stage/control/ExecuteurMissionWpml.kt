package ca.cineflight.stage.control

import dji.v5.common.callback.CommonCallbacks
import android.content.Context
import ca.cineflight.stage.R
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * ExecuteurMissionWpml — exécute une mission de waypoints sur le VRAI drone
 * (DJI MSDK v5.10, API vérifiée via javap sur le SDK installé).
 *
 * Le serveur CineFlight génère un KMZ contenant le WPML (wpmz/waylines.wpml).
 * MSDK v5 l'exécute nativement via WaypointMissionManager :
 *   1. pushKMZFileToAircraft(path, callbackProgress)  -> upload
 *   2. startMission(name, callback)                   -> lancement autonome
 *   3. WaylineExecutingInfoListener                   -> waypoint courant
 *   4. WaypointMissionExecuteStateListener            -> état (FINISHED/INTERRUPTED)
 *
 * SÉCURITÉ — l'appelant DOIT garantir avant d'appeler executer() :
 *   - connexion drone + RC OK, GPS fixe
 *   - vérification batterie/distance passée (MissionCompleteBuilder)
 *   - confirmation pilote explicite
 *   - RTH disponible, pilote prêt à l'arrêt d'urgence
 *   - testé d'abord en SIMULATEUR DJI avant tout vol réel
 */
object ExecuteurMissionWpml {

    enum class Phase { INACTIF, UPLOAD, EN_VOL, TERMINE, ERREUR, INTERROMPU }

    @Volatile var phase: Phase = Phase.INACTIF
        private set

    private var appCtx: Context? = null
    private var nomMission: String = ""
    private var waypointCourant: Int = 0

    // --- Surveillance perception en vol (option 1 : surveiller + alerter) ---
    // OFF par defaut : aucun effet tant que non active ET non calibre sur le terrain.
    @Volatile var surveillancePerceptionActive: Boolean = false
    private var lecteurPerception: ca.cineflight.stage.sentinelle.LecteurPerception? = null
    private var cbAlerteObstacle: ((Int) -> Unit)? = null   // (distance horizontale m)
    private var dernierAlerteMs: Long = 0L
    private val SEUIL_ALERTE_M = 5          // alerte si obstacle horizontal < 5 m (a calibrer)
    private val ANTI_SPAM_MS = 3000L        // au plus une alerte / 3 s

    private var ecouteurEtat: WaypointMissionExecuteStateListener? = null
    private var ecouteurInfo: WaylineExecutingInfoListener? = null

    private var cbProgres: ((Int) -> Unit)? = null
    private var cbTermine: ((Boolean, String) -> Unit)? = null

    // delai d'attente de l'upload : evite un blocage infini sur "Envoi au drone..."
    private val TIMEOUT_UPLOAD_MS = 15000L
    private val handlerDelai = Handler(Looper.getMainLooper())
    private var delaiUpload: Runnable? = null

    private val mgr get() = WaypointMissionManager.getInstance()

    /**
     * Uploade puis lance la mission KMZ sur le drone.
     * @param kmz        fichier KMZ (avec WPML) généré par le serveur.
     * @param onProgres  (waypoint courant) pendant le vol.
     * @param onTermine  (succès, message) à la fin / échec / interruption.
     */
    fun executer(
        ctx: Context,
        kmz: File,
        onProgres: (Int) -> Unit,
        onTermine: (Boolean, String) -> Unit
    ) {
        if (!kmz.exists()) { onTermine(false, ctx.getString(R.string.exw_fichier_introuvable, kmz.absolutePath)); return }
        if (phase == Phase.UPLOAD || phase == Phase.EN_VOL) {
            onTermine(false, ctx.getString(R.string.exw_deja_en_cours)); return
        }

        appCtx = ctx.applicationContext
        nomMission = kmz.nameWithoutExtension
        waypointCourant = 0
        cbProgres = onProgres
        cbTermine = onTermine
        phase = Phase.UPLOAD

        enregistrerEcouteurs()
        armerDelaiUpload()

        // 1) UPLOAD du KMZ au drone
        mgr.pushKMZFileToAircraft(kmz.absolutePath,
            object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) { armerDelaiUpload() }
                override fun onSuccess() { armerDelaiUpload(); lancer() }
                override fun onFailure(error: IDJIError) {
                    phase = Phase.ERREUR
                    finir(false, ctx.getString(R.string.exw_echec_envoi, error.description()))
                }
            })
    }

    private fun lancer() {
        mgr.startMission(nomMission, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { annulerDelaiUpload(); phase = Phase.EN_VOL; demarrerSurveillance() }
            override fun onFailure(error: IDJIError) {
                phase = Phase.ERREUR
                finir(false, appCtx?.getString(R.string.exw_echec_lancement, error.description()) ?: error.description())
            }
        })
    }

    private fun enregistrerEcouteurs() {
        // progression : waypoint courant
        val info = object : WaylineExecutingInfoListener {
            override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo) {
                waypointCourant = info.currentWaypointIndex
                cbProgres?.invoke(waypointCourant)
            }
            override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
                if (error != null) {
                    phase = Phase.INTERROMPU
                    finir(false, appCtx?.getString(R.string.exw_interruption, error.description()) ?: error.description())
                }
            }
        }
        ecouteurInfo = info
        mgr.addWaylineExecutingInfoListener(info)

        // état : détecter FINISHED / INTERRUPTED
        val etat = object : WaypointMissionExecuteStateListener {
            override fun onMissionStateUpdate(state: WaypointMissionExecuteState?) {
                when (state) {
                    WaypointMissionExecuteState.FINISHED -> {
                        phase = Phase.TERMINE
                        finir(true, appCtx?.getString(R.string.exw_terminee) ?: "")
                    }
                    WaypointMissionExecuteState.INTERRUPTED -> {
                        phase = Phase.INTERROMPU
                        finir(false, appCtx?.getString(R.string.exw_interrompue_drone) ?: "")
                    }
                    else -> { /* READY, EXECUTING, RETURN_TO_START_POINT... : en cours */ }
                }
            }
        }
        ecouteurEtat = etat
        mgr.addWaypointMissionExecuteStateListener(etat)
    }

    private fun armerDelaiUpload() {
        annulerDelaiUpload()
        val r = Runnable {
            if (phase == Phase.UPLOAD) {
                phase = Phase.ERREUR
                finir(false, appCtx?.getString(R.string.exw_timeout_upload) ?: "")
            }
        }
        delaiUpload = r
        handlerDelai.postDelayed(r, TIMEOUT_UPLOAD_MS)
    }

    private fun annulerDelaiUpload() {
        delaiUpload?.let { handlerDelai.removeCallbacks(it) }
        delaiUpload = null
    }

    private fun finir(succes: Boolean, message: String) {
        annulerDelaiUpload()
        arreterSurveillance()
        val cb = cbTermine
        nettoyerEcouteurs()
        cbTermine = null
        cbProgres = null
        cb?.invoke(succes, message)
    }

    private fun nettoyerEcouteurs() {
        ecouteurInfo?.let { mgr.removeWaylineExecutingInfoListener(it) }
        ecouteurEtat?.let { mgr.removeWaypointMissionExecuteStateListener(it) }
        ecouteurInfo = null
        ecouteurEtat = null
    }

    /** Interrompt la mission (le drone hover ; déclencher RTH séparément si besoin). */
    // --- Surveillance perception : lecture seule, alerte uniquement, NE TOUCHE PAS au vol ---
    private fun demarrerSurveillance() {
        if (!surveillancePerceptionActive) return
        val l = ca.cineflight.stage.sentinelle.LecteurPerception()
        l.demarrer()
        lecteurPerception = l
        Thread {
            while (phase == Phase.EN_VOL && surveillancePerceptionActive) {
                try {
                    val d = lecteurPerception?.distanceHorizontale()
                    if (d != null && d < SEUIL_ALERTE_M) {
                        val maintenant = System.currentTimeMillis()
                        if (maintenant - dernierAlerteMs > ANTI_SPAM_MS) {
                            dernierAlerteMs = maintenant
                            android.util.Log.w("ExecuteurMission", "ALERTE obstacle horizontal ${d}m (waypoint $waypointCourant)")
                            cbAlerteObstacle?.invoke(d)
                        }
                    }
                    Thread.sleep(200)
                } catch (e: Throwable) { break }
            }
        }.start()
    }

    private fun arreterSurveillance() {
        lecteurPerception?.arreter()
        lecteurPerception = null
    }

    /** Definit le callback d'alerte obstacle (optionnel). Appeler avant executer(). */
    fun definirAlerteObstacle(cb: (Int) -> Unit) { cbAlerteObstacle = cb }

    fun interrompre(onFini: (Boolean, String) -> Unit) {
        if (phase != Phase.EN_VOL && phase != Phase.UPLOAD) {
            onFini(false, "Aucune mission en cours."); return
        }
        mgr.stopMission(nomMission, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                phase = Phase.INTERROMPU
                onFini(true, "Mission interrompue.")
            }
            override fun onFailure(error: IDJIError) {
                onFini(false, "Échec interruption : ${error.description()}")
            }
        })
    }

    fun pause(onFini: (Boolean) -> Unit) {
        mgr.pauseMission(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = onFini(true)
            override fun onFailure(error: IDJIError) = onFini(false)
        })
    }

    fun reprendre(onFini: (Boolean) -> Unit) {
        mgr.resumeMission(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = onFini(true)
            override fun onFailure(error: IDJIError) = onFini(false)
        })
    }
}

