package ca.cineflight.stage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import ca.cineflight.stage.control.MediaDrone
import dji.v5.manager.datacenter.media.MediaFile
import java.io.File

/**
 * PhototequeActivity - album des photos du drone (carte SD), via MSDK v5.
 * A utiliser APRES le tournage : le mode media coupe le flux live.
 * Grille de miniatures (chargees au defilement) ; tap = telecharger + partager.
 */
class PhototequeActivity : Activity() {

    private val media = MediaDrone()
    private lateinit var grille: GridLayout
    private lateinit var statut: TextView
    private var photos: List<MediaFile> = emptyList()
    private val miniCache = HashMap<String, Bitmap>()

    private val FOND = 0xFF101418.toInt()
    private val CARTE = 0xFF1E272E.toInt()
    private val ACCENT = 0xFF007AFF.toInt()

    private fun demanderFormatage() {
        // 1re confirmation
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ph_format_titre))
            .setMessage(getString(R.string.ph_format_msg))
            .setNegativeButton(getString(R.string.ph_annuler), null)
            .setPositiveButton(getString(R.string.ph_continuer)) { _, _ ->
                // 2e confirmation
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ph_confirm_titre))
                    .setMessage(getString(R.string.ph_confirm_msg))
                    .setNegativeButton(getString(R.string.ph_non), null)
                    .setPositiveButton(getString(R.string.ph_oui_formater)) { _, _ ->
                        statut.text = getString(R.string.ph_format_encours)
                        media.formaterCarteSD { ok, msg ->
                            runOnUiThread {
                                statut.text = msg
                                if (ok) {
                                    // recharger la liste apres formatage
                                    media.listerPhotos { liste ->
                                        runOnUiThread {
                                            photos = liste
                                            grille.removeAllViews()
                                            statut.text = if (liste.isEmpty()) getString(R.string.ph_formatee) else getString(R.string.ph_compte, liste.size)
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

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val racine = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        racine.addView(col)

        val titre = TextView(this).apply {
            text = getString(R.string.ph_titre); textSize = 20f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        col.addView(titre)

        statut = TextView(this).apply {
            text = getString(R.string.ph_connexion); textSize = 13f; setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, dp(4), 0, dp(10))
        }
        col.addView(statut)

        val btnFermer = Button(this).apply {
            text = getString(R.string.ph_fermer); isAllCaps = false
            setBackgroundColor(0xFF37474F.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        col.addView(btnFermer)
        // === BOUTON FORMATER (double confirmation) ===
        val btnFormater = Button(this).apply {
            text = getString(R.string.ph_format_btn); isAllCaps = false
            setBackgroundColor(0xFF8E2A2A.toInt()); setTextColor(Color.WHITE)
            (layoutParams as? LinearLayout.LayoutParams)?.also { it.topMargin = dp(6) }
            setOnClickListener { demanderFormatage() }
        }
        col.addView(btnFormater)
        // === BOUTON ASSEMBLER UN PANORAMA 360 (photos du drone -> serveur) ===
        val btnPano360 = Button(this).apply {
            text = getString(R.string.ph_pano_btn); isAllCaps = false
            setBackgroundColor(0xFF00897B.toInt()); setTextColor(Color.WHITE)
            (layoutParams as? LinearLayout.LayoutParams)?.also { it.topMargin = dp(6) }
            setOnClickListener {
                if (photos.size < 2) { toast(getString(R.string.ph_pano_vide)); return@setOnClickListener }
                val choix = arrayOf<CharSequence>(
                    getString(R.string.ph_pano_12), getString(R.string.ph_pano_25),
                    getString(R.string.ph_pano_49), getString(R.string.ph_pano_toutes, photos.size))
                val nbs = intArrayOf(12, 25, 49, photos.size)
                android.app.AlertDialog.Builder(this@PhototequeActivity)
                    .setTitle(getString(R.string.ph_pano_titre))
                    .setItems(choix) { _, w -> assemblerPanoramaDepuisSD(nbs[w]) }
                    .setNegativeButton(getString(R.string.ph_annuler), null)
                    .show()
            }
        }
        col.addView(btnPano360)

        grille = GridLayout(this).apply {
            columnCount = 3
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(grille)

        setContentView(racine)

        // activer le mode media puis lister
        media.activer { ok ->
            runOnUiThread {
                if (!ok) { statut.text = getString(R.string.ph_cam_ko); return@runOnUiThread }
                statut.text = getString(R.string.ph_lecture_sd)
                media.listerPhotos { liste ->
                    runOnUiThread {
                        photos = liste
                        if (liste.isEmpty()) statut.text = getString(R.string.ph_sd_vide)
                        else { statut.text = getString(R.string.ph_sd_compte, liste.size); construireGrille() }
                    }
                }
            }
        }
    }

    private fun construireGrille() {
        grille.removeAllViews()
        val taille = (resources.displayMetrics.widthPixels - dp(40)) / 3
        for (mf in photos) {
            val case = FrameLayout(this).apply {
                val lp = GridLayout.LayoutParams().apply {
                    width = taille; height = taille
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                layoutParams = lp
                setBackgroundColor(CARTE)
            }
            val img = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(null)
            }
            val ph = TextView(this).apply {
                text = "..."; setTextColor(0xFF607D8B.toInt()); textSize = 11f
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            case.addView(img); case.addView(ph)
            case.setOnClickListener { telechargerEtPartager(mf) }
            grille.addView(case)

            // charger la miniature (au fil de la construction)
            val cle = mf.fileName
            val enCache = miniCache[cle]
            if (enCache != null) { img.setImageBitmap(enCache); ph.visibility = View.GONE }
            else {
                media.miniature(mf) { bmp ->
                    runOnUiThread {
                        if (bmp != null) { miniCache[cle] = bmp; img.setImageBitmap(bmp); ph.visibility = View.GONE }
                        else ph.text = "photo"
                    }
                }
            }
        }
    }

    private fun telechargerEtPartager(mf: MediaFile) {
        statut.text = getString(R.string.ph_dl_fichier, mf.fileName)
        media.telecharger(this, mf,
            onProgres = { pct -> runOnUiThread { statut.text = getString(R.string.ph_dl_pct, pct) } },
            onFini = { fichier ->
                runOnUiThread {
                    if (fichier == null) { statut.text = getString(R.string.ph_dl_echec); toast(getString(R.string.ph_dl_echec)); return@runOnUiThread }
                    statut.text = getString(R.string.ph_sd_compte, photos.size)
                    partager(fichier)
                }
            })
    }

    private fun partager(fichier: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.ph_partager_via)))
        } catch (e: Exception) { toast(getString(R.string.ph_partage_ko, e.message)) }
    }

    /** Telecharge les N photos les plus recentes du drone puis les assemble en 360
     *  cote serveur (Hugin). Reutilise le meme module que la fin de panorama. */
    private fun assemblerPanoramaDepuisSD(nb: Int) {
        val cibles = photos.sortedBy { it.fileName }.takeLast(nb.coerceAtMost(photos.size))
        android.util.Log.i("CineFlightPano", "Phototheque: ${photos.size} photos, lot=${cibles.size}: ${cibles.joinToString { it.fileName }}")
        if (cibles.size < 2) { toast(getString(R.string.ph_pano_pas_assez)); return }
        statut.text = getString(R.string.ph_pano_prep, cibles.size)
        val fichiers = java.util.ArrayList<File>()
        fun suivant(i: Int) {
            if (i >= cibles.size) {
                if (fichiers.size < 2) { runOnUiThread { statut.text = getString(R.string.ph_dl_echoue) }; return }
                Thread {
                    ca.cineflight.stage.cine.PanoramaAssemblage.assembler(
                        this, fichiers, "https://cineflight.ca",
                        onProgres = { msg, pct -> runOnUiThread { statut.text = "$msg ($pct%)" } },
                        onFini = { f -> runOnUiThread {
                            if (f != null) { statut.text = getString(R.string.ph_pano_pret, f.name); partager(f) }
                            else statut.text = getString(R.string.ph_pano_ko)
                        } })
                }.start()
                return
            }
            runOnUiThread { statut.text = getString(R.string.ph_dl_n, i + 1, cibles.size) }
            media.telecharger(this, cibles[i], { }, { f ->
                android.util.Log.i("CineFlightPano", "photo ${i + 1}/${cibles.size} -> ${if (f != null) f.name else "ECHEC"}")
                if (f != null) fichiers.add(f)
                suivant(i + 1)
            })
        }
        suivant(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        media.quitter()   // rend la camera au mode normal
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
