# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
if "Mode de montage" in s:
    print("DEJA present"); raise SystemExit

anc = """        col.addView(lignePos)
        btnsPos.forEach { (k, bb) -> majBouton(bb, k == position) }

        // --- bouton creer ---"""
add = """        col.addView(lignePos)
        btnsPos.forEach { (k, bb) -> majBouton(bb, k == position) }

        // --- selecteur de mode de montage ---
        col.addView(TextView(this).apply {
            text = "Mode de montage"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(2))
        })
        val ligneMode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scrollMode = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ligneMode) }
        // options : Rythme (rapide) + 3 modes intelligents YOLO
        data class OptMode(val libelle: String, val rythme: Boolean, val m: SelecteurSegments.Mode?)
        val options = listOf(
            OptMode("Rapide", true, null),
            OptMode("Best-of (IA)", false, SelecteurSegments.Mode.BEST_OF),
            OptMode("Un/clip (IA)", false, SelecteurSegments.Mode.UN_PAR_CLIP),
            OptMode("Adaptatif (IA)", false, SelecteurSegments.Mode.ADAPTATIF)
        )
        val btnsMode = ArrayList<Pair<Button, OptMode>>()
        for (opt in options) {
            val b = Button(this).apply {
                text = opt.libelle; isAllCaps = false; textSize = 13f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)); lp.rightMargin = dp(6); layoutParams = lp
                setOnClickListener {
                    modeRythme = opt.rythme
                    if (opt.m != null) mode = opt.m
                    btnsMode.forEach { (bb, oo) -> majBouton(bb, oo.libelle == opt.libelle) }
                }
            }
            btnsMode.add(b to opt); ligneMode.addView(b)
        }
        col.addView(scrollMode)
        // selection initiale : Best-of (IA)
        btnsMode.forEach { (bb, oo) -> majBouton(bb, oo.m == SelecteurSegments.Mode.BEST_OF && !oo.rythme) }

        // --- bouton creer ---"""
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Etape 2 (selecteur mode) OK")
else:
    print("ANCRE NON TROUVEE")