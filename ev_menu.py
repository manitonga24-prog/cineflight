# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "btnEv" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) variable EV courant (a cote de mouvementActuel / modeAuto)
a1 = "    private var modeAuto = false"
if a1 in s:
    s = s.replace(a1, a1 + "\n    private var evCourant = 0f", 1); ch+=1

# 2) cabler le clic btnEv apres le bloc btnTags
a2 = '''        findViewById<Button>(R.id.btnTags).setOnClickListener {
            startActivity(android.content.Intent(this, TagsActivity::class.java))'''
# on insere apres la fermeture de ce listener : on cible la ligne btnTags et on ajoute le notre juste avant
anc_ins = "        findViewById<Button>(R.id.btnTags).setOnClickListener {"
bloc = '''        findViewById<Button>(R.id.btnEv).setOnClickListener { ouvrirMenuEv() }
        findViewById<Button>(R.id.btnTags).setOnClickListener {'''
if anc_ins in s:
    s = s.replace(anc_ins, bloc, 1); ch+=1

# 3) la fonction ouvrirMenuEv + application, avant rafraichirBoutonsMacros
anc3 = "    private fun rafraichirBoutonsMacros() {"
fct = '''    private fun ouvrirMenuEv() {
        val crans = floatArrayOf(2.0f, 1.0f, 0.3f, 0f, -0.3f, -1.0f, -2.0f)
        val labels = crans.map { evLabel(it) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Exposition (EV)")
            .setItems(labels) { _, i ->
                evCourant = crans[i]
                try { pont.reglerEv(evCourant) } catch (_: Exception) {}
                findViewById<Button>(R.id.btnEv).text = evLabel(evCourant).replace(" ", "")
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun evLabel(v: Float): String = when {
        kotlin.math.abs(v) < 0.05f -> "EV 0"
        v > 0 -> "EV +%.1f".format(v)
        else -> "EV %.1f".format(v)
    }

    private fun rafraichirBoutonsMacros() {'''
if anc3 in s:
    s = s.replace(anc3, fct, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 2 EV (menu) :", ch, "/ 3")