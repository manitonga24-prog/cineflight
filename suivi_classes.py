# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\YoloSuivi.kt"
s = open(f, encoding="utf-8").read()
if "classesSuivies" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) ajouter la variable classesSuivies apres CLASSE_PERSONNE
a1 = "    private val CLASSE_PERSONNE = 0"
n1 = '''    private val CLASSE_PERSONNE = 0
    // Classes COCO suivies en mode AUTO (sans toucher). Defaut = personne.
    // 0=personne, 16=chien, 17=cheval, 2=voiture, 3=moto
    @Volatile private var classesSuivies: Set<Int> = setOf(0)
    /** Definit les classes a suivre en mode auto (le toucher reste universel). */
    fun setClassesSuivies(classes: Set<Int>) { classesSuivies = if (classes.isEmpty()) setOf(0) else classes }'''
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# 2) remplacer le filtre fixe par : si cible designee -> toutes classes ; sinon -> classesSuivies
a2 = '''        val personnes = ArrayList<FloatArray>()  // [cx, cy, fw, fh]
        for (i in 0 until n) {
            val o = 1 + i * 6
            if (res[o].toInt() != CLASSE_PERSONNE) continue'''
n2 = '''        val personnes = ArrayList<FloatArray>()  // [cx, cy, fw, fh]
        for (i in 0 until n) {
            val o = 1 + i * 6
            val cl = res[o].toInt()
            // toucher = universel (toute classe) ; auto = classes choisies
            if (!cibleDesignee && cl !in classesSuivies) continue'''
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 1 (classes suivies) :", ch, "/ 2")