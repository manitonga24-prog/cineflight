package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import ca.cineflight.stage.sentinelle.EtatCapteurs

/**
 * DecisionSuiviRoute — ORCHESTRATEUR du suivi vehicule sur route (DRY_RUN).
 * V4.2 : relie les trois couches DEJA VALIDEES, sans les reecrire :
 *   1. AutorisationControleRtkVision : mode FIX_COMPLET / FLOAT_VISION / BLOQUE
 *      selon l'etat RTK (+ garde-fous YOLO en FLOAT). Fail-closed.
 *   2. ParcoursRoute : cible anticipee sur le rail (utilisee SEULEMENT en FIX).
 *   3. SecuriteSuiviRoute : traduit le verdict en EtatCapteurs.rtkSujetOk -> noyau.
 *
 * >>> DOCTRINE DE BASCULE (issue du code existant) <<<
 *   FIX_COMPLET  : RTK plein. Translation + altitude par ParcoursRoute (anticipation
 *                  route). Gimbal auto vers le sujet. YOLO = verification d'identite.
 *   FLOAT_VISION : precision position degradee. Translation/altitude/yaw = 0 (verrou
 *                  materiel logiciel limiterFloatVision). Seul le GIMBAL bouge, piloté
 *                  par le CADRAGE YOLO (pas par le rail). RTK reste appoint directionnel.
 *   BLOQUE       : GPS / LOST / vision incertaine / RTK trop vieux -> rtkSujetOk=false
 *                  -> le NoyauSecurite gele (0,0,0,0). "Non defini = non sur."
 *
 * >>> LIMITES (grave) <<<
 * Ce module NE COMMANDE RIEN. Il produit une DECISION (cible + axes autorises +
 * capteurs pour le noyau). L'ExecuteurMouvement soumet, le NoyauSecurite tranche.
 * Module PUR (depend seulement de types purs + interfaces) -> testable en JVM.
 */
class DecisionSuiviRoute(
    private val autorisation: AutorisationControleRtkVision = AutorisationControleRtkVision(),
    private val cfg: Config = Config(),
    private val journal: (String) -> Unit = {}
) {

    data class Config(
        val decalageLateralM: Double = 20.0,
        val cote: String = "droite",
        val hauteurAglM: Double = 40.0,
        val anticipation: Boolean = true,
        val tauMaxOverrideS: Double = 0.0
    )

    /** Décision d'un tick de suivi route (rien n'est commandé). */
    data class Decision(
        val mode: AutorisationControleRtkVision.Mode,
        val raison: String,
        /** cible route (translation) — non-null UNIQUEMENT en FIX_COMPLET autorisé. */
        val cibleRoute: GenerateurMouvement.Cible?,
        /** gimbal suggéré (deg) vers le sujet, si connu. */
        val gimbalDeg: Double?,
        /** axes autorisés (miroir du verdict d'autorisation). */
        val translationAutorisee: Boolean,
        val altitudeAutorisee: Boolean,
        val yawAutorise: Boolean,
        val gimbalAutorise: Boolean,
        /** capteurs à soumettre au noyau (rtkSujetOk déjà combiné). */
        val capteurs: EtatCapteurs,
        /** résultat brut ParcoursRoute (si évalué), pour le journal. */
        val resultatRoute: ParcoursRoute.Resultat?
    ) {
        val actif: Boolean get() = mode != AutorisationControleRtkVision.Mode.BLOQUE
    }

    /**
     * @param route rail validé (centerline) déjà instancié.
     * @param position position RTK sujet (ClientRtkSujet.PositionSujet) ou null.
     * @param baseCapteurs capteurs déjà évalués par l'orchestrateur (vidéo/yolo/...).
     * @param predictionControlReady modèle de contrôle stabilisé ? (exigé en FIX).
     * @param cibleVerrouillee la cible YOLO est-elle verrouillée par le pilote ?
     * @param yoloTrouve YOLO voit-il le sujet cette frame ?
     * @param confianceYolo confiance de la boîte (0..1).
     * @param nbCibles nombre de cibles YOLO (doit être 1).
     * @param hauteurBoite hauteur normalisée de la boîte (proxy distance visuelle).
     * @param historique échantillons horodatés pour la vitesse dérivée (fallback).
     */
    fun decider(
        route: ParcoursRoute,
        position: ClientRtkSujet.PositionSujet?,
        baseCapteurs: EtatCapteurs,
        predictionControlReady: Boolean,
        cibleVerrouillee: Boolean,
        yoloTrouve: Boolean,
        confianceYolo: Float,
        nbCibles: Int,
        hauteurBoite: Float,
        historique: List<ParcoursRoute.EchantillonPosition> = emptyList()
    ): Decision {
        // La vision est REQUISE dès que le RTK n'est pas FIX (doctrine FLOAT->YOLO).
        val visionRequise = position?.rtk != ClientRtkSujet.StatutRtk.FIX

        // 1. AUTORISATION (couche existante, fail-closed).
        val v = autorisation.evaluer(
            position = position,
            predictionControlReady = predictionControlReady,
            cibleVerrouillee = cibleVerrouillee,
            visionRequise = visionRequise,
            yoloTrouve = yoloTrouve,
            confianceYolo = confianceYolo,
            nbCibles = nbCibles,
            hauteurBoite = hauteurBoite
        )

        // 2. Selon le mode.
        return when (v.mode) {
            AutorisationControleRtkVision.Mode.BLOQUE ->
                bloquer(v.raison, baseCapteurs)

            AutorisationControleRtkVision.Mode.FLOAT_VISION -> {
                // FLOAT : AUCUNE translation route. Gimbal seul (piloté par le cadrage
                // YOLO côté appelant). On NE calcule PAS de cible route. rtkSujetOk
                // reste vrai (le suivi continue en gimbal), mais tout mouvement de
                // translation est verrouillé à zéro par limiterFloatVision côté exécuteur.
                journal("mode=FLOAT_VISION raison=${v.raison} translation=0 gimbal=YOLO [DRY_RUN]")
                Decision(
                    mode = v.mode, raison = v.raison,
                    cibleRoute = null, gimbalDeg = null,
                    translationAutorisee = false, altitudeAutorisee = false,
                    yawAutorise = false, gimbalAutorise = v.gimbalAutorise,
                    capteurs = baseCapteurs,   // le noyau ne gèle pas : suivi gimbal actif
                    resultatRoute = null
                )
            }

            AutorisationControleRtkVision.Mode.FIX_COMPLET -> {
                // FIX : cible route complète (anticipation). Mapping PositionSujet -> ParcoursRoute.
                val sujet = if (position != null && position.lat.isFinite() && position.lon.isFinite())
                    GeoBarriere.Point(position.lat, position.lon) else null
                // cap serveur SEULEMENT si heading_valid (sinon tangente route dans ParcoursRoute).
                val cap = if (position?.headingValid == true) position.capDeg else null
                val res = route.calculerCible(
                    sujet = sujet,
                    rtk = position?.rtk?.name,
                    ageRtkS = position?.ageS,
                    vitesseServeurMps = position?.groundSpeedMps,
                    capGnssDeg = cap,
                    historique = historique,
                    decalageLateralM = cfg.decalageLateralM,
                    cote = cfg.cote,
                    hauteurAglM = cfg.hauteurAglM,
                    anticipation = cfg.anticipation,
                    tauMaxOverrideS = cfg.tauMaxOverrideS
                )
                // rtkSujetOk combiné : autorisation FIX + verdict route OK.
                val capteurs = SecuriteSuiviRoute.capteursPourRoute(baseCapteurs, res)
                val cible = if (res.verdict == ParcoursRoute.Verdict.OK && res.cibleDrone != null)
                    GenerateurMouvement.Cible(
                        res.cibleDrone.lat, res.cibleDrone.lon,
                        cfg.hauteurAglM, res.yawDroneDeg, res.gimbalDeg
                    ) else null
                journal(
                    "mode=FIX_COMPLET raison=${v.raison} route_verdict=${res.verdict} " +
                    "src_vitesse=${res.sourceVitesse} rtkSujetOk=${capteurs.rtkSujetOk} [DRY_RUN]"
                )
                Decision(
                    mode = v.mode, raison = v.raison,
                    cibleRoute = cible, gimbalDeg = if (res.gimbalDeg.isFinite()) res.gimbalDeg else null,
                    translationAutorisee = v.translationAutorisee && cible != null,
                    altitudeAutorisee = v.altitudeAutorisee,
                    yawAutorise = v.yawAutorise,
                    gimbalAutorise = v.gimbalAutorise,
                    capteurs = capteurs,
                    resultatRoute = res
                )
            }
        }
    }

    private fun bloquer(raison: String, base: EtatCapteurs): Decision {
        journal("mode=BLOQUE raison=$raison rtkSujetOk=false -> noyau gele [DRY_RUN]")
        return Decision(
            mode = AutorisationControleRtkVision.Mode.BLOQUE, raison = raison,
            cibleRoute = null, gimbalDeg = null,
            translationAutorisee = false, altitudeAutorisee = false,
            yawAutorise = false, gimbalAutorise = false,
            // BLOQUE -> rtkSujetOk force a false -> le noyau gele (0,0,0,0).
            capteurs = base.copy(rtkSujetOk = false),
            resultatRoute = null
        )
    }
}
