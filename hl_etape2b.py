# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "downloadAreaAsync" in s:
    print("DEJA present"); raise SystemExit

# 1) import CacheManager
s = s.replace(
    "import org.osmdroid.util.BoundingBox",
    "import org.osmdroid.util.BoundingBox\nimport org.osmdroid.tileprovider.cachemanager.CacheManager",
    1)

# 2) remplacer le placeholder
vieux = '''    private fun lancerTelechargementHorsLigne() {
        // (etape 2 : telechargement reel via CacheManager)
        android.widget.Toast.makeText(this, "Telechargement a venir (etape 2)", android.widget.Toast.LENGTH_SHORT).show()
    }'''

neuf = '''    private fun lancerTelechargementHorsLigne() {
        val nom = champNomHL.text.toString().trim()
        if (nom.isEmpty()) {
            android.widget.Toast.makeText(this, "Donnez un nom a la zone d'abord.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val centre = carte.mapCenter as GeoPoint
        val bb = boundingBoxDuCercle(centre, rayonHorsLigneM)
        val zMin = 14
        val zMax = 18

        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Telechargement de la carte")
            setMessage("Preparation...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
        }
        progress.show()

        val cm = CacheManager(carte)
        cm.downloadAreaAsync(this, bb, zMin, zMax, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                progress.dismiss()
                sauvegarderCarteNommee(nom, centre, rayonHorsLigneM)
                android.widget.Toast.makeText(this@CarteActivity, "Carte telechargee et sauvegardee.", android.widget.Toast.LENGTH_LONG).show()
                fermerPanneauHorsLigne()
            }
            override fun onTaskFailed(errors: Int) {
                progress.dismiss()
                android.widget.Toast.makeText(this@CarteActivity, "Termine avec $errors erreurs (zone partielle).", android.widget.Toast.LENGTH_LONG).show()
                sauvegarderCarteNommee(nom, centre, rayonHorsLigneM)
                fermerPanneauHorsLigne()
            }
            override fun updateProgress(progressVal: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                progress.progress = progressVal
                progress.setMessage("Niveau $currentZoomLevel - $progressVal%")
            }
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(total: Int) {
                progress.setMessage("$total tuiles a telecharger...")
            }
        })
    }

    private fun sauvegarderCarteNommee(nom: String, centre: GeoPoint, rayonM: Double) {
        try {
            val prefs = getSharedPreferences("cartes_hors_ligne", android.content.Context.MODE_PRIVATE)
            val existant = prefs.getString("liste", "") ?: ""
            val ligne = "$nom|${centre.latitude}|${centre.longitude}|${rayonM.toInt()}"
            val lignes = existant.split("\\n").filter { it.isNotBlank() && !it.startsWith("$nom|") }
            val nouveau = (lignes + ligne).joinToString("\\n")
            prefs.edit().putString("liste", nouveau).apply()
        } catch (_: Exception) {}
    }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("telechargement reel + sauvegarde OK")
else:
    print("ANCRE NON TROUVEE")