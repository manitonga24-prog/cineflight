# === 1) LAYOUT : bouton micro (haut, a cote de REC) + zone texte commande ===
fl = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(fl, encoding="utf-8").read()
if "btnMicro" not in s:
    ancre = '''<Button android:id="@+id/btnRec"
            android:layout_width="44dp" android:layout_height="40dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\u23FA" android:textSize="16sp"
            android:textColor="#FFFFFF" android:backgroundTint="#37474F" />'''
    nouveau = ancre + '''
        <Button android:id="@+id/btnMicro"
            android:layout_width="44dp" android:layout_height="40dp"
            android:layout_marginStart="4dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="\\uD83C\\uDFA4" android:textSize="16sp"
            android:textColor="#FFFFFF" android:backgroundTint="#5C6BC0" />'''
    s = s.replace(ancre, nouveau, 1)
    # zone texte "commande reconnue" centree en haut sous le bandeau
    ancre2 = '<TextView android:id="@+id/txtAlerte"'
    zone = '''<TextView android:id="@+id/txtCommandeVoc"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal" android:layout_marginTop="52dp"
        android:padding="8dp" android:text="" android:textColor="#FFFFFF"
        android:textSize="18sp" android:textStyle="bold" android:fontFamily="monospace"
        android:visibility="gone" android:background="#CC1565C0" />

    ''' + ancre2
    s = s.replace(ancre2, zone, 1)
    open(fl, "w", encoding="utf-8", newline="\n").write(s)
    print("Layout: micro + zone commande ajoutes")
else:
    print("Layout: micro deja present")

# === 2) MANIFEST : permission RECORD_AUDIO ===
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
m = open(fm, encoding="utf-8").read()
if "RECORD_AUDIO" not in m:
    anc = '<uses-permission android:name="android.permission.INTERNET" />'
    m = m.replace(anc, anc + '\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />', 1)
    open(fm, "w", encoding="utf-8", newline="\n").write(m)
    print("Manifest: permission RECORD_AUDIO ajoutee")
else:
    print("Manifest: RECORD_AUDIO deja present")

# === 3) MAINACTIVITY : import + declaration + init + cablage ===
fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
ch = 0

# 3a import
a = "import ca.cineflight.stage.control.YoloSuivi"
if a in k and "CommandeVocale" not in k:
    k = k.replace(a, a + "\nimport ca.cineflight.stage.control.CommandeVocale", 1); ch+=1

# 3b declaration
a = "private var yoloSuivi: YoloSuivi? = null"
if a in k and "private var vocal" not in k:
    k = k.replace(a, a + "\n    private var vocal: CommandeVocale? = null\n    private lateinit var txtCommandeVoc: android.widget.TextView", 1); ch+=1

# 3c init + cablage bouton micro, apres txtSujet = findViewById(...)
a = "txtSujet = findViewById(R.id.txtSujet)"
if a in k and "btnMicro" not in k:
    add = a + '''
        txtCommandeVoc = findViewById(R.id.txtCommandeVoc)
        vocal = CommandeVocale(this,
            onCommande = { action -> runOnUiThread { executerCommandeVocale(action) } },
            onTexte = { txt -> runOnUiThread {
                txtCommandeVoc.text = txt
                txtCommandeVoc.visibility = android.view.View.VISIBLE
                txtCommandeVoc.removeCallbacks(null)
                txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 2500)
            } }
        )
        findViewById<Button>(R.id.btnMicro).setOnClickListener {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            } else {
                vocal?.ecouter()
            }
        }'''
    k = k.replace(a, add, 1); ch+=1

# 3d fonction executerCommandeVocale (avant la derniere accolade)
fonc = '''
    // Execute une commande vocale reconnue (mappe vers SUIVRE / mouvements / plan)
    private fun executerCommandeVocale(action: String) {
        when (action) {
            "suivi" -> if (!modeAuto) basculerMode(true)
            "pause" -> if (modeAuto) basculerMode(false)
            "orbite" -> findViewById<Button>(R.id.btnMouvOrbite).performClick()
            "travelling" -> findViewById<Button>(R.id.btnMouvTravel).performClick()
            "revelation" -> findViewById<Button>(R.id.btnMouvRevel).performClick()
            "approche" -> findViewById<Button>(R.id.btnMouvApproche).performClick()
            "statique" -> findViewById<Button>(R.id.btnMouvStatique).performClick()
            "plan_suivant" -> cyclerPlan()
        }
    }

    private var planIndex = 1  // 0=GP 1=AM 2=PD 3=ENS (americain par defaut)
    private fun cyclerPlan() {
        planIndex = (planIndex + 1) % 4
        val ids = listOf(R.id.btnPlanGros, R.id.btnPlanAmericain, R.id.btnPlanPied, R.id.btnPlanEnsemble)
        findViewById<Button>(ids[planIndex]).performClick()
    }
'''
idx = k.rstrip().rfind("}")
k = k[:idx] + fonc + "\n}\n"
ch+=1

# 3e arret du vocal dans onDestroy (apres yoloSuivi?.arreter())
a = "yoloSuivi?.arreter()"
if a in k and "vocal?.arreter()" not in k:
    k = k.replace(a, a + "\n        vocal?.arreter()", 1); ch+=1

open(fk, "w", encoding="utf-8", newline="\n").write(k)
print("MainActivity vocal :", ch, "/ 5")