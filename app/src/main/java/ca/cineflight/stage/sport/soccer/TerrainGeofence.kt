package ca.cineflight.stage.sport.soccer

/**
 * TerrainGeofence — geoperage du TERRAIN (polygone), pur (aucun SDK/Android).
 *
 * Sert a verifier qu'un point (ex. la cible du drone) est A L'INTERIEUR du contour
 * du terrain defini dans le Web. Si le terrain est defini, le drone est autorise a
 * aller n'importe ou DANS ce polygone (au lieu d'etre limite au seul rail).
 *
 * Algorithme : ray casting (parite des intersections d'un rayon horizontal). Robuste
 * pour un polygone simple (non auto-intersectant), ce qui est le cas d'un terrain.
 * Travaille en lat/lon directement (a l'echelle d'un terrain, la distorsion est
 * negligeable pour un test d'appartenance).
 */
object TerrainGeofence {

    /**
     * true si [point] (RailPoint lat/lon) est a l'interieur du polygone [terrain].
     * < 3 sommets -> false. Delegue a [dansPolygone] : SOURCE UNIQUE de l'algorithme.
     */
    fun contient(point: RailPoint, terrain: List<RailPoint>): Boolean =
        dansPolygone(point.lon, point.lat, terrain.map { it.lon }, terrain.map { it.lat })

    /**
     * Ray casting GENERIQUE (source unique, reutilisee par tout le package) : le point
     * (px, py) est-il dans le polygone dont les sommets sont (xs[i], ys[i]) ?
     * < 3 sommets -> false. px/py et xs/ys dans le meme repere (lat/lon OU fraction).
     */
    fun dansPolygone(px: Double, py: Double, xs: List<Double>, ys: List<Double>): Boolean {
        val n = minOf(xs.size, ys.size)
        if (n < 3) return false
        var dedans = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = xs[i]; val yi = ys[i]
            val xj = xs[j]; val yj = ys[j]
            val intersecte = ((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersecte) dedans = !dedans
            j = i
        }
        return dedans
    }
}
