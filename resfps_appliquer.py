# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "reglerResolutionFps" in s:
    print("DEJA present"); raise SystemExit
anc = "        try { yoloSuivi?.setClassesSuivies(reglages.classesPourSujet()) } catch (_: Exception) {}"
add = anc + "\n        try { pont.reglerResolutionFps(reglages.resolutionNom(), reglages.getFps()) } catch (_: Exception) {}"
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Application resolution/fps :", "reglerResolutionFps" in s)
else:
    print("ANCRE NON TROUVEE")