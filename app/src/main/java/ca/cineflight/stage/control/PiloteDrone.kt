package ca.cineflight.stage.control

import android.util.Log
import android.content.Context
import ca.cineflight.stage.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Commande en REPERE CORPS (deja traduite pour DJI) : pitch/roll/throttle en m/s
 * (Virtual Stick VELOCITY), yaw en deg/s. DISTINCTE de CommandeBridge (repere
 * SCENE vx/vy/vz) pour interdire toute confusion d'unite ou de repere.
 */
data class CommandeCorps(
    val pitch: Float,
    val roll: Float,
    val throttle: Float,
    val yawRateDegS: Float
) {
    companion object {
        fun hover() = CommandeCorps(0f, 0f, 0f, 0f)
    }
}

/** Motif d'une commande de securite (priorite absolue sur le suivi normal). */
enum class RaisonSecurite {
    MONTEE_ASSISTEE, BALAYAGE_360, STOP_PILOTE, INTRUSION,
    ERREUR_SYSTEME, PLAFOND, CAPTEUR_INVALIDE
}

/**
 * PiloteDrone — pilote le DJI Mini 3 via le Virtual Stick (MSDK v5).
 *
 * RÔLE :
 *   - active/désactive le mode Virtual Stick,
 *   - envoie les commandes à cadence fixe (15 Hz) au drone,
 *   - applique la SÉCURITÉ propre à l'app (en plus des 5 filets du bridge) :
 *       * timeout : si le bridge cesse d'émettre, on passe en HOVER (zéro),
 *       * arrêt d'urgence : hover immédiat + sortie du Virtual Stick,
 *       * bornage de garde : on plafonne les vitesses même si une commande
 *         aberrante passait (défense en profondeur).
 *
 * IMPORTANT — INTÉGRATION DJI MSDK v5 :
 *   Les appels au SDK sont ISOLÉS derrière l'interface PontDji ci-dessous.
 *   Cela permet (a) de tester la logique sans le SDK, (b) de remplacer
 *   facilement par les vrais appels MSDK v5 dans PontDjiReel. Les vrais appels
 *   utilisent VirtualStickManager :
 *     - setVirtualStickModeEnabled(true/false)
 *     - sendVirtualStickAdvancedParam(VirtualStickFlightControlParam(...))
 *   avec un coordinate system en VITESSE (VELOCITY) et un control mode
 *   adéquat. Le mapping des champs est fait par TraductionAxes.
 *
 * Le cap courant du drone (yaw réel) doit être fourni en continu par le SDK
 * (KeyAircraftAttitude / compass heading) pour la rotation des axes ; à défaut
 * on utilise 0 (drone supposé aligné scène) — à raffiner sur matériel.
 *
 * AJOUT EXTÉRIEUR (Explorer) : l'interface expose maintenant la position GPS du
 * drone (latitude/longitude/altitude AGL + validité). Le pilotage lui-même NE
 * change PAS : le pilote reçoit toujours des vitesses déjà tournées. Le GPS sert
 * uniquement à être RÉ-ÉMIS vers le contrôleur (option A : EmetteurGps lit ces
 * getters et envoie en UDP). Le pont reste le seul à toucher le SDK.
 *
 * AJOUT CAMÉRA/NACELLE : quand une commande active porte un cadrage (calculé par
 * le contrôleur pour pointer la caméra vers la cible), le pilote applique
 * l'orientation de nacelle et pilote l'enregistrement. Ne s'applique qu'en vol
 * actif : en hover/stop/timeout, on ne touche pas à la nacelle.
 */
class PiloteDrone(
    private val ctx: Context,
    private val pont: PontDji,
    private val timeoutHoverMs: Long = 400L,   // > période bridge (66 ms), < 0.5 s
    private val vMaxGardeMps: Float = 2.0f,    // plafond de garde (défense profonde)
    private val yawMaxGardeDps: Float = 60f
) {
    /** Interface isolant les appels DJI MSDK v5 (testable / remplaçable). */
    interface PontDji {
        fun activerVirtualStick(actif: Boolean)
    // true seulement quand le Virtual Stick est REELLEMENT actif (onSuccess DJI).
    // Defaut true : les ponts simules/anciens sont consideres prets immediatement.
    fun virtualStickConfirmeActif(): Boolean = true
        // origin : origine de la commande (ObstacleSafetyGate étape 0). Défaut UNKNOWN =
        // NON-permissif (jamais traité comme MANUAL par défaut). Chaque appelant DOIT
        // déclarer son origine ; UNKNOWN est un état d'erreur interdit après migration.
        fun envoyerVitesses(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                            origin: CommandOrigin = CommandOrigin.UNKNOWN)
        fun decoller(onFini: (Boolean) -> Unit)
        fun atterrir(onFini: (Boolean) -> Unit)
        fun capDroneDeg(): Float          // yaw courant du drone (repère scène)
        fun batteriePourcent(): Int
        fun estConnecte(): Boolean
        // --- GPS (vol extérieur / Explorer) ---
        // Valeurs poussées en continu par le SDK (KeyAircraftLocation / KeyAltitude).
        // Convention "pas de fix" : NaN (et non 0.0, qui est une vraie coordonnée).
        fun latitudeDrone(): Double       // degrés décimaux WGS84 (NaN si pas de fix)
        fun longitudeDrone(): Double      // degrés décimaux WGS84 (NaN si pas de fix)
        fun altitudeDrone(): Double       // mètres, altitude RELATIVE au décollage (AGL)
        fun gpsValide(): Boolean          // true si fix GPS suffisant pour piloter
        // --- CAMÉRA / NACELLE (cadrage cinématographique) ---
        // orienterNacelle : pitch (négatif = vers le bas) et yaw en degrés.
        //   yawAbsolu = true  -> yaw exprimé dans le repère monde (cap absolu)
        //   yawAbsolu = false -> yaw relatif au nez du drone
        fun orienterNacelle(pitchDeg: Float, yawDeg: Float, yawAbsolu: Boolean)
        fun demarrerEnregistrement()      // démarre l'enregistrement vidéo
        fun arreterEnregistrement()       // arrête l'enregistrement vidéo
        fun enregistreEnCours(): Boolean  // true si la caméra enregistre
        fun modeleDrone(): String         // nom du modele de drone connecte
    }

    // ===== CRENEAUX DE COMMANDE (arbitrage : securite > normal) =====
    private val commandeNormale = AtomicReference<RecepteurBridge.CommandeBridge?>(null)
    private val commandeSecurite = AtomicReference<CommandeSecurite?>(null)
    // Proprietaire du controle securite. AtomicLong (pas AtomicReference<Long?>) :
    // compareAndSet compare la VALEUR long, pas l'identite de reference (les Long
    // boxes > 127 ne sont pas caches -> l'identite echouerait). 0L = aucun proprietaire.
    private val proprietaireSecurite = AtomicLong(0L)

    // Generation de controle : incrementee a chaque arret / prise de securite.
    // Un tick ayant deja lu sa sortie la revoit changee -> n'emet pas.
    private val generationControle = AtomicLong(0L)
    // Verrou tres court partage entre l'emission SDK du tick et les arrets.
    private val verrouEmissionSdk = Any()
    // Serialise l'arret synchrone (cancelAndJoin) de la boucle.
    private val mutexCycleVie = Mutex()

    // Crochet de TEST uniquement (null en production, aucun effet) : invoque
    // dans tick() juste avant le verrou d'emission, pour rendre deterministe la
    // course tick <-> arret (verrouEmissionSdk + generationControle).
    internal var avantVerrouEmission: (() -> Unit)? = null

    // HOOK D'OBSERVATION (Phase vocale). Nul par defaut : PiloteDrone ne depend
    // PAS de la voix. Invoque a la FIN de arretUrgence() (fait accompli), avec
    // ?.invoke() blinde -> aucun impact sur la securite du pilotage.
    var obsArretUrgence: (() -> Unit)? = null

    // HOOKS Phase 3A (watchdog VS). Nuls par defaut. Appeles avec ?.invoke() blinde,
    // JAMAIS attendus -> aucun impact sur la boucle de pilotage. Edge-triggered :
    // la transition est detectee ici (etat interne), le hook ne recoit que le booleen.
    var obsWatchdogTimeout: ((Boolean) -> Unit)? = null   // true = hover force (timeout)
    var obsVsNonConfirme: ((Boolean) -> Unit)? = null     // true = emet mais VS pas confirme
    @Volatile private var wdTimeoutPrecedent = false
    @Volatile private var wdNonConfirmePrecedent = false

    @Volatile private var actif = false
    @Volatile var enVol = false
        private set
    @Volatile var dernierEtat: String = ctx.getString(R.string.pil_au_sol)
        private set

    private var boucle: Job? = null

    /** Commande de securite horodatee (nanoTime monotone) + session proprietaire. */
    private data class CommandeSecurite(
        val sessionId: Long,
        val axes: CommandeCorps,
        val raison: RaisonSecurite,
        val recueANanos: Long
    )

    /** Sortie decidee par le tick : repere SCENE (via versDji) ou CORPS (direct). */
    private sealed interface SortieDrone {
        data class Scene(val commande: RecepteurBridge.CommandeBridge) : SortieDrone
        data class Corps(val commande: CommandeCorps) : SortieDrone
    }

    /** Recoit une commande NORMALE (suivi YOLO, reseau, manuel, mission). */
    fun soumettre(cmd: RecepteurBridge.CommandeBridge) {
        commandeNormale.set(cmd)
    }

    // ---------- Controle de SECURITE (Sentinelle / noyau) : override prioritaire ----------

    /** Prend le controle de securite (une seule session a la fois). Efface la
     *  commande normale : aucune ancienne consigne ne survit a la session. */
    fun prendreControleSecurite(sessionId: Long): Boolean {
        val acquis = proprietaireSecurite.compareAndSet(0L, sessionId)
        if (acquis) {
            commandeNormale.set(null)
            commandeSecurite.set(null)
            generationControle.incrementAndGet()
        }
        return acquis
    }

    /** Soumet une manoeuvre de securite (repere corps). Ignoree (false) si
     *  l'appelant n'est pas le proprietaire courant. */
    fun soumettreSecurite(sessionId: Long, axes: CommandeCorps, raison: RaisonSecurite): Boolean {
        if (proprietaireSecurite.get() != sessionId) return false
        commandeSecurite.set(CommandeSecurite(sessionId, axes, raison, System.nanoTime()))
        return true
    }

    /** Libere le controle de securite. Efface AUSSI la commande normale : la
     *  reprise du suivi exige une nouvelle consigne (jamais une ancienne). */
    fun libererSecurite(sessionId: Long): Boolean {
        if (!proprietaireSecurite.compareAndSet(sessionId, 0L)) return false
        commandeSecurite.updateAndGet { c -> if (c?.sessionId == sessionId) null else c }
        commandeNormale.set(null)
        return true
    }

    /** Active le Virtual Stick et démarre la boucle d'émission à 15 Hz. */
    fun demarrer(scope: CoroutineScope) {
        if (actif) return
        actif = true
        pont.activerVirtualStick(true)
        dernierEtat = ctx.getString(R.string.pil_vs_actif)
        boucle = scope.launch(Dispatchers.Default) {
            val periodeMs = 1000L / 15L          // 15 Hz
            while (isActive && actif) {
                val t0 = System.currentTimeMillis()
                tick(t0)
                val reste = periodeMs - (System.currentTimeMillis() - t0)
                if (reste > 0) delay(reste)
            }
        }
    }

    /** Un tick : choisit une sortie (securite > normal), puis l'emet SOUS VERROU
     *  avec re-verification de la generation -> neutralise un tick deja engage
     *  au moment d'un arret d'urgence. */
    private fun tick(maintenant: Long) {
        val gen = generationControle.get()
        val sortie = choisirSortie(maintenant)
        avantVerrouEmission?.invoke()   // crochet de test (no-op en prod)
        synchronized(verrouEmissionSdk) {
            if (!actif || generationControle.get() != gen) return
            envoyerSortie(sortie)
        }
        // OBSERVATION Phase 3A (watchdog) : detecte, en bordure (transition), si la boucle
        // vient de passer en HOVER par timeout (aucune commande fraiche cote NORMAL) et si
        // le VS n'est pas confirme actif alors qu'on pilote. Purement observationnel.
        observerWatchdogInterne(maintenant)
    }

    /** Detecte les transitions watchdog SANS influencer le pilotage. Appele 15x/s mais
     *  les hooks ne sont invoques qu'au CHANGEMENT d'etat (edge-triggered). */
    private fun observerWatchdogInterne(maintenant: Long) {
        try {
            // On ne juge le watchdog que sur le chemin NORMAL (pas quand la securite tient).
            val enSecurite = proprietaireSecurite.get() != 0L
            val cmd = commandeNormale.get()
            val timeout = !enSecurite &&
                (cmd == null || (maintenant - cmd.recuA) > timeoutHoverMs || cmd.mode != "actif")
            if (timeout != wdTimeoutPrecedent) {
                wdTimeoutPrecedent = timeout
                try { obsWatchdogTimeout?.invoke(timeout) } catch (_: Throwable) {}
            }
            // VS non confirme : on emet (actif) mais le pont ne confirme pas le VS.
            val nonConfirme = actif && !pont.virtualStickConfirmeActif()
            if (nonConfirme != wdNonConfirmePrecedent) {
                wdNonConfirmePrecedent = nonConfirme
                try { obsVsNonConfirme?.invoke(nonConfirme) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Arbitrage. La securite PROPRIETAIRE prime toujours ; une consigne de
     *  securite PERIMEE donne un HOVER SANS liberer la propriete (jamais de
     *  reprise auto du suivi). Sans propriete de securite : chemin NORMAL,
     *  identique au comportement historique. */
    private fun choisirSortie(maintenant: Long): SortieDrone {
        val session = proprietaireSecurite.get()
        if (session != 0L) {
            val s = commandeSecurite.get()
            val fraiche = s != null && s.sessionId == session &&
                (System.nanoTime() - s.recueANanos) <= TIMEOUT_SECURITE_NS
            return if (fraiche) {
                val cmd = s!!
                dernierEtat = ctx.getString(R.string.pil_securite, cmd.raison.toString())
                SortieDrone.Corps(cmd.axes)
            } else {
                dernierEtat = ctx.getString(R.string.pil_securite_hover)
                SortieDrone.Corps(CommandeCorps.hover())
            }
        }
        // Pas de securite : chemin NORMAL (timeout -> hover), inchange.
        val cmd = commandeNormale.get()
        // frais inline : cmd == null devient le 1er test (non redondant) -> pas
        // d'avertissement "always false", et il permet le smart-cast de cmd.
        return if (cmd == null || (maintenant - cmd.recuA) > timeoutHoverMs || cmd.mode != "actif") {
            dernierEtat = if (cmd == null || (maintenant - cmd.recuA) > timeoutHoverMs) ctx.getString(R.string.pil_hover_timeout) else ctx.getString(R.string.pil_hover_stop)
            SortieDrone.Corps(CommandeCorps.hover())
        } else {
            dernierEtat = ctx.getString(R.string.pil_vol_actif)
            SortieDrone.Scene(cmd)
        }
    }

    /** Emission. Bornage de garde applique aux DEUX sorties. versDji UNIQUEMENT
     *  pour SCENE ; CORPS est deja en repere drone -> envoye direct (memes valeurs
     *  que l'ancien chemin Sentinelle, memes unites, memes signes). */
    private fun envoyerSortie(sortie: SortieDrone) {
        when (sortie) {
            is SortieDrone.Scene -> {
                val cmd = sortie.commande
                val vx = cmd.vx.coerceIn(-vMaxGardeMps, vMaxGardeMps)
                val vy = cmd.vy.coerceIn(-vMaxGardeMps, vMaxGardeMps)
                val vz = cmd.vz.coerceIn(-vMaxGardeMps, vMaxGardeMps)
                val yawRate = cmd.yawRate.coerceIn(-yawMaxGardeDps, yawMaxGardeDps)
                val dji = TraductionAxes.versDji(vx, vy, vz, yawRate, pont.capDroneDeg())
                emettreSdk(dji.pitch, dji.roll, dji.verticalThrottle, dji.yaw)
                // Cadrage nacelle + enregistrement : uniquement en vol actif scene.
                if (cmd.cadrage) pont.orienterNacelle(cmd.gimbalPitch, cmd.gimbalYaw, true)
                if (cmd.rec && !pont.enregistreEnCours()) pont.demarrerEnregistrement()
                else if (!cmd.rec && pont.enregistreEnCours()) pont.arreterEnregistrement()
            }
            is SortieDrone.Corps -> {
                val a = bornerCorps(sortie.commande)
                emettreSdk(a.pitch, a.roll, a.throttle, a.yawRateDegS)
            }
        }
    }

    /** Bornage de garde en repere corps (memes plafonds que le chemin scene). */
    private fun bornerCorps(c: CommandeCorps): CommandeCorps = CommandeCorps(
        pitch = c.pitch.coerceIn(-vMaxGardeMps, vMaxGardeMps),
        roll = c.roll.coerceIn(-vMaxGardeMps, vMaxGardeMps),
        throttle = c.throttle.coerceIn(-vMaxGardeMps, vMaxGardeMps),
        yawRateDegS = c.yawRateDegS.coerceIn(-yawMaxGardeDps, yawMaxGardeDps)
    )

    /** UNIQUE appel a pont.envoyerVitesses de tout PiloteDrone. Deux durcissements
     *  au point d'emission unique :
     *   (1) valeurs non finies (NaN / +-Infinity) forcees a 0 avant le SDK
     *       (coerceIn NE corrige PAS NaN) -> jamais de NaN transmis au drone ;
     *   (2) toute exception SDK est capturee : une erreur d'emission ne doit ni
     *       tuer la boucle 15 Hz ni remonter. Le bloc synchronized libere de toute
     *       facon le moniteur en cas d'exception ; on hover au prochain tick. */
    private fun emettreSdk(pitch: Float, roll: Float, throttle: Float, yaw: Float,
                           origin: CommandOrigin = CommandOrigin.AUTOMATIC) {
        // DIAGNOSTIC PASSIF (off par defaut) : derniere trace avant le SDK. Non bloquant,
        // jamais d'exception remontante, aucune modification des valeurs envoyees.
        try {
            if (ca.cineflight.stage.sentinelle.PerceptionDiagLogger.actif) {
                ca.cineflight.stage.sentinelle.PerceptionDiagLogger.sdkCommandFinal(
                    cycleId = ca.cineflight.stage.sentinelle.PerceptionDiagLogger.nouveauCycleId(),
                    vxFinal = pitch, vyFinal = roll, vzFinal = throttle, yawFinal = yaw,
                    coordinateSystem = "DJI_BODY_VELOCITY(pitch,roll,throttle,yaw)"
                )
            }
        } catch (_: Throwable) {}
        try {
            // finiOuZero conservé ici (défense en profondeur) ; l'assainissement universel
            // est de toute façon ré-appliqué dans PontDjiReel.envoyerVitesses (étape 0).
            pont.envoyerVitesses(pitch.finiOuZero(), roll.finiOuZero(), throttle.finiOuZero(), yaw.finiOuZero(), origin)
        } catch (e: Exception) {
            Log.w("PiloteDrone", "envoyerVitesses a echoue (ignore, hover au prochain tick): ${e.message}")
        }
    }

    /** Force les valeurs non finies (NaN, +-Infinity) a 0 : jamais de NaN au SDK. */
    private fun Float.finiOuZero(): Float = if (isFinite()) this else 0f

    fun decoller(onFini: (Boolean) -> Unit) {
        pont.decoller { ok ->
            enVol = ok
            dernierEtat = if (ok) ctx.getString(R.string.pil_en_vol_hover) else ctx.getString(R.string.pil_echec_decollage)
            onFini(ok)
        }
    }

    fun atterrir(onFini: (Boolean) -> Unit) {
        synchronized(verrouEmissionSdk) { emettreSdk(0f, 0f, 0f, 0f) }
        pont.atterrir { ok ->
            enVol = !ok && enVol
            dernierEtat = if (ok) ctx.getString(R.string.pil_atterri) else ctx.getString(R.string.pil_echec_atterrissage)
            onFini(ok)
        }
    }

    /** ARRÊT D'URGENCE : hover immédiat + sortie Virtual Stick (le drone reste
     *  en l'air en hover, pilotable à la télécommande). NE coupe PAS les moteurs. */
    fun arretUrgence() {
        actif = false
        generationControle.incrementAndGet()          // neutralise tout tick deja engage
        commandeNormale.set(null)
        commandeSecurite.set(null)
        proprietaireSecurite.set(0L)
        boucle?.cancel(); boucle = null
        synchronized(verrouEmissionSdk) {
            try { emettreSdk(0f, 0f, 0f, 0f) } catch (_: Exception) {}   // hover final
        }
        try { pont.activerVirtualStick(false) } catch (_: Exception) {}
        dernierEtat = ctx.getString(R.string.pil_arret_urgence)
        Log.w("PiloteDrone", "Arrêt d'urgence déclenché")
        try { obsArretUrgence?.invoke() } catch (_: Throwable) {}
    }

    /** Arret NON bloquant (compat existant) : meme teardown synchrone, sans join. */
    fun arreter() {
        actif = false
        generationControle.incrementAndGet()
        commandeNormale.set(null)
        commandeSecurite.set(null)
        proprietaireSecurite.set(0L)
        boucle?.cancel(); boucle = null
        synchronized(verrouEmissionSdk) {
            try { emettreSdk(0f, 0f, 0f, 0f) } catch (_: Exception) {}
        }
        try { pont.activerVirtualStick(false) } catch (_: Exception) {}
        dernierEtat = ctx.getString(R.string.pil_arrete)
    }

    /** Arret SYNCHRONE (transitions de mode) : ATTEND la fin effective de la
     *  boucle (cancelAndJoin) sous mutex de cycle de vie, avant de rendre la main.
     *  Empeche qu'un ancien tick soit encore actif quand un nouveau mode prend le
     *  controle. Reservee a GestionnaireModeVol (etape de cablage ulterieure). */
    suspend fun arreterEtAttendre() {
        mutexCycleVie.withLock {
            actif = false
            generationControle.incrementAndGet()
            val job = boucle
            boucle = null
            job?.cancelAndJoin()
            commandeNormale.set(null)
            commandeSecurite.set(null)
            proprietaireSecurite.set(0L)
            synchronized(verrouEmissionSdk) {
                try { emettreSdk(0f, 0f, 0f, 0f) } catch (_: Exception) {}
            }
            try { pont.activerVirtualStick(false) } catch (_: Exception) {}
            dernierEtat = ctx.getString(R.string.pil_arrete_sync)
        }
    }

    companion object {
        // INVARIANT : TIMEOUT_SECURITE_NS >= 3 x periode boucle Sentinelle (~100 ms)
        // + marge jitter Android. 400 ms, coherent avec timeoutHoverMs normal.
        // NE PAS reduire sans revoir la cadence de la Sentinelle.
        const val TIMEOUT_SECURITE_NS = 400_000_000L
    }
}

