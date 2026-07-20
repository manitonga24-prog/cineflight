package ca.cineflight.stage.sport.soccer

import kotlin.math.abs

/**
 * SoccerCadrageScore — INDICE DE QUALITE DE CADRAGE (pur, aucun SDK/Android).
 *
 * Phase 2 du cadrage cinema. Au lieu de piloter l'altitude/le plan sur un seul signal
 * (taille des joueurs), on calcule un SCORE global de qualite du cadrage dans [0,1] que
 * le systeme cherche a MAXIMISER. Ponderation validee avec l'operateur :
 *
 *   40 %  TAILLE des joueurs   : joueurs a la bonne taille dans l'image (ni trop gros,
 *                                ni des fourmis). Cible [tailleCible], tolerance douce.
 *   25 %  NOMBRE de joueurs    : plus il y a de joueurs pertinents dans le cadre, mieux
 *                                l'action est racontee (sature a [nbJoueursCible]).
 *   20 %  LEAD ROOM            : espace laisse DEVANT l'action (regle cinema). Le sujet
 *                                doit etre decale du cote OPPOSE a sa direction, pour
 *                                laisser voir ou va le jeu.
 *   15 %  STABILITE            : douceur du mouvement recent (peu de variation de la
 *                                position d'action entre frames = image posee, non secouee).
 *
 * Chaque composante est dans [0,1]. Le score final = somme ponderee, dans [0,1].
 *
 * FAIL-SAFE : NaN/Infini -> composante neutralisee (0), jamais de NaN en sortie.
 * PORTEE : observation/planification pure. Ne commande rien ; sert de signal a maximiser.
 */
class SoccerCadrageScore(
    /** Taille cible d'un joueur dans l'image (fraction hauteur [0,1]). ~0.12 = ~100px/800. */
    private val tailleCible: Float = 0.12f,
    /** Largeur de tolerance autour de la taille cible (plus grand = plus indulgent). */
    private val tailleTolerance: Float = 0.10f,
    /** Nombre de joueurs a partir duquel la composante "nombre" sature a 1. */
    private val nbJoueursCible: Int = 6,
    /** Poids (doivent sommer a 1 ; renormalises sinon). */
    private val poidsTaille: Float = 0.40f,
    private val poidsNombre: Float = 0.25f,
    private val poidsLeadRoom: Float = 0.20f,
    private val poidsStabilite: Float = 0.15f,
) {

    /** Detail des composantes, pour l'affichage/diagnostic. */
    data class Detail(
        val taille: Float,
        val nombre: Float,
        val leadRoom: Float,
        val stabilite: Float,
        /** Score de cadrage AVANT confiance (qualite artistique pure) [0,1]. */
        val scoreCadrage: Float,
        /** Facteur de fiabilite issu de la confiance YOLO [plancher..1]. */
        val facteurConfiance: Float,
        /** Score final = scoreCadrage * facteurConfiance [0,1]. */
        val total: Float,
    )

    // Memoire pour la stabilite (derniere position d'action observee).
    private var dernierCx = Float.NaN
    private var dernierCy = Float.NaN

    fun reset() { dernierCx = Float.NaN; dernierCy = Float.NaN }

    /**
     * Calcule le score de cadrage, MODULE par la confiance de l'observation (5e critere).
     *
     * scoreFinal = scoreCadrage * facteurConfiance, avec facteurConfiance = 0.5 + 0.5*conf.
     * La confiance mesure la FIABILITE de l'observation (pas l'esthetique) : un beau cadrage
     * sur des detections douteuses vaut moins. Plancher 0.5 pour ne jamais figer le systeme.
     *
     * @param tailleJoueurMoyenne hauteur moyenne des boites joueurs dans l'image [0,1].
     * @param nbJoueurs           nombre de joueurs pertinents dans le cadre.
     * @param actionCx,actionCy   centre de l'action a l'ecran [0,1] (0,0 = haut-gauche).
     * @param dirX                direction horizontale du jeu [-1,1] (+ = vers la droite).
     * @param confiance           confiance moyenne des detections YOLO [0,1] (1 = fiable).
     */
    fun calculer(
        tailleJoueurMoyenne: Float,
        nbJoueurs: Int,
        actionCx: Float,
        actionCy: Float,
        dirX: Float,
        confiance: Float = 1f,
    ): Detail {
        val cTaille = composanteTaille(tailleJoueurMoyenne)
        val cNombre = composanteNombre(nbJoueurs)
        val cLead = composanteLeadRoom(actionCx, dirX)
        val cStab = composanteStabilite(actionCx, actionCy)

        // Pondération : source UNIQUE (partagee avec le SoccerDirector pour la simulation).
        val scoreCadrage = combiner(cTaille, cNombre, cLead, cStab)
        val facteur = facteurConfiance(confiance)
        val total = (scoreCadrage * facteur).coerceIn(0f, 1f)

        // Memorise la position pour la stabilite de la frame suivante.
        if (actionCx.isFinite()) dernierCx = actionCx
        if (actionCy.isFinite()) dernierCy = actionCy

        return Detail(
            taille = cTaille,
            nombre = cNombre,
            leadRoom = cLead,
            stabilite = cStab,
            scoreCadrage = scoreCadrage,
            facteurConfiance = facteur,
            total = total,
        )
    }

    /**
     * FACTEUR DE CONFIANCE (source unique) : 0.5 + 0.5*confiance, plancher a 0.5.
     * confiance 1 -> 1.0 ; confiance 0 -> 0.5 (jamais 0, pour ne pas figer le realisateur).
     * NaN/hors-borne -> plancher. Utilise par calculer() ET par SoccerDirector.
     */
    fun facteurConfiance(confiance: Float): Float {
        val c = (if (confiance.isFinite()) confiance else 0f).coerceIn(0f, 1f)
        return 0.5f + 0.5f * c
    }

    /**
     * PONDERATION (source unique) : combine les 4 composantes en un score [0,1].
     * Renormalise defensivement si les poids ne somment pas a 1. Pure, sans effet de bord :
     * utilisee par calculer() ET par SoccerDirector pour scorer des candidats simules.
     */
    fun combiner(cTaille: Float, cNombre: Float, cLead: Float, cStab: Float): Float {
        val somme = (poidsTaille + poidsNombre + poidsLeadRoom + poidsStabilite)
            .let { if (it > 1e-6f) it else 1f }
        val total = (
            cTaille * poidsTaille +
            cNombre * poidsNombre +
            cLead * poidsLeadRoom +
            cStab * poidsStabilite
        ) / somme
        return total.coerceIn(0f, 1f)
    }

    // --- Composantes (chacune [0,1], NaN -> 0) ---

    /** 1 quand la taille = cible, decroit doucement de part et d'autre (gaussienne triangulaire). */
    fun composanteTaille(taille: Float): Float {
        if (!taille.isFinite()) return 0f
        val t = taille.coerceIn(0f, 1f)
        val tol = tailleTolerance.coerceAtLeast(1e-3f)
        val ecart = abs(t - tailleCible) / tol
        return (1f - ecart).coerceIn(0f, 1f)
    }

    /**
     * SCORE DE TAILLE CONTINU pour la RECHERCHE (SoccerDirector) : strictement decroissant
     * avec l'ecart a la cible, JAMAIS plat (contrairement a composanteTaille qui sature a 0).
     * Ainsi, meme quand les joueurs sont tres trop grands/petits, le realisateur sait dans
     * quel SENS aller. Forme : 1 / (1 + (ecart/tol)^2). N'est PAS le score affiche.
     */
    fun scoreTailleContinu(taille: Float): Float {
        if (!taille.isFinite()) return 0f
        val t = taille.coerceIn(0f, 1f)
        val tol = tailleTolerance.coerceAtLeast(1e-3f)
        val e = (t - tailleCible) / tol
        return (1f / (1f + e * e)).coerceIn(0f, 1f)
    }

    /** Croit lineairement jusqu'a [nbJoueursCible] puis sature a 1. */
    fun composanteNombre(nb: Int): Float {
        if (nb <= 0) return 0f
        val cible = nbJoueursCible.coerceAtLeast(1)
        return (nb.toFloat() / cible).coerceIn(0f, 1f)
    }

    /**
     * LEAD ROOM : l'action doit etre decalee a l'OPPOSE de sa direction, pour laisser de
     * l'espace devant. Jeu vers la droite (dirX>0) -> action idealement a GAUCHE du cadre.
     * Ideal a une fraction [leadIdeal] du centre du bon cote. 1 au bon endroit, 0 au mauvais.
     */
    fun composanteLeadRoom(actionCx: Float, dirX: Float, leadIdeal: Float = 0.18f): Float {
        if (!actionCx.isFinite() || !dirX.isFinite()) return 0f
        val cx = actionCx.coerceIn(0f, 1f)
        // Position ideale : REGLE partagee (source unique dans SoccerCameraAim).
        val cible = SoccerCameraAim.positionIdealeX(dirX, leadIdeal)
        // Score = 1 quand cx == cible, 0 a une demi-largeur d'ecart.
        val ecart = abs(cx - cible) / 0.5f
        return (1f - ecart).coerceIn(0f, 1f)
    }

    /**
     * STABILITE : 1 si l'action n'a presque pas bouge depuis la frame precedente, decroit
     * avec le deplacement. Premiere frame (pas d'historique) -> neutre 1 (rien a reprocher).
     */
    fun composanteStabilite(actionCx: Float, actionCy: Float, sensibilite: Float = 0.15f): Float {
        if (!actionCx.isFinite() || !actionCy.isFinite()) return 0f
        if (dernierCx.isNaN() || dernierCy.isNaN()) return 1f
        val dx = actionCx - dernierCx
        val dy = actionCy - dernierCy
        val deplacement = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val s = sensibilite.coerceAtLeast(1e-3f)
        return (1f - deplacement / s).coerceIn(0f, 1f)
    }
}
