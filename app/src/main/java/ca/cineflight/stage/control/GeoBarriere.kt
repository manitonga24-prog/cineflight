package ca.cineflight.stage.control

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * GeoBarriere — enveloppe de vol polygonale (brique C, FONDATION de sécurité).
 *
 * >>> DOCTRINE (confirmée) <<<
 * Le drone ne sort JAMAIS du polygone validé. Le sujet peut se déplacer librement,
 * mais si une cible de mouvement tomberait hors de la zone, elle est ramenée à la
 * frontière (ou refusée). "Suivi libre DANS une enveloppe validée."
 *
 * Ce module est PUR (aucune dépendance Android/DJI) et NE COMMANDE PAS le drone.
 * Il répond à deux questions géométriques :
 *   1. estDedans(point)       -> le point est-il dans le polygone ?
 *   2. ecreterCible(...)       -> ramène une cible hors zone à la frontière.
 * La décision finale (gel, blocage) reste au NoyauSecurite, qui utilise ces
 * réponses. Le polygone vient du serveur (tracé + validé dans preview3d).
 *
 * Le polygone est en WGS84 (lat/lon). Les tests géométriques se font en projection
 * plane locale (m/deg) autour d'un point de référence : valable aux échelles d'un
 * tournage (quelques centaines de mètres).
 *
 * SÉCURITÉ PAR DÉFAUT : un polygone vide ou invalide (< 3 sommets) => AUCUN point
 * n'est "dedans" (estDedans renvoie false). Doctrine "non défini = non sûr" :
 * sans enveloppe validée, rien n'est autorisé.
 */
class GeoBarriere(sommets: List<Point> = emptyList()) {

    data class Point(val lat: Double, val lon: Double)

    private val poly: List<Point> = sommets.toList()
    val valide: Boolean get() = poly.size >= 3

    /** Référence de projection : 1er sommet (ou 0,0 si vide). */
    private val latRef = poly.firstOrNull()?.lat ?: 0.0
    private val lonRef = poly.firstOrNull()?.lon ?: 0.0
    private val mLat = 111_320.0
    private val mLon = 111_320.0 * cos(Math.toRadians(latRef))

    private fun toXY(p: Point): DoubleArray =
        doubleArrayOf((p.lon - lonRef) * mLon, (p.lat - latRef) * mLat)  // (est, nord)

    private fun toLL(x: Double, y: Double): Point =
        Point(latRef + y / mLat, lonRef + x / mLon)

    /**
     * Le point est-il DANS le polygone ? (ray-casting).
     * Polygone invalide (< 3 sommets) => false (doctrine "non défini = non sûr").
     */
    fun estDedans(p: Point): Boolean {
        if (!valide) return false
        val q = toXY(p)
        val px = q[0]; val py = q[1]
        var dedans = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = toXY(poly[i]); val b = toXY(poly[j])
            val ax = a[0]; val ay = a[1]; val bx = b[0]; val by = b[1]
            // le rayon horizontal depuis (px,py) coupe-t-il l'arête a-b ?
            if (((ay > py) != (by > py)) &&
                (px < (bx - ax) * (py - ay) / (by - ay) + ax)) {
                dedans = !dedans
            }
            j = i
        }
        return dedans
    }

    /** Distance (m) du point au bord le plus proche (0 si le polygone est invalide). */
    fun distanceAuBordM(p: Point): Double {
        if (!valide) return 0.0
        val q = toXY(p); var best = Double.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = toXY(poly[i]); val b = toXY(poly[j])
            best = min(best, distPointSegment(q[0], q[1], a[0], a[1], b[0], b[1]))
            j = i
        }
        return best
    }

    /**
     * Écrête une CIBLE de mouvement pour rester dans l'enveloppe.
     *
     * @param origine position actuelle du drone (supposée dans la zone en vol normal).
     * @param cible position visée par le module de mouvement.
     * @param margeM marge de sécurité intérieure (le drone s'arrête à margeM du bord).
     * @return CibleEcretee(point, dansZone, ramenee) :
     *   - si la cible est dans la zone (avec marge) : renvoyée telle quelle.
     *   - sinon : ramenée au dernier point sûr le long du segment origine->cible.
     *   - si l'origine elle-même est hors zone (anormal) : on renvoie l'origine
     *     (ne pas bouger) et ramenee=true -> le noyau gèlera.
     */
    fun ecreterCible(origine: Point, cible: Point, margeM: Double = 5.0): CibleEcretee {
        if (!valide) return CibleEcretee(origine, dansZone = false, ramenee = true)

        val dedansCible = estDedans(cible) &&
            (margeM <= 0.0 || distanceAuBordM(cible) >= margeM)
        if (dedansCible) {
            return CibleEcretee(cible, dansZone = true, ramenee = false)
        }
        // origine hors zone : situation anormale -> ne pas bouger (noyau gèlera).
        if (!estDedans(origine)) {
            return CibleEcretee(origine, dansZone = false, ramenee = true)
        }
        // recherche binaire du dernier point sûr le long de origine -> cible.
        val o = toXY(origine); val c = toXY(cible)
        var lo = 0.0; var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2.0
            val x = o[0] + (c[0] - o[0]) * mid
            val y = o[1] + (c[1] - o[1]) * mid
            val pt = toLL(x, y)
            val ok = estDedans(pt) && (margeM <= 0.0 || distanceAuBordM(pt) >= margeM)
            if (ok) lo = mid else hi = mid
        }
        val x = o[0] + (c[0] - o[0]) * lo
        val y = o[1] + (c[1] - o[1]) * lo
        return CibleEcretee(toLL(x, y), dansZone = true, ramenee = true)
    }

    data class CibleEcretee(
        val point: Point,
        val dansZone: Boolean,   // la cible finale est dans la zone
        val ramenee: Boolean     // true si la cible d'origine a dû être bridée/refusée
    )

    private fun distPointSegment(px: Double, py: Double,
                                 ax: Double, ay: Double,
                                 bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return hypot(px - ax, py - ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = max(0.0, min(1.0, t))
        val cx = ax + t * dx; val cy = ay + t * dy
        return hypot(px - cx, py - cy)
    }
}

