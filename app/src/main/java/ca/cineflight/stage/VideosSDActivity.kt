package ca.cineflight.stage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * VideosSDActivity — voir et lire les videos de la carte SD du drone.
 *
 * FLUX (apres le tournage) :
 *   1. Le pilote retire la carte SD du drone et la branche sur le telephone
 *      (adaptateur USB-C).
 *   2. getString(R.string.vs_ouvrir) -> SAF (ACTION_OPEN_DOCUMENT_TREE) : le pilote
 *      selectionne le dossier des videos (ex. DCIM/DJI Album).
 *   3. L'app liste les fichiers video (.mp4/.mov) trouves.
 *   4. Toucher une video -> lecture dans le lecteur video du telephone.
 *
 * Reutilise exactement le meme mecanisme d'acces carte SD que la reconnaissance
 * photo (DocumentsContract), mais filtre les videos au lieu des photos.
 */
class VideosSDActivity : AppCompatActivity() {

    private val FOND = 0xFFF2F2F7.toInt()
    private val CARTE = 0xFFFFFFFF.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val TEXTE = 0xFF1C1C1E.toInt()
    private val TEXTE_DOUX = 0xFF8E8E93.toInt()
    private val CODE_DOSSIER = 6001

    // (uri de la video, nom affiche)
    private val videos = ArrayList<Pair<Uri, String>>()
    // uris cochees par l'utilisateur
    private val selection = LinkedHashSet<Uri>()

    private lateinit var statut: TextView
    private lateinit var liste: LinearLayout
    private lateinit var barreActions: LinearLayout
    private lateinit var btnCopier: Button
    private lateinit var btnPartager: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = getString(R.string.vs_titre); textSize = 24f; setTextColor(TEXTE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.vs_intro)
            textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, dp(12))
        })

        col.addView(Button(this).apply {
            text = getString(R.string.vs_ouvrir); setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD); isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnClickListener { choisirDossier() }
        })

        statut = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(10), 0, dp(6))
        }
        col.addView(statut)

        // Barre d'actions (visible quand au moins une video est cochee)
        barreActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE; setPadding(0, 0, 0, dp(8))
        }
        btnCopier = Button(this).apply {
            text = getString(R.string.vs_copier); isAllCaps = false; textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            lp.rightMargin = dp(8); layoutParams = lp
            setOnClickListener { menuCopier() }
        }
        btnPartager = Button(this).apply {
            text = getString(R.string.vs_partager); isAllCaps = false; textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ACCENT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            setOnClickListener { partagerSelection() }
        }
        barreActions.addView(btnCopier)
        barreActions.addView(btnPartager)
        col.addView(barreActions)

        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(liste)

        setContentView(scroll)
    }

    private fun choisirDossier() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, CODE_DOSSIER)
        } catch (e: Exception) {
            statut.text = getString(R.string.vs_selecteur_ko, e.message)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODE_DOSSIER && resultCode == Activity.RESULT_OK && data != null) {
            val treeUri = data.data ?: return
            videos.clear()
            try {
                // garde l'acces pour pouvoir relire/lancer les fichiers
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}

                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = android.provider.DocumentsContract
                    .buildChildDocumentsUriUsingTree(treeUri, docId)
                val c = contentResolver.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                c?.use {
                    while (it.moveToNext()) {
                        val id = it.getString(0)
                        val nomBrut = it.getString(1)
                        val nom = nomBrut.lowercase()
                        if (nom.endsWith(".mp4") || nom.endsWith(".mov")) {
                            val fileUri = android.provider.DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, id)
                            videos.add(fileUri to nomBrut)
                        }
                    }
                }
            } catch (e: Exception) {
                statut.text = getString(R.string.vs_lecture_ko, e.message)
                return
            }
            videos.sortBy { it.second.lowercase() }
            afficherListe()
        }
    }

    private fun afficherListe() {
        liste.removeAllViews()
        selection.clear()
        majBarreActions()
        if (videos.isEmpty()) {
            statut.text = getString(R.string.vs_vide)
            return
        }
        statut.text = getString(R.string.vs_compte, videos.size)
        for ((uri, nom) in videos) {
            val carte = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(CARTE); setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8); layoutParams = lp
            }
            // case a cocher
            carte.addView(CheckBox(this).apply {
                setOnCheckedChangeListener { _, coche ->
                    if (coche) selection.add(uri) else selection.remove(uri)
                    majBarreActions()
                }
            })
            // icone lecture + nom (tap = lire)
            val zoneNom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener { lireVideo(uri) }
            }
            zoneNom.addView(TextView(this).apply {
                text = "\u25B6"; textSize = 18f; setTextColor(ACCENT); setPadding(dp(6), 0, dp(12), 0)
            })
            zoneNom.addView(TextView(this).apply {
                text = nom; textSize = 15f; setTextColor(TEXTE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            carte.addView(zoneNom)
            liste.addView(carte)
        }
    }

    private fun majBarreActions() {
        val n = selection.size
        barreActions.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        btnCopier.text = if (n > 0) getString(R.string.vs_copier_n, n) else getString(R.string.vs_copier)
        btnPartager.text = if (n > 0) getString(R.string.vs_partager_n, n) else getString(R.string.vs_partager)
    }

    private fun lireVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.vs_lire)))
        } catch (e: Exception) {
            statut.text = getString(R.string.vs_lecteur_ko, e.message)
        }
    }

    // ---- Copier : menu Galerie du telephone / CineFlight (pour le montage) ----
    private fun menuCopier() {
        if (selection.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vs_copier_vers, selection.size))
            .setItems(arrayOf(getString(R.string.vs_galerie), getString(R.string.vs_cineflight))) { _, which ->
                if (which == 0) copierVersGalerie() else copierVersCineFlight()
            }
            .show()
    }

    /** Copie les videos cochees dans la galerie publique (Movies/CineFlight) via MediaStore. */
    private fun copierVersGalerie() {
        val aCopier = selection.toList()
        val dlg = progres(getString(R.string.vs_copie_galerie))
        Thread {
            var ok = 0; var echec = 0
            for ((i, uri) in aCopier.withIndex()) {
                try {
                    val nom = nomDe(uri) ?: "video_${System.currentTimeMillis()}.mp4"
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, nom)
                        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_MOVIES + "/CineFlight")
                            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
                        android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val dest = contentResolver.insert(collection, values)
                    if (dest == null) { echec++; continue }
                    contentResolver.openOutputStream(dest)?.use { out ->
                        contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        values.clear(); values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(dest, values, null, null)
                    }
                    ok++
                } catch (e: Exception) { echec++ }
                val pct = ((i + 1) * 100) / aCopier.size
                runOnUiThread { dlg.setMessage(getString(R.string.vs_copie_galerie_pct, pct)) }
            }
            runOnUiThread {
                dlg.dismiss()
                statut.text = getString(R.string.vs_copie_ok_galerie, ok) +
                    if (echec > 0) getString(R.string.vs_echecs, echec) else ""
            }
        }.start()
    }

    /** Copie les videos cochees dans le dossier CineFlight/Montages (lu par le montage). */
    private fun copierVersCineFlight() {
        val aCopier = selection.toList()
        val dossier = java.io.File(getExternalFilesDir(null), "CineFlight/Montages")
        if (!dossier.exists()) dossier.mkdirs()
        val dlg = progres(getString(R.string.vs_copie_cf))
        Thread {
            var ok = 0; var echec = 0
            for ((i, uri) in aCopier.withIndex()) {
                try {
                    val nom = nomDe(uri) ?: "video_${System.currentTimeMillis()}.mp4"
                    val sortie = java.io.File(dossier, nom)
                    contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(sortie).use { input.copyTo(it) }
                    }
                    ok++
                } catch (e: Exception) { echec++ }
                val pct = ((i + 1) * 100) / aCopier.size
                runOnUiThread { dlg.setMessage(getString(R.string.vs_copie_cf_pct, pct)) }
            }
            runOnUiThread {
                dlg.dismiss()
                statut.text = getString(R.string.vs_copie_ok_cf, ok) +
                    if (echec > 0) getString(R.string.vs_echecs, echec) else ""
            }
        }.start()
    }

    // ---- Partager : envoie les videos cochees vers une autre app ----
    private fun partagerSelection() {
        if (selection.isEmpty()) return
        try {
            val uris = ArrayList<Uri>(selection)
            val envoi = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.vs_partager_chooser)))
        } catch (e: Exception) {
            statut.text = getString(R.string.vs_partage_ko, e.message)
        }
    }

    /** Recupere le nom d'affichage d'un document Uri. */
    private fun nomDe(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    private fun progres(msg: String): android.app.ProgressDialog {
        return android.app.ProgressDialog(this).apply {
            setMessage(msg); setCancelable(false); show()
        }
    }
}

