package ca.cineflight.stage.control

import android.util.Log

/**
 * PontSuiviRoute — relie les DONNEES RTK REELLES (RtkSujet, serveur V4) au module
 * valide ParcoursRoute. ETAPE 2, DRY_RUN STRICT.
 *
 * >>> ROLE <<<
 * A chaque lecture /api/rtk/sujet, construit l'appel ParcoursRoute.calculerCible()
 * en appliquant la SELECTION DE SOURCE figee :
 *   vitesse : serveur (ground_speed_mps) -> derivation Android -> 0.0
 *   cap     : serveur (heading_deg si heading_valid) -> sinon tangente de route
 * et JOURNALISE clairement la source retenue :
 *   source_vitesse=SERVEUR | ANDROID | AUCUNE
 *   source_cap=SERVEUR | TANGENTE_ROUTE
 *
 * >>> LIMITES (grave) <<<
 * NE COMMANDE RIEN. Retourne la cible SUGGEREE par ParcoursRoute (verdict inclus).
 * L'execution reste interdite a cette etape : le resultat n'est ni envoye au
 * pont drone, ni transforme en intention. Le MouvementSafetyValidator (serveur)
 * et le NoyauSecurite gardent le dernier mot lors des etapes suivantes.
 *
 * Testable : la logique de selection est isolee dans selectionner(); Log.* est
 * encapsule pour ne pas gener les tests JVM (journal optionnel).
 */
class PontSuiviRoute(
    private val route: ParcoursRoute,
    private val cfg: Config = Config(),
    /** Journal optionnel (permet de tester la selection sans Android Log). */
    private val journal: (String) -> Unit = { Log.i(TAG, it) }
) {

    companion object { const val TAG = "CineFlightSuiviRoute" }

    data class Config(
        val decalageLateralM: Double = 20.0,
        val cote: String = "droite",
        val decalageLongitudinalM: Double = 0.0,
        val hauteurAglM: Double = 40.0,
        /** Anticipation active pour le profil vehicule route. */
        val anticipation: Boolean = true,
        /** Plafond tau override (0 = defaut ParcoursRoute 1.2 s ; 1.5 = SIMULATEUR). */
        val tauMaxOverrideS: Double = 0.0,
        /** Taille max de l'historique conserve pour la derivation Android. */
        val historiqueMax: Int = 12
    )

    /** Historique interne des positions RTK (pour la vitesse derivee de secours). */
    private val historique = ArrayDeque<ParcoursRoute.EchantillonPosition>()

    /** Resultat enrichi : cible ParcoursRoute + sources journalisees. */
    data class Sortie(
        val resultat: ParcoursRoute.Resultat,
        val sourceCap: SourceCap
    )

    enum class SourceCap { SERVEUR, TANGENTE_ROUTE }

    /**
     * Traite une lecture RTK et calcule la cible suggeree (DRY_RUN).
     *
     * @param rtk lecture /api/rtk/sujet (peut etre null = erreur reseau).
     * @param timestampMonotoneNs horloge monotone locale (System.nanoTime()) au
     *        moment de la lecture — sert a la derivation Android (jamais l'age serveur).
     */
    fun traiter(rtk: RtkSujet?, timestampMonotoneNs: Long): Sortie {
        // 1. Alimente l'historique si la position est exploitable (pour le fallback).
        if (rtk != null && rtk.present && rtk.lat != null && rtk.lon != null && rtk.rtk != null) {
            historique.addLast(
                ParcoursRoute.EchantillonPosition(
                    position = GeoBarriere.Point(rtk.lat, rtk.lon),
                    timestampMonotoneNs = timestampMonotoneNs,
                    rtk = rtk.rtk,
                    ageS = rtk.ageS ?: Double.MAX_VALUE
                )
            )
            while (historique.size > cfg.historiqueMax) historique.removeFirst()
        }

        val sujet = if (rtk?.lat != null && rtk.lon != null)
            GeoBarriere.Point(rtk.lat, rtk.lon) else null

        // 2. CAP : serveur si valide, sinon on ne passe PAS de cap -> ParcoursRoute
        //    utilise la tangente de route (le cap ne sort jamais de la centerline).
        val capServeurUtilisable = rtk?.capValide == true && rtk.capDeg != null
        val capPourCalcul: Double? = if (capServeurUtilisable) rtk!!.capDeg else null
        val sourceCap = if (capServeurUtilisable) SourceCap.SERVEUR else SourceCap.TANGENTE_ROUTE

        // 3. VITESSE : serveur si presente ; sinon ParcoursRoute derivera de
        //    l'historique ; sinon 0. (La selection finale est faite DANS
        //    ParcoursRoute ; ici on passe juste la valeur serveur ou null.)
        val vitesseServeur = rtk?.vitesseMps

        val res = route.calculerCible(
            sujet = sujet,
            rtk = rtk?.rtk,
            ageRtkS = rtk?.ageS,
            vitesseServeurMps = vitesseServeur,
            capGnssDeg = capPourCalcul,
            historique = historique.toList(),
            decalageLateralM = cfg.decalageLateralM,
            cote = cfg.cote,
            decalageLongitudinalM = cfg.decalageLongitudinalM,
            hauteurAglM = cfg.hauteurAglM,
            anticipation = cfg.anticipation,
            tauMaxOverrideS = cfg.tauMaxOverrideS
        )

        // 4. JOURNAL des sources (exigence etape 2).
        journal(
            "source_vitesse=${res.sourceVitesse} " +
            "source_cap=$sourceCap " +
            "verdict=${res.verdict} " +
            "v=%.2f m/s sens=%s err=%.1f m ageMax=%.2f s"
                .format(res.vitesseUtiliseeMps, res.sens, res.erreurEstimeeM, res.ageRtkMaxEffectifS) +
            (res.raisonBlocage?.let { " raison=$it" } ?: "") +
            " [DRY_RUN: aucune commande drone]"
        )

        return Sortie(res, sourceCap)
    }

    /** Vide l'historique (changement de mission / de rail). */
    fun reinitialiser() { historique.clear() }
}
