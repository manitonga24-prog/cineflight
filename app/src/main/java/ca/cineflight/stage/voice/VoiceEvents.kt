package ca.cineflight.stage.voice

/**
 * PHASE 2 — Fabrique d'evenements vocaux pour les COMMANDES DE VOL.
 *
 * Chaque commande respecte la chaine : demande -> accepte/refuse (callback SDK) ->
 * actif/en cours (etat REEL) -> termine. Les callbacks ne produisent JAMAIS "actif" :
 * seul un etat reel du drone le peut.
 *
 * Les evenements sont immuables et sans reference au pilotage. createdAtMs est
 * fourni par l'appelant (System.currentTimeMillis()).
 */
object VoiceEvents {

    private var seq = 0L
    private fun id(p: String): String { seq += 1; return p + "-" + seq }

    // ---------- VIRTUAL STICK (Lot 2A) ----------

    /** Callback d'activation reussi : "acceptee" (mode detaille). PAS "active". */
    fun vsEnableAccepted(now: Long) = FlightVoiceEvent(
        eventId = id("vs"),
        messageKey = "virtual_stick.enable_accepted",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.INFO,   // detail seulement (mode DETAILLE)
        createdAtMs = now,
        deduplicationKey = "vs_enable_accepted",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Transition vsActifConfirme false->true : "Controle automatique active". */
    fun vsEnabled(now: Long) = FlightVoiceEvent(
        eventId = id("vs"),
        messageKey = "virtual_stick.active",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "vs_state",
        recoveryKey = "vs_lost",   // annule une eventuelle "perte" en attente
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun vsEnableRejected(now: Long) = FlightVoiceEvent(
        eventId = id("vs"),
        messageKey = "virtual_stick.enable_rejected",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "vs_enable_rejected",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Transition true->false VOULUE (STOP / atterrissage) : "desactive". */
    fun vsDisabled(now: Long) = FlightVoiceEvent(
        eventId = id("vs"),
        messageKey = "virtual_stick.disabled",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "vs_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Transition true->false INATTENDUE : "perdu" (safety). */
    fun vsLost(now: Long) = FlightVoiceEvent(
        eventId = id("vs"),
        messageKey = "virtual_stick.lost",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vs_lost",
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Avertissement obstacle une fois par session (drone non officiellement supporte). */
    fun vsObstacleWarning(now: Long) = FlightVoiceEvent(
        eventId = id("vs"),
        messageKey = "virtual_stick.obstacle_warning",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vs_obstacle_warning",
        repeatPolicy = RepeatPolicy.ONCE
    )

    // ---------- RETOUR MAISON / RTH (Lot 2B) ----------

    /** Callback onSuccess de KeyStartGoHome : le RTH a demarre (etat reel = en cours). */
    fun rthActive(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.active",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "rth_state",
        recoveryKey = "rth_interrompu",
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Callback onFailure de KeyStartGoHome : RTH refuse. */
    fun rthRejected(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.rejected",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.SAFETY,   // un RTH refuse est important a savoir
        createdAtMs = now,
        deduplicationKey = "rth_rejected",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** RTH annule / interrompu (reprise manuelle aux sticks). */
    fun rthCancelled(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.cancelled",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "rth_interrompu",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- DECOLLAGE / ATTERRISSAGE (Lot 2C) ----------

    /** Callback onSuccess de KeyStartTakeoff : le decollage a REELLEMENT demarre. */
    fun takeoffActive(now: Long) = FlightVoiceEvent(
        eventId = id("tko"),
        messageKey = "command.takeoff.active",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "takeoff_state",
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Callback onFailure de KeyStartTakeoff : decollage refuse. */
    fun takeoffRejected(now: Long) = FlightVoiceEvent(
        eventId = id("tko"),
        messageKey = "command.takeoff.rejected",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.SAFETY,   // un decollage refuse est important a savoir
        createdAtMs = now,
        deduplicationKey = "takeoff_rejected",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Callback onSuccess de KeyStartAutoLanding : l'atterrissage a REELLEMENT demarre. */
    fun landingActive(now: Long) = FlightVoiceEvent(
        eventId = id("lnd"),
        messageKey = "command.landing.active",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "landing_state",
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Callback onFailure de KeyStartAutoLanding : atterrissage refuse. */
    fun landingRejected(now: Long) = FlightVoiceEvent(
        eventId = id("lnd"),
        messageKey = "command.landing.rejected",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "landing_rejected",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ========== PHASE 3 — ETATS CRITIQUES (securite) ==========
    // Ces evenements decrivent l'ETAT REEL du drone (batterie, liaison, GPS), pas des
    // commandes. La logique de seuil / hysteresis / transition vit dans l'adaptateur ;
    // ici on ne fait que fabriquer l'evenement une fois la decision prise.

    // ---------- BATTERIE ----------

    /** Batterie descendue sous le seuil FAIBLE (ex. 30%). Repetable a l'intervalle. */
    fun batteryLow(now: Long, pct: Int) = FlightVoiceEvent(
        eventId = id("bat"),
        messageKey = "battery.low",
        source = VoiceEventSource.BATTERY,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "battery_low",
        recoveryKey = "battery_restore",
        parameters = mapOf("pct" to pct.toString()),
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Batterie CRITIQUE (ex. <=15%) : atterrissage requis. Toujours annoncee. */
    fun batteryCritical(now: Long, pct: Int) = FlightVoiceEvent(
        eventId = id("bat"),
        messageKey = "battery.critical",
        source = VoiceEventSource.BATTERY,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "battery_critical",
        parameters = mapOf("pct" to pct.toString()),
        interruptPolicy = InterruptPolicy.FLUSH,   // prioritaire : coupe le reste
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Batterie repassee au-dessus du seuil faible (recuperation). */
    fun batteryRestored(now: Long) = FlightVoiceEvent(
        eventId = id("bat"),
        messageKey = "battery.restored",
        source = VoiceEventSource.BATTERY,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "battery_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- LIAISON DRONE ----------

    /** Liaison avec le drone PERDUE (etat connexion false). Critique. */
    fun droneLinkLost(now: Long) = FlightVoiceEvent(
        eventId = id("lnk"),
        messageKey = "flight.connection.lost",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "drone_link",
        recoveryKey = "drone_link_restore",
        interruptPolicy = InterruptPolicy.FLUSH,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Liaison drone RETABLIE. */
    fun droneLinkRestored(now: Long) = FlightVoiceEvent(
        eventId = id("lnk"),
        messageKey = "flight.connection.restored",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "drone_link_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- LIAISON TELECOMMANDE ----------

    /** Telecommande deconnectee. */
    fun rcLinkLost(now: Long) = FlightVoiceEvent(
        eventId = id("rc"),
        messageKey = "rc.connection.lost",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "rc_link",
        recoveryKey = "rc_link_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Telecommande reconnectee. */
    fun rcLinkRestored(now: Long) = FlightVoiceEvent(
        eventId = id("rc"),
        messageKey = "rc.connection.restored",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "rc_link_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- GPS DRONE ----------

    /** Nombre de satellites tombe sous le seuil exploitable (fix non fiable). */
    fun gpsDroneWeak(now: Long) = FlightVoiceEvent(
        eventId = id("gps"),
        messageKey = "gps.drone.weak",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "gps_drone",
        recoveryKey = "gps_drone_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** GPS drone repasse au-dessus du seuil (fix retabli). */
    fun gpsDroneRestored(now: Long) = FlightVoiceEvent(
        eventId = id("gps"),
        messageKey = "gps.drone.restored",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "gps_drone_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- ARRET D'URGENCE (utilisateur) ----------

    /**
     * Declenche APRES l'action reelle d'arret d'urgence (hover envoye + sortie VS).
     * CRITICAL + FLUSH : coupe toute autre annonce et passe en priorite absolue.
     * Rappel : l'arret NE coupe PAS les moteurs -> le drone reste en hover pilotable.
     */
    fun emergencyStop(now: Long) = FlightVoiceEvent(
        eventId = id("stop"),
        messageKey = "command.emergency_stop",
        source = VoiceEventSource.USER_INTERFACE,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "emergency_stop",
        interruptPolicy = InterruptPolicy.FLUSH,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ========== PHASE 3 — DISTANCE VLOS (visibilite directe) ==========
    // Rappel reglementaire : la limite configuree est OPERATIONNELLE, PAS une garantie
    // de visibilite. La voix OBSERVE et alerte ; elle ne declenche JAMAIS le RTH.
    // %{d} = distance drone<->pilote (m, arrondie), %{lim} = limite configuree (m).

    /** 70% de la limite : le drone s'eloigne (indicatif, non alarmant). */
    fun vlosApproaching(now: Long, dM: Int, limM: Int) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.approaching",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "vlos_level",
        recoveryKey = "vlos_recover",
        parameters = mapOf("d" to dM.toString(), "lim" to limM.toString()),
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** 85% de la limite : avertissement. */
    fun vlosWarning(now: Long, dM: Int, limM: Int) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.warning",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vlos_level",
        recoveryKey = "vlos_recover",
        parameters = mapOf("d" to dM.toString(), "lim" to limM.toString()),
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** 100% : limite configuree atteinte. */
    fun vlosLimitReached(now: Long, dM: Int, limM: Int) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.limit_reached",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vlos_level",
        recoveryKey = "vlos_recover",
        parameters = mapOf("d" to dM.toString(), "lim" to limM.toString()),
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Au-dela de la limite : depassement. Critique. */
    fun vlosExceeded(now: Long, dM: Int, limM: Int) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.exceeded",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "vlos_level",
        recoveryKey = "vlos_recover",
        parameters = mapOf("d" to dM.toString(), "lim" to limM.toString()),
        interruptPolicy = InterruptPolicy.FLUSH,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Depassement ET la distance continue d'augmenter (repetee a intervalle). */
    fun vlosIncreasing(now: Long, dM: Int, limM: Int) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.increasing",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "vlos_increasing",
        parameters = mapOf("d" to dM.toString(), "lim" to limM.toString()),
        interruptPolicy = InterruptPolicy.FLUSH,
        repeatPolicy = RepeatPolicy.ON_INTERVAL
    )

    /** Distance revenue dans la limite (recuperation). */
    fun vlosRecovered(now: Long) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.recovered",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "vlos_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Position pilote trop imprecise : la distance VLOS n'est pas fiable. */
    fun vlosPilotUnreliable(now: Long) = FlightVoiceEvent(
        eventId = id("vlos"),
        messageKey = "vlos.pilot_position_unreliable",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vlos_pilot_unreliable",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ========== PHASE 3A — MAITRISE DU DRONE ==========

    // ---------- WATCHDOG VIRTUAL STICK (3A-1) ----------

    /** Boucle de commande expiree (aucune commande fraiche) : hover force. */
    fun vsWatchdogTimeout(now: Long) = FlightVoiceEvent(
        eventId = id("wd"),
        messageKey = "vs.watchdog_timeout",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vs_watchdog",
        recoveryKey = "vs_watchdog_restore",
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** La boucle emet mais le Virtual Stick n'est pas confirme actif. */
    fun vsNotConfirmed(now: Long) = FlightVoiceEvent(
        eventId = id("wd"),
        messageKey = "vs.not_confirmed",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "vs_not_confirmed",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    /** Commandes automatiques retablies (boucle repart avec commandes fraiches). */
    fun vsWatchdogRestored(now: Long) = FlightVoiceEvent(
        eventId = id("wd"),
        messageKey = "vs.watchdog_restored",
        source = VoiceEventSource.VIRTUAL_STICK,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "vs_watchdog_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- POINT MAISON + PHASES RTH (3A-2) ----------

    fun homeConfirmed(now: Long) = FlightVoiceEvent(
        eventId = id("home"),
        messageKey = "home.confirmed",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "home_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun homeNotConfirmed(now: Long) = FlightVoiceEvent(
        eventId = id("home"),
        messageKey = "home.not_confirmed",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "home_not_confirmed",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun homeInvalid(now: Long) = FlightVoiceEvent(
        eventId = id("home"),
        messageKey = "home.invalid",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "home_invalid",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rthClimbing(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.climbing",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "rth_phase",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rthReturning(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.returning",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "rth_phase",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rthDescending(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.descending",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "rth_phase",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rthCompleted(now: Long) = FlightVoiceEvent(
        eventId = id("rth"),
        messageKey = "command.rth.completed",
        source = VoiceEventSource.DJI_COMMAND,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "rth_phase",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- ALTITUDE + LIMITES GEO (3A-3) ----------

    fun altitudeApproaching(now: Long, aM: Int) = FlightVoiceEvent(
        eventId = id("alt"),
        messageKey = "altitude.approaching",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "altitude_level",
        recoveryKey = "altitude_recover",
        parameters = mapOf("a" to aM.toString()),
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun altitudeLimitReached(now: Long, aM: Int) = FlightVoiceEvent(
        eventId = id("alt"),
        messageKey = "altitude.limit_reached",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "altitude_level",
        recoveryKey = "altitude_recover",
        parameters = mapOf("a" to aM.toString()),
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun altitudeExceeded(now: Long, aM: Int) = FlightVoiceEvent(
        eventId = id("alt"),
        messageKey = "altitude.exceeded",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "altitude_level",
        recoveryKey = "altitude_recover",
        parameters = mapOf("a" to aM.toString()),
        interruptPolicy = InterruptPolicy.FLUSH,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun altitudeRecovered(now: Long) = FlightVoiceEvent(
        eventId = id("alt"),
        messageKey = "altitude.recovered",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "altitude_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun altitudeLowMargin(now: Long) = FlightVoiceEvent(
        eventId = id("alt"),
        messageKey = "altitude.low_margin",
        source = VoiceEventSource.DJI_FLIGHT_STATE,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "altitude_low_margin",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ---------- QUALITE DE LIAISON RADIO (3A-4) ----------

    fun linkCommandWeak(now: Long) = FlightVoiceEvent(
        eventId = id("link"),
        messageKey = "link.command_weak",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "link_command",
        recoveryKey = "link_command_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun linkCommandCritical(now: Long) = FlightVoiceEvent(
        eventId = id("link"),
        messageKey = "link.command_critical",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.CRITICAL,
        createdAtMs = now,
        deduplicationKey = "link_command",
        recoveryKey = "link_command_restore",
        interruptPolicy = InterruptPolicy.FLUSH,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun linkCommandRestored(now: Long) = FlightVoiceEvent(
        eventId = id("link"),
        messageKey = "link.command_restored",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "link_command_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun linkVideoWeak(now: Long) = FlightVoiceEvent(
        eventId = id("link"),
        messageKey = "link.video_weak",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "link_video",
        recoveryKey = "link_video_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun linkVideoLost(now: Long) = FlightVoiceEvent(
        eventId = id("link"),
        messageKey = "link.video_lost",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "link_video",
        recoveryKey = "link_video_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun linkVideoRestored(now: Long) = FlightVoiceEvent(
        eventId = id("link"),
        messageKey = "link.video_restored",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "link_video_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    // ========== PHASE 3B — SUIVI CINEMATOGRAPHIQUE ==========

    // ---------- RTK SUJET + PEREMPTION (3B-1) ----------
    fun subjectReceived(now: Long) = FlightVoiceEvent(
        eventId = id("sub"),
        messageKey = "subject.received",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "subject_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun subjectStale(now: Long) = FlightVoiceEvent(
        eventId = id("sub"),
        messageKey = "subject.stale",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "subject_state",
        recoveryKey = "subject_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun subjectBeaconLost(now: Long) = FlightVoiceEvent(
        eventId = id("sub"),
        messageKey = "subject.beacon_lost",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "subject_beacon",
        recoveryKey = "subject_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun subjectLost(now: Long) = FlightVoiceEvent(
        eventId = id("sub"),
        messageKey = "subject.lost",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "subject_lost",
        recoveryKey = "subject_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun subjectRestored(now: Long) = FlightVoiceEvent(
        eventId = id("sub"),
        messageKey = "subject.restored",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "subject_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rtkSubjectDegraded(now: Long) = FlightVoiceEvent(
        eventId = id("rtk"),
        messageKey = "rtk.subject.degraded",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "rtk_subject",
        recoveryKey = "rtk_subject_fixed",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rtkSubjectPrecisionLow(now: Long) = FlightVoiceEvent(
        eventId = id("rtk"),
        messageKey = "rtk.subject.precision_low",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "rtk_subject",
        recoveryKey = "rtk_subject_fixed",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun rtkSubjectFixed(now: Long) = FlightVoiceEvent(
        eventId = id("rtk"),
        messageKey = "rtk.subject.fixed",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "rtk_subject_fixed",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun subjectTooClose(now: Long) = FlightVoiceEvent(
        eventId = id("sub"),
        messageKey = "subject.too_close",
        source = VoiceEventSource.RTK_SUBJECT,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "subject_too_close",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )


    // ---------- YOLO + FUSION (3B-2) ----------
    fun fusionVisionLostRtkOk(now: Long) = FlightVoiceEvent(
        eventId = id("fus"),
        messageKey = "fusion.vision_lost_rtk_ok",
        source = VoiceEventSource.VISION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "fusion_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun fusionRtkLostVisionOk(now: Long) = FlightVoiceEvent(
        eventId = id("fus"),
        messageKey = "fusion.rtk_lost_vision_ok",
        source = VoiceEventSource.VISION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "fusion_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun fusionBothLost(now: Long) = FlightVoiceEvent(
        eventId = id("fus"),
        messageKey = "fusion.both_lost",
        source = VoiceEventSource.VISION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "fusion_state",
        recoveryKey = "fusion_recover",
        interruptPolicy = InterruptPolicy.NONE,
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun fusionIncoherent(now: Long) = FlightVoiceEvent(
        eventId = id("fus"),
        messageKey = "fusion.incoherent",
        source = VoiceEventSource.VISION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "fusion_incoherent",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun fusionSubjectFound(now: Long) = FlightVoiceEvent(
        eventId = id("fus"),
        messageKey = "fusion.subject_found",
        source = VoiceEventSource.VISION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "fusion_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun fusionStable(now: Long) = FlightVoiceEvent(
        eventId = id("fus"),
        messageKey = "fusion.stable",
        source = VoiceEventSource.VISION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "fusion_stable",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )


    // ---------- PREDICTION + SERVEUR (3B-3) ----------
    fun predictionPending(now: Long) = FlightVoiceEvent(
        eventId = id("pre"),
        messageKey = "prediction.pending",
        source = VoiceEventSource.PREDICTION,
        severity = VoiceSeverity.INFO,
        createdAtMs = now,
        deduplicationKey = "prediction_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun predictionAvailable(now: Long) = FlightVoiceEvent(
        eventId = id("pre"),
        messageKey = "prediction.available",
        source = VoiceEventSource.PREDICTION,
        severity = VoiceSeverity.INFO,
        createdAtMs = now,
        deduplicationKey = "prediction_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun predictionDegraded(now: Long) = FlightVoiceEvent(
        eventId = id("pre"),
        messageKey = "prediction.degraded",
        source = VoiceEventSource.PREDICTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "prediction_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun predictionSuspended(now: Long) = FlightVoiceEvent(
        eventId = id("pre"),
        messageKey = "prediction.suspended",
        source = VoiceEventSource.PREDICTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "prediction_state",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun predictionUncertain(now: Long) = FlightVoiceEvent(
        eventId = id("pre"),
        messageKey = "prediction.uncertain",
        source = VoiceEventSource.PREDICTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "prediction_uncertain",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun serverUnavailable(now: Long) = FlightVoiceEvent(
        eventId = id("ser"),
        messageKey = "server.unavailable",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "server_state",
        recoveryKey = "server_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun serverRestored(now: Long) = FlightVoiceEvent(
        eventId = id("ser"),
        messageKey = "server.restored",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "server_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun serverDataDelayed(now: Long) = FlightVoiceEvent(
        eventId = id("ser"),
        messageKey = "server.data_delayed",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "server_delay",
        recoveryKey = "server_delay_recover",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun serverDataInterrupted(now: Long) = FlightVoiceEvent(
        eventId = id("ser"),
        messageKey = "server.data_interrupted",
        source = VoiceEventSource.CONNECTION,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "server_interrupted",
        recoveryKey = "server_restore",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )


    // ---------- CAMERA + ENREGISTREMENT (3B-4) ----------
    fun cameraRecordStarted(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.record_started",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "camera_record",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraRecordStopped(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.record_stopped",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.COMMAND,
        createdAtMs = now,
        deduplicationKey = "camera_record",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraRecordFailed(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.record_failed",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "camera_record_failed",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraSdAlmostFull(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.sd_almost_full",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "camera_sd",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraSdFull(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.sd_full",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "camera_sd",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraSdAbsent(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.sd_absent",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "camera_sd_absent",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraUnavailable(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.unavailable",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.SAFETY,
        createdAtMs = now,
        deduplicationKey = "camera_unavailable",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )

    fun cameraSubjectOutOfFrame(now: Long) = FlightVoiceEvent(
        eventId = id("cam"),
        messageKey = "camera.subject_out_of_frame",
        source = VoiceEventSource.CAMERA,
        severity = VoiceSeverity.STATE,
        createdAtMs = now,
        deduplicationKey = "camera_out_of_frame",
        repeatPolicy = RepeatPolicy.ON_STATE_CHANGE
    )
}
