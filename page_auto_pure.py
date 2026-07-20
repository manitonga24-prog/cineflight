# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()

debut = s.find("    override fun onCreate(s: Bundle?) {")
fin_marqueur = "        demanderPermissionEtLister()\n    }"
fin = s.find(fin_marqueur)
if debut == -1 or fin == -1:
    print("BORNES NON TROUVEES"); raise SystemExit
fin += len(fin_marqueur)

nouveau = '''    private var descMode: TextView? = null
    private var champNom: EditText? = null

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
            text = "L'IA choisit les meilleurs moments. Vous n'avez qu'a choisir vos videos et un style."
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

        // === SECTION MODE IA ===
        col.addView(titreSection("2.  Choisissez un style de montage"))
        val ligneMode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scrollMode = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ligneMode) }
        data class OptMode(val libelle: String, val m: SelecteurSegments.Mode, val desc: String)
        val options = listOf(
            OptMode("Best-of", SelecteurSegments.Mode.BEST_OF,
                "Best-of : les meilleurs moments de tous vos clips, condenses en un clip rythme."),
            OptMode("Un par clip", SelecteurSegments.Mode.UN_PAR_CLIP,
                "Un par clip : le meilleur passage de chaque clip choisi. Chaque clip est represente."),
            OptMode("Adaptatif", SelecteurSegments.Mode.ADAPTATIF,
                "Adaptatif : garde tout ce qui est de bonne qualite. La duree s'adapte a vos images.")
        )
        val btnsMode = ArrayList<Pair<Button, OptMode>>()
        fun appliquerMode(opt: OptMode) {
            mode = opt.m
            btnsMode.forEach { (bb, oo) -> majBouton(bb, oo.libelle == opt.libelle) }
            descMode?.text = opt.desc
        }
        for (opt in options) {
            val b = Button(this).apply {
                text = opt.libelle; isAllCaps = false; textSize = 14f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.rightMargin = dp(6); layoutParams = lp
                setOnClickListener { appliquerMode(opt) }
            }
            btnsMode.add(b to opt); ligneMode.addView(b)
        }
        col.addView(scrollMode)
        descMode = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, dp(6), 0, dp(12))
        }
        col.addView(descMode)

        // === NOM DU MONTAGE ===
        col.addView(TextView(this).apply {
            text = "Nom du montage (optionnel)"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(2))
        })
        val edit = EditText(this).apply {
            hint = "Ex : Vol parc Lafontaine"
            setTextColor(Color.WHITE); setHintTextColor(0xFF607D8B.toInt())
            setBackgroundColor(CARTE); setPadding(dp(10), dp(10), dp(10), dp(10)); textSize = 14f
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8); layoutParams = lp
        }
        col.addView(edit); champNom = edit

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
        col.addView(titreSection("3.  Choisissez les videos (l'ordre des numeros = l'ordre du clip)"))
        grille = GridLayout(this).apply { columnCount = 3; setPadding(0, dp(8), 0, 0) }
        col.addView(grille)

        setContentView(racine)

        // mode initial : Best-of
        appliquerMode(options[0])

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
print("MontageActivity = page auto pure OK")