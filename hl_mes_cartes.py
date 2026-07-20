# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "fun ouvrirMesCartes" in s:
    print("DEJA present"); raise SystemExit

# 1) bouton "Mes cartes" dans la barre, apres btnHorsLigne
anc = "        barre.addView(btnHorsLigne, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))"
neuf = anc + '''
        val btnMesCartes = Button(this).apply {
            text = "Mes cartes"; isAllCaps = false
            setOnClickListener { ouvrirMesCartes() }
        }
        barre.addView(btnMesCartes, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))'''
s = s.replace(anc, neuf, 1)

# 2) methode ouvrirMesCartes avant sauvegarderCarteNommee
ancM = "    private fun sauvegarderCarteNommee(nom: String, centre: GeoPoint, rayonM: Double) {"
methode = '''    private fun ouvrirMesCartes() {
        val prefs = getSharedPreferences("cartes_hors_ligne", android.content.Context.MODE_PRIVATE)
        val liste = (prefs.getString("liste", "") ?: "").split("\\n").filter { it.isNotBlank() }
        if (liste.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucune carte sauvegardee. Telechargez une zone d'abord.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // noms a afficher
        val noms = liste.map { it.split("|").firstOrNull() ?: "?" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Mes cartes hors ligne")
            .setItems(noms) { _, which ->
                val parts = liste[which].split("|")
                if (parts.size >= 3) {
                    val lat = parts[1].toDoubleOrNull(); val lon = parts[2].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        suiviAuto = false
                        carte.controller.setCenter(GeoPoint(lat, lon))
                        carte.controller.setZoom(16.0)
                        infoTexte.text = "Carte : ${noms[which]}"
                    }
                }
            }
            .setNeutralButton("Supprimer une carte") { _, _ -> supprimerMesCartes(liste, noms) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun supprimerMesCartes(liste: List<String>, noms: Array<String>) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Supprimer quelle carte ?")
            .setItems(noms) { _, which ->
                val restant = liste.filterIndexed { i, _ -> i != which }
                getSharedPreferences("cartes_hors_ligne", android.content.Context.MODE_PRIVATE)
                    .edit().putString("liste", restant.joinToString("\\n")).apply()
                android.widget.Toast.makeText(this, "Carte \\"${noms[which]}\\" retiree de la liste.", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun sauvegarderCarteNommee(nom: String, centre: GeoPoint, rayonM: Double) {'''
s = s.replace(ancM, methode, 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Mes cartes (liste + recentrage + suppression) OK")