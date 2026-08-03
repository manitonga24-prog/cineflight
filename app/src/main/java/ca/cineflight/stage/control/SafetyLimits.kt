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

    /** Position RTK considérée valide (s). Au-delà : commande automatisée neutralisée/bloquée
     *  (position périmée ; la réaction physique de l'aéronef est à caractériser par E-03). */
    const val RTK_AGE_MAX_S: Double = 2.0

    /** Confiance YOLO minimale pour se fier à une détection [0,1]. En dessous : non fiable. */
    const val YOLO_CONF_MIN: Float = 0.35f

    // ── WATCHDOG DE CYCLE ───────────────────────────────────────────────────────
    /**
     * Âge maximal du dernier battement de la boucle (ms). Au-delà : cycle figé → throttle 0.
     *
     * BUDGET RÉVISÉ le 2026-07-22 (500 → 350 ms) — décision fondée sur l'essai E-03.
     *
     * Avec l'ancienne valeur, le scénario E03-03 (gel du fil d'émission) ne pouvait PAS
     * satisfaire le §378. Démonstration : le watchdog ne déclare la panne qu'au-DELÀ de son
     * timeout, et le thread B ne vérifie qu'à intervalle fixe → détection au pire à
     * `timeout + periode`. Avec 500 + 100, la détection tombait entre 500 et 600 ms
     * (mesuré : 514, 547, 549, 578, 585 ms) alors que le budget de persistance TOTAL est
     * de 500 ms. Le budget était donc consommé en entier par la seule détection, avant même
     * que la réaction commence : 4 répétitions sur 5 en FAIL, à 518..536 ms.
     *
     * Le critère n'était pas trop sévère — la configuration était incompatible avec lui.
     *
     * Nouveau dimensionnement : détection au pire à 350 + 50 = 400 ms ; réaction mesurée
     * 10..40 ms ; total attendu 410..440 ms ; marge ≈ 60 ms sous le seuil de 500 ms.
     * 350 ms tolère ~3,5 périodes de la boucle pilote (10 Hz), ce qui laisse de la gigue
     * d'ordonnancement Android sans rendre le déclenchement fragile.
     *
     * ⚠ PORTÉE : cette constante gouverne AUSSI le watchdog de CYCLE (CommandeWatchdog),
     * pas seulement le watchdog indépendant. Le resserrer rend les deux plus stricts —
     * volontaire, un budget de détection unique se défend mieux au dossier que deux
     * valeurs divergentes. La campagne anti-faux-positif doit couvrir les DEUX.
     */
    const val WATCHDOG_TIMEOUT_MS: Long = 350L

    // ── WATCHDOG INDÉPENDANT (REQ-WDG-001) ──────────────────────────────────────
    /**
     * Période de vérification du thread B indépendant (ms). Doit être < WATCHDOG_TIMEOUT_MS.
     * Ramenée de 100 à 50 ms le 2026-07-22 : c'est elle qui fixe la LATENCE DE DÉTECTION
     * au-delà du timeout (au pire une période entière). La diviser par deux réduit d'autant
     * la dispersion observée sur E03-03, sans coût mesurable (le thread B ne fait qu'une
     * comparaison d'horodatages).
     */
    const val WATCHDOG_INDEP_PERIODE_MS: Long = 50L

    // ── BUDGET DE PERSISTANCE (§378) ────────────────────────────────────────────
    /**
     * Durée maximale pendant laquelle la dernière commande verticale non nulle peut
     * rester en vigueur après une cessation. Exigence du dossier, PAS une valeur de
     * réglage : elle ne se négocie pas pour faire passer un essai.
     * Source unique — [ca.cineflight.stage.sport.soccer.EssaiE03Log] s'y réfère.
     */
    const val PERSISTANCE_MAX_MS: Long = 500L

    /**
     * Budget ALLOUÉ à la réaction (désarmement + commande neutre + sortie Virtual Stick),
     * une fois la défaillance détectée.
     *
     * C'est un CONTRAT, pas un relevé. Les mesures actuelles donnent 10..40 ms ; fixer le
     * budget à la valeur observée rendrait l'invariant dépendant du matériel du jour et
     * il céderait au premier téléphone plus lent. 80 ms laisse le double de la pire
     * réaction mesurée, tout en gardant 20 ms de marge sous le seuil :
     *   350 (timeout) + 50 (période) + 80 (réaction) = 480 < 500.
     */
    const val REACTION_BUDGET_MAX_MS: Long = 80L

    /**
     * Nombre minimal de cycles de la boucle pilote que le timeout doit tolérer avant de
     * déclarer une perte. En dessous, une simple gigue d'ordonnancement Android suffirait
     * à déclencher une mise en sécurité sans cause.
     */
    const val WATCHDOG_CYCLES_BOUCLE_MIN: Int = 3

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

    // ── CONFINEMENT (m) — buffer de contingence (dossier §4.2) ──────────────────
    /** Buffer de confinement PROVISOIRE calculé à vMax 2 m/s (m). VALEUR DE CONCEPTION
     *  non démontrée — ni « robuste » ni majorant tant que E-03/E-12/freinage/latences/
     *  position/vent ne sont pas mesurés ; la mesure peut la confirmer, la réduire OU
     *  l'augmenter (dossier §4.2.2, valeur maîtresse CONF-BUFFER-001). */
    const val BUFFER_CONFINEMENT_M: Double = 16.2

    // ── IDENTIFIANT DE CONFIGURATION ────────────────────────────────────────────
    /**
     * Empreinte STABLE de l'ensemble des seuils ci-dessus (hex). Journalisée à l'armement
     * (traçabilité dossier : quelle configuration de sécurité gouvernait ce vol). Toute
     * modification d'une constante change cette empreinte → revue + re-test exigés.
     */
    val CONFIG_ID: String = run {
        val valeurs = listOf(
            YOLO_FRAIS_MS, RTK_AGE_MAX_S, YOLO_CONF_MIN, WATCHDOG_TIMEOUT_MS,
            WATCHDOG_INDEP_PERIODE_MS,
            // Inclus DÉLIBÉRÉMENT bien qu'ils ne changent aucun comportement à l'exécution :
            // ils portent l'argument de sécurité (budget de persistance et de réaction).
            // Le modifier sans rejouer la campagne serait exactement l'erreur que l'essai
            // E-03 a révélée ; le faire apparaître dans l'empreinte l'empêche de passer
            // inaperçu.
            PERSISTANCE_MAX_MS, REACTION_BUDGET_MAX_MS, WATCHDOG_CYCLES_BOUCLE_MIN,
            BOUCLE_PILOTE_HZ, BOUCLE_TEST_AXES_HZ, POLLER_RTK_HZ,
            BATTERIE_MIN_PCT, V_MAX_HORIZ_MPS, V_MAX_VERT_MPS, SOCCER_2D_VMAX_MPS,
            ALT_MAX_M, ALT_OP_MAX_M, DIST_DECROCHAGE_M, DIST_OPERATEUR_MAX_M,
            BUFFER_CONFINEMENT_M,
        ).joinToString("|") { it.toString() }
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(valeurs.toByteArray(Charsets.UTF_8))
        md.take(8).joinToString("") { "%02x".format(it) }   // 16 hex = suffisant pour tracer
    }
}
