package ca.cineflight.stage.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * RadarView — petit radar facon DJI : le PILOTE au centre, le DRONE en fleche
 * placee dans la direction reelle (azimut depuis le pilote) a une distance
 * proportionnelle, et oriente selon le CAP du drone (ou pointe son nez).
 *
 * Donnees fournies via maj(...) : position drone, position pilote, cap drone.
 * Tout est calcule localement (pas de reseau).
 *
 * Anneaux de distance auto-echelonnes : l'anneau exterieur s'adapte a la distance
 * du drone (min 50 m) pour qu'il reste toujours visible dans le cercle.
 */
class RadarView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, style: Int = 0
) : View(ctx, attrs, style) {

    private var droneLat = Double.NaN
    private var droneLon = Double.NaN
    private var piloteLat = Double.NaN
    private var piloteLon = Double.NaN
    private var capDeg = Float.NaN          // cap du drone (0 = Nord, sens horaire)
    private var distanceM = 0.0
    private var azimutDeg = 0.0             // direction drone vue du pilote

    private val pFond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0E1116.toInt() }
    private val pAnneau = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setStyle(Paint.Style.STROKE); strokeWidth = 2f; color = 0x55FFFFFF.toInt()
    }
    private val pAxe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setStyle(Paint.Style.STROKE); strokeWidth = 1f; color = 0x33FFFFFF.toInt()
    }
    private val pPilote = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C759.toInt() }
    private val pDrone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val pLien = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setStyle(Paint.Style.STROKE); strokeWidth = 1.5f; color = 0x660A84FF.toInt()
    }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x330A84FF.toInt()
    }
    private val pCardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val pEchelle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x77FFFFFF.toInt(); textAlign = Paint.Align.LEFT
    }
    private val pTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val pTexteFort = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Met a jour les positions et redessine. cap en degres (0=N), NaN si inconnu. */
    fun maj(droneLat: Double, droneLon: Double, piloteLat: Double, piloteLon: Double, capDeg: Float) {
        this.droneLat = droneLat; this.droneLon = droneLon
        this.piloteLat = piloteLat; this.piloteLon = piloteLon
        this.capDeg = capDeg
        calculer()
        invalidate()
    }

    private fun calculer() {
        if (droneLat.isNaN() || droneLon.isNaN() || piloteLat.isNaN() || piloteLon.isNaN()) {
            distanceM = 0.0; return
        }
        // distance + azimut (cap vers le drone depuis le pilote) — formule haversine/bearing
        val R = 6371000.0
        val dLat = Math.toRadians(droneLat - piloteLat)
        val dLon = Math.toRadians(droneLon - piloteLon)
        val la1 = Math.toRadians(piloteLat); val la2 = Math.toRadians(droneLat)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        distanceM = R * 2 * atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        azimutDeg = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Distance de l'anneau exterieur (m), echelonnee par paliers lisibles. */
    private fun echelleM(): Double {
        val d = max(distanceM, 1.0)
        val paliers = doubleArrayOf(50.0, 100.0, 200.0, 300.0, 500.0, 1000.0, 2000.0, 5000.0)
        for (p in paliers) if (d <= p * 0.95) return p
        return paliers.last()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val rayon = min(w, h) / 2f - dp(6)

        // fond circulaire
        c.drawCircle(cx, cy, rayon, pFond)
        // anneaux (3) + axes N-S / E-O
        for (i in 1..3) c.drawCircle(cx, cy, rayon * i / 3f, pAnneau)
        c.drawLine(cx, cy - rayon, cx, cy + rayon, pAxe)
        c.drawLine(cx - rayon, cy, cx + rayon, cy, pAxe)

        // 1) Points cardinaux N / S / E / O
        pCardinal.textSize = dp(9)
        c.drawText("N", cx, cy - rayon + dp(10), pCardinal)
        c.drawText("S", cx, cy + rayon - dp(3), pCardinal)
        c.drawText("E", cx + rayon - dp(7), cy + dp(4), pCardinal)
        c.drawText("O", cx - rayon + dp(7), cy + dp(4), pCardinal)
        // N en gras par-dessus (repere principal)
        pTexteFort.textSize = dp(11)
        c.drawText("N", cx, cy - rayon + dp(11), pTexteFort)

        // 2) Etiquette de distance sur l'anneau exterieur
        val ech = echelleM()
        val echTxt = if (ech >= 1000) "%.0f km".format(ech / 1000.0) else "%.0f m".format(ech)
        pEchelle.textSize = dp(8)
        c.drawText(echTxt, cx + dp(3), cy - rayon + dp(11), pEchelle)

        // pilote au centre
        c.drawCircle(cx, cy, dp(4), pPilote)

        if (distanceM <= 0.0) {
            pTexte.textSize = dp(9)
            c.drawText("position…", cx, cy + rayon - dp(6), pTexte)
            return
        }

        // position du drone sur le radar (azimut reel, distance proportionnelle a l'echelle)
        val rNorm = (distanceM / ech).coerceIn(0.0, 1.0)
        val rPix = rNorm * rayon
        // azimut : 0 = Nord (haut), sens horaire
        val ar = Math.toRadians(azimutDeg)
        val dx = (sin(ar) * rPix).toFloat()
        val dy = (-cos(ar) * rPix).toFloat()
        val drx = cx + dx; val dry = cy + dy

        // 3) Trait pilote -> drone (direction + eloignement d'un coup d'oeil)
        c.drawLine(cx, cy, drx, dry, pLien)

        // 4) Halo autour de la fleche drone
        c.drawCircle(drx, dry, dp(9), pGlow)

        // fleche drone orientee selon le CAP (ou son nez pointe) ; sinon vers l'exterieur
        val capUtilise = if (!capDeg.isNaN()) capDeg.toDouble() else azimutDeg
        dessinerFleche(c, drx, dry, capUtilise, dp(7), pDrone)

        // 5) Libelle bas : distance + cap du drone
        pTexte.textSize = dp(9)
        val distTxt = if (distanceM >= 1000) "%.1f km".format(distanceM / 1000.0)
                      else "%.0f m".format(distanceM)
        val capTxt = if (!capDeg.isNaN()) "  \u00b7  %.0f\u00b0".format(capDeg) else ""
        c.drawText(distTxt + capTxt, cx, cy + rayon - dp(5), pTexte)
    }

    private fun dessinerFleche(c: Canvas, x: Float, y: Float, capDeg: Double, taille: Float, p: Paint) {
        val ar = Math.toRadians(capDeg)
        // pointe dans la direction du cap
        val px = x + (sin(ar) * taille).toFloat()
        val py = y - (cos(ar) * taille).toFloat()
        // base : deux ailes a +/-140 deg
        val g = Math.toRadians(capDeg + 140)
        val d = Math.toRadians(capDeg - 140)
        val gx = x + (sin(g) * taille).toFloat()
        val gy = y - (cos(g) * taille).toFloat()
        val dx = x + (sin(d) * taille).toFloat()
        val dy = y - (cos(d) * taille).toFloat()
        val path = Path().apply {
            moveTo(px, py); lineTo(gx, gy); lineTo(x, y); lineTo(dx, dy); close()
        }
        c.drawPath(path, p)
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
    private fun dp(v: Double): Float = (v * resources.displayMetrics.density).toFloat()
}

