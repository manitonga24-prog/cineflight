# -*- coding: utf-8 -*-
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(fm, encoding="utf-8").read()
if '".MontageActivity"' in s:
    print("DEJA present (vraie MontageActivity)")
else:
    anc = '        <activity android:name=".TestMontageActivity" android:exported="false" android:screenOrientation="landscape" />'
    add = anc + '\n        <activity android:name=".MontageActivity" android:exported="false" android:screenOrientation="landscape" />'
    if anc in s:
        s = s.replace(anc, add, 1)
        open(fm, "w", encoding="utf-8", newline="\n").write(s)
        print("MontageActivity declaree OK")
    else:
        print("ANCRE NON TROUVEE")