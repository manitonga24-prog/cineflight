package ca.cineflight.stage.control

/**
 * ObstacleSafetyGate — ÉTAPE 0 : origine de commande + assainissement + clamp universels.
 *
 * Voir docs/PLAN_OBSTACLE_SAFETY_GATE_20260718.md.
 *
 * Cette étape 0 est APPLIQUÉE RÉELLEMENT (jamais en miroir) : envoyer un NaN/∞ ou une
 * vitesse aberrante au SDK serait dangereux. Le GATE obstacle proprement dit (étapes 1-3)
 * viendra plus tard et sera, lui, d'abord en mode miroir.
 *
 * Aucun comportement de vol n'est modifié tant que les bornes de clamp sont ≥ aux
 * commandes légitimes existantes (cf. AssainisseurVitesse.CLAMP_*), ce qui est le cas par
 * construction (enveloppe supérieure des maxima relevés : PiloteDrone ±2/±60,
 * Phase3 ±4 horiz/±2 vert/±35, PremierVol ≤0.4/≤15).
 */
enum class CommandOrigin {
    /** Mouvement AUTOMATIQUE : suivi RTK, vision, orbite, mission, montée auto, sentinelle.
     *  → soumis au gate obstacle (quand il sera activé). */
    AUTOMATIC,

    /** Pilotage MANUEL : joystick, télécommande, bridge piloté par un humain.
     *  → jamais bridé par le gate. */
    MANUAL,

    /** Banc de TEST : test d'axes (Phase2b), test d'acceptation SDK (Phase2).
     *  → jamais bridé par le gate. */
    TEST,

    /** Origine NON déclarée — état d'ERREUR. Ne doit jamais apparaître après migration.
     *  Traité au moins aussi prudemment que AUTOMATIC (jamais laissé passer comme MANUAL). */
    UNKNOWN
}

/**
 * Assainissement + clamp universels appliqués au TOUT DERNIER point avant le SDK
 * (PontDjiReel.envoyerVitesses). Fonction PURE, testable sans drone ni Android.
 *
 * - NaN / ±Infinity → 0 (finiOuZero).
 * - Chaque axe borné à ±CLAMP_*_MAX (filet de dernier recours contre l'aberrant).
 *
 * Les bornes sont l'ENVELOPPE SUPÉRIEURE des maxima légitimes de tous les chemins connus,
 * donc aucune commande légitime n'est bridée (cf. test dédié).
 */
object AssainisseurVitesse {

    const val CLAMP_HORIZ_MAX = 4.0f   // m/s — pitch, roll (≥ max Phase3 4.0)
    const val CLAMP_VERT_MAX  = 2.0f   // m/s — throttle (≥ max PiloteDrone/Phase3 2.0)
    const val CLAMP_YAW_MAX   = 60.0f  // deg/s — yaw (≥ max PiloteDrone 60)

    /** Commande de vitesse en repère CORPS du drone (m/s lin., deg/s yaw). */
    data class Vitesses(val pitch: Float, val roll: Float, val throttle: Float, val yaw: Float)

    /** NaN / ±Infinity → 0. Pas un clamp de magnitude. */
    private fun finiOuZero(v: Float): Float = if (v.isFinite()) v else 0f

    private fun clamp(v: Float, borne: Float): Float =
        if (v < -borne) -borne else if (v > borne) borne else v

    /**
     * Assainit puis borne les 4 axes. TOUJOURS appliqué avant le SDK (jamais en miroir).
     * @return des vitesses garanties finies et dans les bornes.
     */
    fun assainir(pitch: Float, roll: Float, throttle: Float, yaw: Float): Vitesses = Vitesses(
        pitch    = clamp(finiOuZero(pitch),    CLAMP_HORIZ_MAX),
        roll     = clamp(finiOuZero(roll),     CLAMP_HORIZ_MAX),
        throttle = clamp(finiOuZero(throttle), CLAMP_VERT_MAX),
        yaw      = clamp(finiOuZero(yaw),      CLAMP_YAW_MAX)
    )
}
