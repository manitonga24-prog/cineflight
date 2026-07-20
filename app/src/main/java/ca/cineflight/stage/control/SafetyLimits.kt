package ca.cineflight.stage.control

/**
 * SafetyLimits — SOURCE UNIQUE DE VÉRITÉ des seuils de sécurité (NC-T3, dossier §10quater S.2).
 *
 * Regroupe en un seul endroit les constantes de sécurité qui, jusqu'ici, étaient déclarées
 * module par module (Phase3Activity, CommandeWatchdog, RailMotionController…). Le but : que
 * la porte pré-vol (go/no-go) ET les points d'émission consomment EXACTEMENT les mêmes valeurs,
 * pour qu'aucune divergence ne puisse s'installer entre « ce qu'on vérifie avant » et « ce qui
 * gouverne pendant ».
 *
 * Chaque constante porte l'unité et le comportement associé au franchissement. Les valeurs
 * reprennent fidèlement celles déjà en vigueur dans le code (aucun changement de comportement).
 *
 * INVARIANT : pur (aucun état, aucune dépendance Android/SDK). Testable directement.
 * Le dossier de sécurité référence ces valeurs ; toute modification doit être re-testée
 * (E-01..E-12) et reportée au registre.
 */
object SafetyLimits {

    // ── FRAÎCHEUR DES DONNÉES (âge maximal toléré) ──────────────────────────────
    /** Détection YOLO considérée « fraîche » (ms). Au-delà : action jugée non fiable → throttle 0. */
    const val YOLO_FRAIS_MS: Long = 600L

    /** Position RTK considérée valide (s). Au-delà : HOVER immédiat (position périmée). */
    const val RTK_AGE_MAX_S: Double = 2.0

    /** Confiance YOLO minimale pour se fier à une détection [0,1]. En dessous : non fiable. */
    const val YOLO_CONF_MIN: Float = 0.35f

    // ── WATCHDOG DE CYCLE ───────────────────────────────────────────────────────
    /** Âge maximal du dernier battement de la boucle (ms). Au-delà : cycle figé → throttle 0. */
    const val WATCHDOG_TIMEOUT_MS: Long = 500L

    // ── FRÉQUENCES DES BOUCLES (Hz) — valeurs de conception ─────────────────────
    /** Boucle pilote (émission de commande) : ~10 Hz (période 100 ms). */
    const val BOUCLE_PILOTE_HZ: Int = 10
    /** Écran de test d'axes : ~15 Hz (période 66 ms). */
    const val BOUCLE_TEST_AXES_HZ: Int = 15
    /** Poller position RTK : ~5 Hz (période 200 ms). */
    const val POLLER_RTK_HZ: Int = 5

    // ── ÉNERGIE ─────────────────────────────────────────────────────────────────
    /** Batterie drone minimale opérationnelle pour le mode soccer (%). En dessous : pas d'armement. */
    const val BATTERIE_MIN_PCT: Int = 40

    // ── VITESSES (m/s) ──────────────────────────────────────────────────────────
    /** Plafond horizontal général (m/s), doux et sûr. */
    const val V_MAX_HORIZ_MPS: Float = 4.0f
    /** Plafond vertical général (m/s). */
    const val V_MAX_VERT_MPS: Float = 2.0f
    /** Vitesse verticale max du mode soccer 2D (m/s). Essai 1 = 0 (double verrou / observation). */
    const val SOCCER_2D_VMAX_MPS: Float = 2.0f  // valeur de conception soccer (rail configuré 2 m/s)

    // ── DISTANCES / GÉOMÉTRIE (m) ───────────────────────────────────────────────
    /** Plafond d'altitude AGL (m). Au-delà : la composante montée est annulée. */
    const val ALT_MAX_M: Double = 60.0
    /** Plafond d'altitude OPÉRATIONNEL soccer (m), bien sous le plafond réglementaire 122 m. */
    const val ALT_OP_MAX_M: Double = 35.0
    /** Décrochage : HOVER si le drone est à plus de cette distance de la cible (m). */
    const val DIST_DECROCHAGE_M: Double = 120.0
    /** Distance max opérateur (décollage) ↔ rail, garantie VLOS (m). */
    const val DIST_OPERATEUR_MAX_M: Double = 200.0

    // ── CONFINEMENT (m) — buffer de contingence (dossier §4.2quater) ─────────────
    /** Buffer de confinement robuste à vMax 2 m/s, pire cas (m). Majorant défendable. */
    const val BUFFER_CONFINEMENT_M: Double = 16.2
}
