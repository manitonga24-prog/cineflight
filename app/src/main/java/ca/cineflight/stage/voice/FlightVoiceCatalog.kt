package ca.cineflight.stage.voice

/**
 * PHASE 1 — Catalogue bilingue. Cles stables independantes de la langue.
 * Repli controle : cle absente -> renvoie la cle brute (jamais un crash).
 * Parametres : "%{nom}" remplace par parameters["nom"].
 */
object FlightVoiceCatalog {

    private val MESSAGES: Map<String, Pair<String, String>> = mapOf(
        "voice.test.info" to ("Test information" to "Information test"),
        "voice.test.confirmation" to ("Test confirmation" to "Confirmation test"),
        "voice.test.critique" to ("Test alerte critique" to "Critical alert test"),
        "voice.test.expirable" to ("Ancienne annonce" to "Stale announcement"),
        "voice.test.fr" to ("Test en francais" to "Test in French"),
        "voice.test.en" to ("Test en anglais" to "Test in English"),
        "flight.connection.lost" to ("Liaison drone perdue" to "Drone link lost"),
        "flight.connection.restored" to ("Drone reconnecte" to "Drone reconnected"),
        "rc.connection.lost" to ("Telecommande deconnectee" to "Remote controller disconnected"),
        "rc.connection.restored" to ("Telecommande connectee" to "Remote controller connected"),
        "battery.low" to ("Batterie faible" to "Battery low"),
        "battery.critical" to ("Batterie critique. Atterrissage requis" to "Battery critical. Landing required"),
        "battery.restored" to ("Batterie retablie" to "Battery restored"),
        "battery.percent" to ("Batterie a %{pct} pour cent" to "Battery at %{pct} percent"),
        "gps.drone.weak" to ("GPS drone insuffisant" to "Drone GPS insufficient"),
        "gps.drone.restored" to ("GPS drone retabli" to "Drone GPS restored"),
        "vlos.approaching" to ("Le drone s'eloigne. %{d} metres, limite %{lim}" to "Drone moving away. %{d} meters, limit %{lim}"),
        "vlos.warning" to ("Limite de distance bientot atteinte. %{d} metres" to "Distance limit approaching. %{d} meters"),
        "vlos.limit_reached" to ("Limite de visibilite configuree atteinte. Rapprochez le drone" to "Configured visibility limit reached. Bring the drone closer"),
        "vlos.exceeded" to ("Drone au-dela de la limite configuree. Revenez vers le pilote" to "Drone beyond configured limit. Return toward the pilot"),
        "vlos.increasing" to ("Distance critique. Le drone continue de s'eloigner. Revenez immediatement vers le pilote" to "Critical distance. Drone still moving away. Return to the pilot immediately"),
        "vlos.recovered" to ("Distance revenue dans la limite configuree" to "Distance back within configured limit"),
        "vlos.pilot_position_unreliable" to ("Position du pilote imprecise. Distance non fiable" to "Pilot position unreliable. Distance not reliable"),
        "vs.watchdog_timeout" to ("Commandes automatiques interrompues" to "Automatic commands interrupted"),
        "vs.not_confirmed" to ("Controle automatique non confirme" to "Automatic control not confirmed"),
        "vs.watchdog_restored" to ("Commandes automatiques retablies" to "Automatic commands restored"),
        "home.confirmed" to ("Point maison confirme" to "Home point confirmed"),
        "home.not_confirmed" to ("Point maison non confirme" to "Home point not confirmed"),
        "home.invalid" to ("Point maison invalide. Retour automatique non garanti" to "Home point invalid. Automatic return not guaranteed"),
        "command.rth.climbing" to ("Montee vers l\'altitude de retour" to "Climbing to return altitude"),
        "command.rth.returning" to ("Retour vers le point maison" to "Returning to home point"),
        "command.rth.descending" to ("Descente au point maison" to "Descending at home point"),
        "command.rth.completed" to ("Retour maison termine" to "Return to home completed"),
        "altitude.approaching" to ("Limite d\'altitude bientot atteinte. %{a} metres" to "Altitude limit approaching. %{a} meters"),
        "altitude.limit_reached" to ("Limite d\'altitude atteinte. %{a} metres" to "Altitude limit reached. %{a} meters"),
        "altitude.exceeded" to ("Limite d\'altitude depassee. Descendez" to "Altitude limit exceeded. Descend"),
        "altitude.recovered" to ("Altitude revenue sous la limite" to "Altitude back below limit"),
        "altitude.low_margin" to ("Altitude de securite insuffisante" to "Safety altitude insufficient"),
        "link.command_weak" to ("Signal de commande faible" to "Command signal weak"),
        "link.command_critical" to ("Signal de commande critique. Revenez vers le pilote" to "Command signal critical. Return toward the pilot"),
        "link.command_restored" to ("Signal de commande retabli" to "Command signal restored"),
        "link.video_weak" to ("Signal video faible" to "Video signal weak"),
        "link.video_lost" to ("Video perdue. Liaison de commande encore active" to "Video lost. Command link still active"),
        "link.video_restored" to ("Signal video retabli" to "Video signal restored"),
        "rtk.subject.degraded" to ("RTK sujet degrade" to "Subject RTK degraded"),
        "rtk.subject.fixed" to ("RTK fixe" to "RTK fixed"),
        "subject.lost" to ("Position du sujet perdue" to "Subject position lost"),
        "subject.restored" to ("Position du sujet retablie" to "Subject position restored"),
        "subject.received" to ("Position du sujet recue" to "Subject position received"),
        "subject.stale" to ("Position du sujet perimee" to "Subject position stale"),
        "subject.beacon_lost" to ("Balise du sujet deconnectee" to "Subject beacon disconnected"),
        "rtk.subject.precision_low" to ("Precision du sujet insuffisante" to "Subject precision insufficient"),
        "subject.too_close" to ("Distance de securite avec le sujet insuffisante" to "Safety distance to subject insufficient"),
        "fusion.vision_lost_rtk_ok" to ("Vision du sujet perdue. Suivi GPS maintenu" to "Subject vision lost. GPS tracking maintained"),
        "fusion.rtk_lost_vision_ok" to ("RTK sujet perdu. Suivi visuel maintenu" to "Subject RTK lost. Visual tracking maintained"),
        "fusion.both_lost" to ("Sujet perdu. Suivi interrompu" to "Subject lost. Tracking stopped"),
        "fusion.incoherent" to ("Vision et RTK incoherents" to "Vision and RTK inconsistent"),
        "fusion.subject_found" to ("Sujet retrouve" to "Subject reacquired"),
        "fusion.stable" to ("Suivi combine retabli" to "Combined tracking restored"),
        "prediction.pending" to ("Prediction en attente" to "Prediction pending"),
        "prediction.available" to ("Prediction disponible" to "Prediction available"),
        "prediction.degraded" to ("Prediction degradee" to "Prediction degraded"),
        "prediction.suspended" to ("Prediction suspendue. Suivi sur position actuelle" to "Prediction suspended. Tracking on current position"),
        "prediction.uncertain" to ("Trajectoire du sujet incertaine" to "Subject trajectory uncertain"),
        "server.unavailable" to ("Serveur CineFlight indisponible" to "CineFlight server unavailable"),
        "server.restored" to ("Serveur CineFlight reconnecte" to "CineFlight server reconnected"),
        "server.data_delayed" to ("Retard reseau eleve. Position du sujet retardee" to "High network delay. Subject position delayed"),
        "server.data_interrupted" to ("Donnees du sujet interrompues" to "Subject data interrupted"),
        "camera.record_started" to ("Enregistrement demarre" to "Recording started"),
        "camera.record_stopped" to ("Enregistrement arrete" to "Recording stopped"),
        "camera.record_failed" to ("Enregistrement impossible" to "Recording failed"),
        "camera.sd_almost_full" to ("Carte memoire presque pleine" to "Memory card almost full"),
        "camera.sd_full" to ("Carte memoire pleine. Enregistrement arrete" to "Memory card full. Recording stopped"),
        "camera.sd_absent" to ("Carte memoire absente" to "Memory card missing"),
        "camera.unavailable" to ("Camera indisponible" to "Camera unavailable"),
        "camera.subject_out_of_frame" to ("Sujet hors cadre" to "Subject out of frame"),
        "command.takeoff.requested" to ("Decollage demande" to "Takeoff requested"),
        "command.takeoff.active" to ("Decollage en cours" to "Takeoff in progress"),
        "command.takeoff.rejected" to ("Decollage refuse" to "Takeoff rejected"),
        "command.landing.active" to ("Atterrissage en cours" to "Landing in progress"),
        "command.landing.rejected" to ("Atterrissage refuse" to "Landing rejected"),
        "command.rth.active" to ("Retour maison en cours" to "Return to home in progress"),
        "command.rth.rejected" to ("Retour maison refuse" to "Return to home refused"),
        "command.rth.cancelled" to ("Retour maison interrompu" to "Return to home cancelled"),
        "command.emergency_stop" to ("Arret d'urgence. Drone en vol stationnaire" to "Emergency stop. Drone hovering"),
        "virtual_stick.enable_accepted" to ("Commande de controle automatique acceptee" to "Automatic control command accepted"),
        "virtual_stick.enable_rejected" to ("Activation du controle automatique refusee" to "Automatic control activation refused"),
        "virtual_stick.active" to ("Controle automatique active" to "Automatic control active"),
        "virtual_stick.disabled" to ("Controle automatique desactive" to "Automatic control disabled"),
        "virtual_stick.lost" to ("Controle automatique perdu" to "Automatic control lost"),
        "virtual_stick.obstacle_warning" to
            ("Controle automatique actif. Evitement d'obstacles non garanti sur ce drone"
                to "Automatic control active. Obstacle avoidance not guaranteed on this aircraft")
    )

    fun contient(messageKey: String): Boolean = MESSAGES.containsKey(messageKey)

    fun texte(messageKey: String, langue: String, parametres: Map<String, String>): String {
        val paire = MESSAGES[messageKey] ?: return messageKey
        var s = if (langue == "fr") paire.first else paire.second
        if (parametres.isNotEmpty()) {
            for ((k, v) in parametres) s = s.replace("%{" + k + "}", v)
        }
        return s
    }
}
