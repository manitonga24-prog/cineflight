# -*- coding: utf-8 -*-
ch = 0

# 1) Manifest : declarer MontageActivity
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(fm, encoding="utf-8").read()
if "MontageActivity" not in s:
    anc = '        <activity android:name=".TestMontageActivity" android:exported="false" android:screenOrientation="landscape" />'
    add = anc + '\n        <activity android:name=".MontageActivity" android:exported="false" android:screenOrientation="landscape" />'
    if anc in s:
        s = s.replace(anc, add, 1)
        open(fm, "w", encoding="utf-8", newline="\n").write(s)
        ch += 1
        print("Manifest : MontageActivity OK")
    else:
        print("ANCRE Manifest NON TROUVEE")
else:
    print("Manifest deja fait")

# 2) TagsActivity : bouton Montage -> MontageActivity (au lieu de TestMontageActivity)
ft = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TagsActivity.kt"
s2 = open(ft, encoding="utf-8").read()
old = "startActivity(android.content.Intent(this@TagsActivity, TestMontageActivity::class.java))"
new = "startActivity(android.content.Intent(this@TagsActivity, MontageActivity::class.java))"
if old in s2:
    s2 = s2.replace(old, new, 1)
    open(ft, "w", encoding="utf-8", newline="\n").write(s2)
    ch += 1
    print("TagsActivity : bouton -> MontageActivity OK")
elif new in s2:
    print("TagsActivity deja pointe vers MontageActivity")
else:
    print("ANCRE TagsActivity NON TROUVEE")

print("Total :", ch, "/ 2")