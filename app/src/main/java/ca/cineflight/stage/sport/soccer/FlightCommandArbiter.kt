package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse

/**
 * FlightCommandArbiter — ARBITRE DE COMMANDE UNIQUE (Phase 9C), pur (aucun SDK/Android).
 *
 * Decide QUELLE source de commande s'applique, selon un ordre de priorite STRICT, et
 * n'autorise la source SoccerRail que si TOUTES ses conditions d'armement sont vraies.
 * Une seule condition fausse -> commande NEUTRE (zero), jamais une tentative de
 * contournement. L'arbitre ne parle PAS au SDK : il rend une decision que l'appelant
 * (Phase3Activity) transmettra a PontDjiReel — et, en 9B/9C, en mode miroir seulement.
 *
 * ORDRE DE PRIORITE (du plus fort au plus faible) :
 *   1. reprise manuelle du pilote        -> commande PILOTE
 *   2. arret d'urgence / securite         -> commande NEUTRE (source Pilot, arret)
 *   3. gate obstacle refuse le deplacement-> commande NEUTRE
 *   4. limites du rail (pas armable)      -> commande existante
 *   5. batterie / etat DJI insuffisant    -> commande existante
 *   6. commande SoccerRail                -> seulement si tout est OK
 * (Le mode automatique EXISTANT reste la valeur par defaut si SoccerRail n'est pas retenu.)
 */
object FlightCommandArbiter {

    /** Commande neutre = arret complet (aucun mouvement). */
    val NEUTRE = AssainisseurVitesse.Vitesses(0f, 0f, 0f, 0f)

    /** Source retenue pour la commande finale. */
    sealed interface FlightCommandSource {
        data object Pilot : FlightCommandSource
        data object ExistingAutomaticMode : FlightCommandSource
        data object SoccerRail : FlightCommandSource
    }

    /**
     * Decision d'arbitrage.
     * @param command commande retenue (repere corps).
     * @param source source retenue.
     * @param allowed true si un mouvement est autorise ; false = commande neutre/arret.
     * @param reason explication courte et traçable.
     */
    data class FlightCommandDecision(
        val command: AssainisseurVitesse.Vitesses,
        val source: FlightCommandSource,
        val allowed: Boolean,
        val reason: String,
    )

    /**
     * Instantane de securite/etat, fourni par l'appelant. Tous les champs sont des
     * FAITS observes (pas des intentions) ; l'arbitre ne fait qu'appliquer les regles.
     */
    data class SafetySnapshot(
        /** Le pilote reprend la main maintenant (sticks/telecommande). PRIORITE ABSOLUE. */
        val pilotOverride: Boolean,
        /** Arret d'urgence ou condition de securite critique active. */
        val emergencyStop: Boolean,
        /** Le gate obstacle autorise le deplacement horizontal. */
        val obstacleGateAllows: Boolean,
        /** Virtual Stick disponible cote SDK. */
        val virtualStickAvailable: Boolean,
        /** Drone en vol et dans un etat compatible avec le suivi. */
        val inFlightCompatible: Boolean,
        /** Profil rail charge ET valide (verdict de securite approuve). */
        val railLoadedAndValid: Boolean,
        /** Position du drone connue et fraiche. */
        val dronePositionFresh: Boolean,
        /** Action YOLO fraiche et suffisamment fiable. */
        val actionFreshAndConfident: Boolean,
        /** Batterie au-dessus du seuil operationnel. */
        val batteryOk: Boolean,
        /** Aucune personne signalee dans le corridor. */
        val corridorClear: Boolean,
        /** L'operateur (point de decollage) est proche du rail (<= seuil VLOS). Defaut
         *  true pour compat ; mis a false si le decollage est trop loin du rail. */
        val operatorNearRail: Boolean = true,
    )

    /**
     * Rend la decision d'arbitrage.
     *
     * BASELINE ALTITUDE SEULE vs MOUVEMENT HORIZONTAL (audit v50, Option A) :
     * le parametre [horizontalMotionRequested] fixe le PERIMETRE des conditions.
     *   - false (DEFAUT = baseline ALTITUDE_ONLY de la demande SFOC) : la commande soccer
     *     est verticale pure (roll/pitch/yaw forces a 0 par Emission2DGuard). Le gate
     *     obstacle ne fait PAS partie des conditions d'emission de cette baseline : AUCUN
     *     credit d'evitement d'obstacles n'est revendique ; les obstacles sont traites par
     *     l'evaluation du site et les procedures operationnelles (dossier de securite).
     *     Ce n'est PAS un simple false->true : la condition est RETIREE du chemin, et la
     *     preuve exhaustive demontre que la decision est INDEPENDANTE de ce bit.
     *   - true (mode RAIL / deplacement horizontal, HORS du perimetre de la demande
     *     actuelle) : le gate obstacle reste une condition dure — il bloque l'emission.
     *
     * @param existingCommand commande du mode AUTOMATIQUE existant (valeur par defaut).
     * @param soccerCommand commande proposee par le pipeline soccer (RailMotionController).
     * @param pilotCommand commande du pilote si reprise manuelle (repere corps).
     * @param soccerModeArmed le mode SoccerRail est-il explicitement arme par l'operateur ?
     * @param safety instantane de securite/etat.
     * @param horizontalMotionRequested true si la commande soccer contient du deplacement
     *        horizontal (rail/2D) ; false = altitude seule (defaut, baseline SFOC).
     */
    fun decide(
        existingCommand: AssainisseurVitesse.Vitesses,
        soccerCommand: AssainisseurVitesse.Vitesses,
        pilotCommand: AssainisseurVitesse.Vitesses,
        soccerModeArmed: Boolean,
        safety: SafetySnapshot,
        horizontalMotionRequested: Boolean = false,
    ): FlightCommandDecision {
        // 1) PILOTE : priorite absolue, quoi qu'il arrive.
        if (safety.pilotOverride) {
            return FlightCommandDecision(
                command = pilotCommand,
                source = FlightCommandSource.Pilot,
                allowed = true,
                reason = "pilote priorite",
            )
        }

        // 2) URGENCE / SECURITE : arret complet.
        if (safety.emergencyStop) {
            return FlightCommandDecision(
                command = NEUTRE,
                source = FlightCommandSource.Pilot,   // securite = on rend la main / arret
                allowed = false,
                reason = "arret d'urgence",
            )
        }

        // 3) GATE OBSTACLE : condition dure UNIQUEMENT pour le deplacement HORIZONTAL.
        //    Baseline ALTITUDE_ONLY (horizontalMotionRequested=false) : le gate est HORS
        //    perimetre — aucun credit d'evitement d'obstacles n'est revendique, et ce bit
        //    n'influence pas la decision (prouve exhaustivement par les tests).
        if (soccerModeArmed && horizontalMotionRequested && !safety.obstacleGateAllows) {
            return FlightCommandDecision(
                command = NEUTRE,
                source = FlightCommandSource.ExistingAutomaticMode,
                allowed = false,
                reason = "gate obstacle refuse",
            )
        }

        // 4-5-6) SoccerRail n'est retenu que si le mode est arme ET toutes les
        // conditions d'armement APPLICABLES sont vraies. Sinon -> mode automatique existant.
        if (soccerModeArmed) {
            val manque = premiereConditionManquante(safety, horizontalMotionRequested)
            if (manque == null) {
                return FlightCommandDecision(
                    command = soccerCommand,
                    source = FlightCommandSource.SoccerRail,
                    allowed = true,
                    reason = "soccer autorise",
                )
            }
            // Une condition fausse -> commande NEUTRE (pas de contournement).
            return FlightCommandDecision(
                command = NEUTRE,
                source = FlightCommandSource.ExistingAutomaticMode,
                allowed = false,
                reason = "soccer bloque : $manque",
            )
        }

        // Mode soccer non arme : on laisse passer la commande automatique existante.
        return FlightCommandDecision(
            command = existingCommand,
            source = FlightCommandSource.ExistingAutomaticMode,
            allowed = true,
            reason = "mode existant",
        )
    }

    /**
     * Retourne le nom de la PREMIERE condition d'armement manquante, ou null si toutes
     * sont satisfaites. L'ordre reflete la spec (etat SDK -> rail -> perception -> env).
     * Le gate obstacle n'est une condition QUE si le deplacement horizontal est demande
     * (baseline ALTITUDE_ONLY : 8 conditions ; mode horizontal : 9 conditions).
     */
    private fun premiereConditionManquante(
        s: SafetySnapshot,
        horizontalMotionRequested: Boolean,
    ): String? = when {
        !s.virtualStickAvailable -> "virtual_stick_indisponible"
        !s.inFlightCompatible -> "etat_vol_incompatible"
        !s.railLoadedAndValid -> "rail_non_valide"
        !s.dronePositionFresh -> "position_drone_perimee"
        !s.actionFreshAndConfident -> "action_yolo_non_fiable"
        horizontalMotionRequested && !s.obstacleGateAllows -> "gate_obstacle_refuse"
        !s.batteryOk -> "batterie_insuffisante"
        !s.corridorClear -> "corridor_occupe"
        !s.operatorNearRail -> "operateur_trop_loin_du_rail"
        else -> null
    }
}
