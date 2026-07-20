f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
old = '''        if (modeAuto) {
            btnMode.text = "SUIVI"   // suivi actif
            btnMode.setBackgroundColor(0xFF1565C0.toInt())
        } else {
            btnMode.text = "SUIVRE"   // appuyer pour activer le suivi
            btnMode.setBackgroundColor(0xFF2E7D32.toInt())
        }'''
new = '''        if (modeAuto) {
            btnMode.text = "\u2715 STOP SUIVI"   // suivi actif -> ce bouton l'arrete
            btnMode.setBackgroundColor(0xFFEF6C00.toInt())  // orange = attention, suivi en cours
        } else {
            btnMode.text = "\u25B6 SUIVRE"   // appuyer pour activer le suivi
            btnMode.setBackgroundColor(0xFF455A64.toInt())  // gris ardoise discret
        }'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Libelles + couleurs OK")
else:
    print("ANCRE NON TROUVEE")