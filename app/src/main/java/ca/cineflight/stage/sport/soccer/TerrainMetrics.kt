package ca.cineflight.stage.sport.soccer

import kotlin.math.PI
import kotlin.math.cos

/**
 * TerrainMetrics — DIMENSIONS REELLES du terrain (m) + conversion metres <-> fraction.
 * Pur (aucun SDK/Android).
 *
 * Un retrait ou un decalage doit etre exprime en METRES (robuste : un terrain fait
 * 90, 100 ou 120 m). On convertit ces metres en FRACTION du terrain (repere [0,1]
 * utilise par Soccer2DPlanner) grace aux dimensions reelles calculees ici.
 *
 * Les dimensions sont estimees par la boite englobante geographique du polygone :
 * largeur (est-ouest) et longueur (nord-sud) en metres. Suffisant pour convertir un
 * retrait metrique en fraction a l'echelle d'un terrain de sport.
 */
object TerrainMetrics {

    private const val R_TERRE_M = 6_371_000.0
    private const val DEG2RAD = PI / 180.0

    /** Dimensions du terrain en metres (bounding box). 0 si polygone insuffisant. */
    data class Dimensions(val largeurM: Double, val longueurM: Double) {
        val valide: Boolean get() = largeurM > 1.0 && longueurM > 1.0
    }

    /** Calcule largeur (E-O) et longueur (N-S) reelles du polygone terrain. */
    fun dimensions(terrain: List<RailPoint>): Dimensions {
        if (terrain.size < 3) return Dimensions(0.0, 0.0)
        var latMin = Double.MAX_VALUE; var latMax = -Double.MAX_VALUE
        var lonMin = Double.MAX_VALUE; var lonMax = -Double.MAX_VALUE
        for (p in terrain) {
            if (p.lat < latMin) latMin = p.lat; if (p.lat > latMax) latMax = p.lat
            if (p.lon < lonMin) lonMin = p.lon; if (p.lon > lonMax) lonMax = p.lon
        }
        val latRef = (latMin + latMax) / 2.0
        val mParDegLat = R_TERRE_M * DEG2RAD
        val mParDegLon = cos(latRef * DEG2RAD) * R_TERRE_M * DEG2RAD
        val longueurM = (latMax - latMin) * mParDegLat
        val largeurM = (lonMax - lonMin) * mParDegLon
        return Dimensions(largeurM, longueurM)
    }

    /**
     * Convertit une distance en METRES en FRACTION du terrain, en utilisant la
     * dimension MOYENNE (largeur+longueur)/2 comme reference isotrope. Si les
     * dimensions sont invalides, repli sur un terrain standard (~100 m).
     */
    fun metresEnFraction(metres: Double, dims: Dimensions): Float {
        val ref = if (dims.valide) (dims.largeurM + dims.longueurM) / 2.0 else 100.0
        if (ref <= 0.0) return 0f
        return (metres / ref).toFloat().coerceIn(0f, 1f)
    }
}
