# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "chercherAdresse" in s:
    print("DEJA present"); raise SystemExit

# 1) imports reseau
if "import java.net.HttpURLConnection" not in s:
    s = s.replace(
        "import android.widget.EditText",
        "import android.widget.EditText\nimport java.net.HttpURLConnection\nimport java.net.URL\nimport java.net.URLEncoder",
        1)

# 2) barre de recherche d'adresse juste apres racine.addView(infoTexte...)
anc = '''        racine.addView(infoTexte, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))'''
neuf = anc + '''
        // === RECHERCHE D'ADRESSE (geocodage) ===
        val barreRech = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dpx(8), dpx(6), dpx(8), dpx(6))
        }
        val champAdresse = EditText(this).apply {
            hint = "Chercher un lieu / adresse"
            setTextColor(Color.WHITE); setHintTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
        }
        val btnChercher = Button(this).apply {
            text = "Chercher"; isAllCaps = false
            setOnClickListener { chercherAdresse(champAdresse.text.toString().trim()) }
        }
        barreRech.addView(champAdresse, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        barreRech.addView(btnChercher, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rechParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        rechParams.topMargin = dpx(52)   // sous infoTexte
        racine.addView(barreRech, rechParams)'''
s = s.replace(anc, neuf, 1)

# 3) methode chercherAdresse avant ouvrirMesCartes
ancM = "    private fun ouvrirMesCartes() {"
methode = '''    private fun chercherAdresse(requete: String) {
        if (requete.isEmpty()) { android.widget.Toast.makeText(this, "Tapez un lieu a chercher.", android.widget.Toast.LENGTH_SHORT).show(); return }
        infoTexte.text = "Recherche de \\"$requete\\"..."
        Thread {
            try {
                val q = URLEncoder.encode(requete, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "CineFlightSolo/1.0 (drone planning)")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val rep = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                // parse minimal : "lat":"45.5","lon":"-73.5"
                val mLat = Regex("\\"lat\\"\\s*:\\s*\\"([-0-9.]+)\\"").find(rep)
                val mLon = Regex("\\"lon\\"\\s*:\\s*\\"([-0-9.]+)\\"").find(rep)
                val lat = mLat?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = mLon?.groupValues?.get(1)?.toDoubleOrNull()
                runOnUiThread {
                    if (lat != null && lon != null) {
                        suiviAuto = false
                        carte.controller.setCenter(GeoPoint(lat, lon))
                        carte.controller.setZoom(15.0)
                        infoTexte.text = "Lieu trouve : $requete"
                    } else {
                        infoTexte.text = "Lieu introuvable : $requete"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { infoTexte.text = "Recherche impossible (reseau ?)" }
            }
        }.start()
    }

    private fun ouvrirMesCartes() {'''
s = s.replace(ancM, methode, 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("geocodage adresse (Nominatim) OK")