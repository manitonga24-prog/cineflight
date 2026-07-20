package ca.cineflight.stage.voice

import kotlin.math.abs

/**
 * PHASE 3A (complement) — Deduction HEURISTIQUE des phases du retour maison (RTH).
 *
 * Le MSDK v5 n'expose pas d'etat de phase RTH fiable. Mais pendant que le RTH est actif,
 * on connait l'altitude AGL, la distance horizontale au point de decollage et la vitesse
 * verticale. Un RTH DJI classique enchaine : MONTEE vers l'altitude de retour -> RETOUR
 * horizontal vers la maison -> DESCENTE au point maison -> TERMINE. On deduit la phase de
 * ces signaux. C'est une ESTIMATION annoncee au pilote, jamais une commande.
 *
 * ISOLATION : classe pure. Fail-open. Anti-bavardage : la phase n'est annoncee (via le
 * hook fourni) qu'au CHANGEMENT, avec des marges pour eviter les oscillations. L'ordre des
 * phases est monotone (on ne "recule" pas de RETOUR vers MONTEE), ce qui evite le bavardage
 * si l'altitude fluctue un peu.
 *
 * @param onPhase rappel invoque avec le code de phase : 1=montee, 2=retour, 3=descente,
 *        4=termine. Cote appelant, ce code est passe a l'adaptateur (observerRthPhase),
 *        qui gere lui-meme la deduplication finale.
 */
class RthPhaseMonitor(
    private val onPhase: (Int) -> Unit
) {
    // Phases internes ordonnees.
    private val AUCUNE = 0
    private val MONTEE = 1
    private val RETOUR = 2
    private val DESCENTE = 3
    private val TERMINE = 4

    // Seuils (m et m/s), ajustables.
    @Volatile var vitesseMonteeMin: Double = 0.4     // au-dessus = considere en montee
    @Volatile var vitesseDescenteMin: Double = 0.4   // en dessous de -ceci = en descente
    @Volatile var distanceMaisonProcheM: Double = 8.0 // sous cette distance = au-dessus de la maison
    @Volatile var altitudeBasseM: Double = 2.0        // sous cette altitude pres de la maison = pose

    @Volatile private var phase = AUCUNE
    @Volatile private var rthActifPrecedent = false

    /**
     * A appeler ~1-3 Hz PENDANT et autour du RTH.
     * @param rthActif etat reel rthEnCours (true tant que le RTH tourne).
     * @param altitudeAglM altitude au-dessus du decollage (NaN si inconnue).
     * @param distanceMaisonM distance horizontale au point de decollage (NaN si inconnue).
     * @param vitesseVertMps vitesse verticale, + = montee (NaN si inconnue).
     */
    fun observer(
        rthActif: Boolean,
        altitudeAglM: Double,
        distanceMaisonM: Double,
        vitesseVertMps: Double
    ) {
        runCatching {
            // --- Debut du RTH : on amorce la phase. ---
            if (rthActif && !rthActifPrecedent) {
                rthActifPrecedent = true
                // Si le drone monte encore -> MONTEE ; sinon il est deja haut -> RETOUR.
                phase = if (!vitesseVertMps.isNaN() && vitesseVertMps > vitesseMonteeMin) MONTEE else RETOUR
                emettre(phase)
                return
            }
            // --- Fin du RTH : termine (une seule fois). ---
            if (!rthActif && rthActifPrecedent) {
                rthActifPrecedent = false
                if (phase != TERMINE && phase != AUCUNE) {
                    phase = TERMINE
                    emettre(TERMINE)
                }
                phase = AUCUNE
                return
            }
            if (!rthActif) return   // pas de RTH : rien a deduire.

            // --- RTH en cours : progression MONOTONE des phases. ---
            val proche = !distanceMaisonM.isNaN() && distanceMaisonM <= distanceMaisonProcheM
            val descend = !vitesseVertMps.isNaN() && vitesseVertMps < -vitesseDescenteMin
            val monte = !vitesseVertMps.isNaN() && vitesseVertMps > vitesseMonteeMin

            when (phase) {
                MONTEE -> {
                    // Fin de montee : la vitesse verticale se stabilise (plafond RTH atteint).
                    if (!monte) { phase = RETOUR; emettre(RETOUR) }
                }
                RETOUR -> {
                    // Arrive au-dessus de la maison et amorce la descente.
                    if (proche && descend) { phase = DESCENTE; emettre(DESCENTE) }
                }
                DESCENTE -> {
                    // Pose : proche de la maison et altitude tres basse.
                    val bas = !altitudeAglM.isNaN() && altitudeAglM <= altitudeBasseM
                    if (proche && bas) { phase = TERMINE; emettre(TERMINE) }
                }
                else -> { /* AUCUNE/TERMINE : rien tant que rthActif reste vrai */ }
            }
        }
    }

    private fun emettre(code: Int) {
        try { onPhase(code) } catch (_: Throwable) {}
    }

    /** Reinitialise a chaque nouvelle session de vol. */
    fun nouvelleSessionVol() {
        runCatching { phase = AUCUNE; rthActifPrecedent = false }
    }
}
