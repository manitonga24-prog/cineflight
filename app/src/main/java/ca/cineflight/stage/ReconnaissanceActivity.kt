package ca.cineflight.stage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ReconnaissanceActivity — déclenche la triangulation 3D du sujet depuis le terrain.
 *
 * FLUX (le pilote, sur le terrain, carte SD du Mini 3 dans un adaptateur USB-C) :
 *   1. "Choisir les photos" -> SAF, le pilote sélectionne les ~48 photos
 *      du dossier DCIM/DJI Album de la carte SD.
 *   2. L'app affiche "N photos sélectionnées".
 *   3. "Lancer la triangulation" -> upload multipart vers le serveur DO
 *      (POST /api/reconnaissance), avec le POI approximatif (lat/lon/alt).
 *   4. Le serveur fait perception -> sélection -> triangulation -> T.
 *   5. L'app affiche T (position 3D réelle du sujet) + avertissements, et propose
 *      de générer la mission de tournage autour de T (écran TournageActivity).
 *
 * Réseau : HttpURLConnection vers https://cineflight.ca.
 */
class ReconnaissanceActivity : AppCompatActivity() {

    private val BASE_URL = "https://cineflight.ca"
    private val CODE_CHOISIR_PHOTOS = 5001
    private val CODE_DOSSIER = 5002

    private val photos = ArrayList<Uri>()

    // POI approximatif (Niveau 1) — passé en extra par l'écran précédent, ou saisi.
    private var poiLat = 0.0
    private var poiLon = 0.0
    private var poiAlt = 50.0

    private lateinit var statut: TextView
    private lateinit var btnChoisir: Button
    private lateinit var btnLancer: Button
    private lateinit var btnEffacerSD: Button
    private lateinit var progres: ProgressBar
    private lateinit var resultat: TextView
    private lateinit var champLat: EditText
    private lateinit var champLon: EditText
    private lateinit var champAlt: EditText
    private lateinit var carteResultat: LinearLayout

    private val BG = android.graphics.Color.parseColor("#0A1220")
    private val CARTE = android.graphics.Color.parseColor("#141A24")
    private val BORDURE = android.graphics.Color.parseColor("#2A3340")
    private val BLEU = android.graphics.Color.parseColor("#4FC3F7")
    private val VERT = android.graphics.Color.parseColor("#3A7D5A")
    private val ROUGE = android.graphics.Color.parseColor("#8A3B34")
    private val VERT_CLAIR = android.graphics.Color.parseColor("#6FCF97")
    private val ROUGE_CLAIR = android.graphics.Color.parseColor("#E8837A")
    private val TEXTE = android.graphics.Color.parseColor("#E8EEF5")
    private val TEXTE_DOUX = android.graphics.Color.parseColor("#8A97A8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // POI depuis l'écran précédent (Niveau 1 / carte)
        poiLat = intent.getDoubleExtra("poi_lat", 0.0)
        poiLon = intent.getDoubleExtra("poi_lon", 0.0)
        poiAlt = intent.getDoubleExtra("poi_alt", 50.0)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        racine.addView(TextView(this).apply {
            text = getString(R.string.reco_titre)
            setTextColor(BLEU); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        racine.addView(TextView(this).apply {
            text = getString(R.string.reco_intro)
            setTextColor(TEXTE_DOUX); textSize = 14f
            setPadding(0, 0, 0, dp(4))
        })

        val c1 = carte()
        c1.addView(enTete(getString(R.string.reco_etape1)))
        c1.addView(TextView(this).apply {
            text = getString(R.string.reco_geo_hint)
            setTextColor(TEXTE_DOUX); textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        champLat = EditText(this).apply {
            hint = getString(R.string.reco_lat_hint)
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.-")
            if (poiLat != 0.0) setText(poiLat.toString())
        }
        styleChamp(champLat); c1.addView(champLat)
        champLon = EditText(this).apply {
            hint = getString(R.string.reco_lon_hint)
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.-")
            if (poiLon != 0.0) setText(poiLon.toString())
        }
        styleChamp(champLon); c1.addView(champLon)
        champAlt = EditText(this).apply {
            hint = getString(R.string.reco_alt_hint)
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.")
            setText(poiAlt.toString())
        }
        styleChamp(champAlt); c1.addView(champAlt)
        racine.addView(c1)

        val c2 = carte()
        c2.addView(enTete(getString(R.string.reco_etape2)))
        btnChoisir = boutonStyle(getString(R.string.reco_choisir_photos), BORDURE)
        btnChoisir.setOnClickListener { choisirPhotos() }
        c2.addView(btnChoisir)
        val btnDossier = boutonStyle(getString(R.string.reco_charger_dossier), BORDURE)
        btnDossier.setOnClickListener { choisirDossier() }
        c2.addView(btnDossier)
        statut = TextView(this).apply {
            text = getString(R.string.reco_aucune)
            setTextColor(TEXTE_DOUX); textSize = 13f
            setPadding(0, dp(12), 0, 0)
        }
        c2.addView(statut)
        racine.addView(c2)

        val c3 = carte()
        c3.addView(enTete(getString(R.string.reco_etape3)))
        btnLancer = boutonStyle(getString(R.string.reco_calculer), VERT, actif = false)
        btnLancer.setOnClickListener { lancerTriangulation() }
        c3.addView(btnLancer)
        progres = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(10), 0, 0)
            layoutParams = lp
        }
        c3.addView(progres)
        racine.addView(c3)

        carteResultat = carte()
        carteResultat.visibility = View.GONE
        resultat = TextView(this).apply {
            setTextColor(TEXTE); textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        carteResultat.addView(resultat)
        racine.addView(carteResultat)

        btnEffacerSD = boutonStyle(getString(R.string.reco_effacer), ROUGE, actif = false)
        btnEffacerSD.setOnClickListener { confirmerEffacement1() }
        racine.addView(btnEffacerSD)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(racine)
        })
    }

    /** Pré-remplit lat/lon depuis le GPS EXIF de la 1re photo chargée. */
    private fun lirePoiDepuisExif() {
        if (photos.isEmpty()) return
        try {
            contentResolver.openInputStream(photos[0])?.use { flux ->
                val exif = androidx.exifinterface.media.ExifInterface(flux)
                val ll = exif.latLong
                if (ll != null) {
                    poiLat = ll[0]; poiLon = ll[1]
                    champLat.setText(ll[0].toString())
                    champLon.setText(ll[1].toString())
                    val alt = exif.getAltitude(Double.NaN)
                    if (!alt.isNaN()) champAlt.setText(String.format("%.0f", alt))
                }
            }
        } catch (_: Exception) {}
    }

    // --- Effacement des photos de la carte SD (double protection) ---
    private fun confirmerEffacement1() {
        if (photos.isEmpty()) { statut.text = getString(R.string.reco_aucune_chargee); return }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.reco_verif_titre))
            .setMessage(getString(R.string.reco_verif_msg, photos.size))
            .setNegativeButton(getString(R.string.reco_non_annuler), null)
            .setPositiveButton(getString(R.string.reco_oui_envoyees)) { _, _ -> confirmerEffacement2() }
            .setCancelable(false)
            .show()
    }

    private fun confirmerEffacement2() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.reco_suppr_titre))
            .setMessage(getString(R.string.reco_suppr_msg, photos.size))
            .setNegativeButton(getString(R.string.reco_annuler), null)
            .setPositiveButton(getString(R.string.reco_supprimer)) { _, _ -> effacerPhotosSD() }
            .setCancelable(false)
            .show()
    }

    private fun effacerPhotosSD() {
        var ok = 0; var echec = 0
        for (uri in photos) {
            try {
                val supprime = android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                if (supprime) ok++ else echec++
            } catch (e: Exception) { echec++ }
        }
        photos.clear()
        btnEffacerSD.isEnabled = false
        btnLancer.isEnabled = false
        statut.text = getString(R.string.reco_supprimees, ok) + if (echec > 0) getString(R.string.reco_echecs, echec) else ""
    }

    private fun choisirDossier() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, CODE_DOSSIER)
        } catch (e: Exception) {
            statut.text = getString(R.string.reco_selecteur_ko, e.message)
        }
    }

    private fun choisirPhotos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.reco_chooser_titre)),
                CODE_CHOISIR_PHOTOS)
        } catch (e: Exception) {
            statut.text = getString(R.string.reco_selecteur_ko2, e.message)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODE_CHOISIR_PHOTOS && resultCode == Activity.RESULT_OK && data != null) {
            photos.clear()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {}
                        photos.add(uri)
                    }
                }
            } else {
                data.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                    photos.add(uri)
                }
            }
            statut.text = getString(R.string.reco_n_selectionnees, photos.size)
            btnLancer.isEnabled = photos.size >= 6
            if (photos.size < 6) {
                statut.text = getString(R.string.reco_n_min6, photos.size)
            }
        }
        if (requestCode == CODE_DOSSIER && resultCode == Activity.RESULT_OK && data != null) {
            val treeUri = data.data
            if (treeUri != null) {
                photos.clear()
                try {
                    val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                    val c = contentResolver.query(childrenUri, arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    c?.use {
                        while (it.moveToNext()) {
                            val id = it.getString(0)
                            val nom = it.getString(1).lowercase()
                            if (nom.endsWith(".jpg") || nom.endsWith(".jpeg") || nom.endsWith(".dng")) {
                                val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                                photos.add(fileUri)
                            }
                        }
                    }
                } catch (e: Exception) {
                    statut.text = getString(R.string.reco_lecture_ko, e.message)
                }
                lirePoiDepuisExif()
                statut.text = getString(R.string.reco_n_chargees, photos.size)
                btnLancer.isEnabled = photos.size >= 6
                if (photos.size < 6) statut.text = getString(R.string.reco_n_min6, photos.size)
            }
        }
    }

    // --- 2-3. Upload multipart vers le serveur + affichage du résultat ---
    private fun lancerTriangulation() {
        if (photos.isEmpty()) return

        poiLat = champLat.text.toString().trim().toDoubleOrNull() ?: Double.NaN
        poiLon = champLon.text.toString().trim().toDoubleOrNull() ?: Double.NaN
        poiAlt = champAlt.text.toString().trim().toDoubleOrNull() ?: 50.0
        if (poiLat.isNaN() || poiLon.isNaN()) {
            statut.text = getString(R.string.reco_latlon_invalide)
            return
        }

        btnLancer.isEnabled = false
        btnChoisir.isEnabled = false
        progres.visibility = View.VISIBLE
        progres.progress = 0
        resultat.text = ""
        statut.text = getString(R.string.reco_envoi, photos.size)

        Thread {
            try {
                val reponse = uploadEtTraiter()
                runOnUiThread { afficherResultat(reponse) }
            } catch (e: Exception) {
                runOnUiThread {
                    statut.text = getString(R.string.reco_erreur, e.message)
                    progres.visibility = View.GONE
                    btnLancer.isEnabled = true
                    btnChoisir.isEnabled = true
                }
            }
        }.start()
    }

    private fun uploadEtTraiter(): JSONObject {
        val boundary = "----CineFlightReco${System.currentTimeMillis()}"
        val url = URL("$BASE_URL/api/reconnaissance")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 30000
            readTimeout = 300000   // 5 min : la perception+triangulation peut être longue
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        DataOutputStream(conn.outputStream).use { out ->
            ecrireChamp(out, boundary, "poi_lat", poiLat.toString())
            ecrireChamp(out, boundary, "poi_lon", poiLon.toString())
            ecrireChamp(out, boundary, "poi_alt_m", poiAlt.toString())

            for ((i, uri) in photos.withIndex()) {
                val nom = nomFichier(uri) ?: "photo_$i.jpg"
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"photos\"; filename=\"$nom\"\r\n")
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(out)
                }
                out.writeBytes("\r\n")
                val pct = ((i + 1) * 100 / photos.size)
                runOnUiThread {
                    progres.progress = pct
                    statut.text = getString(R.string.reco_envoi_photo, i + 1, photos.size)
                }
            }
            out.writeBytes("--$boundary--\r\n")
            out.flush()
        }

        runOnUiThread { statut.text = getString(R.string.reco_traitement) }

        val code = conn.responseCode
        if (code != 200) {
            val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            throw RuntimeException(getString(R.string.reco_serveur_err, code, err ?: getString(R.string.reco_erreur_inconnue)))
        }
        val texte = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(texte)
    }

    private fun ecrireChamp(out: DataOutputStream, boundary: String, nom: String, valeur: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$nom\"\r\n\r\n")
        out.writeBytes("$valeur\r\n")
    }

    private fun nomFichier(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    // --- 4. Affichage du résultat ---
    private fun afficherResultat(r: JSONObject) {
        progres.visibility = View.GONE
        btnChoisir.isEnabled = true
        carteResultat.visibility = View.VISIBLE

        if (!r.optBoolean("succes", false)) {
            statut.text = getString(R.string.reco_loc_impossible)
            resultat.setTextColor(ROUGE_CLAIR)
            val sb = StringBuilder()
            sb.append(getString(R.string.reco_loc_msg))
            sb.append(getString(R.string.reco_raison, r.optString("raison", getString(R.string.reco_inconnue))))
            val avert = r.optJSONArray("avertissements")
            if (avert != null && avert.length() > 0) {
                sb.append(getString(R.string.reco_a_corriger))
                for (i in 0 until avert.length()) sb.append(getString(R.string.reco_bullet, avert.getString(i)))
            }
            sb.append("\n" + getString(R.string.reco_photos_utilisees, r.optInt("nb_retenues", 0), r.optInt("nb_photos", 0)))
            sb.append(getString(R.string.reco_conseil))
            resultat.text = sb.toString()
            btnLancer.isEnabled = true
            btnEffacerSD.isEnabled = true
            return
        }

        val t = r.getJSONObject("T")
        resultat.setTextColor(VERT_CLAIR)
        val sb = StringBuilder()
        sb.append(getString(R.string.reco_localise))
        sb.append(getString(R.string.reco_position))
        sb.append("   ${"%.6f".format(t.getDouble("lat"))}, ${"%.6f".format(t.getDouble("lon"))}\n")
        sb.append(getString(R.string.reco_altitude, "%.0f".format(t.getDouble("alt_m"))))
        sb.append(getString(R.string.reco_precision, "%.1f".format(r.optDouble("erreur_horizontale_m", 0.0))))
        sb.append(getString(R.string.reco_fiabilite, "%.0f".format(r.optDouble("confiance", 0.0) * 100)))
        sb.append(getString(R.string.reco_photos_utilisees, r.optInt("nb_retenues", 0), r.optInt("nb_photos", 0)))
        val avert = r.optJSONArray("avertissements")
        if (avert != null && avert.length() > 0) {
            sb.append(getString(R.string.reco_a_noter))
            for (i in 0 until avert.length()) sb.append(getString(R.string.reco_bullet, avert.getString(i)))
        }
        resultat.text = sb.toString()

        btnLancer.text = getString(R.string.reco_generer)
        btnLancer.isEnabled = true
        btnEffacerSD.isEnabled = true
        btnLancer.setOnClickListener {
            val intent = Intent(this, TournageActivity::class.java).apply {
                putExtra("sujet_lat", t.getDouble("lat"))
                putExtra("sujet_lon", t.getDouble("lon"))
                putExtra("sujet_alt", t.getDouble("alt_m"))
                putExtra("hauteur_sujet_m", r.optDouble("hauteur_sujet_m", 0.0))
                putExtra("type_sujet", r.optString("type_sujet", "ponctuel"))
            }
            startActivity(intent)
        }
    }

    // styles visuels
    private fun carte(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(CARTE)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(10), 0, 0)
        layoutParams = lp
    }

    private fun enTete(txt: String): TextView = TextView(this).apply {
        text = txt
        setTextColor(BLEU); textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(10))
    }

    private fun boutonStyle(txt: String, fond: Int, actif: Boolean = true): Button = Button(this).apply {
        text = txt
        isAllCaps = false
        textSize = 16f
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(fond)
        isEnabled = actif
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, 0)
        layoutParams = lp
    }

    private fun styleChamp(e: EditText) {
        e.setTextColor(TEXTE)
        e.setHintTextColor(TEXTE_DOUX)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, dp(4))
        e.layoutParams = lp
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

