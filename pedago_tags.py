f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s = open(f, encoding="utf-8").read()
if "explication" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) Remplacer la legende simple par une legende avec explication pedagogique
old = '''    private val legende = listOf(
        0 to "PRESENTATION (stable)",
        1 to "MARCHE (travelling)",
        2 to "DANSE (orbite)",
        3 to "IMMOBILIER (revelation)",
        4 to "SPORT (suivi ON)",
        5 to "PLAN LARGE (ensemble)",
        6 to "AMERICAIN",
        7 to "RAPPROCHE (gros plan)",
        8 to "ORBITE",
        9 to "PAUSE",
        10 to "STOP"
    )'''
new = '''    // chaque tag : nom court + explication pedagogique (a quoi il sert)
    private val legende = listOf(
        Triple(0, "PRESENTATION", "VERROUILLE LA CIBLE + plan stable. Montrez-le en premier en vous placant : le drone vous memorise et commence a vous suivre."),
        Triple(1, "MARCHE", "Le drone vous accompagne en travelling pendant que vous marchez. Ideal pour une sequence en mouvement."),
        Triple(2, "DANSE", "Mouvements d'orbite dynamiques autour de vous. Pour des plans energiques, chorégraphies, performances."),
        Triple(3, "IMMOBILIER", "Revelations lentes : le drone recule et monte doucement. Pour devoiler un lieu, un decor, une piece."),
        Triple(4, "SPORT", "Active le suivi energique. Le drone vous suit de pres et reactif. Pour l'action rapide."),
        Triple(5, "PLAN LARGE", "Cadrage large (ensemble) : vous etes petit dans l'image, on voit le decor autour. Pour situer la scene."),
        Triple(6, "AMERICAIN", "Cadrage americain : vous etes cadre des cuisses a la tete. Le plan polyvalent par defaut."),
        Triple(7, "RAPPROCHE", "Gros plan : le drone se rapproche, vous remplissez l'image. Pour l'emotion, le detail, l'intensite."),
        Triple(8, "ORBITE", "Le drone tourne autour de vous. Plan circulaire classique et spectaculaire."),
        Triple(9, "PAUSE", "Met le suivi en pause : le drone arrete de vous suivre et reste stable (hover)."),
        Triple(10, "STOP", "ARRET D'URGENCE. Interrompt tout (suivi, mouvement, macro en cours). Le tag de securite a connaitre.")
    )'''
if old in s:
    s = s.replace(old, new, 1); ch+=1
else:
    print("ancre legende non trouvee")

# 2) Adapter la boucle d'affichage pour montrer l'explication
old2 = '''        for ((id, nom) in legende) {
            val bmp = genererBitmap(id, 300)
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(220, 220)
            }
            val infos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 0, 0, 0)
            }
            infos.addView(TextView(this@TagsActivity).apply { text = "#$id"; textSize = 18f })
            infos.addView(TextView(this@TagsActivity).apply { text = nom; textSize = 15f })
            infos.addView(Button(this@TagsActivity).apply {
                text = "Imprimer"
                setOnClickListener { imprimer(id, nom, bmp) }
            })
            ligne.addView(img)
            ligne.addView(infos)
            col.addView(ligne)
        }'''
new2 = '''        for ((id, nom, explication) in legende) {
            val bmp = genererBitmap(id, 300)
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(200, 200)
            }
            val infos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infos.addView(TextView(this@TagsActivity).apply {
                text = "#$id  $nom"; textSize = 17f
                setTextColor(0xFF00897B.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            infos.addView(TextView(this@TagsActivity).apply {
                text = explication; textSize = 13f
                setTextColor(0xFF555555.toInt())
                setPadding(0, 4, 0, 6)
            })
            infos.addView(Button(this@TagsActivity).apply {
                text = "Imprimer ce tag"
                setOnClickListener { imprimer(id, nom, bmp) }
            })
            ligne.addView(img)
            ligne.addView(infos)
            col.addView(ligne)
            // separateur
            col.addView(android.view.View(this@TagsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(0xFFE0E0E0.toInt())
            })
        }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1
else:
    print("ancre boucle non trouvee")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Pedagogie tags :", ch, "/ 2")