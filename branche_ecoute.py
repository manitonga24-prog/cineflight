# 1) LAYOUT : transformer le bouton MIC en bouton ECOUTE (on/off) - on garde l'id btnMicro
fl = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(fl, encoding="utf-8").read()
old = 'android:text="MIC" android:textSize="12sp"\n            android:textColor="#FFFFFF" android:backgroundTint="#5C6BC0" />'
new = 'android:text="VOIX" android:textSize="11sp"\n            android:textColor="#FFFFFF" android:backgroundTint="#5C6BC0" />'
if old in s:
    s = s.replace(old, new, 1); print("Layout: MIC -> VOIX")
else:
    print("Layout: ancre MIC non trouvee (ok si deja VOIX)")
open(fl, "w", encoding="utf-8", newline="\n").write(s)

# 2) MAINACTIVITY : import + declaration + bouton bascule + mapping stop
fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
ch = 0

# 2a import
a = "import ca.cineflight.stage.control.CommandeVocale"
if a in k and "EcouteContinue" not in k:
    k = k.replace(a, a + "\nimport ca.cineflight.stage.control.EcouteContinue", 1); ch+=1

# 2b declaration
a = "private var vocal: CommandeVocale? = null"
if a in k and "private var ecoute" not in k:
    k = k.replace(a, a + "\n    private var ecoute: EcouteContinue? = null", 1); ch+=1

# 2c remplacer le clic du bouton (popup) par bascule ecoute continue
old_clic = '''        findViewById<Button>(R.id.btnMicro).setOnClickListener {
            lancerPopupVocale()
        }'''
new_clic = '''        ecoute = EcouteContinue(this,
            onCommande = { action -> runOnUiThread { executerCommandeVocale(action) } },
            onTexte = { txt -> runOnUiThread {
                txtCommandeVoc.text = txt
                txtCommandeVoc.visibility = android.view.View.VISIBLE
            } }
        )
        findViewById<Button>(R.id.btnMicro).setOnClickListener {
            val b = it as Button
            if (ecoute?.estActif() == true) {
                ecoute?.arreter()
                b.text = "VOIX"
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF5C6BC0.toInt())
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                } else {
                    ecoute?.demarrer()
                    b.text = "\\u25CF ON"
                    b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt())
                }
            }
        }'''
if old_clic in k:
    k = k.replace(old_clic, new_clic, 1); ch+=1

# 2d mapping "stop" dans executerCommandeVocale (urgence)
a = '"suivi" -> if (!modeAuto) basculerMode(true)'
if a in k and '"stop" ->' not in k:
    k = k.replace(a, '"stop" -> { pilote.arretUrgence(); modeAuto = false; majBoutonMode() }\n            ' + a, 1); ch+=1

# 2e arret de l'ecoute dans onDestroy
a = "vocal?.arreter()"
if a in k and "ecoute?.arreter()" not in k:
    k = k.replace(a, a + "\n        ecoute?.arreter()", 1); ch+=1

open(fk, "w", encoding="utf-8", newline="\n").write(k)
print("MainActivity ecoute continue :", ch, "/ 5")