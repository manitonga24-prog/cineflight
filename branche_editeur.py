# 1) Declarer EditeurMacrosActivity dans le Manifest
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
m = open(fm, encoding="utf-8").read()
if "EditeurMacrosActivity" not in m:
    m = m.replace('</application>',
        '    <activity android:name=".EditeurMacrosActivity" android:exported="false" />\n    </application>', 1)
    open(fm, "w", encoding="utf-8", newline="\n").write(m)
    print("EditeurMacrosActivity declaree")
else:
    print("deja declaree")

# 2) Ajouter un bouton "MACROS" dans l'ecran TAGS (TagsActivity)
ft = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
t = open(ft, encoding="utf-8").read()
if "EditeurMacros" not in t:
    # ajouter le bouton juste apres le titre, avant la boucle des tags
    anc = '''        col.addView(TextView(this).apply {
            text = "TAGS ArUco - CineFlight Solo"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        })'''
    add = anc + '''

        col.addView(Button(this).apply {
            text = "CREER / MODIFIER MES MACROS (tags 11-30)"
            setOnClickListener {
                startActivity(android.content.Intent(this@TagsActivity, EditeurMacrosActivity::class.java))
            }
        })'''
    t = t.replace(anc, add, 1)
    open(ft, "w", encoding="utf-8", newline="\n").write(t)
    print("Bouton MACROS ajoute dans TagsActivity :", "EditeurMacros" in t)
else:
    print("bouton macros deja present")