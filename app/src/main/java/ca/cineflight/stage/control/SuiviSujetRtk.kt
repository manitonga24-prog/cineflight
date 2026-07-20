package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SuiviSujetRtk — MODE SUJET MOBILE (RTK + Vision).
 *
 * Combine :
 *   - la position RTK du sujet (ClientRtkSujetV4, flux WSS ~10 Hz),
 *   - la position du drone (EtatCockpit de Telemetrie, telemetrie DJI),
 * et produit un EtatSujet exploitable a la fois par :
 *   - l'AFFICHAGE (carte : sujet, drone, ligne+distance, cercles 3 m / 5 m, trace),
 *   - la SECURITE (option A) : RTK perdu OU distance sous la limite -> conditions
 *     que la couche appelante injecte dans EtatCapteurs du NoyauSecurite. Le noyau
 *     garde le dernier mot ; ce module NE commande jamais le drone.
 *
 * >>> RTK sujet != position drone <<<
 * Le sujet a une position RTK precise (±2 cm). Le drone reste sur sa position
 * DJI/GNSS standard. La distance calculee est une BONNE ESTIMATION, pas une
 * garantie centimetrique. La limite basse (3 m) integre cette incertitude.
 *
 * Ce module est PUR (aucune dependance Android/DJI) -> testable seul en JVM.
 */
class SuiviSujetRtk(private val cfg: Config = Config()) {

    data class Config(
        /** Distance cible de cadrage (m) — zone "correcte". */
        val distanceCibleM: Double = 5.0,
        /** Limite minimale absolue (m) — sous cette valeur : DANGER + blocage. */
        val limiteMinM: Double = 3.0,
        /** Rayon de la zone de prudence (m) : entre limiteMin et prudence = vigilance. */
        val prudenceM: Double = 5.0,
        /** Age max de la position sujet avant de la considerer perdue (s). */
        val ageMaxS: Double = 3.0
    )

    /** Verdict de distance, pour l'affichage (couleur) et la conscience pilote. */
    enum class ZoneDistance {
        DANGER,      // < limiteMin (3 m) : cercle rouge, blocage
        PRUDENCE,    // entre limiteMin et prudence : vigilance
        CORRECTE,    // autour de la distance cible
        LOIN,        // au-dela : le sujet s'eloigne du cadrage
        INCONNUE     // pas de position fiable
    }

    /**
     * Etat consolide du suivi, recalcule a chaque cycle.
     *  - rtkSujetOk : la position sujet est LIVE, FIX, >=5 Hz et pas perimee.
     *                 (option A) : si false -> la couche appelante bloque le drone.
     *  - distanceSousLimite : distance drone-sujet < limiteMin -> (option A) blocage.
     *  - distanceM : distance horizontale drone-sujet (m), NaN si incalculable.
     *  - zone : verdict pour l'affichage.
     *  - capSujetDeg : cap de deplacement du sujet (deduit de la trace), ou null.
     */
    data class EtatSujet(
        val sujetPresent: Boolean,
        val rtkSujetOk: Boolean,
        val rtk: ClientRtkSujet.StatutRtk,
        val ageS: Double?,
        val distanceM: Double,
        val distanceSousLimite: Boolean,
        val zone: ZoneDistance,
        val sujetLat: Double,
        val sujetLon: Double,
        val capSujetDeg: Double?,
        val trace: List<DoubleArray>
    )

    /**
     * Calcule l'etat courant.
     * @param sujet position sujet (peut etre present=false).
     * @param droneLat / droneLon position drone (EtatCockpit) ; NaN si inconnue.
     */
    fun evaluer(
        sujet: ClientRtkSujet.PositionSujet,
        droneLat: Double,
        droneLon: Double
    ): EtatSujet {
        val rtkOk = sujet.controleFiable && (sujet.ageS == null || sujet.ageS <= cfg.ageMaxS)

        // distance drone-sujet (haversine) si les deux positions existent
        val distance: Double =
            if (rtkOk && !droneLat.isNaN() && !droneLon.isNaN())
                haversineM(droneLat, droneLon, sujet.lat, sujet.lon)
            else Double.NaN

        val sousLimite = !distance.isNaN() && distance < cfg.limiteMinM

        val zone = when {
            distance.isNaN() -> ZoneDistance.INCONNUE
            distance < cfg.limiteMinM -> ZoneDistance.DANGER
            distance < cfg.prudenceM -> ZoneDistance.PRUDENCE
            distance <= cfg.distanceCibleM + 1.0 -> ZoneDistance.CORRECTE
            else -> ZoneDistance.LOIN
        }

        val cap = sujet.capDeg ?: capDepuisTrace(sujet.trace)

        return EtatSujet(
            sujetPresent = sujet.present,
            rtkSujetOk = rtkOk,
            rtk = sujet.rtk,
            ageS = sujet.ageS,
            distanceM = distance,
            distanceSousLimite = sousLimite,
            zone = zone,
            sujetLat = sujet.lat,
            sujetLon = sujet.lon,
            capSujetDeg = cap,
            trace = sujet.trace
        )
    }

    /** Cap de deplacement (deg, 0=N, 90=E) deduit des 2 derniers points de trace. */
    private fun capDepuisTrace(trace: List<DoubleArray>): Double? {
        if (trace.size < 2) return null
        val a = trace[trace.size - 2]
        val b = trace[trace.size - 1]
        val dLat = b[0] - a[0]
        val dLon = (b[1] - a[1]) * cos(Math.toRadians((a[0] + b[0]) / 2.0))
        if (hypot(dLat, dLon) < 1e-7) return null   // sujet quasi immobile
        var cap = Math.toDegrees(atan2(dLon, dLat))
        if (cap < 0) cap += 360.0
        return cap
    }

    companion object {
        private const val R_TERRE_M = 6_371_000.0

        /** Distance horizontale (m) entre deux points WGS84 (haversine). */
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1)
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) +
                    cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * R_TERRE_M * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

