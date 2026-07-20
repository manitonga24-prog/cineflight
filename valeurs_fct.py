# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "majValeursMouvement" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) appel dans choisirMouv (apres mouvementActuel = m)
a1 = "            fun choisirMouv(sel: Button, m: Int) {\n                mouvementActuel = m"
if a1 in s:
    s = s.replace(a1, a1 + "\n                majValeursMouvement()", 1); ch+=1

# 2) appel dans le clic approche (apres mouvementActuel = 4)
a2 = "            mApp.setOnClickListener {\n                mouvementActuel = 4"
if a2 in s:
    s = s.replace(a2, a2 + "\n                majValeursMouvement()", 1); ch+=1

# 3) la fonction elle-meme, avant rafraichirBoutonsMacros
anc = "    private fun rafraichirBoutonsMacros() {"
fct = '''    private fun majValeursMouvement() {
        val t = findViewById<android.widget.TextView>(R.id.txtValeursMouv) ?: return
        val r = reglages
        fun f(p: Reglages.Param) = "%.1f".format(r.get(p))
        t.text = when (mouvementActuel) {
            1 -> "\u25CE orbite  h" + f(Reglages.ORBITE_HAUTEUR) + "m  r" + f(Reglages.ORBITE_RAYON) + "m  " + f(Reglages.ORBITE_VITESSE) + "m/s"
            2 -> "\u2194 travel  d" + f(Reglages.TRAVEL_DISTANCE) + "m  h" + f(Reglages.TRAVEL_HAUTEUR) + "m  react" + f(Reglages.TRAVEL_REACTIV)
            3 -> "\u2922 revel  recul" + f(Reglages.REVEL_RECUL) + "  montee" + f(Reglages.REVEL_MONTEE)
            4 -> "\u2191F approche  " + f(Reglages.APP_VITESSE) + "m/s  arret" + f(Reglages.APP_DISTANCE) + "m"
            else -> "\u25A3 statique"
        }
    }

    private fun rafraichirBoutonsMacros() {'''
if anc in s:
    s = s.replace(anc, fct, 1); ch+=1

# 4) appel au demarrage : dans onResume (apres setClassesSuivies) ou onCreate
a4 = "        try { yoloSuivi?.setClassesSuivies(reglages.classesPourSujet()) } catch (_: Exception) {}"
if a4 in s:
    s = s.replace(a4, a4 + "\n        try { majValeursMouvement() } catch (_: Exception) {}", 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Valeurs mouvement :", ch, "/ 4")