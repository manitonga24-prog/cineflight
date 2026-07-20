# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\PhototequeActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# remplacer telechargerEtPartager (ProgressDialog -> statut texte + toast)
old = '''    private fun telechargerEtPartager(mf: MediaFile) {
        val dlg = ProgressDialog(this).apply {
            setMessage("Telechargement de ${mf.fileName}...")
            setCancelable(false); show()
        }
        media.telecharger(this, mf,
            onProgres = { pct -> runOnUiThread { dlg.setMessage("Telechargement... $pct%") } },
            onFini = { fichier ->
                runOnUiThread {
                    dlg.dismiss()
                    if (fichier == null) { toast("Echec du telechargement"); return@runOnUiThread }
                    partager(fichier)
                }
            })
    }'''
new = '''    private fun telechargerEtPartager(mf: MediaFile) {
        statut.text = "Telechargement de ${mf.fileName}..."
        media.telecharger(this, mf,
            onProgres = { pct -> runOnUiThread { statut.text = "Telechargement... $pct%" } },
            onFini = { fichier ->
                runOnUiThread {
                    if (fichier == null) { statut.text = "Echec du telechargement"; toast("Echec du telechargement"); return@runOnUiThread }
                    statut.text = "${photos.size} photo(s). Touchez pour telecharger et partager."
                    partager(fichier)
                }
            })
    }'''
if old in s:
    s = s.replace(old, new, 1); ch += 1
else:
    print("ANCRE telechargerEtPartager NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("PhototequeActivity corrige :", ch, "/ 1")