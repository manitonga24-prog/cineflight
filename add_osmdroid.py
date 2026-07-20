# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\build.gradle"
s = open(f, encoding="utf-8").read()
if "osmdroid" in s:
    print("DEJA present"); raise SystemExit
anc = "    implementation 'com.dji:dji-sdk-v5-aircraft:5.10.0'"
add = anc + "\n    implementation 'org.osmdroid:osmdroid-android:6.1.18'"
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("osmdroid ajoute :", "osmdroid" in s)
else:
    print("ANCRE NON TROUVEE")