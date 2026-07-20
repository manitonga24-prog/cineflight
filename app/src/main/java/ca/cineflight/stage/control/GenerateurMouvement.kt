package ca.cineflight.stage.control

import kotlin.math.cos
import kotlin.math.sin

/**
 * GenerateurMouvement — catalogue de mouvements cinématographiques autour d'un
 * SUJET MOBILE (étape 5, module PUR).
 *
 * Chaque mouvement est un OFFSET relatif au sujet qui évolue avec la phase (0..1).
 * À chaque instant : position sujet (RTK) + type + phase -> CIBLE (position + yaw
 * vers le sujet + gimbal). La cible passe ENSUITE par :
 *   GeoBarriere.ecreterCible()  (le drone ne sort jamais de la zone) -> NoyauSecurite
 * Ce module NE COMMANDE PAS le drone : il ne fait que calculer où viser.
 *
 * >>> DOCTRINE <<<
 * Le sujet peut bouger librement ; le mouvement se recompose autour de sa position
 * courante, mais la cible est toujours bridée par la géo-barrière. Distance et
 * hauteur sont bornées (distance min de sécurité). Le yaw vise le sujet (cadrage).
 *
 * Repère : projection plane locale autour du sujet (est, nord en mètres), valable
 * aux distances d'un tournage. Cap/yaw en degrés (0=N, 90=E).
 */
class GenerateurMouvement {

    /** Types de mouvement du catalogue V1. */
    enum class TypeMouvement {
        ORBITE,        // tourne autour du sujet (distance/hauteur constantes)
        TRAVELLING,    // longe le sujet, offset latéral fixe
        REVEAL,        // s'éloigne + monte (dévoile le décor)
        RAPPROCHE      // se rapproche du sujet (distance décroît)
    }

    /** Paramètres d'un mouvement (bornés pour la sécurité). */
    data class Params(
        val type: TypeMouvement,
        val distanceM: Double = 35.0,       // distance drone-sujet
        val hauteurM: Double = 40.0,        // altitude AGL
        val azimutDepartDeg: Double = 0.0,  // azimut de départ autour du sujet (0=N)
        val amplitudeDeg: Double = 180.0,   // pour l'orbite : arc parcouru
        val cote: String = "droite",        // pour le travelling : côté du sujet
        val distanceFinM: Double? = null,   // pour reveal/rapproche : distance finale
        val hauteurFinM: Double? = null,    // pour reveal : hauteur finale
        val gimbalAuto: Boolean = true,     // gimbal calculé vers le sujet
        val gimbalManuelDeg: Double = -45.0,
        // bornes de sécurité (le générateur ne produit jamais hors de ces limites)
        val distanceMinM: Double = 8.0,     // jamais plus près (personne à pied)
        val altMinM: Double = 10.0,
        val altMaxM: Double = 120.0
    )

    /** Cible calculée à une phase donnée. */
    data class Cible(
        val lat: Double,
        val lon: Double,
        val altM: Double,
        val yawDeg: Double,     // cap du drone vers le sujet
        val gimbalDeg: Double   // pitch nacelle
    )

    /**
     * Calcule la cible du drone pour une position sujet et une phase (0..1).
     *
     * @param sujetLat / sujetLon position courante du sujet (RTK).
     * @param p paramètres du mouvement.
     * @param phase avancement du mouvement, 0.0 (début) -> 1.0 (fin).
     */
    fun cible(sujetLat: Double, sujetLon: Double, p: Params, phase: Double): Cible {
        val ph = phase.coerceIn(0.0, 1.0)

        // distance et hauteur selon le type (interpolées pour reveal/rapproche).
        var dist: Double
        var haut: Double
        var azimutDeg: Double

        when (p.type) {
            TypeMouvement.ORBITE -> {
                dist = p.distanceM
                haut = p.hauteurM
                // l'azimut balaie amplitudeDeg autour du sujet.
                azimutDeg = p.azimutDepartDeg + p.amplitudeDeg * ph
            }
            TypeMouvement.TRAVELLING -> {
                dist = p.distanceM
                haut = p.hauteurM
                // azimut latéral fixe : perpendiculaire (gauche/droite). Le
                // "mouvement" vient du sujet qui avance ; le drone garde l'offset.
                azimutDeg = if (p.cote == "gauche") p.azimutDepartDeg - 90.0
                            else p.azimutDepartDeg + 90.0
            }
            TypeMouvement.REVEAL -> {
                val dFin = p.distanceFinM ?: (p.distanceM * 2.0)
                val hFin = p.hauteurFinM ?: (p.hauteurM * 1.6)
                dist = p.distanceM + (dFin - p.distanceM) * ph
                haut = p.hauteurM + (hFin - p.hauteurM) * ph
                azimutDeg = p.azimutDepartDeg
            }
            TypeMouvement.RAPPROCHE -> {
                val dFin = p.distanceFinM ?: (p.distanceM * 0.5)
                dist = p.distanceM + (dFin - p.distanceM) * ph
                haut = p.hauteurM
                azimutDeg = p.azimutDepartDeg
            }
        }

        // BORNES DE SÉCURITÉ : jamais plus près que distanceMin, altitude bornée.
        // fail-closed sur NaN/Infini : coerceIn(NaN)=NaN et "NaN < min" est faux.
        if (!dist.isFinite() || dist < p.distanceMinM) dist = p.distanceMinM
        haut = (if (haut.isFinite()) haut else p.altMinM).coerceIn(p.altMinM, p.altMaxM)

        // position drone = sujet + offset (azimut, distance) en projection locale.
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(sujetLat))
        val az = Math.toRadians(azimutDeg)
        // azimut 0=N, 90=E : est = sin(az), nord = cos(az).
        val est = dist * sin(az)
        val nord = dist * cos(az)
        val droneLat = sujetLat + nord / mLat
        val droneLon = sujetLon + est / mLon

        // yaw du drone vers le sujet (cap boussole). vecteur drone->sujet.
        val vEst = -est   // sujet - drone (en est)
        val vNord = -nord
        var yaw = Math.toDegrees(kotlin.math.atan2(vEst, vNord))
        if (yaw < 0) yaw += 360.0

        // gimbal : auto = angle vers le sujet = -atan(hauteur/distance).
        val gimbal = if (p.gimbalAuto)
            -Math.toDegrees(kotlin.math.atan2(haut, dist))
        else p.gimbalManuelDeg

        return Cible(droneLat, droneLon, haut, yaw, gimbal.coerceIn(-90.0, 0.0))
    }

    // ── AUDIT-SUIVI-ROUTE-2026-07 : PROFIL VEHICULE SUR ROUTE (DRY_RUN) ──────────
    // Consomme le module valide ParcoursRoute : le sujet (vehicule) est contraint
    // par la centerline validee ; le drone anticipe (s + v*tau) au lieu de suivre
    // en reaction. NE REMPLACE PAS cible() : c'est une methode SEPAREE, activee
    // explicitement par la couche appelante pour le profil route. Les 4 mouvements
    // historiques (ORBITE/TRAVELLING/REVEAL/RAPPROCHE) restent inchanges.
    //
    // DOCTRINE identique : la Cible produite passe ENSUITE par GeoBarriere.ecreterCible
    // puis NoyauSecurite. Ce module ne commande rien. Si ParcoursRoute refuse
    // (verdict != OK), on retourne null -> la couche appelante NE bouge pas (et le
    // noyau gelera de toute facon via rtkSujetOk=false).

    /** Resultat du profil route : Cible (si utilisable) + le resultat brut de
     *  ParcoursRoute (verdict, sources, erreur estimee) pour le journal/securite. */
    data class SortieRoute(
        val cible: Cible?,                 // null si ParcoursRoute a refuse
        val resultat: ParcoursRoute.Resultat
    )

    /**
     * Cible du drone pour un sujet VEHICULE contraint par la route.
     * Delegue entierement la geometrie + la securite d'anticipation a ParcoursRoute
     * (deja teste : 32 assertions). Convertit sa sortie en Cible du generateur.
     *
     * @param route rail validi (centerline) deja instancie.
     * @param sujet position RTK du sujet, null si perdu.
     * @param rtk etat RTK ("FIX"/"FLOAT"/"GPS"/"LOST").
     * @param ageRtkS age position RTK (s).
     * @param vitesseServeurMps ground_speed_mps serveur (source principale) ou null.
     * @param capGnssDeg cap GNSS (deg) SEULEMENT si heading_valid cote serveur, sinon null.
     * @param historique echantillons horodates (fallback vitesse derivee).
     * @param p parametres (distance laterale, hauteur, cote, bornes) reutilises.
     * @param anticipation active s+v*tau (profil route : true).
     * @param tauMaxOverrideS plafond tau (>0 = SIMULATEUR).
     */
    fun cibleRoute(
        route: ParcoursRoute,
        sujet: GeoBarriere.Point?,
        rtk: String?,
        ageRtkS: Double?,
        vitesseServeurMps: Double?,
        capGnssDeg: Double?,
        historique: List<ParcoursRoute.EchantillonPosition>,
        p: Params,
        anticipation: Boolean = true,
        tauMaxOverrideS: Double = 0.0
    ): SortieRoute {
        val res = route.calculerCible(
            sujet = sujet,
            rtk = rtk,
            ageRtkS = ageRtkS,
            vitesseServeurMps = vitesseServeurMps,
            capGnssDeg = capGnssDeg,
            historique = historique,
            decalageLateralM = p.distanceM,      // distance laterale au rail = distanceM
            cote = p.cote,
            decalageLongitudinalM = 0.0,
            hauteurAglM = p.hauteurM,
            anticipation = anticipation,
            tauMaxOverrideS = tauMaxOverrideS
        )
        if (res.verdict != ParcoursRoute.Verdict.OK ||
            res.cibleDrone == null) {
            return SortieRoute(null, res)   // refus -> pas de cible (le noyau gelera)
        }
        // BORNES DE SECURITE du generateur appliquees a la hauteur (coherence avec cible()).
        val haut = res.gimbalDeg.let { _ ->
            // la hauteur vient de p.hauteurM, bornee comme dans cible()
            p.hauteurM.coerceIn(p.altMinM, p.altMaxM)
        }
        val c = res.cibleDrone
        // gimbal : ParcoursRoute calcule deja l'angle auto vers le sujet ; on borne.
        val gimbal = res.gimbalDeg.coerceIn(-90.0, 0.0)
        return SortieRoute(
            Cible(c.lat, c.lon, haut, res.yawDroneDeg, gimbal),
            res
        )
    }
}

