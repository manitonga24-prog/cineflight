package ca.cineflight.stage.cine

/**
 * La GRAMMAIRE : table deterministe (Scene x Effet) -> sequence brute.
 * Aucune IA. Memes entrees => memes sorties, toujours.
 *
 * La table maitresse encode le savoir cinema. Les durees sont reparties
 * proportionnellement sur la duree totale demandee par la recette.
 */
object Grammaire {

    /** Modele brut d'une cellule : la liste ordonnee de (mouvement, plan), + vitesse dominante. */
    private data class Cellule(
        val mouvements: List<Pair<Mouvement, Plan>>,
        val vitesse: Vitesse
    )

    // Raccourcis lisibles
    private val GROS = Plan.GROS; private val AME = Plan.AMERICAIN
    private val PIED = Plan.PIED; private val ENS = Plan.ENSEMBLE
    private val M = Mouvement.values()

    private fun c(vitesse: Vitesse, vararg mp: Pair<Mouvement, Plan>) =
        Cellule(mp.toList(), vitesse)

    // --- LA TABLE MAITRESSE (extrait coherent avec le document Grammaire) ---
    private val TABLE: Map<Pair<Scene, Effet>, Cellule> = mapOf(
        // 3.1 Danseur solo
        (Scene.DANSEUR_SOLO to Effet.EMOTION)   to c(Vitesse.LENTE,   Mouvement.APPROCHE to GROS, Mouvement.ORBITE to AME, Mouvement.STATIQUE to GROS),
        (Scene.DANSEUR_SOLO to Effet.ENERGIE)   to c(Vitesse.RAPIDE,  Mouvement.ORBITE to PIED, Mouvement.TRAVELLING to PIED, Mouvement.ORBITE to PIED),
        (Scene.DANSEUR_SOLO to Effet.INTIMITE)  to c(Vitesse.LENTE,   Mouvement.STATIQUE to AME, Mouvement.APPROCHE to AME),
        (Scene.DANSEUR_SOLO to Effet.PUISSANCE) to c(Vitesse.MODEREE, Mouvement.APPROCHE to PIED, Mouvement.ORBITE to PIED),
        (Scene.DANSEUR_SOLO to Effet.GRANDEUR)  to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.APPROCHE to PIED),
        // 3.2 Groupe de danse
        (Scene.GROUPE_DANSE to Effet.ENERGIE)     to c(Vitesse.RAPIDE,  Mouvement.TRAVELLING to PIED, Mouvement.ORBITE to ENS, Mouvement.RECUL to PIED),
        (Scene.GROUPE_DANSE to Effet.CELEBRATION) to c(Vitesse.MODEREE, Mouvement.ORBITE to ENS, Mouvement.TRAVELLING to ENS, Mouvement.REVELATION to ENS),
        (Scene.GROUPE_DANSE to Effet.GRANDEUR)    to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.ORBITE to ENS),
        (Scene.GROUPE_DANSE to Effet.EMOTION)     to c(Vitesse.LENTE,   Mouvement.APPROCHE to AME, Mouvement.ORBITE to AME),
        // 3.3 Chanteur
        (Scene.CHANTEUR to Effet.EMOTION)     to c(Vitesse.LENTE,   Mouvement.APPROCHE to GROS, Mouvement.STATIQUE to GROS),
        (Scene.CHANTEUR to Effet.INTIMITE)    to c(Vitesse.LENTE,   Mouvement.STATIQUE to GROS, Mouvement.APPROCHE to AME),
        (Scene.CHANTEUR to Effet.PUISSANCE)   to c(Vitesse.MODEREE, Mouvement.APPROCHE to AME, Mouvement.RECUL to AME),
        (Scene.CHANTEUR to Effet.CELEBRATION) to c(Vitesse.MODEREE, Mouvement.ORBITE to PIED, Mouvement.RECUL to PIED),
        // 3.4 Groupe de musique
        (Scene.GROUPE_MUSIQUE to Effet.ENERGIE)     to c(Vitesse.RAPIDE,  Mouvement.TRAVELLING to ENS, Mouvement.ORBITE to ENS, Mouvement.TRAVELLING to ENS),
        (Scene.GROUPE_MUSIQUE to Effet.CELEBRATION) to c(Vitesse.MODEREE, Mouvement.ORBITE to ENS, Mouvement.REVELATION to ENS),
        (Scene.GROUPE_MUSIQUE to Effet.PUISSANCE)   to c(Vitesse.MODEREE, Mouvement.APPROCHE to PIED, Mouvement.ORBITE to PIED),
        (Scene.GROUPE_MUSIQUE to Effet.GRANDEUR)    to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.ORBITE to ENS),
        // 3.5 Mariage
        (Scene.MARIAGE to Effet.EMOTION)     to c(Vitesse.LENTE,   Mouvement.APPROCHE to AME, Mouvement.ORBITE to AME),
        (Scene.MARIAGE to Effet.GRANDEUR)    to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.ORBITE to ENS),
        (Scene.MARIAGE to Effet.INTIMITE)    to c(Vitesse.LENTE,   Mouvement.STATIQUE to GROS, Mouvement.APPROCHE to GROS),
        (Scene.MARIAGE to Effet.CELEBRATION) to c(Vitesse.MODEREE, Mouvement.ORBITE to PIED, Mouvement.TRAVELLING to ENS),
        // 3.6 Athlete
        (Scene.ATHLETE to Effet.PUISSANCE) to c(Vitesse.RAPIDE,  Mouvement.SUIVI to PIED, Mouvement.TRAVELLING to PIED),
        (Scene.ATHLETE to Effet.ENERGIE)   to c(Vitesse.RAPIDE,  Mouvement.TRAVELLING to PIED, Mouvement.RECUL to AME, Mouvement.SUIVI to PIED),
        (Scene.ATHLETE to Effet.LIBERTE)   to c(Vitesse.RAPIDE,  Mouvement.SUIVI to ENS, Mouvement.TRAVELLING to ENS),
        (Scene.ATHLETE to Effet.GRANDEUR)  to c(Vitesse.MODEREE, Mouvement.REVELATION to ENS, Mouvement.SUIVI to ENS),
        // 3.7 Vehicule
        (Scene.VEHICULE to Effet.LIBERTE)   to c(Vitesse.RAPIDE,  Mouvement.SUIVI to ENS, Mouvement.TRAVELLING to ENS, Mouvement.RECUL to ENS),
        (Scene.VEHICULE to Effet.PUISSANCE) to c(Vitesse.RAPIDE,  Mouvement.RECUL to AME, Mouvement.SUIVI to AME),
        (Scene.VEHICULE to Effet.ENERGIE)   to c(Vitesse.RAPIDE,  Mouvement.TRAVELLING to PIED, Mouvement.ORBITE to PIED),
        (Scene.VEHICULE to Effet.GRANDEUR)  to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.SUIVI to ENS),
        // 3.8 Paysage
        (Scene.PAYSAGE to Effet.GRANDEUR) to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.TRAVELLING to ENS),
        (Scene.PAYSAGE to Effet.MYSTERE)  to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.ORBITE to ENS),
        (Scene.PAYSAGE to Effet.LIBERTE)  to c(Vitesse.MODEREE, Mouvement.TRAVELLING to ENS, Mouvement.REVELATION to ENS),
        (Scene.PAYSAGE to Effet.INTIMITE) to c(Vitesse.LENTE,   Mouvement.APPROCHE to AME, Mouvement.STATIQUE to AME),
        // 3.9 Discours
        (Scene.DISCOURS to Effet.INTIMITE)  to c(Vitesse.LENTE,   Mouvement.STATIQUE to AME, Mouvement.APPROCHE to AME),
        (Scene.DISCOURS to Effet.EMOTION)   to c(Vitesse.LENTE,   Mouvement.APPROCHE to GROS, Mouvement.STATIQUE to GROS),
        (Scene.DISCOURS to Effet.PUISSANCE) to c(Vitesse.MODEREE, Mouvement.APPROCHE to AME, Mouvement.STATIQUE to AME),
        (Scene.DISCOURS to Effet.GRANDEUR)  to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.STATIQUE to ENS),
        // 3.10 Famille / Groupe (souvenir reuni : groupe statique, jamais de recul aveugle)
        (Scene.FAMILLE to Effet.EMOTION)     to c(Vitesse.LENTE,   Mouvement.APPROCHE to ENS, Mouvement.STATIQUE to ENS, Mouvement.ORBITE to ENS),
        (Scene.FAMILLE to Effet.GRANDEUR)    to c(Vitesse.LENTE,   Mouvement.REVELATION to ENS, Mouvement.ORBITE to ENS),
        (Scene.FAMILLE to Effet.CELEBRATION) to c(Vitesse.MODEREE, Mouvement.ORBITE to ENS, Mouvement.REVELATION to ENS),
        (Scene.FAMILLE to Effet.INTIMITE)    to c(Vitesse.LENTE,   Mouvement.STATIQUE to AME, Mouvement.APPROCHE to AME),

        // --- AUDIT-COHERENCE-2026-07 : combinaisons manquantes comblees (etaient sur REPLI) ---
        (Scene.MARIAGE to Effet.MYSTERE)         to c(Vitesse.LENTE, Mouvement.REVELATION to ENS, Mouvement.ORBITE to AME),
        (Scene.DANSEUR_SOLO to Effet.MYSTERE)    to c(Vitesse.LENTE, Mouvement.REVELATION to PIED, Mouvement.ORBITE to AME),
        (Scene.FAMILLE to Effet.MYSTERE)         to c(Vitesse.LENTE, Mouvement.REVELATION to ENS, Mouvement.STATIQUE to ENS),
        (Scene.CHANTEUR to Effet.GRANDEUR)       to c(Vitesse.LENTE, Mouvement.REVELATION to ENS, Mouvement.APPROCHE to AME),
        (Scene.PAYSAGE to Effet.EMOTION)         to c(Vitesse.LENTE, Mouvement.APPROCHE to ENS, Mouvement.ORBITE to ENS),
        (Scene.GROUPE_MUSIQUE to Effet.EMOTION)  to c(Vitesse.LENTE, Mouvement.APPROCHE to PIED, Mouvement.ORBITE to AME),
        (Scene.ATHLETE to Effet.EMOTION)         to c(Vitesse.LENTE, Mouvement.APPROCHE to AME, Mouvement.STATIQUE to AME),
        (Scene.VEHICULE to Effet.EMOTION)        to c(Vitesse.LENTE, Mouvement.APPROCHE to ENS, Mouvement.ORBITE to ENS)
    )

    /** Repli si une combinaison precise n'est pas dans la table : une valeur sure et douce. */
    private val REPLI = c(Vitesse.LENTE, Mouvement.APPROCHE to AME, Mouvement.ORBITE to AME)

    /** Amplitude (rayon/distance) selon vitesse et plan, en metres. Valeurs de base, ajustables par le validateur. */
    private fun amplitude(m: Mouvement, vitesse: Vitesse): Pair<Float, Float> {
        // retourne (rayon, distance)
        return when (m) {
            Mouvement.ORBITE     -> (when (vitesse) { Vitesse.LENTE -> 6f; Vitesse.MODEREE -> 8f; Vitesse.RAPIDE -> 10f }) to 0f
            Mouvement.TRAVELLING -> 0f to (when (vitesse) { Vitesse.LENTE -> 6f; Vitesse.MODEREE -> 8f; Vitesse.RAPIDE -> 12f })
            Mouvement.RECUL,
            Mouvement.APPROCHE,
            Mouvement.REVELATION -> 0f to (when (vitesse) { Vitesse.LENTE -> 4f; Vitesse.MODEREE -> 6f; Vitesse.RAPIDE -> 8f })
            else -> 0f to 0f
        }
    }

    /**
     * Genere la sequence brute pour (scene, effet, duree totale).
     * La duree est repartie a parts egales entre les pas (arrondi, reste sur le dernier pas).
     */
    fun generer(scene: Scene, effet: Effet, dureeTotaleS: Int): Sequence {
        val cellule = TABLE[scene to effet] ?: REPLI
        val n = cellule.mouvements.size
        val base = dureeTotaleS / n
        val reste = dureeTotaleS - base * n
        val pas = cellule.mouvements.mapIndexed { i, (mv, pl) ->
            val (rayon, dist) = amplitude(mv, cellule.vitesse)
            Pas(
                mouvement = mv,
                vitesse = cellule.vitesse,
                plan = pl,
                dureeS = base + (if (i == n - 1) reste else 0),
                rayonM = rayon,
                distanceM = dist
            )
        }
        return Sequence(pas)
    }

    /** Indique si une combinaison precise existe dans la table (utile pour masquer les combinaisons absurdes). */
    fun existe(scene: Scene, effet: Effet): Boolean = TABLE.containsKey(scene to effet)

    /**
     * Applique une SIGNATURE a une sequence brute : remplace le mouvement d'UN pas
     * (le premier ou le dernier) sans changer le nombre de pas ni les durees.
     * Sert a distinguer des recettes qui partageraient sinon la meme choregraphie
     * (ex. plusieurs cartes "paysage grandeur"). L'amplitude est recalculee pour
     * le nouveau mouvement afin que le controle d'espace reste coherent.
     */
    fun appliquerSignature(seq: Sequence, signature: Signature): Sequence {
        if (signature == Signature.AUCUNE || seq.pas.isEmpty()) return seq
        val pas = seq.pas.toMutableList()
        fun remplacer(i: Int, mv: Mouvement) {
            val p = pas[i]
            val (rayon, dist) = amplitude(mv, p.vitesse)
            pas[i] = p.copy(mouvement = mv, rayonM = rayon, distanceM = dist)
        }
        when (signature) {
            Signature.ORBITE_FINALE  -> remplacer(pas.lastIndex, Mouvement.ORBITE)
            Signature.ORBITE_TETE    -> remplacer(0, Mouvement.ORBITE)
            Signature.FINAL_STATIQUE -> remplacer(pas.lastIndex, Mouvement.STATIQUE)
            Signature.AUCUNE         -> {}
        }
        return Sequence(pas)
    }

    /** Consigne pilote/sujet lisible, derivee de la choregraphie (mouvements) + type de scene. */
    fun consignePilote(scene: Scene, seq: Sequence): String {
        val mvts = seq.pas.map { it.mouvement }
        if (mvts.isEmpty()) return "Zone degagee, doigt sur l'arret d'urgence."
        val sansSujet = (scene == Scene.PAYSAGE || scene == Scene.VEHICULE)
        val suit = mvts.any { it == Mouvement.SUIVI }
        val recule = mvts.any { it == Mouvement.RECUL }
        val cible = if (sansSujet) "de la scene" else "de vous"
        val sujet = when {
            sansSujet -> "Aucune personne requise ; degagez la zone autour du point."
            suit      -> "Avancez a rythme regulier, le drone vous suit."
            else      -> "Restez immobile, face au drone au depart."
        }
        val drone = mvts.take(2).joinToString(", puis ") { m -> when (m) {
            Mouvement.REVELATION -> "recule en montant pour devoiler le decor"
            Mouvement.ORBITE     -> "tourne autour $cible"
            Mouvement.APPROCHE   -> "se rapproche"
            Mouvement.TRAVELLING -> "glisse sur le cote"
            Mouvement.STATIQUE   -> "tient le plan"
            Mouvement.RECUL      -> "recule doucement"
            Mouvement.SUIVI      -> "vous suit"
            Mouvement.SPOTLIGHT  -> "vous garde au centre"
        } }
        val secu = when {
            mvts.any { it == Mouvement.ORBITE } -> " Gardez 360° degages autour du point."
            recule -> " Laissez l'espace libre derriere le drone."
            else -> ""
        }
        return "$sujet Le drone $drone.$secu"
    }
}

