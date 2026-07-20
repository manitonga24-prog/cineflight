# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Reglages.kt"
s = open(f, encoding="utf-8").read()
if "getResolution" in s:
    print("DEJA present"); raise SystemExit
anc = "    fun classesPourSujet(): Set<Int> = when (getSujetASuivre()) {"
add = '''    // --- Camera : resolution + fps (applique sur drone reel, best effort) ---
    // Resolution : 0=FHD(1080p), 1=2.7K, 2=4K | fps : 24, 30, 60
    fun getResolution(): Int = prefs.getInt("cam_res", 0)
    fun setResolution(i: Int) = prefs.edit().putInt("cam_res", i.coerceIn(0, 2)).apply()
    fun getFps(): Int = prefs.getInt("cam_fps", 30)
    fun setFps(f: Int) = prefs.edit().putInt("cam_fps", f).apply()
    fun resolutionNom(): String = when (getResolution()) { 1 -> "2.7K"; 2 -> "4K"; else -> "FHD" }

    fun classesPourSujet(): Set<Int> = when (getSujetASuivre()) {'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Reglages resolution/fps :", "getResolution" in s)
else:
    print("ANCRE NON TROUVEE")