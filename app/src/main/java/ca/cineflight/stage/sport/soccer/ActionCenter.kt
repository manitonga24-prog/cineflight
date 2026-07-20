package ca.cineflight.stage.sport.soccer

import kotlin.math.hypot

/**
 * ActionCenter — CENTRE DE L'ACTION a partir du GROUPE PRINCIPAL (section 5), pur.
 *
 * A partir des joueurs suivis, identifie le groupe le plus concentre et calcule le
 * centre de l'action (moyenne du groupe) + l'etalement. Ecarte les joueurs ISOLES
 * (gardien loin, remplacant) pour ne pas tirer le centre vers un joueur hors du jeu.
 *
 * Methode simple et robuste (pas de clustering lourd) : pour chaque joueur, on compte
 * combien d'autres sont dans un rayon [rayonGroupe]. Le groupe principal = les joueurs
 * autour du joueur le plus "entoure". Centre = moyenne de ce groupe.
 */
object ActionCenter {

    /** Resultat : centre de l'action (unites image [0,1]) + taille et etalement du groupe. */
    data class Centre(
        val x: Float, val y: Float,
        val taille: Int,          // nb de joueurs dans le groupe principal
        val etalement: Float,     // dispersion [0,1] (ecart-type normalise)
        val present: Boolean,     // false si aucun joueur exploitable
    )

    /**
     * @param joueurs positions des joueurs (centre normalise).
     * @param rayonGroupe rayon (unites image) definissant "proche" pour le groupe.
     */
    fun calculer(
        joueurs: List<Pair<Float, Float>>,
        rayonGroupe: Float = 0.20f,
    ): Centre {
        if (joueurs.isEmpty()) return Centre(0.5f, 0.5f, 0, 0f, false)
        if (joueurs.size == 1) return Centre(joueurs[0].first, joueurs[0].second, 1, 0f, true)

        // 1) Joueur le plus "entoure" (max de voisins dans le rayon).
        var meilleurIdx = 0
        var meilleurVoisins = -1
        for (i in joueurs.indices) {
            var voisins = 0
            for (k in joueurs.indices) {
                if (k == i) continue
                if (dist(joueurs[i], joueurs[k]) <= rayonGroupe) voisins++
            }
            if (voisins > meilleurVoisins) { meilleurVoisins = voisins; meilleurIdx = i }
        }

        // 2) Groupe principal = ce joueur + ceux dans son rayon.
        val pivot = joueurs[meilleurIdx]
        val groupe = joueurs.filter { dist(pivot, it) <= rayonGroupe }
        // (contient au moins le pivot)

        // 3) Centre = moyenne du groupe.
        val cx = groupe.sumOf { it.first.toDouble() }.toFloat() / groupe.size
        val cy = groupe.sumOf { it.second.toDouble() }.toFloat() / groupe.size

        // 4) Etalement = ecart-type des positions du groupe, normalise (~0.3 = tres etale).
        var s = 0.0
        for (p in groupe) {
            val dx = p.first - cx; val dy = p.second - cy
            s += dx * dx + dy * dy
        }
        val ecartType = Math.sqrt(s / groupe.size)
        val etalement = (ecartType / 0.3).toFloat().coerceIn(0f, 1f)

        return Centre(cx, cy, groupe.size, etalement, true)
    }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot(a.first - b.first, a.second - b.second)
}
