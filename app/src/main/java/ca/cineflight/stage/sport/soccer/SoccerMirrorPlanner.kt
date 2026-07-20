package ca.cineflight.stage.sport.soccer

import kotlin.math.abs

/**
 * SoccerMirrorPlanner — DEPLACEMENT EN MODE MIROIR (Phase 8), pur (aucun SDK/Android).
 *
 * MEME DOCTRINE que le gate obstacle en mode miroir (OrchestrationMiroir) :
 *   on CALCULE une commande theorique (position de rail visee + vitesse), on la
 *   JOURNALISE, mais on NE L'APPLIQUE JAMAIS. La sortie porte explicitement le fait
 *   qu'aucune commande reelle n'est emise (commandeMiroir = true).
 *
 * ROLE : a partir de la position d'action stable (deja filtree) et de la position
 * ACTUELLE du drone sur le rail, decider ou il DEVRAIT aller et a quelle vitesse,
 * pour observer — sur un match enregistre/simule — si ces choix sont logiques.
 *
 * INVARIANT MIROIR : rien dans ce composant ne touche le drone, la nacelle, ni le
 * pont DJI. Il ne produit qu'une [MirrorDecision] descriptible et journalisable.
 *
 * REGLES (V1, prudentes) :
 *   - action indisponible (null) -> aucune cible, vitesse 0, raison ACTION_INDISPONIBLE ;
 *   - confiance sous le seuil     -> aucun mouvement, raison CONFIANCE_FAIBLE ;
 *   - deplacement demande sous la zone morte -> aucun mouvement, raison ZONE_MORTE ;
 *   - sinon -> vitesse proportionnelle a l'ecart, BORNEE a vitesseMaxMps, raison DEPLACEMENT.
 * La vitesse porte un signe : + = vers l'extremite end (fraction croissante), - = vers start.
 */
class SoccerMirrorPlanner(
    private val planner: SoccerRailPlanner = SoccerRailPlanner(),
    /** En-deca de cette confiance, aucun mouvement propose. */
    private val confianceMin: Float = 0.50f,
    /** Ecart de fraction de rail en-deca duquel on ne propose aucun mouvement. */
    private val zoneMorteFraction: Float = 0.03f,
    /** Gain vitesse (m/s par unite d'ecart de fraction). A calibrer en vol. */
    private val gainMps: Float = 4f,
    /** Plafond de vitesse propose (m/s). Tres doux par defaut. */
    private val vitesseMaxMps: Float = 2f,
) {

    /** Raison de la decision, pour la tracabilite. */
    enum class Raison {
        ACTION_INDISPONIBLE,   // pas d'estimation exploitable
        CONFIANCE_FAIBLE,      // estimation sous le seuil de confiance
        ZONE_MORTE,            // deplacement demande trop petit : on reste en place
        DEPLACEMENT,           // deplacement propose (theorique, non applique)
    }

    /**
     * Decision miroir : entierement descriptive, JAMAIS appliquee.
     *
     * @param positionAction position d'action normalisee retenue (ou null si indisponible).
     * @param positionRailActuelle fraction de rail ou se trouve le drone [0f,1f].
     * @param positionRailDemandee fraction de rail visee [0f,1f] (== actuelle si pas de mouvement).
     * @param cible cible geographique correspondant a positionRailDemandee (via SoccerRailPlanner).
     * @param vitesseDemandeeMps vitesse THEORIQUE signee et bornee (0 si pas de mouvement).
     * @param raison pourquoi cette decision.
     * @param confianceYolo confiance de l'estimation retenue.
     * @param commandeMiroir TOUJOURS true : marque qu'aucune commande reelle n'est emise.
     */
    data class MirrorDecision(
        val positionAction: Float?,
        val positionRailActuelle: Float,
        val positionRailDemandee: Float,
        val cible: RailTarget,
        val vitesseDemandeeMps: Float,
        val raison: Raison,
        val confianceYolo: Float,
        val commandeMiroir: Boolean = true,
    )

    /**
     * Calcule la decision miroir. PURE. N'applique rien.
     *
     * @param estimate position d'action stable (sortie du tracker), ou null.
     * @param rail rail autorise.
     * @param positionRailActuelle fraction actuelle du drone sur le rail [0f,1f].
     */
    fun decideMirror(
        estimate: SoccerActionEstimate?,
        rail: DroneRail,
        positionRailActuelle: Float,
    ): MirrorDecision {
        val actuelle = positionRailActuelle.coerceIn(0f, 1f)

        // Cas 1 : aucune action exploitable -> on reste, vitesse 0.
        if (estimate == null) {
            return immobile(
                positionAction = null,
                actuelle = actuelle,
                rail = rail,
                confiance = 0f,
                raison = Raison.ACTION_INDISPONIBLE,
            )
        }

        // Cas 2 : confiance trop faible -> aucun mouvement.
        if (estimate.confidence < confianceMin) {
            return immobile(
                positionAction = estimate.positionNormalized.coerceIn(0f, 1f),
                actuelle = actuelle,
                rail = rail,
                confiance = estimate.confidence,
                raison = Raison.CONFIANCE_FAIBLE,
            )
        }

        // Cible = projection directe de l'action sur le rail (mapping 1:1, borne).
        val cibleTarget = planner.calculateTarget(estimate.positionNormalized, rail)
        val demandee = cibleTarget.railFraction
        val ecart = demandee - actuelle

        // Cas 3 : deplacement demande sous la zone morte -> on reste en place.
        if (abs(ecart) < zoneMorteFraction) {
            return MirrorDecision(
                positionAction = estimate.positionNormalized.coerceIn(0f, 1f),
                positionRailActuelle = actuelle,
                positionRailDemandee = actuelle,            // pas de mouvement
                cible = planner.calculateTarget(actuelle, rail),
                vitesseDemandeeMps = 0f,
                raison = Raison.ZONE_MORTE,
                confianceYolo = estimate.confidence,
            )
        }

        // Cas 4 : deplacement propose. Vitesse proportionnelle, signee, bornee
        // (loi commune RailVitesse, partagee avec RailMotionController).
        val vitesse = RailVitesse.proportionnelle(ecart, gainMps, vitesseMaxMps)

        return MirrorDecision(
            positionAction = estimate.positionNormalized.coerceIn(0f, 1f),
            positionRailActuelle = actuelle,
            positionRailDemandee = demandee,
            cible = cibleTarget,
            vitesseDemandeeMps = vitesse,
            raison = Raison.DEPLACEMENT,
            confianceYolo = estimate.confidence,
        )
    }

    private fun immobile(
        positionAction: Float?,
        actuelle: Float,
        rail: DroneRail,
        confiance: Float,
        raison: Raison,
    ) = MirrorDecision(
        positionAction = positionAction,
        positionRailActuelle = actuelle,
        positionRailDemandee = actuelle,
        cible = planner.calculateTarget(actuelle, rail),
        vitesseDemandeeMps = 0f,
        raison = raison,
        confianceYolo = confiance,
    )
}
