# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()
old = '''    override fun reglerIso(iso: Int) {}
    override fun reglerEv(ev: Float) {}
}'''
new = '''    override fun reglerIso(iso: Int) {}
    override fun reglerEv(ev: Float) {}
    override fun reglerResolutionFps(resNom: String, fps: Int) {}
}'''
if s.count("override fun reglerResolutionFps") >= 2:
    print("DEJA present (2)")
elif old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Stub simule ajoute. Total :", s.count("override fun reglerResolutionFps"))
else:
    print("ANCRE NON TROUVEE")