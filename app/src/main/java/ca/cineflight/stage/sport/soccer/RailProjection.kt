package ca.cineflight.stage.sport.soccer

import kotlin.math.PI
import kotlin.math.cos

/**
 * RailProjection — PROJECTION GNSS -> RAIL (passe E), pure (aucun SDK/Android).
 *
 * Transforme une position geographique du drone (lat/lon) en une FRACTION [0,1] le
 * long du rail, en projetant orthogonalement le point sur le segment [start, end].
 *
 * POURQUOI (spec 9D/E) : la position simulee 0.5 est INTERDITE pour tout deplacement
 * reel. Cette projection fournit la position ACTUELLE reelle du drone sur le rail,
 * indispensable avant l'Essai 2 (vitesse > 0).
 *
 * METHODE : on convertit les coordonnees en metres locaux via une projection
 * equirectangulaire centree sur le rail (precise a l'echelle d'un terrain de sport),
 * puis on calcule la projection scalaire du vecteur (start->drone) sur (start->end),
 * divisee par la longueur du rail. Resultat borne dans [0,1].
 *
 * CONVENTION : fraction 0 = extremite [start], fraction 1 = extremite [end]. Un
 * deplacement du drone VERS end fait CROITRE la fraction (axe et signe verifies par tests).
 */
object RailProjection {

    /** Rayon terrestre moyen (m). */
    private const val R_TERRE_M = 6_371_000.0
    private const val DEG2RAD = PI / 180.0

    /**
     * Repere local (metres) du point projete sur le rail. SOURCE UNIQUE de la
     * projection equirectangulaire : ex/ey = vecteur rail, dx/dy = vecteur start->point,
     * longueur2 = |rail|^2. Utilise par [fraction] ET [distanceM] (pas de copie).
     */
    private data class RepereLocal(
        val ex: Double, val ey: Double, val dx: Double, val dy: Double, val longueur2: Double,
    )

    private fun projeterLocal(point: RailPoint, rail: DroneRail): RepereLocal {
        val latRef = (rail.start.lat + rail.end.lat) / 2.0
        val kx = cos(latRef * DEG2RAD) * R_TERRE_M * DEG2RAD   // m par degre de longitude
        val ky = R_TERRE_M * DEG2RAD                            // m par degre de latitude
        val ex = (rail.end.lon - rail.start.lon) * kx
        val ey = (rail.end.lat - rail.start.lat) * ky
        val dx = (point.lon - rail.start.lon) * kx
        val dy = (point.lat - rail.start.lat) * ky
        return RepereLocal(ex, ey, dx, dy, ex * ex + ey * ey)
    }

    /**
     * Projette [drone] sur le rail [rail] et renvoie la fraction [0f, 1f].
     * Si le rail est degenere (longueur nulle), renvoie 0f (fail-safe).
     */
    fun fraction(drone: RailPoint, rail: DroneRail): Float {
        val r = projeterLocal(drone, rail)
        if (r.longueur2 <= 0.0) return 0f   // rail degenere
        val t = (r.dx * r.ex + r.dy * r.ey) / r.longueur2
        return t.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Distance REELLE (metres) d'un point au SEGMENT de rail [start, end]. Prend en
     * compte le bornage aux extremites : si le point se projette au-dela d'un bout,
     * la distance est mesuree jusqu'a ce bout (pas la ligne infinie). Sert a verifier
     * qu'un point (ex. decollage) est proche du rail.
     */
    fun distanceM(point: RailPoint, rail: DroneRail): Double {
        val r = projeterLocal(point, rail)
        if (r.longueur2 <= 0.0) return Math.hypot(r.dx, r.dy)   // rail degenere -> distance a start
        val t = ((r.dx * r.ex + r.dy * r.ey) / r.longueur2).coerceIn(0.0, 1.0)
        return Math.hypot(r.dx - t * r.ex, r.dy - t * r.ey)
    }
}
