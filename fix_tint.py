f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# helper : remplacer setBackgroundColor par setBackgroundTintList partout dans les fonctions de selection
# 1) PLANS
old1 = '''            fun choisir(sel: Button, cible: Float) {
                cibleHPlan = cible
                tous.forEach {
                    it.setBackgroundColor(0xFF263238.toInt())   // inactif : gris tres sombre
                    it.setTextColor(0xFF90A4AE.toInt())          // texte grise
                }
                sel.setBackgroundColor(0xFF00C853.toInt())       // actif : vert VIF
                sel.setTextColor(0xFF000000.toInt())             // texte noir sur vert vif
            }'''
new1 = '''            fun teinte(b: Button, couleur: Int) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            }
            fun choisir(sel: Button, cible: Float) {
                cibleHPlan = cible
                tous.forEach {
                    teinte(it, 0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                teinte(sel, 0xFF00C853.toInt())
                sel.setTextColor(0xFF000000.toInt())
            }'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1

# 2) MOUVEMENTS
old2 = '''            fun choisirMouv(sel: Button, m: Int) {
                mouvementActuel = m
                tousM.forEach {
                    it.setBackgroundColor(0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                findViewById<Button>(R.id.btnMouvApproche).setBackgroundColor(0xFF263238.toInt())
                findViewById<Button>(R.id.btnMouvApproche).setTextColor(0xFF90A4AE.toInt())
                sel.setBackgroundColor(0xFF00C853.toInt())
                sel.setTextColor(0xFF000000.toInt())
            }'''
new2 = '''            fun teinteM(b: Button, couleur: Int) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            }
            fun choisirMouv(sel: Button, m: Int) {
                mouvementActuel = m
                tousM.forEach {
                    teinteM(it, 0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                val mApp0 = findViewById<Button>(R.id.btnMouvApproche)
                teinteM(mApp0, 0xFF263238.toInt())
                mApp0.setTextColor(0xFF90A4AE.toInt())
                teinteM(sel, 0xFF00C853.toInt())
                sel.setTextColor(0xFF000000.toInt())
            }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1

# 3) APPROCHE
old3 = '''            mApp.setOnClickListener { 
                mouvementActuel = 4
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach {
                    it.setBackgroundColor(0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                mApp.setBackgroundColor(0xFF00C853.toInt())
                mApp.setTextColor(0xFF000000.toInt())
            }'''
new3 = '''            mApp.setOnClickListener { 
                mouvementActuel = 4
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach {
                    it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                mApp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt())
                mApp.setTextColor(0xFF000000.toInt())
            }'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Fix backgroundTint :", ch, "/ 3")