package ca.cineflight.stage.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * OverlayYolo — vue transparente qui dessine les boites de detection YOLO
 * par-dessus le flux video (SurfaceView).
 *
 * Les boites arrivent en coordonnees NORMALISEES 0-1 (via RecepteurBoxes) ;
 * on les multiplie par la taille de la vue pour obtenir des pixels ecran.
 *
 * IMPORTANT — calage avec la video : FluxCamera utilise ScaleType.CENTER_INSIDE
 * (garde le ratio, peut laisser des bandes). Si la video et l'overlay n'ont pas
 * le meme ratio, les boites peuvent etre legerement decalees sur les bords.
 * Pour un calage parfait il faudrait connaitre le ratio reel de la video ; on
 * commence simple (la vue plein ecran) et on ajustera si besoin.
 *
 * Usage : placer cette vue par-dessus la SurfaceView dans le layout, puis
 *   overlay.majBoxes(listeDeBoxes)  // appelle invalidate() -> onDraw
 */
class OverlayYolo @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var boxes: List<RecepteurBoxes.Box> = emptyList()

    private val traitNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E5FF")   // cyan : personnes detectees
    }
    private val traitSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#76FF03")    // vert vif : le danseur suivi
    }
    private val mirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#76FF03")    // vert vif : mire de la cible
    }
    private val fondLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")  // noir semi-transparent
    }
    private val texteLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
    }

    /** Met a jour les boites a dessiner (appel depuis le thread UI). */
    fun majBoxes(nouvelles: List<RecepteurBoxes.Box>) {
        boxes = nouvelles
        invalidate()   // declenche onDraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()

        for (b in boxes) {
            val left = b.x * w
            val top = b.y * h
            val right = (b.x + b.w) * w
            val bottom = (b.y + b.h) * h

            if (b.sel) {
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val rayon = (Math.min(right - left, bottom - top) / 2f).coerceAtLeast(24f)
                canvas.drawCircle(cx, cy, rayon, mirePaint)
                val croix = rayon * 0.6f
                canvas.drawLine(cx - croix, cy, cx + croix, cy, mirePaint)
                canvas.drawLine(cx, cy - croix, cx, cy + croix, mirePaint)
            } else {
                canvas.drawRect(left, top, right, bottom, traitNormal)
            }

            // label : confiance (+ DANSEUR si selectionne)
            val label = (if (b.sel) "CIBLE " else "") +
                "${(b.conf * 100).toInt()}%"
            val largeurTxt = texteLabel.measureText(label)
            val hautTxt = 38f
            canvas.drawRect(left, (top - hautTxt).coerceAtLeast(0f),
                left + largeurTxt + 12f, top, fondLabel)
            canvas.drawText(label, left + 6f,
                (top - 8f).coerceAtLeast(hautTxt - 8f), texteLabel)
        }
    }
}

