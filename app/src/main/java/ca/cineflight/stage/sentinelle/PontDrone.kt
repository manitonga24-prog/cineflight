package ca.cineflight.stage.sentinelle

/**
 * Interface minimale entre la Sentinelle V2 et le drone.
 *
 * Le noyau sécurité et la machine V2 ne dépendent QUE de cette interface, jamais
 * directement de PontDji / MSDK. Cela permet :
 *  - de tester la logique de sécurité sans drone (avec un faux PontDrone),
 *  - de garder le noyau indépendant de DJI,
 *  - de brancher le vrai PontDji via un adaptateur (cf. ExempleIntegration.kt).
 *
 * Mêmes signatures que PontDji.kt (yaw en deg/s, gimbal en degrés).
 */
interface PontDrone {

    /** Commande de vitesse VirtualStick. yaw en deg/s (ANGULAR_VELOCITY). */
    fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double)

    /** Oriente la nacelle (gimbal). */
    fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean)
}

