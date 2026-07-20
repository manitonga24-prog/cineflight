# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) ajouter un champ pour memoriser le conteneur + l'uri public
anc1 = "    private var dureeParClip = 4"
if "private var conteneur" not in s:
    s = s.replace(anc1, "    private var conteneur: LinearLayout? = null\n    private var uriMontage: android.net.Uri? = null\n" + anc1, 1); ch += 1

# 2) memoriser col comme conteneur (juste apres sa creation)
anc2 = "        racine.addView(col)"
if "conteneur = col" not in s:
    s = s.replace(anc2, anc2 + "\n        conteneur = col", 1); ch += 1

# 3) remplacer le bloc onFini : publier galerie + boutons Lire/Partager
old3 = '''                    val sec = (System.currentTimeMillis() - t0) / 1000
                    statut.text = "Montage pret en ${sec}s !"
                    partager(fichier)'''
new3 = '''                    val sec = (System.currentTimeMillis() - t0) / 1000
                    statut.text = "Montage pret en ${sec}s ! Ajout a la galerie..."
                    // publier dans la galerie publique (apparait dans l'app Galerie)
                    Thread {
                        val uriPub = monteur.publierDansGalerie(fichier)
                        runOnUiThread {
                            uriMontage = uriPub ?: androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
                            statut.text = if (uriPub != null) "Montage pret (${sec}s) et ajoute a votre galerie !" else "Montage pret en ${sec}s !"
                            afficherBoutonsResultat(fichier)
                        }
                    }.start()'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch += 1
else:
    print("ANCRE onFini NON TROUVEE")

# 4) ajouter les fonctions afficherBoutonsResultat + lire, avant partager()
anc4 = "    private fun partager(fichier: File) {"
fct4 = '''    private fun afficherBoutonsResultat(fichier: File) {
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        ligne.addView(Button(this).apply {
            text = "Lire le montage"; isAllCaps = false
            setBackgroundColor(0xFF2E7D32.toInt()); setTextColor(android.graphics.Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f); lp.rightMargin = dp(6); layoutParams = lp
            setOnClickListener { lire() }
        })
        ligne.addView(Button(this).apply {
            text = "Partager"; isAllCaps = false
            setBackgroundColor(ACCENT); setTextColor(android.graphics.Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f); layoutParams = lp
            setOnClickListener { partager(fichier) }
        })
        conteneur?.addView(ligne, 0)
    }

    private fun lire() {
        val uri = uriMontage ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) { statut.text = "Lecture impossible: ${e.message}" }
    }

    private fun partager(fichier: File) {'''
if anc4 in s and "fun afficherBoutonsResultat" not in s:
    s = s.replace(anc4, fct4, 1); ch += 1

# 5) partager() doit utiliser uriMontage si dispo (sinon FileProvider)
old5 = '''            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
            val envoi = Intent(Intent.ACTION_SEND).apply {'''
new5 = '''            val uri = uriMontage ?: FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
            val envoi = Intent(Intent.ACTION_SEND).apply {'''
if old5 in s:
    s = s.replace(old5, new5, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("MontageActivity modifie :", ch, "/ 5")