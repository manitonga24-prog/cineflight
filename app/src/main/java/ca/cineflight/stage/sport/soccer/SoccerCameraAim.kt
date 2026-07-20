package ca.cineflight.stage.sport.soccer

import kotlin.math.abs

/**
 * SoccerCameraAim — ORIENTATION CAMERA/NACELLE (section 9), pure (aucun SDK/Android).
 *
 * SEPARE de la position du drone : la position donne l'angle general et la distance ;
 * l'orientation garde l'action cadree SANS deplacer inutilement le drone.
 *
 * Regles cinema (doc) :
 *  - viser le centre d'action FILTRE (pas les detections brutes) ;
 *  - ne PAS garder l'action strictement au centre : laisser de l'espace DEVANT la
 *    direction du jeu (lead room) ;
 *  - PRIVILEGIER la nacelle : on ne fait tourner le drone (yaw) que si l'ecart
 *    horizontal depasse une zone morte plus large (evite de faire pivoter tout le
 *    drone pour de petits ajustements) ;
 *  - mouvements DOUX : yaw et nacelle bornes par pas.
 *
 * Entrees en coordonnees IMAGE normalisees [0,1] (cx, cy du centre d'action dans le
 * cadre courant). Sorties : increment de yaw (deg/s) et increment de tilt nacelle (deg).
 */
class SoccerCameraAim(
    /** Zone morte horizontale image sous laquelle on ne corrige pas du tout. */
    private val zoneMorteImage: Float = 0.04f,
    /** Ecart horizontal au-dela duquel on autorise le YAW (sinon nacelle seule). */
    private val seuilYaw: Float = 0.25f,
    /** Lead room : espace laisse DEVANT la direction du jeu (fraction image). */
    private val leadRoom: Float = 0.12f,
    /** Gain yaw (deg/s par unite d'ecart image), borne. */
    private val gainYawDps: Float = 40f,
    private val yawMaxDps: Float = 20f,
    /** Gain tilt nacelle (deg par unite d'ecart image), borne par pas. */
    private val gainTiltDeg: Float = 25f,
    private val tiltStepMaxDeg: Float = 4f,
) {

    /**
     * @param yawDps rotation drone demandee (deg/s), 0 si l'ecart est faible.
     * @param tiltStepDeg increment de nacelle demande (deg, relatif), borne.
     * @param useNacelleOnly true si on n'utilise QUE la nacelle (pas de yaw).
     */
    data class Aim(val yawDps: Float, val tiltStepDeg: Float, val useNacelleOnly: Boolean)

    /**
     * Calcule l'orientation pour viser le centre d'action.
     * @param cx,cy centre d'action dans l'image [0,1].
     * @param dirX direction horizontale du jeu (-1..1 ; >0 = jeu vers la droite).
     */
    fun calculer(cx: Float, cy: Float, dirX: Float): Aim {
        // Cible visee = centre, DECALEE pour laisser de l'espace devant le jeu (lead room).
        // Source UNIQUE de la regle (partagee avec SoccerCadrageScore).
        val cibleX = positionIdealeX(dirX, leadRoom)
        val errX = cx - cibleX          // >0 : action trop a droite -> tourner a droite
        val errY = cy - 0.5f            // >0 : action trop bas -> incliner nacelle vers le bas

        // Zone morte horizontale : rien a corriger.
        if (abs(errX) < zoneMorteImage) {
            return Aim(0f, tiltStep(errY), true)
        }
        // Petit ecart -> nacelle seule (pas de yaw). Gros ecart -> yaw + nacelle.
        return if (abs(errX) < seuilYaw) {
            Aim(0f, tiltStep(errY), true)
        } else {
            val yaw = (gainYawDps * errX).coerceIn(-yawMaxDps, yawMaxDps)
            Aim(yaw, tiltStep(errY), false)
        }
    }

    /** Increment de nacelle borne par pas (mouvement doux). */
    private fun tiltStep(errY: Float): Float =
        (gainTiltDeg * errY).coerceIn(-tiltStepMaxDeg, tiltStepMaxDeg)

    /** Signe borne : -1, 0 ou +1 selon dirX (avec petite zone morte). */
    private fun sign01(v: Float): Float = when {
        v > 0.05f -> 1f
        v < -0.05f -> -1f
        else -> 0f
    }

    companion object {
        /**
         * REGLE LEAD ROOM (source unique) : position ideale de l'action dans l'image [0,1]
         * selon la direction du jeu. Jeu vers la droite -> action decalee a GAUCHE pour
         * laisser voir devant. Utilisee par le controleur camera ET par le score de cadrage.
         * NaN-safe.
         */
        fun positionIdealeX(dirX: Float, leadRoom: Float): Float {
            if (!dirX.isFinite() || !leadRoom.isFinite()) return 0.5f
            val signe = when {
                dirX > 0.05f -> 1f
                dirX < -0.05f -> -1f
                else -> 0f
            }
            return (0.5f - leadRoom * signe).coerceIn(0f, 1f)
        }
    }
}
