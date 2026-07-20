# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\build.gradle"
s = open(f, encoding="utf-8").read()
if "media3-transformer" in s:
    print("DEJA present"); raise SystemExit
anc = "    implementation 'org.osmdroid:osmdroid-android:6.1.18'"
lignes = [
    anc,
    "    // Media3 Transformer - montage video (assemblage automatique)",
    "    implementation 'androidx.media3:media3-transformer:1.4.1'",
    "    implementation 'androidx.media3:media3-effect:1.4.1'",
    "    implementation 'androidx.media3:media3-common:1.4.1'",
    "    implementation 'androidx.media3:media3-exoplayer:1.4.1'",
]
add = "\n".join(lignes)
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Media3 ajoute OK")
else:
    print("ANCRE NON TROUVEE")