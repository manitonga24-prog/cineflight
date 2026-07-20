package ca.cineflight.stage.voice

/**
 * PHASE 2 — Adaptateur d'observation DJI (Lot 2A : Virtual Stick).
 *
 * Recoit des NOTIFICATIONS depuis le code de pilotage (via des hooks optionnels)
 * et les transforme en evenements vocaux. Il ne contient AUCUN appel DJI, AUCUNE
 * decision de pilotage, AUCUNE attente. Chaque methode est blindee (runCatching) :
 * meme si la voix echoue, le callback DJI appelant continue normalement.
 *
 * Respecte le flag settings.observationDji : si false, rien n'est publie.
 *
 * Gere la logique de TRANSITION (false->true une seule fois) et le CONTEXTE
 * (desactivation voulue vs perte inattendue) sans jamais intervenir dans le vol.
 */
class DjiVoiceObservationAdapter(
    private val systeme: FlightVoiceSystem
) {
    // contexte d'observation (pas du pilotage) : dernier etat VS confirme connu.
    @Volatile private var vsConfirmePrecedent = false
    // true = une desactivation a ete DEMANDEE (STOP/atterrissage) -> la prochaine
    // transition true->false est VOULUE, pas une perte.
    @Volatile private var desactivationDemandee = false
    @Volatile private var avertObstacleFait = false

    // ---- PHASE 3 : contexte d'observation des etats critiques (pas du pilotage) ----
    // Seuils batterie (avec hysteresis anti-chatter). Modifiables si besoin.
    @Volatile private var seuilBatFaible = 30
    @Volatile private var seuilBatCritique = 15
    private val hysterese = 3   // marge de remontee avant d'annoncer "retablie"
    // Niveau batterie : 0 = OK, 1 = faible, 2 = critique. -1 = inconnu.
    @Volatile private var niveauBatPrecedent = -1
    // Etats de liaison / GPS connus (Boolean? pour distinguer "inconnu" du 1er passage).
    @Volatile private var droneConnecteConnu: Boolean? = null
    @Volatile private var rcConnecteConnu: Boolean? = null
    @Volatile private var gpsOkConnu: Boolean? = null
    @Volatile private var seuilSatMin = 8   // aligne sur PontDji.SAT_MIN

    // ---- PHASE 3A : etats de maitrise du drone ----
    @Volatile private var watchdogActifPrecedent = false   // true = timeout en cours annonce
    @Volatile private var vsNonConfirmePrecedent = false
    @Volatile private var homeConnu: Boolean? = null       // true=confirme, false=non confirme
    @Volatile private var linkCmdNiveauPrecedent = 0       // 0=ok,1=faible,2=critique
    @Volatile private var linkVideoNiveauPrecedent = 0     // 0=ok,1=faible,2=perdue
    @Volatile private var rthPhasePrecedente = 0           // 0=aucune,1=montee,2=retour,3=descente,4=termine
    // Seuils signal (0..100). En dessous = faible ; en dessous du critique = critique.
    @Volatile var seuilLinkFaible = 40
    @Volatile var seuilLinkCritique = 15
    @Volatile var seuilVideoFaible = 30

    // ---- PHASE 3B : camera / stockage ----
    @Volatile private var sdNiveauPrecedent = -1   // 0=absente,1=pleine,2=presque pleine,3=ok
    @Volatile private var cameraDispoPrecedent: Boolean? = null
    @Volatile private var horsCadrePrecedent = false
    @Volatile var seuilSdPresquePleinMin = 3   // minutes restantes -> "presque pleine"

    private fun actif(): Boolean = try {
        systeme.settings.active && systeme.settings.observationDji
    } catch (_: Throwable) { false }

    private fun now() = System.currentTimeMillis()

    private fun pub(e: FlightVoiceEvent) {
        try { systeme.publishVoiceEventSafely(e, now()) } catch (_: Throwable) {}
    }

    // ---------- VIRTUAL STICK ----------

    /** Callback onSuccess de enableVirtualStick (accepte, PAS encore actif). */
    fun observerVsEnableAccepte() {
        runCatching { if (actif()) pub(VoiceEvents.vsEnableAccepted(now())) }
    }

    /** Callback onFailure de enableVirtualStick (refuse). */
    fun observerVsEnableRefuse() {
        runCatching { if (actif()) pub(VoiceEvents.vsEnableRejected(now())) }
    }

    /**
     * A appeler quand l'etat REEL vsActifConfirme est (re)lu. Detecte les transitions.
     * false->true : "Controle automatique active" + avert obstacle 1x/session.
     * true->false : "desactive" (si demande) ou "perdu" (inattendu).
     */
    fun observerVsConfirme(actuel: Boolean) {
        runCatching {
            if (!actif()) { vsConfirmePrecedent = actuel; return }
            if (actuel == vsConfirmePrecedent) return
            val avant = vsConfirmePrecedent
            vsConfirmePrecedent = actuel
            if (actuel) {
                pub(VoiceEvents.vsEnabled(now()))
                if (!avertObstacleFait) {
                    avertObstacleFait = true
                    pub(VoiceEvents.vsObstacleWarning(now()))
                }
                desactivationDemandee = false
            } else if (avant) {
                if (desactivationDemandee) {
                    pub(VoiceEvents.vsDisabled(now()))
                } else {
                    pub(VoiceEvents.vsLost(now()))
                }
                desactivationDemandee = false
            }
        }
    }

    /** Signale qu'une desactivation VS est VOULUE (STOP / atterrissage). */
    fun marquerDesactivationDemandee() {
        runCatching { desactivationDemandee = true }
    }

    /** Reinitialise l'avertissement obstacle a chaque nouvelle session de vol. */
    fun nouvelleSessionVol() {
        runCatching { avertObstacleFait = false }
    }

    // ---------- RETOUR MAISON / RTH ----------

    /** Callback onSuccess de KeyStartGoHome : RTH demarre reellement. */
    fun observerRthActive() {
        runCatching { if (actif()) pub(VoiceEvents.rthActive(now())) }
    }

    /** Callback onFailure de KeyStartGoHome : RTH refuse. */
    fun observerRthRefuse() {
        runCatching { if (actif()) pub(VoiceEvents.rthRejected(now())) }
    }

    /** RTH annule / interrompu (reprise manuelle). */
    fun observerRthAnnule() {
        runCatching { if (actif()) pub(VoiceEvents.rthCancelled(now())) }
    }

    // ---------- DECOLLAGE / ATTERRISSAGE ----------

    /** Callback onSuccess de KeyStartTakeoff : decollage reellement demarre. */
    fun observerTakeoffActive() {
        runCatching { if (actif()) pub(VoiceEvents.takeoffActive(now())) }
    }

    /** Callback onFailure de KeyStartTakeoff : decollage refuse. */
    fun observerTakeoffRefuse() {
        runCatching { if (actif()) pub(VoiceEvents.takeoffRejected(now())) }
    }

    /** Callback onSuccess de KeyStartAutoLanding : atterrissage reellement demarre. */
    fun observerLandingActive() {
        runCatching { if (actif()) pub(VoiceEvents.landingActive(now())) }
    }

    /** Callback onFailure de KeyStartAutoLanding : atterrissage refuse. */
    fun observerLandingRefuse() {
        runCatching { if (actif()) pub(VoiceEvents.landingRejected(now())) }
    }

    // ========== PHASE 3 — ETATS CRITIQUES ==========

    // ---------- BATTERIE (seuils + hysteresis) ----------

    /**
     * Recoit le pourcentage batterie REEL. Determine le niveau (OK/faible/critique)
     * avec hysteresis pour eviter le bavardage au bord du seuil, et n'annonce que
     * sur CHANGEMENT de niveau. "retablie" quand on remonte franchement au-dessus.
     */
    fun observerBatterie(pct: Int) {
        runCatching {
            if (!actif()) { return }
            if (pct < 0 || pct > 100) return   // valeur invalide -> ignore
            val avant = niveauBatPrecedent
            val niveau = when {
                pct <= seuilBatCritique -> 2
                pct <= seuilBatFaible -> 1
                else -> 0
            }
            // Hysteresis : pour REDESCENDRE de niveau (retour vers 0/1), exiger une
            // remontee au-dessus du seuil + marge, sinon on garde le niveau courant.
            val niveauEffectif = when {
                avant == 2 && pct <= seuilBatCritique + hysterese -> 2
                avant >= 1 && niveau == 0 && pct <= seuilBatFaible + hysterese -> avant
                else -> niveau
            }
            if (niveauEffectif == avant) return
            niveauBatPrecedent = niveauEffectif
            when (niveauEffectif) {
                2 -> pub(VoiceEvents.batteryCritical(now(), pct))
                1 -> if (avant == 2) { /* remonte de critique a faible : rien de neuf */ }
                     else pub(VoiceEvents.batteryLow(now(), pct))
                0 -> if (avant >= 1) pub(VoiceEvents.batteryRestored(now()))
            }
        }
    }

    // ---------- LIAISON DRONE ----------

    /** Recoit l'etat de connexion REEL du drone. Annonce sur transition seulement. */
    fun observerConnexionDrone(connecte: Boolean) {
        runCatching {
            if (!actif()) { droneConnecteConnu = connecte; return }
            val avant = droneConnecteConnu
            if (avant == connecte) return
            droneConnecteConnu = connecte
            if (avant == null) return   // 1er etat connu : pas d'annonce de transition
            if (connecte) pub(VoiceEvents.droneLinkRestored(now()))
            else pub(VoiceEvents.droneLinkLost(now()))
        }
    }

    // ---------- LIAISON TELECOMMANDE ----------

    /** Recoit l'etat de connexion REEL de la telecommande. Transition seulement. */
    fun observerConnexionRc(connecte: Boolean) {
        runCatching {
            if (!actif()) { rcConnecteConnu = connecte; return }
            val avant = rcConnecteConnu
            if (avant == connecte) return
            rcConnecteConnu = connecte
            if (avant == null) return
            if (connecte) pub(VoiceEvents.rcLinkRestored(now()))
            else pub(VoiceEvents.rcLinkLost(now()))
        }
    }

    // ---------- GPS DRONE ----------

    /** Recoit le nombre de satellites REEL. Compare au seuil, transition seulement. */
    fun observerGpsSatellites(nbSat: Int) {
        runCatching {
            if (!actif()) { gpsOkConnu = (nbSat >= seuilSatMin); return }
            if (nbSat < 0) return
            val ok = nbSat >= seuilSatMin
            val avant = gpsOkConnu
            if (avant == ok) return
            gpsOkConnu = ok
            if (avant == null) return
            if (ok) pub(VoiceEvents.gpsDroneRestored(now()))
            else pub(VoiceEvents.gpsDroneWeak(now()))
        }
    }

    // ---------- ARRET D'URGENCE ----------

    /** L'utilisateur a declenche l'arret d'urgence (hover + sortie VS deja faits). */
    fun observerArretUrgence() {
        runCatching { if (actif()) pub(VoiceEvents.emergencyStop(now())) }
    }

    // ========== PHASE 3A — MAITRISE DU DRONE ==========

    // ---------- WATCHDOG VIRTUAL STICK ----------

    /** timeout=true : la boucle est passee en hover force (aucune commande fraiche). */
    fun observerWatchdog(timeout: Boolean) {
        runCatching {
            if (!actif()) { watchdogActifPrecedent = timeout; return }
            if (timeout == watchdogActifPrecedent) return
            watchdogActifPrecedent = timeout
            if (timeout) pub(VoiceEvents.vsWatchdogTimeout(now()))
            else pub(VoiceEvents.vsWatchdogRestored(now()))
        }
    }

    /** nonConfirme=true : la boucle emet mais le VS n'est pas confirme actif. */
    fun observerVsNonConfirme(nonConfirme: Boolean) {
        runCatching {
            if (!actif()) { vsNonConfirmePrecedent = nonConfirme; return }
            if (nonConfirme == vsNonConfirmePrecedent) return
            vsNonConfirmePrecedent = nonConfirme
            if (nonConfirme) pub(VoiceEvents.vsNotConfirmed(now()))
            // pas d'annonce quand ca redevient confirme : vsEnabled s'en charge deja.
        }
    }

    // ---------- POINT MAISON ----------

    /** confirme=true : le point maison a ete capture (GPS valide). Transition seulement. */
    fun observerPointMaison(confirme: Boolean) {
        runCatching {
            if (!actif()) { homeConnu = confirme; return }
            val avant = homeConnu
            if (avant == confirme) return
            homeConnu = confirme
            if (avant == null && confirme) { pub(VoiceEvents.homeConfirmed(now())); return }
            if (confirme) pub(VoiceEvents.homeConfirmed(now()))
            else pub(VoiceEvents.homeNotConfirmed(now()))
        }
    }

    /** Point maison juge invalide (retour automatique non garanti). */
    fun observerPointMaisonInvalide() {
        runCatching { if (actif()) pub(VoiceEvents.homeInvalid(now())) }
    }

    // ---------- PHASES RTH ----------
    // phase : 1=montee, 2=retour, 3=descente, 4=termine. Transition seulement.
    fun observerRthPhase(phase: Int) {
        runCatching {
            if (!actif()) { rthPhasePrecedente = phase; return }
            if (phase == rthPhasePrecedente) return
            rthPhasePrecedente = phase
            when (phase) {
                1 -> pub(VoiceEvents.rthClimbing(now()))
                2 -> pub(VoiceEvents.rthReturning(now()))
                3 -> pub(VoiceEvents.rthDescending(now()))
                4 -> pub(VoiceEvents.rthCompleted(now()))
            }
        }
    }

    // ---------- QUALITE DE LIAISON RADIO ----------

    /** Signal de commande (0..100). Niveaux avec hysteresis simple sur transitions. */
    fun observerSignalCommande(pct: Int) {
        runCatching {
            if (!actif()) return
            if (pct < 0) return   // -1 = non cable / inconnu -> ignore
            val niveau = when {
                pct <= seuilLinkCritique -> 2
                pct <= seuilLinkFaible -> 1
                else -> 0
            }
            if (niveau == linkCmdNiveauPrecedent) return
            val avant = linkCmdNiveauPrecedent
            linkCmdNiveauPrecedent = niveau
            when (niveau) {
                2 -> pub(VoiceEvents.linkCommandCritical(now()))
                1 -> if (avant == 0) pub(VoiceEvents.linkCommandWeak(now()))
                0 -> if (avant >= 1) pub(VoiceEvents.linkCommandRestored(now()))
            }
        }
    }

    /** Signal video (0..100). 0 (ou tres bas) = video perdue mais commande active. */
    fun observerSignalVideo(pct: Int) {
        runCatching {
            if (!actif()) return
            if (pct < 0) return
            val niveau = when {
                pct <= 0 -> 2
                pct <= seuilVideoFaible -> 1
                else -> 0
            }
            if (niveau == linkVideoNiveauPrecedent) return
            val avant = linkVideoNiveauPrecedent
            linkVideoNiveauPrecedent = niveau
            when (niveau) {
                2 -> pub(VoiceEvents.linkVideoLost(now()))
                1 -> if (avant == 0) pub(VoiceEvents.linkVideoWeak(now()))
                0 -> if (avant >= 1) pub(VoiceEvents.linkVideoRestored(now()))
            }
        }
    }

    // ========== PHASE 3B — CAMERA / ENREGISTREMENT ==========

    /** Callback onSuccess KeyStartRecord : enregistrement reellement demarre. */
    fun observerRecordStarted() {
        runCatching { if (actif()) pub(VoiceEvents.cameraRecordStarted(now())) }
    }

    /** Callback onSuccess KeyStopRecord : enregistrement reellement arrete. */
    fun observerRecordStopped() {
        runCatching { if (actif()) pub(VoiceEvents.cameraRecordStopped(now())) }
    }

    /** Callback onFailure d'un start/stop d'enregistrement. */
    fun observerRecordFailed() {
        runCatching { if (actif()) pub(VoiceEvents.cameraRecordFailed(now())) }
    }

    /**
     * Etat de la carte SD, evalue depuis l'etat cockpit (~2 Hz). Transition seulement.
     * @param presente true si carte inseree/utilisable.
     * @param minutesRestantes minutes video restantes ; -1 = inconnu.
     */
    fun observerCarteSd(presente: Boolean, minutesRestantes: Int) {
        runCatching {
            if (!actif()) return
            val niveau = when {
                !presente -> 0
                minutesRestantes in 0..0 -> 1                                   // pleine
                minutesRestantes in 1..seuilSdPresquePleinMin -> 2              // presque pleine
                else -> 3                                                       // ok (ou inconnu)
            }
            if (niveau == sdNiveauPrecedent) return
            val avant = sdNiveauPrecedent
            sdNiveauPrecedent = niveau
            if (avant == -1 && niveau == 3) return   // 1er etat OK : pas d'annonce
            when (niveau) {
                0 -> pub(VoiceEvents.cameraSdAbsent(now()))
                1 -> pub(VoiceEvents.cameraSdFull(now()))
                2 -> pub(VoiceEvents.cameraSdAlmostFull(now()))
                3 -> { /* retour a OK : silencieux */ }
            }
        }
    }

    /** Camera disponible/indisponible (deduit de l'etat cockpit). Transition seulement. */
    fun observerCameraDispo(disponible: Boolean) {
        runCatching {
            if (!actif()) { cameraDispoPrecedent = disponible; return }
            val avant = cameraDispoPrecedent
            if (avant == disponible) return
            cameraDispoPrecedent = disponible
            if (avant == null) return
            if (!disponible) pub(VoiceEvents.cameraUnavailable(now()))
        }
    }

    /** Sujet hors cadre (perte de cadrage). Transition seulement. */
    fun observerSujetHorsCadre(horsCadre: Boolean) {
        runCatching {
            if (!actif()) { horsCadrePrecedent = horsCadre; return }
            if (horsCadre == horsCadrePrecedent) return
            horsCadrePrecedent = horsCadre
            if (horsCadre) pub(VoiceEvents.cameraSubjectOutOfFrame(now()))
        }
    }
}
