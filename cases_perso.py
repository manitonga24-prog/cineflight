# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "zonePerso" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) ajouter "Personnalise" a la liste de choix
old1 = 'val choix = listOf("Personne", "Personne + Animal", "Personne + Vehicule", "Tout")'
new1 = 'val choix = listOf("Personne", "Personne + Animal", "Personne + Vehicule", "Tout", "Personnalise")'
if old1 in s:
    s = s.replace(old1, new1, 1); ch += 1
else:
    print("ANCRE 1 NON TROUVEE")

# 2) zone de cases a cocher : declaree avant maj(), remplie apres les boutons.
#    maj() gere la visibilite. On insere la zone + on enrichit maj().
old2 = '''        val boutons = ArrayList<Button>()
        fun maj() {
            val sel = reglages.getSujetASuivre()
            for ((i, b) in boutons.withIndex()) {
                if (i == sel) { b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt()) }
                else { b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE) }
            }
        }'''
new2 = '''        val boutons = ArrayList<Button>()
        // Zone des cases a cocher (mode Personnalise = index 4)
        val zonePerso = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(2))
            visibility = android.view.View.GONE
        }
        val casesPerso = ArrayList<android.widget.CheckBox>()
        for ((id, nom) in Reglages.CLASSES_PERSO) {
            val cb = android.widget.CheckBox(this).apply {
                text = nom; textSize = 14f; setTextColor(TEXTE)
                isChecked = reglages.getClassesPerso().contains(id)
                setOnClickListener { reglages.toggleClassePerso(id) }
            }
            casesPerso.add(cb); zonePerso.addView(cb)
        }
        fun maj() {
            val sel = reglages.getSujetASuivre()
            for ((i, b) in boutons.withIndex()) {
                if (i == sel) { b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt()) }
                else { b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE) }
            }
            // cases a cocher visibles uniquement en mode Personnalise
            zonePerso.visibility = if (sel == 4) android.view.View.VISIBLE else android.view.View.GONE
            // refleter l'etat reel des classes cochees
            for ((idx, pair) in Reglages.CLASSES_PERSO.withIndex()) {
                casesPerso[idx].isChecked = reglages.getClassesPerso().contains(pair.first)
            }
        }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch += 1
else:
    print("ANCRE 2 NON TROUVEE")

# 3) ajouter zonePerso dans box, juste avant maj() final / card.addView
old3 = '''        maj()
        card.addView(box)
        return card
    }
    private fun ligneParam'''
new3 = '''        box.addView(zonePerso)
        maj()
        card.addView(box)
        return card
    }
    private fun ligneParam'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch += 1
else:
    print("ANCRE 3 NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Cases a cocher mode perso :", ch, "/ 3")