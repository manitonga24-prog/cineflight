package ca.cineflight.stage.control

import ca.cineflight.stage.sentinelle.PerceptionSnapshotStore

/**
 * ObstacleGateWiring — cablage OBLIGATOIRE et TYPE du gate obstacle pour un PontDjiReel.
 *
 * OBJECTIF : rendre IMPOSSIBLE un etat incoherent. Avec deux champs independants
 * (miroir=true + provider=null, ou miroir=false + provider fourni) on pouvait cabler
 * de travers. Ici, le seul moyen d'activer le miroir est de fournir un [Mirror] qui
 * PORTE son provider : pas de miroir sans source de snapshots, pas de provider ignore.
 *
 * REGLE : aucune instance de PontDjiReel ne peut etre creee sans declarer explicitement
 * son cablage obstacle (le constructeur exige un ObstacleGateWiring). Les ecrans qui ne
 * veulent pas du gate passent [Off] ; celui qui observe passe [Mirror].
 */
sealed interface ObstacleGateWiring {

    /** Aucun gate : la commande assainie part telle quelle au SDK. Aucun log miroir. */
    data object Off : ObstacleGateWiring

    /**
     * Mode MIROIR : le gate est CALCULE et JOURNALISE, mais JAMAIS applique (cmd_a_envoyer
     * reste == cmd_assainie). C'est un observatoire, pas un frein.
     *
     * @property snapshotProvider lecture ATOMIQUE combinee (un seul now) de la perception,
     *   appelee UNE fois par tick. Peut renvoyer null si aucun snapshot n'a encore ete publie.
     * @property source etiquette identifiant l'ecran/instance (ex. "PHASE3"), journalisee dans
     *   PERCEPTION_SAMPLE / GATE_MIRROR pour distinguer les sources de logs.
     */
    data class Mirror(
        val snapshotProvider: () -> PerceptionSnapshotStore.GatePerceptionSnapshot?,
        val source: String
    ) : ObstacleGateWiring
}
