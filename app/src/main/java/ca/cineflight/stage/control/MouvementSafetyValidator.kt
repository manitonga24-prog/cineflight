package ca.cineflight.stage.control

data class ValidationMouvement(
    val autorise: Boolean,
    val status: String,
    val raison: String,
    // Avertissement non bloquant (ex. leger skew d'horloge). Null si aucun.
    // Observable par l'appelant et testable sans dependance Android.
    val avertissement: String? = null
)

object MouvementSafetyValidator {

    private val mouvementsPermis = setOf(
        "RAPPROCHE_SUJET",
        "RAPPROCHE_SUJET_PRONONCE",
        "ELOIGNEMENT_SUJET",
        "TRAVELLING_ARRIERE",
        "PAUSE_HOVER",
        "REPRENDRE_SUIVI",
        "CHANGER_COTE",
        "ORBITE_LARGE",
        "STOP_HOVER"
    )

    fun validerDemande(demande: DemandeMouvement, rtk: RtkSujet?): ValidationMouvement {
        val mouvement = demande.movement ?: return ValidationMouvement(
            autorise = false,
            status = "BLOCKED_NO_MOVEMENT",
            raison = "Aucun mouvement dans la demande"
        )

        if (mouvement !in mouvementsPermis) {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_UNKNOWN_MOVEMENT",
                raison = "Mouvement non autorise : $mouvement"
            )
        }

        val serverTs = demande.serverRxTs ?: return ValidationMouvement(
            autorise = false,
            status = "BLOCKED_NO_TIMESTAMP",
            raison = "Timestamp serveur absent"
        )

        val ageDemandeSec = (System.currentTimeMillis() / 1000.0) - serverTs

        // Fail-closed : non finie, futur LOINTAIN (< -2 s = horloge desynchronisee grave
        // ou timestamp corrompu), ou trop vieille (> 30 s) -> rejet.
        if (!ageDemandeSec.isFinite() || ageDemandeSec < -2.0 || ageDemandeSec > 30.0) {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_STALE_REQUEST",
                raison = "Demande invalide/hors fenetre : %.1f secondes".format(ageDemandeSec)
            )
        }
        // Skew d'horloge MINEUR (-2 s <= age < 0) : acceptable, mais journalise via un
        // avertissement porte par le verdict final (pas de rejet).
        val avertissementHorloge: String? =
            if (ageDemandeSec < 0.0)
                "CLOCK_SKEW_FUTURE_TIMESTAMP: demande %.1fs dans le futur".format(-ageDemandeSec)
            else null

        // PAUSE et HOLD sont des intentions de sécurité : elles restent recevables
        // même sans RTK. À cette étape, elles sont seulement validées et acquittées.
        if (mouvement == "PAUSE_HOVER" || mouvement == "STOP_HOVER") {
            return ValidationMouvement(
                autorise = true,
                status = "SAFETY_COMMAND_OK_NO_EXECUTION",
                raison = "Commande de sécurité récente. RTK non requis. Aucun mouvement drone exécuté.",
                avertissement = avertissementHorloge
            )
        }
        if (rtk == null) {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_RTK_READ_ERROR",
                raison = "Impossible de lire /api/rtk/sujet"
            )
        }

        if (!rtk.present) {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_NO_RTK_SUBJECT",
                raison = "Aucun sujet RTK present"
            )
        }

        if (rtk.rtk == null || rtk.rtk.uppercase() == "LOST") {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_RTK_LOST",
                raison = "RTK sujet perdu"
            )
        }

        val ageRtk = rtk.ageS ?: return ValidationMouvement(
            autorise = false,
            status = "BLOCKED_RTK_NO_AGE",
            raison = "Age RTK absent"
        )

        // Garde fail-closed : non finie (NaN/±Inf), NEGATIVE (age "du futur" = donnee
        // corrompue ou horloge desynchronisee, jamais valide -> rejet STRICT sans
        // tolerance), ou trop vieille (> 2.5 s).
        if (!ageRtk.isFinite() || ageRtk < 0.0 || ageRtk > 2.5) {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_RTK_TOO_OLD",
                raison = "Position RTK invalide/trop vieille : %.1f secondes".format(ageRtk)
            )
        }

        if (rtk.rtk.uppercase() != "FIX") {
            return ValidationMouvement(
                autorise = false,
                status = "BLOCKED_RTK_NOT_FIX",
                raison = "RTK non FIX : ${rtk.rtk}"
            )
        }

        return ValidationMouvement(
            autorise = true,
            status = "AUTO_VALIDATION_OK_RTK_ONLY",
            raison = "Demande fraiche + RTK FIX recent. Validation seulement, aucun mouvement drone execute.",
            avertissement = avertissementHorloge
        )
    }
}

