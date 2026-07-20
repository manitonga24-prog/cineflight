# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\PhototequeActivity.kt"
s = open(f, encoding="utf-8").read()

# 1) retirer l'appel decouvrirFormat obsolete
s = s.replace('                media.decouvrirFormat()   // DECOUVERTE temporaire : log les cles de formatage\n', '', 1)

if "Formater la carte" in s:
    print("bouton DEJA present")
else:
    # 2) ajouter le bouton Formater apres le bouton Fermer
    anc = '''        col.addView(btnFermer)'''
    bouton = '''        col.addView(btnFermer)
        // === BOUTON FORMATER (double confirmation) ===
        val btnFormater = Button(this).apply {
            text = "Formater la carte SD"; isAllCaps = false
            setBackgroundColor(0xFF8E2A2A.toInt()); setTextColor(Color.WHITE)
            (layoutParams as? LinearLayout.LayoutParams)?.also { it.topMargin = dp(6) }
            setOnClickListener { demanderFormatage() }
        }
        col.addView(btnFormater)'''
    s = s.replace(anc, bouton, 1)

    # 3) ajouter la methode demanderFormatage avec double confirmation, avant onCreate
    ancM = '''    override fun onCreate(s: Bundle?) {'''
    methode = '''    private fun demanderFormatage() {
        // 1re confirmation
        android.app.AlertDialog.Builder(this)
            .setTitle("Formater la carte SD ?")
            .setMessage("Cela EFFACERA TOUTES les photos et videos de la carte. Action irreversible.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Continuer") { _, _ ->
                // 2e confirmation
                android.app.AlertDialog.Builder(this)
                    .setTitle("Etes-vous VRAIMENT sur ?")
                    .setMessage("Tout sera supprime definitivement. Cette action ne peut pas etre annulee.")
                    .setNegativeButton("Non", null)
                    .setPositiveButton("OUI, formater") { _, _ ->
                        statut.text = "Formatage en cours..."
                        media.formaterCarteSD { ok, msg ->
                            runOnUiThread {
                                statut.text = msg
                                if (ok) {
                                    // recharger la liste apres formatage
                                    media.listerPhotos { liste ->
                                        runOnUiThread {
                                            photos = liste
                                            grille.removeAllViews()
                                            statut.text = if (liste.isEmpty()) "Carte formatee. Aucune photo." else "${liste.size} photo(s)."
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .show()
            }
            .show()
    }

    override fun onCreate(s: Bundle?) {'''
    s = s.replace(ancM, methode, 1)

    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("bouton Formater + double confirmation ajoutes OK")