# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Reglages.kt"
s = open(f, encoding="utf-8").read()
if "getRailSujetDist" in s:
    print("DEJA present"); raise SystemExit

anc = '''    fun resolutionNom(): String = when (getResolution()) { 1 -> "2.7K"; 2 -> "4K"; else -> "FHD" }'''
ajout = anc + '''

    // --- Cable-Cam : distance du rail "vers le sujet" (appui long sur Rail A), en metres ---
    fun getRailSujetDist(): Int = prefs.getInt("rail_sujet_dist", 8)
    fun setRailSujetDist(d: Int) = prefs.edit().putInt("rail_sujet_dist", d.coerceIn(3, 30)).apply()'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("champ getRailSujetDist OK")
else:
    print("ANCRE NON TROUVEE")