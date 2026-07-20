package ca.cineflight.stage.sport.soccer

import kotlin.math.abs

/**
 * RailMotionController — CONTROLEUR DE RAIL PUR (aucun Android, aucun DJI).
 *
 * Transforme une intention (position cible sur le rail) en une VITESSE demandee le
 * long du rail, sous contraintes de securite :
 *   - vitesse limitee (maxSpeedMps) ;
 *   - ACCELERATION limitee (variation de vitesse bornee par tick) ;
 *   - zone morte (deadband) pour eviter les oscillations gauche-droite ;
 *   - donnee perimee (actionAgeMs > staleAfterMs) OU confiance faible -> vitesse 0 ;
 *   - cible toujours bornee entre les extremites du rail [0,1].
 *
 * PORTEE : ce composant ne parle PAS au SDK. Il produit une commande THEORIQUE que
 * l'arbitre (Phase 9C) validera avant toute emission via PontDjiReel. Il conserve
 * un petit etat (derniere vitesse) pour limiter l'acceleration ; sinon deterministe.
 *
 * CONVENTION DE SIGNE : vitesse > 0 = vers l'extremite "end" du rail (fraction
 * croissante, ex. but droit) ; vitesse < 0 = vers "start".
 */
class RailMotionController(
    private val maxSpeedMps: Float,
    private val deadband: Float,
    private val staleAfterMs: Long,
    /** Gain vitesse : m/s par unite d'ecart de fraction. */
    private val gainMps: Float = 4f,
    /** Variation max de vitesse autorisee par appel (m/s). 0 = pas de limite. */
    private val maxDeltaVMps: Float = 0.5f,
    /** En-deca de cette confiance, vitesse 0. */
    private val confidenceMin: Float = 0.50f,
) {

    private var lastVelocity: Float = 0f

    /** Reinitialise l'etat (nouvelle session / desarmement). */
    fun reset() { lastVelocity = 0f }

    /**
     * Calcule la vitesse demandee pour [input].
     * Applique dans l'ordre : garde fraicheur/confiance -> zone morte -> vitesse
     * proportionnelle bornee -> limite d'acceleration. Met a jour l'etat interne.
     */
    fun compute(input: RailControlInput): RailControlOutput {
        // 0) Assainissement fail-safe : entree non finie -> arret.
        if (!input.currentPosition.isFinite() || !input.targetPosition.isFinite() ||
            !input.confidence.isFinite()) {
            return sortie(0f, RailControlOutput.Reason.LOW_CONFIDENCE)
        }
        // 1) Donnee perimee -> arret (avec rampe de deceleration limitee).
        if (staleAfterMs > 0 && input.actionAgeMs > staleAfterMs) {
            return sortie(0f, RailControlOutput.Reason.ACTION_STALE)
        }
        // 2) Confiance faible -> arret.
        if (input.confidence < confidenceMin) {
            return sortie(0f, RailControlOutput.Reason.LOW_CONFIDENCE)
        }

        val current = input.currentPosition.coerceIn(0f, 1f)
        val target = input.targetPosition.coerceIn(0f, 1f)   // cible toujours sur le rail
        val ecart = target - current

        // 3) Zone morte -> HOLD (mais on decelere quand meme en douceur si on roulait).
        if (abs(ecart) < deadband) {
            return sortie(0f, RailControlOutput.Reason.INSIDE_DEADBAND)
        }

        // 4) Vitesse proportionnelle, signee, bornee (loi commune RailVitesse).
        val cible = RailVitesse.proportionnelle(ecart, gainMps, maxSpeedMps)

        val raison = if (ecart > 0f) RailControlOutput.Reason.MOVE_RIGHT
                     else RailControlOutput.Reason.MOVE_LEFT
        return sortie(cible, raison)
    }

    /**
     * Applique la LIMITE D'ACCELERATION vers [vitesseCible] (rampe commune RailVitesse),
     * met a jour l'etat, et renvoie la sortie. Une cible 0 (arret) est aussi soumise
     * a la rampe pour ne pas freiner brutalement.
     */
    private fun sortie(vitesseCible: Float, raison: RailControlOutput.Reason): RailControlOutput {
        val borneeMax = vitesseCible.coerceIn(-maxSpeedMps, maxSpeedMps)
        val v = RailVitesse.rampe(borneeMax, lastVelocity, maxDeltaVMps)
        lastVelocity = v
        return RailControlOutput(requestedVelocityMps = v, reason = raison)
    }
}

/**
 * Entree du controleur de rail (positions normalisees 0..1).
 * @param currentPosition position actuelle du drone sur le rail.
 * @param targetPosition position cible demandee.
 * @param actionAgeMs age de la derniere action YOLO retenue (ms).
 * @param confidence confiance de l'action retenue [0,1].
 */
data class RailControlInput(
    val currentPosition: Float,
    val targetPosition: Float,
    val actionAgeMs: Long,
    val confidence: Float,
)

/**
 * Sortie du controleur : vitesse demandee (signee, m/s) + raison traçable.
 */
data class RailControlOutput(
    val requestedVelocityMps: Float,
    val reason: Reason,
) {
    enum class Reason {
        MOVE_LEFT,        // deplacement vers start (vitesse < 0)
        MOVE_RIGHT,       // deplacement vers end (vitesse > 0)
        HOLD,             // maintien explicite (reserve)
        ACTION_STALE,     // action trop ancienne -> arret
        LOW_CONFIDENCE,   // confiance insuffisante -> arret
        INSIDE_DEADBAND,  // ecart sous la zone morte -> pas de mouvement
    }
}
