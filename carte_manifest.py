# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(f, encoding="utf-8").read()
if "CarteActivity" in s:
    print("DEJA present"); raise SystemExit
anc = '<activity android:name=".DisclaimerActivity" android:exported="false" android:screenOrientation="landscape" />'
add = anc + '\n        <activity android:name=".CarteActivity" android:exported="false" android:screenOrientation="landscape" />'
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("CarteActivity declaree :", "CarteActivity" in s)
else:
    print("ANCRE NON TROUVEE - colle-moi les lignes activity du manifest")