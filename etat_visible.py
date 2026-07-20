f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) PLANS : etat actif plus visible (vert vif + texte blanc gras, inactifs gris atténué)
old1 = '''            fun choisir(sel: Button, cible: Float) {
                cibleHPlan = cible
                tous.forEach { it.setBackgroundColor(0xFF37474F.toInt()) }
                sel.setBackgroundColor(0xFF2E7D32.toInt())
            }'''
new1 = '''            fun choisir(sel: Button, cible: Float) {
                cibleHPlan = cible
                tous.forEach {
                    it.setBackgroundColor(0xFF263238.toInt())   // inactif : gris tres sombre
                    it.setTextColor(0xFF90A4AE.toInt())          // texte grise
                }
                sel.setBackgroundColor(0xFF00C853.toInt())       // actif : vert VIF
                sel.setTextColor(0xFF000000.toInt())             // texte noir sur vert vif
            }'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1

# 2) MOUVEMENTS (les 4 de base) : meme traitement
old2 = '''            fun choisirMouv(sel: Button, m: Int) {
                mouvementActuel = m
                tousM.forEach { it.setBackgroundColor(0xFF37474F.toInt()) }
                sel.setBackgroundColor(0xFF2E7D32.toInt())
            }'''
new2 = '''            fun choisirMouv(sel: Button, m: Int) {
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
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1

# 3) APPROCHE (cas a part) : meme traitement
old3 = '''            mApp.setOnClickListener { 
                mouvementActuel = 4
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach { it.setBackgroundColor(0xFF37474F.toInt()) }
                mApp.setBackgroundColor(0xFF2E7D32.toInt())
            }'''
new3 = '''            mApp.setOnClickListener { 
                mouvementActuel = 4
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach {
                    it.setBackgroundColor(0xFF263238.toInt())
                    it.setTextColor(0xFF90A4AE.toInt())
                }
                mApp.setBackgroundColor(0xFF00C853.toInt())
                mApp.setTextColor(0xFF000000.toInt())
            }'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Etat visible :", ch, "/ 3")