f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "DisclaimerActivity.dejaAccepte" in s:
    print("DEJA present"); raise SystemExit

# inserer la redirection juste avant setContentView
anc = "        setContentView(R.layout.activity_main)"
add = '''        // Disclaimer obligatoire au premier lancement
        if (!DisclaimerActivity.dejaAccepte(this)) {
            startActivity(android.content.Intent(this, DisclaimerActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Redirection disclaimer inseree")
else:
    print("ANCRE NON TROUVEE")