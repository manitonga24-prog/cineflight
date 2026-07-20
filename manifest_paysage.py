# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(f, encoding="utf-8").read()
ch = 0

# les activites declarees en une ligne sans orientation
remap = [
    ('<activity android:name=".TagsActivity" android:exported="false" />',
     '<activity android:name=".TagsActivity" android:exported="false" android:screenOrientation="landscape" />'),
    ('<activity android:name=".EditeurMacrosActivity" android:exported="false" />',
     '<activity android:name=".EditeurMacrosActivity" android:exported="false" android:screenOrientation="landscape" />'),
    ('<activity android:name=".GuideActivity" android:exported="false" />',
     '<activity android:name=".GuideActivity" android:exported="false" android:screenOrientation="landscape" />'),
    ('<activity android:name=".ReglagesActivity" android:exported="false" />',
     '<activity android:name=".ReglagesActivity" android:exported="false" android:screenOrientation="landscape" />'),
    ('<activity android:name=".DisclaimerActivity" android:exported="false" />',
     '<activity android:name=".DisclaimerActivity" android:exported="false" android:screenOrientation="landscape" />'),
]
for old, new in remap:
    if old in s:
        s = s.replace(old, new, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Activites passees en paysage :", ch, "/ 5")
print("PlacementActivity : a verifier (declaration multi-lignes)")