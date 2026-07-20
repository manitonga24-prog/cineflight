package ca.cineflight.stage.sentinelle

import ca.cineflight.stage.control.CommandeCorps
import ca.cineflight.stage.control.PiloteDrone
import ca.cineflight.stage.control.RaisonSecurite

/**
 * PontPiloteSecuriteAdapter - sink securite de la Sentinelle vers l'UNIQUE ecrivain SDK.
 *
 * AVANT : la Sentinelle (NoyauSecurite) ecrivait DIRECTEMENT au drone via
 * PontDjiAdapter -> pont.envoyerVitesses(...). PiloteDrone ecrivait AUSSI au SDK
 * dans sa boucle 15 Hz -> DEUX ecrivains concurrents sur le meme lien DJI.
 *
 * MAINTENANT : ce PontDrone n'ecrit plus au SDK. Il DEPOSE la manoeuvre dans le
 * creneau securite de PiloteDrone (pilote.soumettreSecurite). PiloteDrone reste le
 * SEUL a appeler pont.envoyerVitesses, dans sa boucle, apres arbitrage
 * (securite > normal) et bornage de garde (+/-2 m/s, +/-60 deg/s).
 *
 * REPERE / UNITES INCHANGES : PontDrone (Double) recoit deja pitch/roll/throttle en
 * m/s et yaw en deg/s (repere corps DJI), exactement ce que CommandeCorps attend.
 * On convertit seulement Double -> Float. Aucune rotation d'axe : versDji ne
 * s'applique qu'au chemin SCENE, jamais ici.
 *
 * sessionId : jeton de propriete. soumettreSecurite est ignore par PiloteDrone si la
 * session courante n'est plus proprietaire (course a l'arret geree cote pilote).
 *
 * raisonBlocage : fournisseur du motif de blocage noyau, cable APRES construction
 * (init de SentinelleVol) pour un libelle precis (STOP / INTRUSION / ERREUR).
 * Purement informatif cote PiloteDrone ; n'affecte jamais la trajectoire.
 */
class PontPiloteSecuriteAdapter(
    private val pilote: PiloteDrone,
    private val sessionId: Long
) : PontDrone {

    /** Cable apres construction (voir SentinelleVol.init). Defaut sur : AUCUNE. */
    @Volatile var raisonBlocage: () -> RaisonBlocage = { RaisonBlocage.AUCUNE }

    override fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double) {
        // Depot dans le creneau securite ; l'emission SDK reelle est faite par la
        // boucle de PiloteDrone (ecrivain unique). Ignore si session non proprietaire.
        pilote.soumettreSecurite(
            sessionId,
            CommandeCorps(pitch.toFloat(), roll.toFloat(), throttle.toFloat(), yaw.toFloat()),
            deduireRaison(throttle, yaw)
        )
    }

    /** La Sentinelle ne commande jamais la nacelle : no-op volontaire. */
    override fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean) {
        // Intentionnellement vide.
    }

    /**
     * Motif affiche par PiloteDrone. Priorite au blocage noyau (drone fige a 0,0,0,0) ;
     * sinon deduit de la manoeuvre en cours (montee vs balayage).
     */
    private fun deduireRaison(throttle: Double, yaw: Double): RaisonSecurite =
        when (raisonBlocage()) {
            RaisonBlocage.STOP_PILOTE    -> RaisonSecurite.STOP_PILOTE
            RaisonBlocage.INTRUSION      -> RaisonSecurite.INTRUSION
            RaisonBlocage.ERREUR_SYSTEME -> RaisonSecurite.ERREUR_SYSTEME
            RaisonBlocage.AUCUNE         -> when {
                throttle > 0.05            -> RaisonSecurite.MONTEE_ASSISTEE
                kotlin.math.abs(yaw) > 0.5 -> RaisonSecurite.BALAYAGE_360
                else                       -> RaisonSecurite.MONTEE_ASSISTEE
            }
        }
}
