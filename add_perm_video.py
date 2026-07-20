# -*- coding: utf-8 -*-
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(fm, encoding="utf-8").read()
if "READ_MEDIA_VIDEO" in s:
    print("DEJA present")
else:
    anc = '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />'
    add = anc + '\n    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />'
    if anc in s:
        s = s.replace(anc, add, 1)
        open(fm, "w", encoding="utf-8", newline="\n").write(s)
        print("READ_MEDIA_VIDEO ajoute OK")
    else:
        print("ANCRE NON TROUVEE")