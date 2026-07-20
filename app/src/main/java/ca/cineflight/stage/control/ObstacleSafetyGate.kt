package ca.cineflight.stage.control

/**
 * ObstacleSafetyGate — ÉTAPE 1 : gate VERTICAL directionnel (évaluateur PUR).
 *
 * Voir docs/PLAN_OBSTACLE_SAFETY_GATE_20260718.md.
 *
 * PÉRIMÈTRE ÉTAPE 1 :
 *  - VERTICAL uniquement (upward / downward, scalaires prouvés par la doc DJI).
 *  - HORIZONTAL non traité ici (secteurs non calibrés → étape 2/3 plus tard).
 *  - Fonction PURE : aucune dépendance Android (pas de Log, pas d'horloge). Les âges et
 *    l'état sont passés en argument. 100% testable sans drone/SDK/Android.
 *  - Le gate est OFF par défaut et n'est branché qu'en MODE MIROIR (calculé + loggé, JAMAIS
 *    appliqué à la commande réelle) tant que les valeurs verticales n'ont pas été observées
 *    en vol.
 *
 * RÈGLES DE SÛRETÉ CLÉS :
 *  - Validité stricte : une distance verticale null/0/négative/sentinelle-non-prouvée est
 *    INDISPONIBLE, JAMAIS « dégagé » (§4.0bis / §4.4). Elle déclenche la staleness du canal.
 *  - Verrouillage : PERCEPTION_STALE, BLOCK_DIRECTION critique et STOP_TRANSLATION critique
 *    suspendent le canal concerné. Pas de reprise automatique : seule une reprise explicite
 *    du pilote lève le verrou (§4.2, correction v5-2).
 *  - Éloignement toujours autorisé côté vertical (sens opposé identifiable) et STOP toujours
 *    autorisé (§4.3 / §4.3bis).
 */
object ObstacleSafetyGate {

    // --- Seuils (mm) et timeout (ms). Valeurs de départ, à calibrer en vol. ---
    const val D_DEGAGE_V = 3000            // vertical dégagé (>= → PASS)
    const val D_STOP_V = 1000              // vertical critique (<= → BLOCK)
    const val D_DEGAGE_H = 4000            // horizontal dégagé (>= → PASS)
    const val D_STOP_H = 2000              // horizontal critique (<= → STOP_TRANSLATION)
    const val TIMEOUT_PERCEPTION_MS = 500L // au-delà : canal périmé
    const val H_MM = 200                   // marge d'hystérésis (sortie SLOW = D_DEGAGE + H_MM)

    /**
     * GARDE-FOU ÉTAPE 2 (horizontal). false = l'étape 2 ne peut PAS être activée reellement.
     * L'orientation des secteurs et la sémantique de la sentinelle 60000 ne sont PAS prouvees
     * (ni code ni doc DJI). Tant que ce n'est pas confirme en vol, l'horizontal ne tourne
     * qu'en MIROIR (calcul + log), jamais applique. NE PAS passer a true sans validation.
     */
    const val ETAPE2_HORIZONTAL_ACTIVABLE = false

    /** Actions possibles du gate (sous-ensemble pertinent pour l'étape 1). */
    enum class GateAction {
        PASS,               // commande inchangée
        SLOW,               // vitesse vers l'obstacle réduite (facteur ∈ ]0,1[)
        BLOCK_DIRECTION,    // vitesse vers l'obstacle = 0 (autres axes libres) — vertical
        STOP_TRANSLATION,   // toutes translations horizontales à 0 (yaw conservé) — horizontal
        PERCEPTION_STALE,   // canal engagé absent/périmé/INDISPONIBLE → mouvement stoppé
        SUSPENDED,          // canal verrouillé — attend une reprise explicite
        DISABLED,           // origine MANUAL/TEST → PASS intégral
        ORIGIN_UNKNOWN      // origine non déclarée → traité prudemment comme AUTOMATIC
    }

    /** Commande de vitesse en repère CORPS (m/s lin., deg/s yaw). */
    data class Vitesses(val pitch: Float, val roll: Float, val throttle: Float, val yaw: Float)

    /**
     * Instantané de perception verticale. Les distances sont en mm, chacune avec son âge
     * (ms, calculé en amont sur une horloge MONOTONE — SystemClock.elapsedRealtime()).
     * null = jamais reçu. Étape 1 : seuls upward/downward sont utilisés.
     */
    data class VerticalSnapshot(
        val upwardMm: Int?,
        val upwardAgeMs: Long,
        val downwardMm: Int?,
        val downwardAgeMs: Long
    )

    /** État persistant PAR CANAL (suspensions verrouillées + mémoire d'hystérésis). */
    data class SafetyState(
        val upSuspended: Boolean = false,
        val downSuspended: Boolean = false,
        val horizSuspended: Boolean = false,
        val upSlowing: Boolean = false,
        val downSlowing: Boolean = false,
        val horizSlowing: Boolean = false
    )

    /**
     * Instantané horizontal : liste des distances par secteur (mm) + âge (monotone).
     * ORIENTATION DES SECTEURS NON CALIBRÉE → on n'utilise QUE la distance minimale (frein
     * omnidirectionnel), jamais une direction. null = jamais reçu.
     */
    data class HorizontalSnapshot(
        val distancesMm: List<Int>?,
        val ageMs: Long
    )

    /** Résultat de l'analyse horizontale (miroir) : min filtré + diagnostic. */
    data class HorizontalAnalyse(
        val minMm: Int?,        // plus petite distance < sentinelle, ou null si INDISPONIBLE
        val nbFiltres: Int,     // nb de secteurs >= sentinelle (ou invalides) ecartes
        val nbTotal: Int,       // taille de la liste brute
        val indisponible: Boolean,
        val raison: String
    )

    data class GateResult(
        val command: Vitesses,   // commande éventuellement modifiée (throttle seul en étape 1)
        val action: GateAction,
        val state: SafetyState,  // état MIS À JOUR (le miroir tient son propre état, cf. plan §6)
        val reason: String
    )

    // ----------------------------------------------------------------------------
    // Validité d'une mesure verticale — INDISPONIBLE ≠ dégagé.
    // Une distance est exploitable seulement si elle est présente ET strictement positive
    // ET sous la sentinelle non prouvée. Tout le reste = INDISPONIBLE.
    // ----------------------------------------------------------------------------
    /** Valeur ~"rien détecté" observée (expérimentale, non certifiée DJI). */
    const val SENTINELLE_MM = 60000

    private fun mesureExploitable(mm: Int?, ageMs: Long): Boolean {
        if (mm == null) return false            // jamais reçu
        if (mm <= 0) return false               // 0 ou négatif : non plausible → INDISPONIBLE
        if (mm >= SENTINELLE_MM) return false   // sentinelle non prouvée → PAS « dégagé »
        if (ageMs > TIMEOUT_PERCEPTION_MS) return false // périmé
        return true
    }

    /** Facteur de ralentissement borné [0,1] pour une distance en zone approche. */
    private fun facteurSlow(distMm: Int): Float {
        val f = (distMm - D_STOP_V).toFloat() / (D_DEGAGE_V - D_STOP_V).toFloat()
        return if (f < 0f) 0f else if (f > 1f) 1f else f
    }

    /**
     * Évalue une commande AUTOMATIQUE au regard de la perception verticale. PURE.
     *
     * @param cmd commande courante (repère corps).
     * @param perception instantané vertical cohérent (upward/downward + âges monotones).
     * @param origin origine de la commande.
     * @param state état persistant courant (suspensions + hystérésis) du canal.
     * @param resumeRequested true si le pilote a explicitement demandé la reprise (lève les verrous).
     * @return résultat + état mis à jour. NE modifie PAS state en place (renvoie une copie).
     */
    fun evaluate(
        cmd: Vitesses,
        perception: VerticalSnapshot?,
        origin: CommandOrigin,
        state: SafetyState,
        resumeRequested: Boolean = false
    ): GateResult {
        // Reprise explicite : lève tous les verrous (choix par défaut : global).
        var st = if (resumeRequested) SafetyState() else state

        // Origines non automatiques : le gate ne touche à rien.
        when (origin) {
            CommandOrigin.MANUAL, CommandOrigin.TEST ->
                return GateResult(cmd, GateAction.DISABLED, st, "origin=$origin")
            else -> { /* AUTOMATIC ou UNKNOWN : gate actif (UNKNOWN = prudent) */ }
        }
        val unknownNote = if (origin == CommandOrigin.UNKNOWN) " origin=UNKNOWN(prudent)" else ""

        val montee = cmd.throttle > 0f
        val descente = cmd.throttle < 0f

        // Pas de mouvement vertical : rien à filtrer côté vertical (étape 1). PASS.
        if (!montee && !descente) {
            return GateResult(cmd, GateAction.PASS, st, "vertical=0$unknownNote")
        }

        // Canal engagé par la commande.
        val mm: Int?; val ageMs: Long; val dejaSuspendu: Boolean; val dejaSlowing: Boolean
        if (montee) {
            mm = perception?.upwardMm; ageMs = perception?.upwardAgeMs ?: Long.MAX_VALUE
            dejaSuspendu = st.upSuspended; dejaSlowing = st.upSlowing
        } else {
            mm = perception?.downwardMm; ageMs = perception?.downwardAgeMs ?: Long.MAX_VALUE
            dejaSuspendu = st.downSuspended; dejaSlowing = st.downSlowing
        }

        // Canal déjà verrouillé : reste suspendu (pas de reprise auto), on bloque ce sens.
        if (dejaSuspendu) {
            return GateResult(bloquerVertical(cmd), GateAction.SUSPENDED, st,
                "canal ${if (montee) "up" else "down"} verrouille$unknownNote")
        }

        // Mesure INDISPONIBLE (null / <=0 / sentinelle / périmée) → staleness + verrou du canal.
        if (!mesureExploitable(mm, ageMs)) {
            st = if (montee) st.copy(upSuspended = true, upSlowing = false)
                 else st.copy(downSuspended = true, downSlowing = false)
            return GateResult(bloquerVertical(cmd), GateAction.PERCEPTION_STALE, st,
                "vertical INDISPONIBLE (mm=${mm ?: "null"} age=$ageMs)$unknownNote")
        }

        val d = mm!! // exploitable => non null

        // Barème : loin → PASS ; approche → SLOW ; proche → BLOCK_DIRECTION (verrouille).
        // Hystérésis : on sort de SLOW seulement au-dessus de D_DEGAGE + H_MM.
        val seuilSortie = D_DEGAGE_V + H_MM
        val enDegage = if (dejaSlowing) d >= seuilSortie else d >= D_DEGAGE_V

        return when {
            d <= D_STOP_V -> {
                // Critique : bloque le sens vers l'obstacle ET verrouille (pas de reprise auto).
                st = if (montee) st.copy(upSuspended = true, upSlowing = false)
                     else st.copy(downSuspended = true, downSlowing = false)
                GateResult(bloquerVertical(cmd), GateAction.BLOCK_DIRECTION, st,
                    "vertical critique d=${d}mm$unknownNote")
            }
            enDegage -> {
                st = if (montee) st.copy(upSlowing = false) else st.copy(downSlowing = false)
                GateResult(cmd, GateAction.PASS, st, "vertical degage d=${d}mm$unknownNote")
            }
            else -> {
                val f = facteurSlow(d)
                val cmdSlow = cmd.copy(throttle = cmd.throttle * f)
                st = if (montee) st.copy(upSlowing = true) else st.copy(downSlowing = true)
                GateResult(cmdSlow, GateAction.SLOW, st,
                    "vertical approche d=${d}mm facteur=${"%.2f".format(f)}$unknownNote")
            }
        }
    }

    /** Met à 0 la composante verticale (throttle), laisse pitch/roll/yaw intacts. */
    private fun bloquerVertical(cmd: Vitesses): Vitesses = cmd.copy(throttle = 0f)

    // ============================================================================
    // ÉTAPE 2 — HORIZONTAL CONSERVATEUR (miroir, NON activable tant que 60000 non prouvee).
    // Aucune direction : on utilise SEULEMENT la distance minimale (frein omnidirectionnel).
    // ============================================================================

    /** Facteur SLOW horizontal borné [0,1] (mêmes règles que le vertical, seuils H). */
    private fun facteurSlowH(distMm: Int): Float {
        val f = (distMm - D_STOP_H).toFloat() / (D_DEGAGE_H - D_STOP_H).toFloat()
        return if (f < 0f) 0f else if (f > 1f) 1f else f
    }

    /**
     * Analyse la liste horizontale : filtre les secteurs >= sentinelle (et invalides <=0),
     * calcule min_h sur ce qui reste. Si la liste filtree est VIDE (tout >= 60000, ou liste
     * vide/null) → INDISPONIBLE, JAMAIS « degage » (§4.0bis). Journalise le diagnostic.
     */
    fun analyserHorizontal(snap: HorizontalSnapshot?): HorizontalAnalyse {
        val liste = snap?.distancesMm
        val ageMs = snap?.ageMs ?: Long.MAX_VALUE
        if (liste == null || liste.isEmpty()) {
            return HorizontalAnalyse(null, 0, 0, indisponible = true, raison = "liste vide/null")
        }
        if (ageMs > TIMEOUT_PERCEPTION_MS) {
            return HorizontalAnalyse(null, 0, liste.size, indisponible = true, raison = "perime age=$ageMs")
        }
        // Exploitables = valeurs > 0 ET < sentinelle. Le reste est filtre.
        val exploitables = liste.filter { it in 1 until SENTINELLE_MM }
        val nbFiltres = liste.size - exploitables.size
        if (exploitables.isEmpty()) {
            // tout vaut ~60000 (ou invalide) : on ne sait PAS si c'est « rien a proximite »
            // tant que 60000 n'est pas prouvee -> INDISPONIBLE, pas « degage ».
            return HorizontalAnalyse(null, nbFiltres, liste.size, indisponible = true,
                raison = "tout>=sentinelle/invalide (60000 non prouvee)")
        }
        val minMm = exploitables.min()
        return HorizontalAnalyse(minMm, nbFiltres, liste.size, indisponible = false,
            raison = "min_h=$minMm nb_filtres=$nbFiltres/${liste.size}")
    }

    /**
     * Évalue le volet HORIZONTAL (conservateur, omnidirectionnel). PURE.
     * IMPORTANT : conservateur = on freine/arrete TOUTES les translations, meme celles qui
     * s'eloigneraient, car l'orientation est inconnue (pas d'exception d'eloignement, §4.5).
     * Le yaw et le vertical restent intacts. Une translation horizontale = pitch!=0 || roll!=0.
     *
     * @param resumeRequested leve le verrou horizontal.
     */
    fun evaluateHorizontal(
        cmd: Vitesses,
        horizontal: HorizontalSnapshot?,
        origin: CommandOrigin,
        state: SafetyState,
        resumeRequested: Boolean = false
    ): GateResult {
        var st = if (resumeRequested) state.copy(horizSuspended = false, horizSlowing = false) else state

        when (origin) {
            CommandOrigin.MANUAL, CommandOrigin.TEST ->
                return GateResult(cmd, GateAction.DISABLED, st, "origin=$origin")
            else -> {}
        }
        val unknownNote = if (origin == CommandOrigin.UNKNOWN) " origin=UNKNOWN(prudent)" else ""

        val translation = cmd.pitch != 0f || cmd.roll != 0f
        if (!translation) {
            return GateResult(cmd, GateAction.PASS, st, "horizontal=0$unknownNote")
        }

        // Canal deja verrouille : reste suspendu (pas de reprise auto).
        if (st.horizSuspended) {
            return GateResult(bloquerHorizontal(cmd), GateAction.SUSPENDED, st, "horiz verrouille$unknownNote")
        }

        val a = analyserHorizontal(horizontal)
        if (a.indisponible) {
            st = st.copy(horizSuspended = true, horizSlowing = false)
            return GateResult(bloquerHorizontal(cmd), GateAction.PERCEPTION_STALE, st,
                "horizontal INDISPONIBLE (${a.raison})$unknownNote")
        }

        val d = a.minMm!!
        val seuilSortie = D_DEGAGE_H + H_MM
        val enDegage = if (st.horizSlowing) d >= seuilSortie else d >= D_DEGAGE_H

        return when {
            d <= D_STOP_H -> {
                // Critique : arrete TOUTES les translations horizontales ET verrouille.
                st = st.copy(horizSuspended = true, horizSlowing = false)
                GateResult(bloquerHorizontal(cmd), GateAction.STOP_TRANSLATION, st,
                    "horizontal critique min_h=${d}mm (${a.raison})$unknownNote")
            }
            enDegage -> {
                st = st.copy(horizSlowing = false)
                GateResult(cmd, GateAction.PASS, st, "horizontal degage min_h=${d}mm$unknownNote")
            }
            else -> {
                // SLOW : reduit la NORME horizontale (pitch et roll par le meme facteur).
                val f = facteurSlowH(d)
                val cmdSlow = cmd.copy(pitch = cmd.pitch * f, roll = cmd.roll * f)
                st = st.copy(horizSlowing = true)
                GateResult(cmdSlow, GateAction.SLOW, st,
                    "horizontal approche min_h=${d}mm facteur=${"%.2f".format(f)} (${a.raison})$unknownNote")
            }
        }
    }

    /** Met à 0 les translations horizontales (pitch, roll), laisse throttle/yaw intacts. */
    private fun bloquerHorizontal(cmd: Vitesses): Vitesses = cmd.copy(pitch = 0f, roll = 0f)
}
