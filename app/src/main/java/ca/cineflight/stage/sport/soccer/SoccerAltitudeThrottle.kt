package ca.cineflight.stage.sport.soccer

import kotlin.math.abs

/**
 * SoccerAltitudeThrottle — CONTROLEUR ALTITUDE -> THROTTLE (pur, aucun SDK/Android).
 *
 * PREMIER pas de l'emission REELLE (Essai 1). Convertit l'ecart entre l'altitude OPTIMISEE
 * (proposee par le SoccerDirector) et l'altitude ACTUELLE en une vitesse verticale
 * (throttle, m/s) a envoyer au drone. AUCUN autre axe (roll/pitch/yaw = 0 par l'appelant).
 *
 * Loi simple et prudente :
 *   erreur   = altitudeOptimisee - altitudeActuelle
 *   si |erreur| < zoneMorte -> 0 (pas de micro-corrections)
 *   sinon    -> throttle = Kp * erreur, borne a +/- vMax (Essai 1 : vMax tres bas / 0)
 *
 * SIGNE DJI (a VERIFIER au sol avant tout vol) : dans ce projet, verticalThrottle POSITIF
 * = MONTEE (VerticalControlMode.VELOCITY, cf. PontDji). Donc une altitude optimisee PLUS
 * HAUTE que l'actuelle -> erreur > 0 -> throttle > 0 -> MONTEE. Le signe est isole dans
 * [SENS_THROTTLE_MONTEE] pour pouvoir l'inverser en un seul endroit si un test statique
 * revele la convention inverse sur le drone reel. NE PAS supposer : tester au sol.
 *
 * FAIL-SAFE : toute entree non finie -> throttle 0. Jamais de NaN en sortie.
 * PORTEE : calcul pur. La commande passe OBLIGATOIREMENT par l'arbitre + double verrou.
 */
class SoccerAltitudeThrottle(
    /** Gain proportionnel (m/s par metre d'ecart). Doux. */
    private val kp: Float = 0.3f,
    /** Zone morte (m) : sous cet ecart, on ne bouge pas (anti micro-corrections). */
    private val zoneMorteM: Float = 0.30f,
) {

    /**
     * @param altitudeOptimiseeM altitude cible proposee par le realisateur (m).
     * @param altitudeActuelleM  altitude reelle/estimee courante (m).
     * @param vMaxMps limite de vitesse verticale (m/s). Essai 1 = 0 (double verrou) -> throttle 0.
     * @return throttle en m/s (signe : + = montee dans ce projet), NaN-safe, borne.
     */
    fun throttle(altitudeOptimiseeM: Double, altitudeActuelleM: Double, vMaxMps: Float): Float {
        // Fail-safe : entrees invalides -> aucun mouvement vertical.
        if (!altitudeOptimiseeM.isFinite() || !altitudeActuelleM.isFinite() ||
            !vMaxMps.isFinite() || vMaxMps <= 0f) return 0f

        val erreurM = (altitudeOptimiseeM - altitudeActuelleM).toFloat()
        if (!erreurM.isFinite()) return 0f
        if (abs(erreurM) < zoneMorteM) return 0f

        val brut = kp * erreurM * SENS_THROTTLE_MONTEE
        if (!brut.isFinite()) return 0f
        return brut.coerceIn(-vMaxMps, vMaxMps)
    }

    companion object {
        /**
         * Signe reliant "monter" au signe du throttle. +1 : throttle positif = montee
         * (convention DJI VELOCITY de ce projet). Mettre -1 SI et seulement si un test
         * statique au sol prouve la convention inverse. C'est l'UNIQUE endroit a changer.
         */
        const val SENS_THROTTLE_MONTEE: Float = 1f
    }
}
