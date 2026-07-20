# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Reglages.kt"
s = open(f, encoding="utf-8").read()
if "sujetASuivre" in s:
    print("DEJA present"); raise SystemExit

# ajouter get/set du sujet apres la ligne reset(p)
anc = "    fun reset(p: Param) = prefs.edit().remove(p.cle).apply()"
add = '''    fun reset(p: Param) = prefs.edit().remove(p.cle).apply()

    // --- Sujet a suivre en mode AUTO (le toucher reste universel) ---
    // 0 = Personne | 1 = Personne + Animal | 2 = Personne + Vehicule | 3 = Tout
    fun getSujetASuivre(): Int = prefs.getInt("sujet_suivi", 0)
    fun setSujetASuivre(mode: Int) = prefs.edit().putInt("sujet_suivi", mode.coerceIn(0, 3)).apply()

    /** Convertit le mode en ensemble de classes COCO. */
    fun classesPourSujet(): Set<Int> = when (getSujetASuivre()) {
        1 -> setOf(0, 15, 16, 17)        // personne + chat/chien/cheval
        2 -> setOf(0, 1, 2, 3, 5, 7)     // personne + velo/voiture/moto/bus/camion
        3 -> setOf(0, 1, 2, 3, 5, 7, 15, 16, 17)  // tout
        else -> setOf(0)                 // personne seule
    }'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Brique 2 (sujet a suivre) OK :", "sujetASuivre" in s or "getSujetASuivre" in s)
else:
    print("ANCRE NON TROUVEE")