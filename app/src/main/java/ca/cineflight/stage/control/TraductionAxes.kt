package ca.cineflight.stage.control

import kotlin.math.cos
import kotlin.math.sin

/**
 * TraductionAxes — le cœur délicat de l'app : convertir une commande du bridge
 * (vitesses dans le repère SCÈNE, convention OpenVR) en une commande Virtual
 * Stick DJI (dans le repère du DRONE).
 *
 * C'est ICI que le bug Y/Z historique guette. Chaque correspondance est
 * explicite et documentée. À vérifier en vol à TRÈS BASSE ALTITUDE (voir
 * VerificationAxes) avant tout vol réel.
 *
 * ── REPÈRE BRIDGE (scène, OpenVR) ────────────────────────────────────
 *   vx = vitesse latérale   (axe X scène : + vers la droite scène)
 *   vy = vitesse VERTICALE  (axe Y scène : + vers le HAUT)   <-- altitude
 *   vz = vitesse profondeur (axe Z scène : + vers le fond de scène)
 *   yawRate = vitesse de rotation (deg/s, + = sens horaire vu de dessus)
 *
 * ── REPÈRE DJI VIRTUAL STICK (drone) ─────────────────────────────────
 *   En mode stick "vitesse", DJI MSDK v5 attend (selon le coordinate system) :
 *     pitch  = vitesse avant/arrière du DRONE (+ avance, dans le nez du drone)
 *     roll   = vitesse gauche/droite du DRONE (+ vers la droite du drone)
 *     verticalThrottle = vitesse verticale (+ monte)         <-- = vy
 *     yaw    = vitesse de rotation (deg/s)                    <-- = yawRate
 *
 * ── LE POINT CLÉ : le drone TOURNE pendant le vol ────────────────────
 * Le bridge calcule un yawRate pour pointer la caméra vers le sujet. Donc le
 * cap du drone change en permanence. Les vitesses vx/vz du bridge sont dans le
 * repère SCÈNE (fixe), mais pitch/roll DJI sont dans le repère du DRONE (qui
 * tourne). Il faut donc faire pivoter (vx, vz) du cap courant du drone pour
 * obtenir (roll, pitch). C'est la rotation 2D ci-dessous. Sans elle, le drone
 * partirait "de travers" dès qu'il n'est plus aligné avec la scène.
 */
object TraductionAxes {

    /**
     * Commande Virtual Stick prête pour DJI (repère drone).
     * Unités : m/s pour les vitesses linéaires, deg/s pour le yaw.
     */
    data class CommandeDji(
        val pitch: Float,             // avant(+)/arrière(-) du drone, m/s
        val roll: Float,              // droite(+)/gauche(-) du drone, m/s
        val verticalThrottle: Float,  // monte(+)/descend(-), m/s
        val yaw: Float                // rotation, deg/s
    )

    /**
     * Convertit une commande bridge en commande DJI, en tenant compte du CAP
     * COURANT du drone (capDroneDeg : yaw réel du drone dans le repère scène,
     * en degrés, 0 = nez vers +Z profondeur, sens horaire positif).
     *
     * @param vx vitesse latérale scène (m/s)
     * @param vy vitesse verticale scène (m/s)  -> verticalThrottle
     * @param vz vitesse profondeur scène (m/s)
     * @param yawRate vitesse de rotation (deg/s) -> yaw
     * @param capDroneDeg cap actuel du drone dans le repère scène (deg)
     */
    fun versDji(
        vx: Float, vy: Float, vz: Float, yawRate: Float,
        capDroneDeg: Float
    ): CommandeDji {
        // rotation du vecteur vitesse horizontal (vx, vz) du repère SCÈNE vers
        // le repère DRONE : on tourne de -cap (on "annule" l'orientation du drone).
        val cap = Math.toRadians(capDroneDeg.toDouble())
        val cosC = cos(cap)
        val sinC = sin(cap)

        // Dans le repère scène : axe profondeur = Z (avant nominal), axe latéral = X.
        // Le pitch DJI = composante de la vitesse selon l'axe "nez du drone".
        // Le roll  DJI = composante selon l'axe "droite du drone".
        // Rotation 2D inverse (monde -> corps) :
        //   pitch =  vz*cos(cap) + vx*sin(cap)
        //   roll  = -vz*sin(cap) + vx*cos(cap)
        val pitch = (vz * cosC + vx * sinC).toFloat().finiOuZero()
        val roll = (-vz * sinC + vx * cosC).toFloat().finiOuZero()

        // L'altitude est commune (verticale) aux deux repères : vy -> throttle.
        // Pas de conversion d'axe ici — c'est précisément le piège Y/Z évité.
        val throttle = vy.finiOuZero()

        return CommandeDji(
            pitch = pitch,
            roll = roll,
            verticalThrottle = throttle,
            yaw = yawRate.finiOuZero()
        )
    }

    /** Garde de finitude : NaN / +-Infini -> 0 (fail-safe axes). */
    private fun Float.finiOuZero(): Float = if (isFinite()) this else 0f
}

