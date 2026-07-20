# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\OverlayYolo.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) remplacer trait+drawRect par mire (cible) / boite fine (autres)
a1 = '''            val trait = if (b.sel) traitSel else traitNormal
            canvas.drawRect(left, top, right, bottom, trait)'''
n1 = '''            if (b.sel) {
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val rayon = (Math.min(right - left, bottom - top) / 2f).coerceAtLeast(24f)
                canvas.drawCircle(cx, cy, rayon, mirePaint)
                val croix = rayon * 0.6f
                canvas.drawLine(cx - croix, cy, cx + croix, cy, mirePaint)
                canvas.drawLine(cx, cy - croix, cx, cy + croix, mirePaint)
            } else {
                canvas.drawRect(left, top, right, bottom, traitNormal)
            }'''
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# 2) label DANSEUR -> CIBLE
a2 = 'val label = (if (b.sel) "DANSEUR " else "") +'
n2 = 'val label = (if (b.sel) "CIBLE " else "") +'
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Mire (complement) :", ch, "/ 2")