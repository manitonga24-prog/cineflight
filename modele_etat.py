f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Telemetrie.kt"
s = open(f, encoding="utf-8").read()
if "val modele" in s:
    print("DEJA present"); raise SystemExit
anc = "    val rthEnCours: Boolean = false"
new = "    val rthEnCours: Boolean = false,\n    val modele: String = \"\""
if anc in s:
    s = s.replace(anc, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Champ modele ajoute a EtatCockpit")
else:
    print("ANCRE NON TROUVEE")