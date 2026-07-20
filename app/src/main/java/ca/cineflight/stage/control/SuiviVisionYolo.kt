package ca.cineflight.stage.control

/**
 * SuiviVisionYolo - CALCUL PUR (aucun SDK, aucun Android) de la commande de suivi
 * d'un sujet SANS RTK, a partir de la SEULE boite YOLO. Testable sans materiel.
 *
 * >>> DOCTRINE DE SECURITE (fail-closed) <<<
 * La vision seule est imprecise. On ne fait donc confiance a la detection que si
 * elle est FRAICHE, UNIQUE (une seule cible), assez CONFIANTE, et de taille
 * PLAUSIBLE. Au moindre doute -> HOLD (le drone tient sa position, aucune
 * correction). "Non defini = non sur."
 *
 * Deux comportements selon le MODE fourni par CapacitesDrone :
 *  - CADRAGE_PIVOT  : le drone NE SE DEPLACE PAS. On calcule seulement une
 *    rotation (yaw) pour centrer horizontalement + un ajustement de nacelle pour
 *    centrer verticalement. avanceMps = 0. C'est le seul mode sur un drone sans
 *    capteur d'obstacle (Mini 3).
 *  - DEPLACEMENT_BORNE : en plus du cadrage, on autorise une AVANCE / RECUL
 *    BORNE(E) pour tenir une distance cible, estimee par la TAILLE de la boite.
 *    Reserve aux drones a evitement omnidirectionnel actif (Mini 4 Pro). Meme la,
 *    un PLANCHER interdit d'avancer quand le sujet est trop proche.
 *
 * INVARIANTS (verifies par les tests) :
 *  - Jamais de commande non finie (NaN / Infini) : tout est borne et fini.
 *  - roll (translation laterale) et throttle (altitude) restent TOUJOURS 0 :
 *    le suivi vision-seule ne strafe pas et ne gere pas l'altitude (le drone
 *    tient son altitude tout seul). Seuls yaw + nacelle + avance/recul agissent.
 *  - En CADRAGE_PIVOT, avanceMps est TOUJOURS 0.
 *  - avanceMps > 0 (avancer) est IMPOSSIBLE si la boite depasse le plancher de
 *    proximite (sujet trop proche) : on ne peut alors que reculer ou tenir.
 */
class SuiviVisionYolo(private val cfg: Config = Config()) {

    /** Mode de suivi autorise selon le materiel (decide via modeAutorise()). */
    enum class ModeVision { CADRAGE_PIVOT, DEPLACEMENT_BORNE }

    companion object {
        /**
         * Decide le mode de suivi vision, en REUTILISANT la classification materiel
         * existante (CapacitesDrone.analyser().evitement) : aucune copie ici.
         * DEPLACEMENT_BORNE uniquement pour un drone a evitement COMPLET dont
         * l'evitement est REELLEMENT actif ; tout le reste -> CADRAGE_PIVOT (fail-closed).
         */
        fun modeAutorise(evitement: CapacitesDrone.Evitement, evitementActif: Boolean): ModeVision =
            if (evitement == CapacitesDrone.Evitement.COMPLET && evitementActif)
                ModeVision.DEPLACEMENT_BORNE
            else
                ModeVision.CADRAGE_PIVOT
    }

    data class Config(
        // Seuils de confiance / taille : on reprend ceux de la doctrine RTK+vision
        // (AutorisationControleRtkVision) pour ne pas dupliquer une 2e verite.
        val confianceMin: Float = AutorisationControleRtkVision.CONFIANCE_YOLO_MIN,   // 0.65
        val hauteurBoiteMin: Float = AutorisationControleRtkVision.HAUTEUR_BOITE_MIN, // 0.06
        val hauteurBoiteMax: Float = AutorisationControleRtkVision.HAUTEUR_BOITE_MAX, // 0.70
        // --- Cadrage (rotation + nacelle) ---
        val zoneMorteImage: Float = 0.04f,     // ecart image en-deca duquel on ne corrige pas
        val gainYawDps: Float = 60f,           // deg/s par unite d'ecart horizontal
        val yawMaxDps: Float = 25f,            // plafond de rotation (doux)
        val gainGimbalDeg: Float = 20f,        // deg par unite d'ecart vertical
        val gimbalStepMaxDeg: Float = 4f,      // nudge nacelle max par tick (relatif)
        // --- Deplacement borne (mode DEPLACEMENT_BORNE seulement) ---
        val hauteurCible: Float = 0.45f,       // taille de boite visee (~distance cible). A CALIBRER en vol.
        val zoneMorteHauteur: Float = 0.05f,   // en-deca de cet ecart de taille, on ne bouge pas
        val gainAvanceMps: Float = 4f,         // m/s par unite d'ecart de taille
        val avanceMaxMps: Float = 1.2f,        // plafond d'avance/recul (tres doux)
        val hauteurPlancher: Float = 0.60f     // au-dela : sujet trop proche -> avance INTERDITE
    )

    /** Observation issue de YOLO pour ce tick. Toutes les valeurs en fraction d'image (0..1). */
    data class Observation(
        val fraiche: Boolean,      // detection recente ?
        val nbCibles: Int,         // nombre de sujets de la bonne classe vus
        val confiance: Float,      // confiance 0..1 de la detection choisie
        val cx: Float,             // centre horizontal 0..1 (0.5 = centre image)
        val cy: Float,             // centre vertical 0..1 (0.5 = centre image)
        val hauteurBoite: Float    // hauteur de la boite / hauteur image (proxy de distance)
    )

    /**
     * Commande de suivi vision-seule.
     *  - roll et throttle sont IMPLICITEMENT 0 (jamais de strafe ni d'altitude ici).
     */
    data class Commande(
        val suit: Boolean,            // false = HOLD (drone tient position, aucune correction)
        val raison: String,           // motif (pour l'UI / le journal)
        val avanceMps: Float,         // pitch corps : >0 avance vers le sujet, <0 recule, 0 en pivot
        val yawDps: Float,            // rotation pour centrer horizontalement
        val gimbalDeltaDeg: Float,    // ajustement nacelle RELATIF (a accumuler cote appelant)
        val deplacementAutorise: Boolean  // info : le mode permet-il l'avance ?
    ) {
        companion object {
            fun hold(raison: String, deplacementAutorise: Boolean) =
                Commande(false, raison, 0f, 0f, 0f, deplacementAutorise)
        }
    }

    /**
     * Calcule la commande pour ce tick.
     * @param obs  observation YOLO courante.
     * @param mode mode autorise par CapacitesDrone (selon le drone + evitement actif).
     */
    fun calculer(obs: Observation, mode: ModeVision): Commande {
        val deplacementAutorise = (mode == ModeVision.DEPLACEMENT_BORNE)

        // ---- Barrieres fail-closed : au moindre doute, HOLD ----
        if (!obs.fraiche) return Commande.hold("SUJET_PERDU", deplacementAutorise)
        if (obs.nbCibles != 1) return Commande.hold("CIBLES_AMBIGUES", deplacementAutorise)
        if (!obs.confiance.isFinite() || obs.confiance < cfg.confianceMin)
            return Commande.hold("CONFIANCE_INSUFFISANTE", deplacementAutorise)
        if (!obs.cx.isFinite() || !obs.cy.isFinite() || !obs.hauteurBoite.isFinite())
            return Commande.hold("DETECTION_NON_FINIE", deplacementAutorise)
        if (obs.hauteurBoite < cfg.hauteurBoiteMin || obs.hauteurBoite > cfg.hauteurBoiteMax)
            return Commande.hold("DISTANCE_VISUELLE_HORS_PLAGE", deplacementAutorise)

        // ---- Cadrage horizontal (yaw) : amener le sujet au centre en X ----
        val errX = obs.cx - 0.5f
        val yaw = if (kotlin.math.abs(errX) > cfg.zoneMorteImage)
            clamp(cfg.gainYawDps * errX, -cfg.yawMaxDps, cfg.yawMaxDps)
        else 0f

        // ---- Cadrage vertical (nacelle) : sujet BAS de l'image -> incliner vers le bas ----
        val errY = obs.cy - 0.5f
        val gimbal = if (kotlin.math.abs(errY) > cfg.zoneMorteImage)
            clamp(-cfg.gainGimbalDeg * errY, -cfg.gimbalStepMaxDeg, cfg.gimbalStepMaxDeg)
        else 0f

        // ---- Deplacement (avance/recul) : uniquement en DEPLACEMENT_BORNE ----
        var avance = 0f
        if (deplacementAutorise) {
            // boite plus PETITE que la cible -> sujet plus LOIN -> avancer (>0).
            // boite plus GRANDE que la cible -> sujet plus PRES -> reculer (<0).
            val errTaille = cfg.hauteurCible - obs.hauteurBoite
            if (kotlin.math.abs(errTaille) > cfg.zoneMorteHauteur) {
                avance = clamp(cfg.gainAvanceMps * errTaille, -cfg.avanceMaxMps, cfg.avanceMaxMps)
            }
            // PLANCHER DE PROXIMITE : sujet trop proche -> interdiction d'avancer.
            // On ne garde alors que le recul (avance <= 0). Filet materiel ou non.
            if (obs.hauteurBoite >= cfg.hauteurPlancher && avance > 0f) avance = 0f
        }

        val bouge = yaw != 0f || gimbal != 0f || avance != 0f
        return Commande(
            suit = true,
            raison = if (bouge) "SUIVI" else "CENTRE",
            avanceMps = fini(avance),
            yawDps = fini(yaw),
            gimbalDeltaDeg = fini(gimbal),
            deplacementAutorise = deplacementAutorise
        )
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float {
        if (!v.isFinite()) return 0f
        return if (v < lo) lo else if (v > hi) hi else v
    }

    private fun fini(v: Float): Float = if (v.isFinite()) v else 0f
}
