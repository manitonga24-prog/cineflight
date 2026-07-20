f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s = open(f, encoding="utf-8").read()
if "commentPresenter" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# Ajouter le guide juste apres le titre de l'ecran TAGS
old = '''        col.addView(TextView(this).apply {
            text = "TAGS ArUco - CineFlight Solo"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        })'''
new = '''        col.addView(TextView(this).apply {
            text = "TAGS ArUco - CineFlight Solo"
            textSize = 20f
            setPadding(0, 0, 0, 8)
        })

        // --- guide : comment presenter un tag au drone ---
        val commentPresenter = TextView(this).apply {
            text = "COMMENT PRESENTER UN TAG AU DRONE\\n\\n" +
                   "Deux facons, selon la situation :\\n" +
                   "  - En main : tenez la carte face a la camera du drone, bien a plat.\\n" +
                   "  - Posee/affichee : placez le tag la ou le drone le verra (mur, support, ecran).\\n\\n" +
                   "Regles pour une bonne lecture :\\n" +
                   "  - Tag bien VISIBLE et stable ~1 seconde (le drone confirme apres l'avoir vu plusieurs images d'affilee - anti-erreur).\\n" +
                   "  - Quand l'action est reconnue, l'ecran affiche le tag detecte -> vous pouvez ranger la carte.\\n" +
                   "  - Le drone doit etre assez proche pour LIRE le tag. Plus le tag est gros, plus il se lit de loin.\\n" +
                   "  - Si le drone est trop loin : rapprochez-vous, ou il reviendra de lui-meme (mode chien fidele).\\n" +
                   "  - Evitez le contre-jour et les reflets ; un tag MAT se lit mieux qu'un brillant.\\n\\n" +
                   "ASTUCE : imprimez les tags utilises de loin en GRAND format, et ceux montres de pres en format carte."
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF0F4F3.toInt())
        }
        col.addView(commentPresenter)
        col.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
        })'''
if old in s:
    s = s.replace(old, new, 1); ch+=1
else:
    print("ancre titre TAGS non trouvee")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Guide presentation tag :", ch, "/ 1")