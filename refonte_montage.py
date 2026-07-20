# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()

debut = s.find("    override fun onCreate(s: Bundle?) {")
if debut == -1:
    print("onCreate NON TROUVE"); raise SystemExit
fin_marqueur = "        demanderPermissionEtLister()\n    }"
fin = s.find(fin_marqueur)
if fin == -1:
    print("FIN onCreate NON TROUVEE"); raise SystemExit
fin += len(fin_marqueur)

nouveau = '''    // refs pour affichage conditionnel des reglages
    private var blocDuree: View? = null
    private var blocPosition: View? = null
    private var descMode: TextView? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val racine = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        racine.addView(col)
        conteneur = col

        // === TITRE ===
        col.addView(TextView(this).apply {
            text = "Montage automatique"; textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Assemblez vos plans en un clip, automatiquement."
            textSize = 13f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, dp(2), 0, dp(14))
        })

        // === ENCADRE : recuperer les videos via QuickTransfer ===
        col.addView(carte(
            "1.  D'abord, recuperez vos videos",
            "Les videos du drone se transferent le plus vite avec QuickTransfer dans l'app DJI Fly : " +
            "le telephone se connecte directement au drone en Wi-Fi (jusqu'a 25 Mo/s), bien plus rapide " +
            "que par la radiocommande.\\n\\nDrone pose : ouvrez DJI Fly, allez dans l'album, lancez QuickTransfer, " +
            "telechargez vos videos. Revenez ensuite ici pour les monter."
        ))

        // === SECTION MODE ===
        col.addView(titreSection("2.  Choisissez un mode"))
        val ligneMode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scrollMode = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ligneMode) }
        data class OptMode(val libelle: String, val rythme: Boolean, val m: SelecteurSegments.Mode?, val desc: String)
        val options = listOf(
            OptMode("Rapide", true, null,
                "Rapide : sans analyse, garde un extrait de chaque clip. Instantane."),
            OptMode("Best-of (IA)", false, SelecteurSegments.Mode.BEST_OF,
                "Best-of : l'IA analyse vos clips et garde les meilleurs moments en un condense (~30 s)."),
            OptMode("Un/clip (IA)", false, SelecteurSegments.Mode.UN_PAR_CLIP,
                "Un par clip : l'IA garde le meilleur passage de chaque clip choisi."),
            OptMode("Adaptatif (IA)", false, SelecteurSegments.Mode.ADAPTATIF,
                "Adaptatif : l'IA garde tout ce qui depasse un seuil de qualite. La duree s'adapte au contenu.")
        )
        val btnsMode = ArrayList<Pair<Button, OptMode>>()
        fun appliquerMode(opt: OptMode) {
            modeRythme = opt.rythme
            if (opt.m != null) mode = opt.m
            btnsMode.forEach { (bb, oo) -> majBouton(bb, oo.libelle == opt.libelle) }
            descMode?.text = opt.desc
            // affichage conditionnel : Duree (Rapide + Un/clip), Position (Rapide seul)
            blocDuree?.visibility = if (opt.rythme || opt.m == SelecteurSegments.Mode.UN_PAR_CLIP) View.VISIBLE else View.GONE
            blocPosition?.visibility = if (opt.rythme) View.VISIBLE else View.GONE
        }
        for (opt in options) {
            val b = Button(this).apply {
                text = opt.libelle; isAllCaps = false; textSize = 13f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)); lp.rightMargin = dp(6); layoutParams = lp
                setOnClickListener { appliquerMode(opt) }
            }
            btnsMode.add(b to opt); ligneMode.addView(b)
        }
        col.addView(scrollMode)
        descMode = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, dp(6), 0, dp(10))
        }
        col.addView(descMode)

        // === SECTION REGLAGES (conditionnels) ===
        // bloc duree
        val bDuree = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bDuree.addView(TextView(this).apply {
            text = "Duree gardee par clip"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(2))
        })
        val ligneDuree = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnsDuree = HashMap<Int, Button>()
        for (d in listOf(3, 5, 8)) {
            val b = Button(this).apply {
                text = "${d}s"; isAllCaps = false
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)); lp.rightMargin = dp(6); layoutParams = lp
                setOnClickListener { dureeParClip = d; btnsDuree.forEach { (k, bb) -> majBouton(bb, k == d) } }
            }
            btnsDuree[d] = b; ligneDuree.addView(b)
        }
        bDuree.addView(ligneDuree)
        btnsDuree.forEach { (k, bb) -> majBouton(bb, k == dureeParClip) }
        bDuree.setPadding(0, dp(4), 0, dp(6))
        col.addView(bDuree); blocDuree = bDuree

        // bloc position
        val bPos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bPos.addView(TextView(this).apply {
            text = "Ou prendre dans chaque clip"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(2))
        })
        val lignePos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnsPos = HashMap<MonteurVideo.Position, Button>()
        for (p in listOf(MonteurVideo.Position.DEBUT, MonteurVideo.Position.MILIEU)) {
            val b = Button(this).apply {
                text = if (p == MonteurVideo.Position.DEBUT) "Debut" else "Milieu"; isAllCaps = false
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)); lp.rightMargin = dp(6); layoutParams = lp
                setOnClickListener { position = p; btnsPos.forEach { (k, bb) -> majBouton(bb, k == p) } }
            }
            btnsPos[p] = b; lignePos.addView(b)
        }
        bPos.addView(lignePos)
        btnsPos.forEach { (k, bb) -> majBouton(bb, k == position) }
        bPos.setPadding(0, dp(4), 0, dp(10))
        col.addView(bPos); blocPosition = bPos

        // === BOUTON CREER ===
        col.addView(Button(this).apply {
            text = "Creer le montage"; isAllCaps = false; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(ACCENT); setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); lp.topMargin = dp(6); lp.bottomMargin = dp(6); layoutParams = lp
            setOnClickListener { creerMontage() }
        })

        // === STATUT ===
        statut = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(4), 0, dp(10))
        }
        col.addView(statut)

        // === SECTION VIDEOS ===
        col.addView(titreSection("3.  Choisissez les videos a monter"))
        grille = GridLayout(this).apply { columnCount = 3; setPadding(0, dp(8), 0, 0) }
        col.addView(grille)

        setContentView(racine)

        // mode initial : Best-of (IA)
        appliquerMode(options[1])

        demanderPermissionEtLister()
    }

    /** Petit titre de section. */
    private fun titreSection(t: String): TextView = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(8), 0, dp(6))
    }

    /** Carte d'explication (titre + texte) avec fond. */
    private fun carte(titre: String, texte: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARTE); setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(14); layoutParams = lp
        }
        box.addView(TextView(this).apply {
            text = titre; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = texte; textSize = 12f; setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        return box
    }'''

s = s[:debut] + nouveau + s[fin:]
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Page Montage reorganisee OK")