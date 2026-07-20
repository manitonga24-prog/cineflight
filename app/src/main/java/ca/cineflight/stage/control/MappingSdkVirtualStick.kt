package ca.cineflight.stage.control

/**
 * MappingSdkVirtualStick — DERNIER maillon logiciel avant le SDK DJI (pur, testable).
 *
 * Isole la correspondance entre la commande assainie (repère BODY : pitch/roll/throttle/yaw)
 * et les 4 champs du VirtualStickFlightControlParam DJI. C'est le mapping qui était auparavant
 * inline dans PontDji.envoyerVitesses ; il est extrait ici pour pouvoir le TESTER sans matériel
 * (test « faux drone » : on vérifie le SIGNE et le mapping envoyés au SDK).
 *
 * IMPORTANT — ce que ce mapping garantit (et ce qu'il ne garantit pas) :
 *  - GARANTIT (testable) : throttle+ -> verticalThrottle+ (intention MONTER) ; yaw inchangé ;
 *    pitch/roll transmis directement, ou échangés si INVERSER_ROLL_PITCH.
 *  - NE GARANTIT PAS : que le drone RÉEL monte quand verticalThrottle est positif. Cette
 *    correspondance physique appartient au SDK/firmware DJI et se valide uniquement par
 *    l'essai physique E-01 (au sol) puis en vol (Phase 2b).
 */
object MappingSdkVirtualStick {

    /** Les 4 valeurs destinées au VirtualStickFlightControlParam DJI (mode VITESSE). */
    data class ParamSdk(
        /** avant(+)/arrière(-) du drone, m/s. */
        val pitch: Double,
        /** droite(+)/gauche(-) du drone, m/s. */
        val roll: Double,
        /** rotation, deg/s. */
        val yaw: Double,
        /** monte(+)/descend(-), m/s. */
        val verticalThrottle: Double,
    )

    /**
     * Traduit une commande assainie (repère BODY) en paramètres SDK.
     *
     * @param pitch avant(+)/arrière(-), m/s.
     * @param roll droite(+)/gauche(-), m/s.
     * @param throttle monte(+)/descend(-), m/s -> verticalThrottle (signe préservé).
     * @param yaw rotation, deg/s (signe préservé).
     * @param inverserRollPitch si true, échange pitch et roll (cf. INVERSER_ROLL_PITCH).
     */
    fun versParam(
        pitch: Float,
        roll: Float,
        throttle: Float,
        yaw: Float,
        inverserRollPitch: Boolean,
    ): ParamSdk {
        val pPitch: Double
        val pRoll: Double
        if (inverserRollPitch) {
            pPitch = roll.toDouble()
            pRoll = pitch.toDouble()
        } else {
            pPitch = pitch.toDouble()
            pRoll = roll.toDouble()
        }
        return ParamSdk(
            pitch = pPitch,
            roll = pRoll,
            yaw = yaw.toDouble(),
            verticalThrottle = throttle.toDouble(),   // throttle+ = MONTER (signe préservé)
        )
    }
}
