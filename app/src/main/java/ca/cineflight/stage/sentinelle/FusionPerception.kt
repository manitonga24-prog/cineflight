package ca.cineflight.stage.sentinelle

import android.util.Log

/**
 * FusionPerception - combine le SCORE D'OUVERTURE visuel (V0) avec les DISTANCES
 * d'obstacle (capteurs du drone via LecteurPerception).
 *
 * ROLE : ameliorer la decision de la Sentinelle sur un drone a capteurs
 * (Mini 4 Pro, Mavic 4 Pro...). La camera juge l'aspect de la scene ; les
 * capteurs mesurent la distance reelle. Ensemble, ils levent les fausses
 * fermetures du V0 (eau scintillante, arbres de bordure) et ajoutent une
 * securite (obstacle proche non vu par la camera).
 *
 * SECURITE - DRAPEAU 'active' :
 *   - Par defaut 'active = false' : la fusion ne fait RIEN, on renvoie le score
 *     visuel pur (comportement actuel, valide). AUCUNE regression possible.
 *   - On passe 'active = true' SEULEMENT apres avoir observe les vraies distances
 *     en vol (Logcat tag LecteurPerception) et calibre les seuils ci-dessous.
 *
 * Logique validee par prototype (7 cas de test, voir test_fusion.py) :
 *   - fusion off / pas de perception -> score visuel inchange
 *   - obstacle proche (< DIST_DANGER) -> score abaisse (securite, meme si visuel bon)
 *   - franchement degage (>= DIST_DEGAGE) + visuel "ferme" a tort -> score remonte
 *   - zone intermediaire -> le visuel decide
 *
 * SEUILS A CALIBRER sur le terrain (valeurs de depart raisonnables, en metres) :
 *   l'unite exacte des distances sera confirmee par les logs du drone reel.
 */
class FusionPerception(
    private val lecteur: LecteurPerception,
    /** Drapeau de securite : false = fusion inactive (score visuel pur). */
    @Volatile var active: Boolean = false
) {
    private val TAG = "FusionPerception"

    companion object {
        // Seuils de depart - A AJUSTER avec les vraies valeurs vues en vol.
        const val DIST_DEGAGE = 10            // m : au-dela, horizontal degage
        const val DIST_DANGER = 3             // m : en deca, obstacle proche -> danger
        const val BONUS_DEGAGE = 25.0         // points ajoutes si degage et visuel bas
        const val MALUS_DANGER = 40.0         // points retires si obstacle proche
        const val CONF_PLANCHER_DEGAGE = 60.0 // confiance min garantie si franchement degage
    }

    private fun clip100(v: Double): Double = if (v < 0.0) 0.0 else if (v > 100.0) 100.0 else v

    /**
     * Enveloppe un scoreFn visuel et retourne un scoreFn fusionne, a passer
     * tel quel a SentinelleRuntime. Si 'active' est false ou la perception
     * indisponible, le score visuel ressort inchange.
     */
    fun envelopper(scoreVisuelFn: () -> Pair<Double, Double>): () -> Pair<Double, Double> {
        return {
            val (scoreV, confV) = scoreVisuelFn()
            fusionner(scoreV, confV)
        }
    }

    /** Coeur de la fusion (port fidele du prototype valide). */
    fun fusionner(scoreVisuel: Double, confVisuel: Double): Pair<Double, Double> {
        // 1. Fusion off OU pas de perception -> score visuel pur.
        if (!active || !lecteur.perceptionDisponible()) {
            return Pair(scoreVisuel, confVisuel)
        }
        val distH = lecteur.distanceHorizontale() ?: return Pair(scoreVisuel, confVisuel)

        var score = scoreVisuel
        var conf = confVisuel

        // 2. Obstacle proche -> on ABAISSE (securite). La camera peut ne pas l'avoir vu.
        if (distH < DIST_DANGER) {
            score = clip100(score - MALUS_DANGER)
            Log.i(TAG, "DANGER distH=${distH}m -> score $scoreVisuel -> $score")
            return Pair(score, conf)
        }

        // 3. Franchement degage -> on leve une eventuelle fausse fermeture visuelle.
        if (distH >= DIST_DEGAGE) {
            if (scoreVisuel < 50.0) {
                score = clip100(score + BONUS_DEGAGE)
            }
            conf = maxOf(conf, CONF_PLANCHER_DEGAGE)
            Log.i(TAG, "DEGAGE distH=${distH}m -> score $scoreVisuel -> $score, conf -> $conf")
            return Pair(score, conf)
        }

        // 4. Zone intermediaire -> le visuel decide.
        return Pair(score, conf)
    }
}

