f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\YoloSuivi.kt"
s = open(f, encoding="utf-8").read()
if "cibleDesignee" in s:
    print("DEJA present"); raise SystemExit

old = '''    private fun traiter(res: FloatArray?, w: Int, h: Int) {
        if (res == null || res.isEmpty()) { onSujet(false, 0f, 0f, 0f, 0f); return }
        val n = res[0].toInt()
        var meilleureAire = 0f
        var bx = 0f; var by = 0f; var bw = 0f; var bh = 0f
        var trouve = false
        for (i in 0 until n) {
            val o = 1 + i * 6
            val label = res[o].toInt()
            if (label != CLASSE_PERSONNE) continue
            val x = res[o + 2]; val y = res[o + 3]
            val ww = res[o + 4]; val hh = res[o + 5]
            val aire = ww * hh
            if (aire > meilleureAire) {
                meilleureAire = aire
                bx = x; by = y; bw = ww; bh = hh
                trouve = true
            }
        }
        if (trouve) {
            // normalise + centre
            val cx = (bx + bw / 2f) / w
            val cy = (by + bh / 2f) / h
            onSujet(true, cx, cy, bw / w, bh / h)
        } else {
            onSujet(false, 0f, 0f, 0f, 0f)
        }
    }'''

new = '''    private fun traiter(res: FloatArray?, w: Int, h: Int) {
        if (res == null || res.isEmpty()) { onSujet(false, 0f, 0f, 0f, 0f); return }
        val n = res[0].toInt()
        // collecte toutes les personnes (centre normalise + taille)
        val personnes = ArrayList<FloatArray>()  // [cx, cy, fw, fh]
        for (i in 0 until n) {
            val o = 1 + i * 6
            if (res[o].toInt() != CLASSE_PERSONNE) continue
            val x = res[o + 2]; val y = res[o + 3]
            val ww = res[o + 4]; val hh = res[o + 5]
            val cx = (x + ww / 2f) / w
            val cy = (y + hh / 2f) / h
            personnes.add(floatArrayOf(cx, cy, ww / w, hh / h))
        }
        if (personnes.isEmpty()) { onSujet(false, 0f, 0f, 0f, 0f); return }

        val choisie: FloatArray = if (cibleDesignee) {
            // ré-association : la personne la plus proche de la derniere position connue
            var best = personnes[0]
            var bestD = Float.MAX_VALUE
            for (p in personnes) {
                val dx = p[0] - cibleCx; val dy = p[1] - cibleCy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = p }
            }
            best
        } else {
            // pas de cible designee : la plus grande (comportement par defaut)
            personnes.maxByOrNull { it[2] * it[3] }!!
        }
        // memorise la position pour la frame suivante
        cibleCx = choisie[0]; cibleCy = choisie[1]
        onSujet(true, choisie[0], choisie[1], choisie[2], choisie[3])
    }

    // --- Cible designee par le pilote (toucher ecran) ---
    @Volatile private var cibleDesignee = false
    @Volatile private var cibleCx = 0.5f
    @Volatile private var cibleCy = 0.5f

    /** Le pilote touche l'ecran : on designe la cible a cette position normalisee (0..1). */
    fun designerCible(cxNorm: Float, cyNorm: Float) {
        cibleCx = cxNorm.coerceIn(0f, 1f)
        cibleCy = cyNorm.coerceIn(0f, 1f)
        cibleDesignee = true
    }

    /** Annule la designation : retour au comportement "plus grande personne". */
    fun annulerCible() { cibleDesignee = false }

    fun aCibleDesignee(): Boolean = cibleDesignee'''

if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Brique A : cible designee + reassociation ajoutee")
else:
    print("ANCRE NON TROUVEE")