package ca.cineflight.stage

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import ca.cineflight.stage.cine.CineAuth

/**
 * Splash cinematique : un drone bleu ecrit "Cine" en vol (sillage lumineux),
 * puis le logo telecommande + clap monte et se stabilise.
 * Orientation paysage. Duree ~5 s, puis lance MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vue = SplashView(this) {
            // anime terminee -> login si pas connecte, sinon l'app
            val cible = if (CineAuth.estConnecte(this))
                MainActivity::class.java else LoginActivity::class.java
            startActivity(Intent(this, cible))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
        setContentView(vue)
    }

    override fun onBackPressed() { /* on bloque le retour pendant le splash */ }
}

private class SplashView(
    context: android.content.Context,
    val onFini: () -> Unit
) : View(context) {

    // ----- couleurs -----
    private val BLEU = Color.parseColor("#4FC3F7")
    private val GRIS = Color.parseColor("#90A4AE")
    private val NOIR = Color.parseColor("#0E0E12")
    private val BLANC = Color.WHITE
    private val BG_HAUT = Color.parseColor("#0A1E3D")
    private val BG_BAS = Color.parseColor("#05080F")

    // ----- peintures -----
    private val pFond = Paint()
    private val pTrace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = BLEU
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL) // halo lumineux
    }
    private val pTraceNet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = BLEU
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pDrone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = BLEU; strokeCap = Paint.Cap.ROUND
    }
    private val pDroneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLEU }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BLEU; maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
    }
    private val pTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFE2F7"); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    // ----- geometrie du trace "Cine" (coordonnees dans un repere 460x300, comme le proto) -----
    private val VW = 460f; private val VH = 300f
    private val pathCine = Path().apply {
        // C
        moveTo(150f, 80f)
        cubicTo(120f, 55f, 80f, 70f, 80f, 120f)
        cubicTo(80f, 170f, 125f, 180f, 150f, 150f)
        cubicTo(155f, 143f, 156f, 138f, 156f, 132f)
        // i (tige)
        moveTo(186f, 108f); lineTo(186f, 175f)
        // n
        moveTo(212f, 175f); lineTo(212f, 135f)
        cubicTo(212f, 135f, 235f, 118f, 256f, 138f)
        cubicTo(262f, 144f, 262f, 158f, 262f, 175f)
        // e accent (é)
        moveTo(286f, 160f)
        cubicTo(286f, 160f, 326f, 166f, 326f, 134f)
        cubicTo(326f, 108f, 292f, 108f, 286f, 138f)
        cubicTo(282f, 168f, 318f, 168f, 330f, 150f)
    }
    // Mesure TOUS les contours (C, i, n, é sont des sous-traces separes)
    private data class Contour(val length: Float, val path: Path, val measure: PathMeasure)
    private val contours: List<Contour> by lazy {
        val list = ArrayList<Contour>()
        val pm = PathMeasure(pathCine, false)
        do {
            val len = pm.length
            if (len > 0f) {
                // copier ce contour dans un Path dedie
                val seg = Path()
                pm.getSegment(0f, len, seg, true)
                val pmSeg = PathMeasure(seg, false)
                list.add(Contour(len, seg, pmSeg))
            }
        } while (pm.nextContour())
        list
    }
    private val longueurTotale: Float by lazy { contours.sumOf { it.length.toDouble() }.toFloat() }
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    // matrice pour passer du repere 460x300 a l'ecran (centre, paysage)
    private val M = Matrix()

    // ----- etat anime -----
    private var progTrace = 0f   // 0..1 : avancement du trace "Cine"
    private var alphaDrone = 0f
    private var progLogo = 0f    // 0..1 : montee du logo
    private var alphaTexte = 0f
    private var phase = 0        // 0 = ecriture, 1 = reveal logo

    init {
        // sequence d'animations
        // Phase ecriture : 4000 ms
        val aEcriture = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { progTrace = it.animatedValue as Float; alphaDrone = 1f; invalidate() }
        }
        // Phase reveal logo : 1100 ms, demarre apres l'ecriture
        val aReveal = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 4000
            addUpdateListener {
                phase = 1
                progLogo = it.animatedValue as Float
                alphaDrone = 1f - progLogo
                alphaTexte = progLogo
                invalidate()
            }
        }
        aEcriture.start()
        aReveal.start()
        // fin -> callback apres 5300 ms
        postDelayed({ onFini() }, 5300)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // fond degrade vertical
        pFond.shader = LinearGradient(0f, 0f, 0f, h, BG_HAUT, BG_BAS, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, pFond)

        // matrice : adapter le repere virtuel 460x300 a l'ecran, centre, avec marge
        val scale = minOf(w / VW, h / VH) * 0.82f
        val dx = (w - VW * scale) / 2f
        val dy = (h - VH * scale) / 2f
        M.reset(); M.postScale(scale, scale); M.postTranslate(dx, dy)

        canvas.save()
        canvas.concat(M)

        if (phase == 0 || progLogo < 1f) {
            // ----- trace "Cine" revele progressivement sur TOUS les contours -----
            pTrace.strokeWidth = 5f
            pTraceNet.strokeWidth = 5f
            val a = ((1f - progLogo) * 255).toInt().coerceIn(0, 255)
            pTrace.alpha = (a * 0.9f).toInt(); pTraceNet.alpha = a

            var aParcourir = longueurTotale * progTrace   // longueur totale a reveler
            var dronePosX = 0f; var dronePosY = 0f; var droneAng = 0f; var droneVisible = false

            for (ct in contours) {
                if (aParcourir <= 0f) break
                val montre = minOf(aParcourir, ct.length)
                val seg = Path()
                ct.measure.getSegment(0f, montre, seg, true)
                canvas.drawPath(seg, pTrace)
                canvas.drawPath(seg, pTraceNet)
                // le drone est au bout du contour en cours de trace
                if (montre < ct.length || aParcourir <= ct.length) {
                    ct.measure.getPosTan(montre, pos, tan)
                    dronePosX = pos[0]; dronePosY = pos[1]
                    droneAng = Math.toDegrees(Math.atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
                    droneVisible = true
                }
                aParcourir -= ct.length
            }

            // ----- drone bleu au bout du trace -----
            if (droneVisible && progTrace > 0f && progTrace < 1f && alphaDrone > 0f) {
                dessineDrone(canvas, dronePosX, dronePosY, droneAng, alphaDrone)
            }
        }

        // ----- logo RC + clap (monte depuis le bas) -----
        if (phase == 1 && progLogo > 0f) {
            val offY = (1f - progLogo) * 46f
            canvas.save()
            canvas.translate(0f, offY)
            val al = (progLogo * 255).toInt().coerceIn(0, 255)
            dessineLogo(canvas, al)
            canvas.restore()
        }

        canvas.restore()

        // ----- texte "Flight" (hors matrice, taille ecran) -----
        if (alphaTexte > 0f) {
            pTexte.alpha = (alphaTexte * 255).toInt().coerceIn(0, 255)
            pTexte.textSize = h * 0.07f
            canvas.drawText("Flight", w / 2f, h * 0.80f, pTexte)
        }
    }

    private fun dessineDrone(c: Canvas, cx: Float, cy: Float, angle: Float, alpha: Float) {
        c.save(); c.translate(cx, cy); c.rotate(angle)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        pDrone.alpha = a; pDroneFill.alpha = a
        pDrone.strokeWidth = 2.4f
        val br = 11f; val hr = 4.5f
        // bras en X
        c.drawLine(0f, 0f, -br, -br, pDrone); c.drawLine(0f, 0f, br, -br, pDrone)
        c.drawLine(0f, 0f, -br, br, pDrone);  c.drawLine(0f, 0f, br, br, pDrone)
        // helices (cercles)
        c.drawCircle(-br, -br, hr, pDrone); c.drawCircle(br, -br, hr, pDrone)
        c.drawCircle(-br, br, hr, pDrone);  c.drawCircle(br, br, hr, pDrone)
        // corps
        c.drawCircle(0f, 0f, 5f, pDroneFill)
        c.restore()
    }

    private fun dessineLogo(c: Canvas, alpha: Int) {
        // coordonnees dans le repere virtuel (centrees ~ x:170-290, y:150-230)
        pFill.alpha = alpha
        // antennes (gris)
        pFill.color = GRIS; pFill.alpha = alpha
        val pAnt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRIS; this.alpha = alpha; strokeWidth = 3.5f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(207f, 170f, 201f, 150f, pAnt)
        c.drawLine(253f, 170f, 259f, 150f, pAnt)
        // corps RC (gris) rectangle arrondi
        pFill.color = GRIS; pFill.alpha = alpha
        val corps = RectF(170f, 170f, 290f, 230f)
        c.drawRoundRect(corps, 10f, 10f, pFill)
        // claquoir (noir) - quadrilatere incline
        pFill.color = NOIR; pFill.alpha = alpha
        val claq = Path().apply {
            moveTo(176f, 166f); lineTo(286f, 136f); lineTo(292f, 154f); lineTo(182f, 184f); close()
        }
        c.drawPath(claq, pFill)
        // bandes blanches du claquoir
        pFill.color = BLANC; pFill.alpha = alpha
        val xs = floatArrayOf(182f, 200f, 218f, 236f, 254f, 272f)
        for (i in xs.indices) {
            val x = xs[i]; val yo = 150f - i * 5f
            val b = Path().apply {
                moveTo(x, yo); lineTo(x + 7f, yo - 2f); lineTo(x + 3f, yo + 13f); lineTo(x - 4f, yo + 15f); close()
            }
            c.drawPath(b, pFill)
        }
        // sticks (noir + cyan)
        pFill.color = NOIR; pFill.alpha = alpha
        c.drawCircle(202f, 206f, 9f, pFill)
        c.drawCircle(258f, 206f, 9f, pFill)
        pFill.color = BLEU; pFill.alpha = alpha
        c.drawCircle(202f, 206f, 5f, pFill)
        c.drawCircle(258f, 206f, 5f, pFill)
        // ecran central (noir)
        pFill.color = NOIR; pFill.alpha = alpha
        c.drawRect(220f, 190f, 240f, 204f, pFill)
        // charniere cyan
        pFill.color = BLEU; pFill.alpha = alpha
        c.drawCircle(177f, 171f, 3f, pFill)
    }
}

