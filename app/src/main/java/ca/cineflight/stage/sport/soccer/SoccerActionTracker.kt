package ca.cineflight.stage.sport.soccer

import kotlin.math.abs

/**
 * SoccerActionTracker — FILTRE TEMPOREL pur (aucun SDK, aucun Android) qui stabilise
 * le flux d'estimations image-par-image de [SoccerActionEstimator].
 *
 * PROBLEME : YOLO produit une estimation par frame ; elle tremble, disparait, ou
 * saute. Utiliser ces valeurs brutes ferait changer le drone de direction sans arret.
 *
 * DOCTRINE (verbatim de la spec) :
 *   - confiance faible          -> aucune nouvelle position (on garde l'ancienne) ;
 *   - action perdue (null)      -> conserver la position (jamais de saut vers 0) ;
 *   - action stable N mesures   -> accepter le deplacement ;
 *   - petit deplacement demande -> rester en place (zone morte).
 *
 * PORTEE : OBSERVATION seulement. Ce filtre ne commande NI drone, NI nacelle, NI rail.
 * Sa sortie [SoccerStablePosition] est une position stabilisee, prete a etre projetee
 * plus tard sur le rail (etape distincte, non incluse ici).
 *
 * ETAT INTERNE minimal : la derniere position stable retenue, et une position
 * "candidate" en cours de confirmation avec son compteur de mesures coherentes.
 * Deterministe et testable : le temps est fourni par l'appelant (timestamp des frames).
 */
class SoccerActionTracker(
    /** En-deca de cette confiance, l'estimation est ignoree (position conservee). */
    private val confianceMin: Float = 0.50f,
    /** Nombre de mesures coherentes requises avant d'accepter un nouveau deplacement. */
    private val mesuresStablesRequises: Int = 3,
    /** Deplacement (en position normalisee) en-deca duquel on ne bouge pas (zone morte). */
    private val zoneMorte: Float = 0.05f,
    /** Tolerance : deux mesures sont "coherentes" si elles different de moins que ceci. */
    private val toleranceCoherence: Float = 0.05f,
    /** Age max d'une position stable (ms) avant de la considerer perimee. 0 = jamais. */
    private val ageMaxMs: Long = 2_000L,
) {

    /** Etat de la sortie, pour la tracabilite (logs / diagnostic). */
    enum class Etat {
        /** Aucune position stable encore etablie (rien n'a jamais ete confirme). */
        AUCUNE,

        /** Position stable maintenue (nouvelle mesure ignoree, perdue, ou zone morte). */
        MAINTENUE,

        /** Position mise a jour vers une nouvelle cible confirmee stable. */
        MISE_A_JOUR,

        /** Position stable perimee (trop ancienne) : a traiter comme non fiable. */
        PERIMEE,
    }

    /**
     * Sortie du filtre.
     * @param positionNormalized position stable dans [0f, 1f], ou null si AUCUNE.
     * @param etat pourquoi cette sortie (voir [Etat]).
     * @param confidence confiance de la derniere estimation ayant fixe la position.
     * @param timestampMs horodatage de la derniere estimation retenue.
     */
    data class SoccerStablePosition(
        val positionNormalized: Float?,
        val etat: Etat,
        val confidence: Float,
        val timestampMs: Long,
    )

    // --- Etat interne ---
    private var stable: Float? = null
    private var stableConfidence: Float = 0f
    private var stableTimestampMs: Long = 0L

    private var candidate: Float = 0f
    private var candidateCount: Int = 0

    /** Reinitialise le filtre (nouvelle session). */
    fun reset() {
        stable = null
        stableConfidence = 0f
        stableTimestampMs = 0L
        candidate = 0f
        candidateCount = 0
    }

    /**
     * Ingere une estimation (ou null = action perdue) et rend la position stabilisee.
     * @param estimate estimation de la frame courante, ou null si indisponible.
     * @param nowMs temps courant (ms) pour juger la peremption. Par defaut, le
     *              timestamp de l'estimation ; pour null, l'appelant doit fournir nowMs.
     */
    fun update(estimate: SoccerActionEstimate?, nowMs: Long): SoccerStablePosition {
        // 1) Action perdue -> on CONSERVE la position (jamais de saut).
        if (estimate == null) {
            return sortieMaintenueOuPerimee(nowMs)
        }

        // 2) Confiance faible -> aucune nouvelle position, on conserve.
        if (estimate.confidence < confianceMin) {
            candidateCount = 0   // la serie de confirmation est cassee
            return sortieMaintenueOuPerimee(nowMs)
        }

        val pos = estimate.positionNormalized.coerceIn(0f, 1f)

        // 3) Premiere position stable : on l'accepte directement (rien a conserver).
        if (stable == null) {
            fixer(pos, estimate.confidence, estimate.timestampMs)
            return SoccerStablePosition(stable, Etat.MISE_A_JOUR, stableConfidence, stableTimestampMs)
        }

        // 4) Petit deplacement demande -> zone morte : on reste en place, mais on
        //    rafraichit la fraicheur (la position reste "vue" recemment).
        if (abs(pos - stable!!) < zoneMorte) {
            candidateCount = 0
            stableConfidence = estimate.confidence
            stableTimestampMs = estimate.timestampMs
            return SoccerStablePosition(stable, Etat.MAINTENUE, stableConfidence, stableTimestampMs)
        }

        // 5) Deplacement significatif : il doit etre CONFIRME sur plusieurs mesures
        //    coherentes avant d'etre accepte (anti-saut sur une detection isolee).
        if (candidateCount > 0 && abs(pos - candidate) <= toleranceCoherence) {
            candidate = pos
            candidateCount += 1
        } else {
            candidate = pos
            candidateCount = 1
        }

        return if (candidateCount >= mesuresStablesRequises) {
            fixer(pos, estimate.confidence, estimate.timestampMs)
            candidateCount = 0
            SoccerStablePosition(stable, Etat.MISE_A_JOUR, stableConfidence, stableTimestampMs)
        } else {
            // Pas encore assez confirme : on garde l'ancienne position stable.
            SoccerStablePosition(stable, Etat.MAINTENUE, stableConfidence, stableTimestampMs)
        }
    }

    /** Sortie quand on conserve la position stable (ou signale sa peremption). */
    private fun sortieMaintenueOuPerimee(nowMs: Long): SoccerStablePosition {
        val s = stable
            ?: return SoccerStablePosition(null, Etat.AUCUNE, 0f, 0L)
        val perimee = ageMaxMs > 0 && (nowMs - stableTimestampMs) > ageMaxMs
        val etat = if (perimee) Etat.PERIMEE else Etat.MAINTENUE
        return SoccerStablePosition(s, etat, stableConfidence, stableTimestampMs)
    }

    private fun fixer(pos: Float, conf: Float, ts: Long) {
        stable = pos
        stableConfidence = conf
        stableTimestampMs = ts
    }
}
