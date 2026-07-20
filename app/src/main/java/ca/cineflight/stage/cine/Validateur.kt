package ca.cineflight.stage.cine

/**
 * Le VALIDATEUR : derniere etape, autorite finale. Fonction pure (Contexte -> Verdict).
 * Logique : Verifier -> Adapter (corriger si possible) -> Refuser (avec alternative).
 *
 * La pastille 🟢/🟡/🔴 est une simple projection du verdict.
 */

enum class Pastille(val emoji: String, val texte: String) {
    VERT("🟢", "Tres sur"),
    JAUNE("🟡", "Ajuste pour votre espace"),
    ROUGE("🔴", "Non recommande ici")
}

sealed class Verdict {
    abstract fun pastille(): Pastille

    data class Valide(val sequence: Sequence) : Verdict() {
        override fun pastille() = Pastille.VERT
    }
    data class Adapte(val sequence: Sequence, val raisons: List<String>) : Verdict() {
        override fun pastille() = Pastille.JAUNE
    }
    data class Refuse(val message: String, val alternative: Recette) : Verdict() {
        override fun pastille() = Pastille.ROUGE
    }
}

object Validateur {

    // Bornes de securite (alignees sur les Param de Reglages)
    private const val DIST_ARRET_MIN = 2.0f      // distance d'arret en approche
    private const val RAYON_RESTREINT_MAX = 4.0f // rayon d'orbite max en espace restreint
    private const val RAYON_MOYEN_MAX = 7.0f     // rayon d'orbite max en espace moyen
    private const val DIST_RESTREINT_MAX = 4.0f  // distance travelling max en espace restreint
    private const val BATTERIE_MIN_PCT = 20      // sous ce seuil : marge de retour insuffisante
    private const val SECONDES_PAR_PCT = 1.2f    // estimation grossiere : 1% ~ 1.2 s de vol utile

    /**
     * Valide une sequence dans un contexte. Tente d'adapter avant de refuser.
     */
    fun valider(seq: Sequence, ctx: ContexteValidation, alternative: Recette): Verdict {
        val raisons = mutableListOf<String>()
        var pas = seq.pas.toMutableList()

        // --- RANG 1 & 2 : Adapter le recul aveugle sur drone sans evitement ---
        if (ctx.drone.reculAveugleRisque && !ctx.espaceArriereConnu) {
            var change = false
            pas = pas.map { p ->
                if (p.mouvement.estReculAveugle) {
                    change = true
                    p.copy(mouvement = Mouvement.SUIVI) // le drone voit ou il va
                } else p
            }.toMutableList()
            if (change) raisons.add("Recul remplace par Suivi (drone sans evitement arriere)")
        }
        // Brider le plafond de recul restant (si capteurs partiels/complets gardent le recul)
        // -> ici on borne la vitesse logique ; la vitesse reelle est mappee plus tard.

        // --- RANG 2 : Adapter l'amplitude a l'espace ---
        val rayonMax = when (ctx.espace) {
            Espace.RESTREINT -> RAYON_RESTREINT_MAX
            Espace.MOYEN     -> RAYON_MOYEN_MAX
            Espace.LARGE     -> Float.MAX_VALUE
        }
        val distMax = when (ctx.espace) {
            Espace.RESTREINT -> DIST_RESTREINT_MAX
            else             -> Float.MAX_VALUE
        }
        var amplitudeReduite = false
        pas = pas.map { p ->
            var np = p
            if (np.rayonM > rayonMax) { np = np.copy(rayonM = rayonMax); amplitudeReduite = true }
            if (np.distanceM > distMax) { np = np.copy(distanceM = distMax); amplitudeReduite = true }
            // distance d'arret minimale en approche
            if (np.mouvement == Mouvement.APPROCHE && np.distanceM < DIST_ARRET_MIN) {
                np = np.copy(distanceM = DIST_ARRET_MIN)
            }
            np
        }.toMutableList()
        if (amplitudeReduite) raisons.add("Amplitude reduite pour tenir dans l'espace")

        // --- RANG 1 : Plafonner la hauteur (modelisee via reglage externe; ici symbolique) ---
        // (la hauteur reelle est geree a l'execution ; on garde la regle documentee)

        // --- RANG 3 : GPS requis pour les mouvements ancres ---
        if (Sequence(pas).exigeGps() && !ctx.gpsValide) {
            // Tenter d'adapter : retirer les pas exigeant le GPS s'il reste une sequence utile
            val sansGps = pas.filterNot { it.mouvement.exigeGps }
            if (sansGps.isNotEmpty()) {
                pas = sansGps.toMutableList()
                raisons.add("Mouvements ancres retires (GPS instable)")
            } else {
                return Verdict.Refuse("Position GPS instable — ce plan n'est pas possible ici.", alternative)
            }
        }

        // --- RANG 3 : Batterie suffisante pour la duree + marge ---
        if (ctx.batteriePct < BATTERIE_MIN_PCT) {
            return Verdict.Refuse("Batterie trop basse pour voler en securite.", alternative)
        }
        val dureeDispo = (ctx.batteriePct - BATTERIE_MIN_PCT) * SECONDES_PAR_PCT
        var seq2 = Sequence(pas)
        if (seq2.dureeTotaleS > dureeDispo) {
            // Adapter : raccourcir en retirant les derniers pas jusqu'a tenir
            val raccourci = mutableListOf<Pas>()
            var cumul = 0
            for (p in pas) {
                if (cumul + p.dureeS <= dureeDispo) { raccourci.add(p); cumul += p.dureeS }
                else break
            }
            if (raccourci.isEmpty()) {
                return Verdict.Refuse("Batterie insuffisante pour ce plan.", alternative)
            }
            pas = raccourci
            raisons.add("Sequence raccourcie (batterie limitee)")
        }

        // --- RANG 2 : Espace trop restreint pour TOUT mouvement ample ---
        seq2 = Sequence(pas)
        val toutAmple = seq2.pas.all { it.mouvement == Mouvement.ORBITE || it.mouvement == Mouvement.TRAVELLING || it.mouvement == Mouvement.REVELATION }
        if (ctx.espace == Espace.RESTREINT && toutAmple) {
            return Verdict.Refuse("L'espace est un peu serre pour ce plan.", alternative)
        }

        val finale = Sequence(pas)
        return if (raisons.isEmpty()) Verdict.Valide(finale)
               else Verdict.Adapte(finale, raisons)
    }
}

