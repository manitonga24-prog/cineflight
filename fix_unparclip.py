# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\SelecteurSegments.kt"
s = open(f, encoding="utf-8").read()

old = '''        val out = ArrayList<Segment>()
        for (cs in scores) {
            val n = cs.scoresParSeconde.size
            if (n == 0) continue
            val fen = dureeSec.coerceAtMost(n)
            // fenetre glissante : somme max
            var meilleurDebut = 0; var meilleureSomme = -1f
            for (d in 0..(n - fen)) {
                var s = 0f
                for (k in d until d + fen) s += cs.scoresParSeconde[k]
                if (s > meilleureSomme) { meilleureSomme = s; meilleurDebut = d }
            }
            out.add(Segment(cs.uri, meilleurDebut * 1000L, (meilleurDebut + fen) * 1000L))
        }
        return out
    }'''
new = '''        val out = ArrayList<Segment>()
        val maxSec = if (dureeSec > 0) dureeSec else 12   // plafond de securite
        for (cs in scores) {
            val sc = cs.scoresParSeconde
            val n = sc.size
            if (n == 0) continue
            // 1) trouver le pic de qualite (meilleure seconde)
            var pic = 0; var meilleur = sc[0]
            for (i in 1 until n) if (sc[i] > meilleur) { meilleur = sc[i]; pic = i }
            // seuil relatif : on etend tant que c'est "assez bon" autour du pic
            val seuilLocal = (meilleur * 0.55f).coerceAtLeast(0.3f)
            var d = pic; var fEnd = pic
            // etendre vers la gauche
            while (d - 1 >= 0 && sc[d - 1] >= seuilLocal && (fEnd - (d - 1) + 1) <= maxSec) d--
            // etendre vers la droite
            while (fEnd + 1 < n && sc[fEnd + 1] >= seuilLocal && (fEnd + 1 - d + 1) <= maxSec) fEnd++
            // au moins 2 s
            if (fEnd - d + 1 < 2) { fEnd = (d + 1).coerceAtMost(n - 1) }
            out.add(Segment(cs.uri, d * 1000L, (fEnd + 1) * 1000L))
        }
        return out
    }'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("unParClip autonome OK")
else:
    print("ANCRE NON TROUVEE")