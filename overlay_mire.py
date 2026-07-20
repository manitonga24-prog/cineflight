# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\OverlayYolo.kt"
s = open(f, encoding="utf-8").read()
if "drawMire" in s or "mirePaint" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) ajouter un Paint pour la mire (apres traitSel)
a1 = '''    private val fondLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {'''
n1 = '''    private val mirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#76FF03")    // vert vif : mire de la cible
    }
    private val fondLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {'''
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# 2) dans onDraw : pour la cible (b.sel) dessiner une mire au lieu de la boite
a2 = '''            val trait = if (b.sel) traitSel else traitNormal
            canvas.drawRect(left, top, right, bottom, trait)
            // label : confiance (+ DANSEUR si selectionne)
            val label = (if (b.sel) "DANSEUR " else "") +
                "${(b.conf * 100).toInt()}%"'''
n2 = '''            if (b.sel) {
                // CIBLE : mire cercle + croix au centre
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val rayon = (Math.min(right - left, bottom - top) / 2f).coerceAtLeast(24f)
                canvas.drawCircle(cx, cy, rayon, mirePaint)
                val croix = rayon * 0.6f
                canvas.drawLine(cx - croix, cy, cx + croix, cy, mirePaint)
                canvas.drawLine(cx, cy - croix, cx, cy + croix, mirePaint)
            } else {
                // autres personnes : boite fine cyan
                canvas.drawRect(left, top, right, bottom, traitNormal)
            }
            // label : confiance (+ CIBLE si selectionne)
            val label = (if (b.sel) "CIBLE " else "") +
                "${(b.conf * 100).toInt()}%"'''
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Mire cible :", ch, "/ 2")