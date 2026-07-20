package ca.cineflight.stage

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ca.cineflight.stage.control.MonteurVideo
import ca.cineflight.stage.control.AnalyseurMontage
import ca.cineflight.stage.control.SelecteurSegments
import ca.cineflight.stage.control.Reglages
import com.tencent.yolo11ncnn.YOLO11Ncnn
import java.io.File

/**
 * MontageActivity - editeur automatique : galerie video integree + assemblage.
 * Liste les videos du telephone (rapatriees via DJI Fly), cases a cocher, choix
 * d'un style, puis cree un montage MP4 (analyse IA des meilleurs moments) et
 * propose de le lire / partager.
 *
 * i18n : tous les textes visibles passent par getString(R.string.mt_*)
 * (res/values = anglais, res/values-fr = francais). Ecran en AppCompatActivity
 * pour que la langue choisie (AppCompatDelegate) s'applique sur toutes versions.
 */
class MontageActivity : AppCompatActivity() {

    private val FOND = 0xFF101418.toInt()
    private val CARTE = 0xFF1E272E.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val REQ_PERM = 801
    private val REQ_IMPORT = 802

    private lateinit var grille: GridLayout
    private lateinit var statut: TextView
    private val videos = ArrayList<Uri>()
    private val choisis = LinkedHashSet<Uri>()
    private val miniCache = HashMap<Uri, Bitmap>()
    private val coches = LinkedHashMap<Uri, TextView>()

    private var conteneur: LinearLayout? = null
    private var uriMontage: android.net.Uri? = null
    private val dureeParClip = 4   // duree cible par clip, utilisee par le montage intelligent
    private var mode = SelecteurSegments.Mode.BEST_OF
    private val yolo = YOLO11Ncnn()
    private var yoloPret = false

    private var descMode: TextView? = null
    private var champNom: EditText? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val racine = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        racine.addView(col)
        conteneur = col

        // === TITRE ===
        col.addView(TextView(this).apply {
            text = getString(R.string.mt_titre); textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.mt_sous)
            textSize = 13f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, dp(2), 0, dp(14))
        })

        // === ETAPE 1 : recuperer les videos (QuickTransfer) ===
        col.addView(carte(getString(R.string.mt_s1_titre), getString(R.string.mt_s1_texte)))
        col.addView(Button(this).apply {
            text = getString(R.string.mt_importer)
            setBackgroundColor(ACCENT); setTextColor(Color.WHITE)
            textSize = 15f; setPadding(0, dp(12), 0, dp(12))
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also { it.topMargin = dp(6); it.bottomMargin = dp(8); layoutParams = it }
            setOnClickListener { importerVideos() }
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.mt_importer_aide)
            textSize = 12f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, 0, 0, dp(10))
        })

        // === ETAPE 2 : choisir les videos (grille) ===
        col.addView(titreSection(getString(R.string.mt_s2_titre)))
        grille = GridLayout(this).apply { columnCount = 3; setPadding(0, dp(8), 0, 0) }
        col.addView(grille)
        statut = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(8), 0, dp(10))
        }
        col.addView(statut)

        // === ETAPE 3 : choisir un style ===
        col.addView(titreSection(getString(R.string.mt_s3_titre)))
        val ligneMode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scrollMode = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ligneMode) }
        data class OptMode(val libelleRes: Int, val m: SelecteurSegments.Mode, val descRes: Int)
        val options = listOf(
            OptMode(R.string.mt_style_bestof, SelecteurSegments.Mode.BEST_OF, R.string.mt_style_bestof_desc),
            OptMode(R.string.mt_style_unparclip, SelecteurSegments.Mode.UN_PAR_CLIP, R.string.mt_style_unparclip_desc),
            OptMode(R.string.mt_style_adaptatif, SelecteurSegments.Mode.ADAPTATIF, R.string.mt_style_adaptatif_desc)
        )
        val btnsMode = ArrayList<Pair<Button, OptMode>>()
        fun appliquerMode(opt: OptMode) {
            mode = opt.m
            btnsMode.forEach { (bb, oo) -> majBouton(bb, oo.m == opt.m) }
            descMode?.text = getString(opt.descRes)
        }
        for (opt in options) {
            val b = Button(this).apply {
                text = getString(opt.libelleRes); isAllCaps = false; textSize = 14f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)); lp.rightMargin = dp(6); layoutParams = lp
                setOnClickListener { appliquerMode(opt) }
            }
            btnsMode.add(b to opt); ligneMode.addView(b)
        }
        col.addView(scrollMode)
        descMode = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, dp(6), 0, dp(12))
        }
        col.addView(descMode)

        // === NOM DU MONTAGE ===
        col.addView(TextView(this).apply {
            text = getString(R.string.mt_nom_label); textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(2))
        })
        val edit = EditText(this).apply {
            hint = getString(R.string.mt_nom_hint)
            setTextColor(Color.WHITE); setHintTextColor(0xFF607D8B.toInt())
            setBackgroundColor(CARTE); setPadding(dp(10), dp(10), dp(10), dp(10)); textSize = 14f
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8); layoutParams = lp
        }
        col.addView(edit); champNom = edit

        // === BOUTON CREER (en bas, apres la selection) ===
        col.addView(Button(this).apply {
            text = getString(R.string.mt_creer); isAllCaps = false; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(ACCENT); setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); lp.topMargin = dp(6); lp.bottomMargin = dp(6); layoutParams = lp
            setOnClickListener { creerMontage() }
        })

        setContentView(racine)

        appliquerMode(options[0])   // style initial : Best-of
        demanderPermissionEtLister()
    }

    private fun titreSection(t: String): TextView = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(8), 0, dp(6))
    }

    private fun carte(titre: String, texte: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARTE); setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(14); layoutParams = lp
        }
        box.addView(TextView(this).apply {
            text = titre; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = texte; textSize = 12f; setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        return box
    }

    private fun majBouton(b: Button, actif: Boolean) {
        if (actif) { b.setBackgroundColor(ACCENT); b.setTextColor(Color.WHITE) }
        else { b.setBackgroundColor(CARTE); b.setTextColor(0xFFB0BEC5.toInt()) }
    }

    private fun demanderPermissionEtLister() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) listerVideos()
        else requestPermissions(arrayOf(perm), REQ_PERM)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_PERM) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) listerVideos()
            else statut.text = getString(R.string.mt_perm_refusee)
        }
    }

    private fun importerVideos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.mt_choisir_videos)), REQ_IMPORT)
        } catch (e: Exception) {
            statut.text = getString(R.string.mt_import_impossible)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT && res == Activity.RESULT_OK && data != null) {
            var ajout = 0
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri
                    if (uri != null && !videos.contains(uri)) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        videos.add(0, uri); ajout++
                    }
                }
            } else {
                data.data?.let { uri ->
                    if (!videos.contains(uri)) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        videos.add(0, uri); ajout++
                    }
                }
            }
            if (ajout > 0) {
                statut.text = getString(R.string.mt_importees, ajout, videos.size)
                construireGrille()
            } else {
                statut.text = getString(R.string.mt_aucune_nouvelle)
            }
        }
    }

    private fun listerVideos() {
        videos.clear()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED)
        val tri = MediaStore.Video.Media.DATE_ADDED + " DESC"
        try {
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, tri)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    videos.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id))
                }
            }
            if (videos.isEmpty()) statut.text = getString(R.string.mt_aucune_video)
            else { statut.text = getString(R.string.mt_videos_cochez, videos.size); construireGrille() }
        } catch (e: Exception) { statut.text = getString(R.string.mt_erreur_lecture, e.message ?: "") }
    }

    private fun construireGrille() {
        grille.removeAllViews()
        coches.clear()
        val taille = (resources.displayMetrics.widthPixels - dp(48)) / 3
        for (uri in videos) {
            val case = FrameLayout(this).apply {
                val lp = GridLayout.LayoutParams().apply {
                    width = taille; height = taille; setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                layoutParams = lp; setBackgroundColor(CARTE)
            }
            val img = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val coche = TextView(this).apply {
                text = ""; textSize = 22f; setTextColor(ACCENT)
                setBackgroundColor(0x88000000.toInt())
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            }
            case.addView(img); case.addView(coche)
            coches[uri] = coche
            case.setOnClickListener {
                if (choisis.contains(uri)) choisis.remove(uri) else choisis.add(uri)
                rafraichirNumeros()
            }
            grille.addView(case)

            val enCache = miniCache[uri]
            if (enCache != null) img.setImageBitmap(enCache)
            else {
                Thread {
                    val bmp = miniatureVideo(uri)
                    if (bmp != null) runOnUiThread { miniCache[uri] = bmp; img.setImageBitmap(bmp) }
                }.start()
            }
        }
    }

    private fun rafraichirNumeros() {
        val ordre = choisis.toList()
        for ((uri, tv) in coches) {
            val idx = ordre.indexOf(uri)
            if (idx >= 0) {
                tv.text = " ${idx + 1} "
                tv.setBackgroundColor(ACCENT)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.text = ""
                tv.setBackgroundColor(0x88000000.toInt())
            }
        }
        statut.text = if (choisis.isEmpty()) getString(R.string.mt_cochez_ordre)
                      else getString(R.string.mt_selection, choisis.size)
    }

    private fun miniatureVideo(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val b = contentResolver.loadThumbnail(uri, Size(256, 256), null)
                if (b != null) return b
            } catch (_: Exception) {}
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) Bitmap.createScaledBitmap(frame, 256, 256, true) else null
        } catch (e: Exception) { null }
        finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun creerMontage() {
        if (choisis.size < 2) { statut.text = getString(R.string.mt_min2); return }
        montageIntelligent()
    }

    /** Montage intelligent : analyse YOLO -> selection des meilleurs segments -> assemblage. */
    private fun montageIntelligent() {
        statut.text = getString(R.string.mt_analyse)
        val t0 = System.currentTimeMillis()
        Thread {
            if (!yoloPret) {
                try { yoloPret = yolo.loadModel(assets, 0, 0, 0) } catch (e: Exception) { yoloPret = false }
            }
            if (!yoloPret) {
                runOnUiThread { statut.text = getString(R.string.mt_ia_ko) }
                return@Thread
            }
            val classes = try { Reglages(this).classesPourSujet() } catch (e: Exception) { setOf(0) }
            val analyseur = AnalyseurMontage(this, yolo, classes)
            analyseur.analyser(
                clips = choisis.toList(),
                onProgres = { pct -> runOnUiThread { statut.text = getString(R.string.mt_analyse_pct, pct) } },
                onFini = { resultats ->
                    val segments = SelecteurSegments.choisir(
                        scores = resultats,
                        mode = mode,
                        dureeCibleSec = 30,
                        dureePclipSec = dureeParClip,
                        seuil = 0.45f
                    )
                    if (segments.isEmpty()) {
                        runOnUiThread { statut.text = getString(R.string.mt_aucun_moment) }
                        return@analyser
                    }
                    runOnUiThread {
                        statut.text = getString(R.string.mt_assemblage, segments.size)
                        val monteur = MonteurVideo(this)
                        monteur.assemblerSegments(
                            segments = segments,
                            nom = champNom?.text?.toString() ?: "",
                            onProgres = { pct -> runOnUiThread { statut.text = getString(R.string.mt_montage_ia_pct, pct) } },
                            onFini = { fichier -> runOnUiThread { finaliser(fichier, t0) } }
                        )
                    }
                }
            )
        }.start()
    }

    private fun finaliser(fichier: File?, t0: Long) {
        if (fichier == null) { statut.text = getString(R.string.mt_echec); return }
        val sec = (System.currentTimeMillis() - t0) / 1000
        statut.text = getString(R.string.mt_pret_ajout, sec)
        val monteur = MonteurVideo(this)
        Thread {
            val uriPub = monteur.publierDansGalerie(fichier)
            runOnUiThread {
                uriMontage = uriPub ?: androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
                statut.text = if (uriPub != null) getString(R.string.mt_pret_galerie, sec) else getString(R.string.mt_pret, sec)
                afficherBoutonsResultat(fichier)
            }
        }.start()
    }

    private fun afficherBoutonsResultat(fichier: File) {
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        ligne.addView(Button(this).apply {
            text = getString(R.string.mt_lire); isAllCaps = false
            setBackgroundColor(0xFF2E7D32.toInt()); setTextColor(android.graphics.Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f); lp.rightMargin = dp(6); layoutParams = lp
            setOnClickListener { lire() }
        })
        ligne.addView(Button(this).apply {
            text = getString(R.string.mt_partager); isAllCaps = false
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
        } catch (e: Exception) { statut.text = getString(R.string.mt_lecture_ko, e.message ?: "") }
    }

    private fun partager(fichier: File) {
        try {
            val uri = uriMontage ?: FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.mt_partager_chooser)))
        } catch (e: Exception) { statut.text = getString(R.string.mt_partage_ko, e.message ?: "") }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
