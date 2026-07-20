# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
old = '''                mouvementActuel = 4
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach {'''
new = '''                mouvementActuel = 4
                majValeursMouvement()
                listOf(mStat, mOrb, mTrav, mRev, mApp).forEach {'''
if "majValeursMouvement()\n                listOf(mStat, mOrb, mTrav, mRev, mApp)" in s:
    print("DEJA present")
elif old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Appel approche ajoute : OK")
else:
    print("ANCRE NON TROUVEE")