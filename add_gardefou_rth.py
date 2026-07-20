# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "rthEnCours" in s and "cableCam.arreter()" in s and "RTH detecte" in s:
    print("DEJA present"); raise SystemExit

anc = '''                        if (cableCam.enCours) {
                            val e = pont.lireEtat(pilote.enVol)
                            if (e.gpsValide) {'''

neuf = '''                        if (cableCam.enCours) {
                            val e = pont.lireEtat(pilote.enVol)
                            // GARDE-FOU RTH : si le retour auto s'enclenche, le rail LACHE le controle
                            // (sinon le rail et le RTH se battent pour piloter le drone).
                            if (e.rthEnCours) {
                                cableCam.arreter()
                                hyperlapse.arreter()
                                txtSujet.text = "\\u25CF RTH - rail interrompu"
                                txtSujet.setTextColor(0xFFE65100.toInt())
                                findViewById<Button>(R.id.btnRailGo)?.let { teinte(it, 0xFF263238.toInt()); it.text = "Go" }
                                return@runOnUiThread
                            }
                            if (e.gpsValide) {'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("garde-fou RTH OK")
else:
    print("ANCRE NON TROUVEE")