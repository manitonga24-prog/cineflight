package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse

/**
 * Emission2DGuard — DECISION D'EMISSION 2D (pur, aucun SDK/Android).
 *
 * Source UNIQUE de la regle du DOUBLE VERROU 2D + arbitre, extraite de Phase3Activity
 * pour etre entierement testable. Ne parle PAS au SDK : rend la commande finale a emettre
 * (throttle seul en Essai 1) et un libelle d'etat. L'appelant se contente d'envoyer.
 *
 * DOCTRINE (identique au mode RAIL) : une commande n'est emise que si TOUTES les
 * conditions cumulatives sont vraies :
 *   flagReel (SOCCER_2D_REAL_ENABLED) ET operateurArme ET vMax>0 ET
 *   l'arbitre (FlightCommandArbiter) retient la source SoccerRail.
 * Un seul manquant -> commande NEUTRE (zero). Aucun contournement, aucun second chemin :
 * la decision de securite reste celle de FlightCommandArbiter.
 */
object Emission2DGuard {

    /** Etat d'emission, pour l'affichage et le diagnostic. */
    enum class Etat { INERTE_FLAG_OFF, INERTE_NON_ARME, INERTE_VMAX_ZERO, BLOQUE_ARBITRE, ACTIVE }

    /** Resultat : commande finale (repere corps) + etat + raison arbitre. */
    data class Resultat(
        val command: AssainisseurVitesse.Vitesses,
        val etat: Etat,
        val raison: String,
        /** true seulement si une commande non-neutre doit reellement etre emise. */
        val emettre: Boolean,
    )

    val NEUTRE: AssainisseurVitesse.Vitesses = FlightCommandArbiter.NEUTRE

    /**
     * Decide l'emission 2D (Essai 1 : ALTITUDE SEULE -> throttle).
     *
     * @param throttleMps throttle propose par le controleur d'altitude (m/s, deja borne).
     * @param flagReel SOCCER_2D_REAL_ENABLED.
     * @param operateurArme l'operateur a arme le mode.
     * @param vMaxMps SOCCER_2D_MAX_VSPEED_MPS (0 = verrou ferme).
     * @param safety instantane de securite (transmis a l'arbitre, inchange).
     */
    fun decider(
        throttleMps: Float,
        flagReel: Boolean,
        operateurArme: Boolean,
        vMaxMps: Float,
        safety: FlightCommandArbiter.SafetySnapshot,
    ): Resultat {
        // Verrous "durs" evalues AVANT l'arbitre : chacun coupe seul.
        if (!flagReel) return Resultat(NEUTRE, Etat.INERTE_FLAG_OFF, "flag reel off", false)
        if (!operateurArme) return Resultat(NEUTRE, Etat.INERTE_NON_ARME, "non arme", false)
        if (!(vMaxMps > 0f) || !vMaxMps.isFinite())
            return Resultat(NEUTRE, Etat.INERTE_VMAX_ZERO, "vMax = 0", false)

        // Commande 2D : Essai 1 = THROTTLE SEUL, le reste a zero. NaN -> 0 (fail-safe).
        val th = if (throttleMps.isFinite()) throttleMps.coerceIn(-vMaxMps, vMaxMps) else 0f
        val cmd2D = AssainisseurVitesse.Vitesses(pitch = 0f, roll = 0f, throttle = th, yaw = 0f)

        // ARBITRE : juge unique. On arme la source soccer (flag+arme deja verifies).
        val decision = FlightCommandArbiter.decide(
            existingCommand = NEUTRE,        // pas de mode auto concurrent en 2D
            soccerCommand = cmd2D,
            pilotCommand = NEUTRE,
            soccerModeArmed = true,          // flagReel && operateurArme prouves ci-dessus
            safety = safety,
        )
        val retenu = decision.source == FlightCommandArbiter.FlightCommandSource.SoccerRail
        return if (retenu) {
            Resultat(decision.command, Etat.ACTIVE, decision.reason, true)
        } else {
            // L'arbitre a bloque (une condition de securite manque) -> NEUTRE, pas d'emission.
            Resultat(NEUTRE, Etat.BLOQUE_ARBITRE, decision.reason, false)
        }
    }
}
