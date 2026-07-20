package ca.cineflight.stage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MesMontagesActivity - galerie integree des montages crees par CineFlight Solo.
 * Liste le dossier CineFlight/Montages : miniature + date + taille, tap = lire, bouton partager.
 */
class MesMontagesActivity : Activity() {

    private val FOND = 0xFF101418.toInt()
    private val CARTE = 0xFF1E272E.toInt()
    private val ACCENT = 0xFF007AFF.toInt()

    private lateinit var liste: LinearLayout
    private lateinit var statut: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val racine = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        racine.addView(col)

        col.addView(TextView(this).apply {
            text = getString(R.string.mm_titre); textSize = 20f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statut = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, dp(4), 0, dp(10))
        }
        col.addView(statut)

        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(liste)

        setContentView(racine)
        charger()
    }

    override fun onResume() {
        super.onResume()
        charger()  // rafraichir si on revient apres avoir cree un montage
    }

    private fun charger() {
        liste.removeAllViews()
        val dossier = File(getExternalFilesDir(null), "CineFlight/Montages")
        val fichiers = dossier.listFiles { f -> f.isFile && f.name.endsWith(".mp4", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (fichiers.isEmpty()) {
            statut.text = getString(R.string.mm_vide)
            return
        }
        statut.text = getString(R.string.mm_compte, fichiers.size)

        val fmtDate = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.CANADA_FRENCH)
        for (f in fichiers) {
            val carte = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(CARTE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8); layoutParams = lp
                gravity = Gravity.CENTER_VERTICAL
            }

            // miniature
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(64))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF000000.toInt())
            }
            carte.addView(img)
            Thread {
                val bmp = miniature(f)
                if (bmp != null) runOnUiThread { img.setImageBitmap(bmp) }
            }.start()

            // infos
            val infos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.leftMargin = dp(10); layoutParams = lp
            }
            infos.addView(TextView(this).apply {
                text = fmtDate.format(Date(f.lastModified())); textSize = 14f; setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            infos.addView(TextView(this).apply {
                val mo = f.length() / (1024.0 * 1024.0)
                text = String.format(Locale.CANADA_FRENCH, getString(R.string.mm_taille_mo), mo); textSize = 12f; setTextColor(0xFFB0BEC5.toInt())
            })
            carte.addView(infos)

            // boutons empiles a droite : partager + supprimer
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            actions.addView(Button(this).apply {
                text = getString(R.string.mm_partager); isAllCaps = false; textSize = 12f
                setBackgroundColor(ACCENT); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                setOnClickListener { partager(f) }
            })
            actions.addView(Button(this).apply {
                text = getString(R.string.mm_supprimer); isAllCaps = false; textSize = 12f
                setBackgroundColor(0xFFFF3B30.toInt()); setTextColor(Color.WHITE)
                val lpb = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                lpb.topMargin = dp(6); layoutParams = lpb
                setOnClickListener { confirmerSuppression(f) }
            })
            carte.addView(actions)

            // tap sur la carte (hors bouton) = lire
            carte.setOnClickListener { lire(f) }
            liste.addView(carte)
        }
    }

    private fun miniature(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame
        } catch (e: Exception) { null }
        finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun uriDe(f: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun lire(f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriDe(f), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) { statut.text = getString(R.string.mm_lecture_ko, e.message) }
    }

    private fun partager(f: File) {
        try {
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uriDe(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.mm_partager_chooser)))
        } catch (e: Exception) { statut.text = getString(R.string.mm_partage_ko, e.message) }
    }

    private fun confirmerSuppression(f: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mm_suppr_titre))
            .setMessage(getString(R.string.mm_suppr_msg))
            .setNegativeButton(getString(R.string.mm_annuler), null)
            .setPositiveButton(getString(R.string.mm_supprimer)) { _, _ -> supprimer(f) }
            .show()
    }

    private fun supprimer(f: File) {
        val ok = try { f.delete() } catch (e: Exception) { false }
        statut.text = if (ok) getString(R.string.mm_suppr_ok) else getString(R.string.mm_suppr_ko)
        if (ok) charger()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
