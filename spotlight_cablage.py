# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "btnMouvSpotlight" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) recuperer le bouton spotlight (apres mRev)
a1 = "            val mRev  = findViewById<Button>(R.id.btnMouvRevel)"
n1 = '''            val mRev  = findViewById<Button>(R.id.btnMouvRevel)
            val mSpot = findViewById<Button>(R.id.btnMouvSpotlight)'''
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# 2) inclure mSpot dans la liste tousM pour la gestion teinte
a2 = "            val tousM = listOf(mStat, mOrb, mTrav, mRev)"
n2 = "            val tousM = listOf(mStat, mOrb, mTrav, mRev, mSpot)"
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

# 3) cabler le clic spotlight (mouvement 5), apres le clic mRev
a3 = "            mRev.setOnClickListener  { choisirMouv(mRev, 3) }"
n3 = '''            mRev.setOnClickListener  { choisirMouv(mRev, 3) }
            mSpot.setOnClickListener { choisirMouv(mSpot, 5) }'''
if a3 in s:
    s = s.replace(a3, n3, 1); ch+=1

# 4) texte des valeurs pour spotlight (cas 5)
a4 = '            else -> "\\u25A3 statique"'
n4 = '''            5 -> "\\u25C9 spotlight  vous pilotez, la camera suit"
            else -> "\\u25A3 statique"'''
if a4 in s:
    s = s.replace(a4, n4, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 3 spotlight (cablage) :", ch, "/ 4")