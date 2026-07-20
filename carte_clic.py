# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "R.id.btnCarte" in s:
    print("DEJA present"); raise SystemExit
anc = "        findViewById<Button>(R.id.btnEv).setOnClickListener { ouvrirMenuEv() }"
add = anc + '''
        findViewById<Button>(R.id.btnCarte).setOnClickListener {
            // transmettre l'ancre de decollage si connue (chien fidele)
            try { MainActivity.ancreLatCarte = ancreLat; MainActivity.ancreLonCarte = ancreLon } catch (_: Exception) {}
            startActivity(android.content.Intent(this, CarteActivity::class.java))
        }'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Clic carte cable :", "R.id.btnCarte" in s)
else:
    print("ANCRE NON TROUVEE")