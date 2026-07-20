# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()
# le simule : reglerEv vide suivi de la fermeture de classe
old = '''    override fun reglerEv(ev: Float) {}
}'''
new = '''    override fun reglerEv(ev: Float) {}
    override fun reglerResolutionFps(resNom: String, fps: Int) {}
}'''
if s.count("reglerResolutionFps") >= 2:
    print("DEJA 2 occurrences")
elif old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Stub simule ajoute :", s.count("reglerResolutionFps"))
else:
    print("ANCRE NON TROUVEE")