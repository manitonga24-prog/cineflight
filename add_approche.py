# 1) Ajouter le bouton APPROCHE dans le layout (barre mouvements)
fl = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(fl, encoding="utf-8").read()
if "btnMouvApproche" not in s:
    ancre = '<Button android:id="@+id/btnMouvRevel"'
    nouveau = '''<Button android:id="@+id/btnMouvApproche" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u2191F" android:textSize="13sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            ''' + ancre
    s = s.replace(ancre, nouveau, 1)
    open(fl, "w", encoding="utf-8", newline="\n").write(s)
    print("Layout: bouton APPROCHE ajoute")
else:
    print("Layout: APPROCHE deja present")

# 2) Cabler le bouton + ajouter la logique mouvement (cas 4 = approche)
fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
ch = 0

# 2a cablage du bouton (apres mRev.setOnClickListener)
a1 = "mRev.setOnClickListener  { choisirMouv(mRev, 3) }"
if a1 in k and "btnMouvApproche" not in k:
    add1 = a1 + '''
            val mApp = findViewById<Button>(R.id.btnMouvApproche)
            mApp.setOnClickListener { 
                mouvementActuel = 4
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach { it.setBackgroundColor(0xFF37474F.toInt()) }
                mApp.setBackgroundColor(0xFF2E7D32.toInt())
            }'''
    k = k.replace(a1, add1, 1); ch+=1

# 2b logique : cas 4 dans le when (approche = avancer vers le sujet)
a2 = "3 -> { vxMouv = (vx - 0.4f).coerceIn(-0.8f, 0.8f); vzMouv = 0.3f }  // REVELATION : recule + monte"
if a2 in k:
    add2 = a2 + '''
            4 -> { vxMouv = 0.6f }                    // APPROCHE : avance vers le sujet'''
    k = k.replace(a2, add2, 1); ch+=1

open(fk, "w", encoding="utf-8", newline="\n").write(k)
print("Code APPROCHE :", ch, "/ 2")