package ca.cineflight.stage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
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
    private lateinit var etapeGrosse: TextView   // étapes d'assemblage, gros caractères
    @Volatile private var assemblageEnCours = false   // verrou : un seul assemblage à la fois
    private var masquageConfirmation: Runnable? = null   // un seul compte à rebours à la fois
    private var photos: List<MediaFile> = emptyList()
    private var videos: List<MediaFile> = emptyList()   // vidéos du drone (▶ dans la grille)
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
        // ÉTAPES D'ASSEMBLAGE EN GROS (2026-07-25) : le statut normal (13 sp gris) est
        // illisible pendant un assemblage qui dure des minutes, souvent à bout de bras au
        // soleil. Ce bandeau n'apparaît QUE pendant le processus, en gros et contrasté.
        etapeGrosse = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(18), dp(14), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(0xFF00695C.toInt())
            }
            visibility = View.GONE
        }
        col.addView(etapeGrosse)

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
                // CHOIX « 12 photos » RETIRÉ (2026-07-26) : avec 12 vues, l'écart entre
                // prises dépasse le recouvrement utile de l'objectif -> l'assembleur COLLE
                // les images au lieu de les fondre (coupure franche constatée). On ne
                // propose que des lots qui donnent un VRAI panorama.
                val choix = arrayOf<CharSequence>(
                    getString(R.string.ph_pano_25),
                    getString(R.string.ph_pano_49), getString(R.string.ph_pano_toutes, photos.size))
                val nbs = intArrayOf(25, 49, photos.size)
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

        // activer le mode media puis lister : PHOTOS puis VIDÉOS (même grille, ▶ sur les vidéos)
        media.activer { ok ->
            runOnUiThread {
                if (!ok) { statut.text = getString(R.string.ph_cam_ko); return@runOnUiThread }
                statut.text = getString(R.string.ph_lecture_sd)
                media.listerPhotos { ph ->
                    media.listerVideos { vid ->
                        runOnUiThread {
                            photos = ph; videos = vid
                            if (ph.isEmpty() && vid.isEmpty()) statut.text = getString(R.string.ph_sd_vide_tout)
                            else {
                                statut.text = getString(R.string.ph_sd_compte_av, ph.size, vid.size)
                                construireGrille()
                                listePrete = true      // les MediaFile sont valides à partir d'ici
                            }
                        }
                    }
                }
            }
        }
    }

    private fun construireGrille() {
        grille.removeAllViews()
        val taille = (resources.displayMetrics.widthPixels - dp(40)) / 3
        val nomsVideos = videos.map { it.fileName }.toHashSet()
        for (mf in photos + videos) {
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
            // Badge ✓ VERT : fichier DÉJÀ sur le téléphone (même nom, même taille) — on voit
            // d'un coup d'œil ce qui reste à transférer, sans toucher (2026-07-26).
            if (media.dejaTelecharge(this, mf) != null) {
                case.addView(TextView(this).apply {
                    text = getString(R.string.ph_deja_badge)
                    setTextColor(0xFF00E676.toInt()); textSize = 16f
                    setShadowLayer(4f, 0f, 0f, Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(6), 0) }
                })
            }
            // Badge ▶ sur les vidéos (la miniature seule ne distingue pas photo/vidéo).
            if (mf.fileName in nomsVideos) {
                case.addView(TextView(this).apply {
                    text = "▶"; setTextColor(Color.WHITE); textSize = 18f
                    setShadowLayer(4f, 0f, 0f, Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(4)) }
                })
            }
            case.setOnClickListener { if (pretPourToucher()) telechargerEtPartager(mf) }
            // APPUI LONG = partager (au lieu de lire) : évite le menu qui s'ouvrait à
            // chaque téléchargement et qu'il fallait annuler pour continuer.
            case.setOnLongClickListener {
                if (pretPourToucher()) telechargerEtPartager(mf, forcerPartage = true); true
            }
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

    /**
     * Le SDK INVALIDE les objets MediaFile dès que la liste est rechargée : toucher une
     * vignette pendant un rechargement (ou un téléchargement déjà en cours) produisait un
     * `dl echec: null` SILENCIEUX — 11 échecs relevés au journal du 2026-07-26 pour
     * 5 touches ressenties. On refuse le geste et on le DIT, au lieu de ne rien faire.
     */
    @Volatile private var listePrete = false
    @Volatile private var telechargementEnCours = false
    @Volatile private var debutTelechargementMs = 0L
    @Volatile private var dernierProgresMs = 0L

    /**
     * Le verrou « un transfert à la fois » ne doit JAMAIS rester coincé : si le SDK ne
     * rappelle pas (cas observé), toutes les touches suivantes seraient refusées à tort —
     * « dès que je presse une vidéo : transfert en cours » (2026-07-26).
     * On considère le transfert ABANDONNÉ s'il n'a produit AUCUNE progression depuis 15 s.
     */
    private fun transfertVraimentEnCours(): Boolean {
        if (!telechargementEnCours) return false
        val now = SystemClock.elapsedRealtime()
        val reference = if (dernierProgresMs > 0L) dernierProgresMs else debutTelechargementMs
        if (now - reference > 15_000L) {
            android.util.Log.w("Phototheque", "verrou de transfert LIBÉRÉ (aucune progression depuis 15 s)")
            telechargementEnCours = false
            return false
        }
        return true
    }

    /** "45" -> "45 s" ; "125" -> "2 min 05 s". */
    private fun formatDuree(s: Int): String =
        if (s < 60) "$s s" else "%d min %02d s".format(s / 60, s % 60)

    private fun pretPourToucher(): Boolean {
        if (!listePrete) { flottant(getString(R.string.ph_pas_pret), 2000L); return false }
        if (transfertVraimentEnCours()) { flottant(getString(R.string.ph_dl_deja), 2000L); return false }
        return true
    }

    private fun telechargerEtPartager(mf: MediaFile, forcerPartage: Boolean = false) {
        // DÉJÀ SUR LE TÉLÉPHONE ? On ne retélécharge pas des centaines de Mo pour rien :
        // on agit tout de suite (lecture / partage) et on le DIT (2026-07-26).
        val local = media.dejaTelecharge(this, mf)
        if (local != null) {
            val estVid = local.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") }
            flottant(getString(R.string.ph_deja_local, local.name), 3000L)
            if (estVid && !forcerPartage) lire(local) else partager(local)
            return
        }
        telechargementEnCours = true
        debutTelechargementMs = SystemClock.elapsedRealtime()
        dernierProgresMs = 0L
        statut.text = getString(R.string.ph_dl_fichier, mf.fileName)
        flottant(getString(R.string.ph_dl_debut, mf.fileName), 2500L)   // retour IMMÉDIAT au toucher
        media.telechargerDetaille(this, mf,
            onProgres = { p -> runOnUiThread {
                dernierProgresMs = SystemClock.elapsedRealtime()
                // TEMPS RESTANT ESTIMÉ : indispensable sur une vidéo de plusieurs centaines
                // de Mo — sans lui, l'usager ne sait pas s'il attend 5 s ou 3 minutes.
                val detail = if (p.resteS >= 0)
                    getString(R.string.ph_dl_reste, p.pct, formatDuree(p.resteS), p.debitMoS)
                else getString(R.string.ph_dl_pct, p.pct)
                statut.text = detail
                flottant(detail, 2500L)
            } },
            onFini = { fichier ->
                runOnUiThread {
                    telechargementEnCours = false
                    if (fichier == null) {
                        statut.text = getString(R.string.ph_dl_echec)
                        flottant(getString(R.string.ph_dl_echec), 3000L)   // ÉCHEC VISIBLE
                        return@runOnUiThread
                    }
                    // CONFIRMATION VISIBLE (2026-07-26) : sans elle, l'usager ne sait pas si
                    // son geste a fonctionné — le statut revenait au compte de fichiers et
                    // le partage s'ouvrait par-dessus. Bandeau vert + toast, quelques
                    // secondes, puis retour au compte normal.
                    val estVideo0 = fichier.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") }
                    val ko = (fichier.length() / 1024L).coerceAtLeast(1L)
                    val taille = if (ko >= 1024) "%.1f Mo".format(ko / 1024.0) else "$ko Ko"
                    etape(getString(
                        if (estVideo0 && !forcerPartage) R.string.ph_ok_video else R.string.ph_ok_photo,
                        fichier.name, taille))
                    // ⚠ ANNULER le masquage précédent (correctif 2026-07-26) : sans ça, le
                    // compte à rebours de la confirmation N°1 effaçait la confirmation N°2
                    // dès qu'on touchait une 2e vignette -> « ça ne marche que la 1re fois ».
                    masquageConfirmation?.let { etapeGrosse.removeCallbacks(it) }
                    val r = Runnable {
                        etapeGrosse.visibility = View.GONE
                        statut.text = getString(R.string.ph_sd_compte_av, photos.size, videos.size)
                    }
                    masquageConfirmation = r
                    etapeGrosse.postDelayed(r, 4000)
                    toast(getString(R.string.ph_ok_court))
                    // PAS DE MENU (2026-07-26) : un dialogue gris s'ouvrait à CHAQUE
                    // téléchargement et il fallait l'annuler pour continuer. Action DIRECTE :
                    // vidéo -> lecture ; photo -> partage (comportement historique).
                    // Pour partager une vidéo : APPUI LONG sur sa vignette.
                    val estVideo = fichier.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") }
                    if (estVideo && !forcerPartage) lire(fichier) else partager(fichier)
                }
            })
    }

    /** Ouvre la vidéo téléchargée dans le lecteur vidéo du téléphone (même mécanique
     *  que « Mes vidéos » : ACTION_VIEW + FileProvider, aucun lecteur intégré à maintenir). */
    private fun lire(fichier: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
            val voir = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(voir)
        } catch (e: Exception) { toast(getString(R.string.ph_lecture_ko, e.message)) }
    }

    /**
     * Panorama prêt : propose la VISIONNEUSE 360/VR (le lien) ou l'image plate.
     * Le lien s'ouvre en immersion dans un casque (Quest), avec le gyroscope sur téléphone
     * et à la souris sur ordinateur — c'est la seule façon de voir un 360 tel qu'il a été
     * filmé. L'image plate reste disponible pour l'impression ou l'archivage.
     */
    private fun proposerLienVr(lien: String, image: File) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ph_vr_titre))
            .setMessage(getString(R.string.ph_vr_msg, lien))
            .setPositiveButton(getString(R.string.ph_vr_ouvrir)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(lien)))
                } catch (e: Exception) { toast(getString(R.string.ph_lecture_ko, e.message)) }
            }
            .setNeutralButton(getString(R.string.ph_vr_partager)) { _, _ ->
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ph_vr_sujet))
                        putExtra(Intent.EXTRA_TEXT, getString(R.string.ph_vr_texte, lien))
                    }, getString(R.string.ph_partager_via)))
                } catch (e: Exception) { toast(getString(R.string.ph_partage_ko, e.message)) }
            }
            .setNegativeButton(getString(R.string.ph_vr_image)) { _, _ -> partager(image) }
            .show()
    }

    private fun partager(fichier: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
            val estVideo = fichier.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") }
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = if (estVideo) "video/*" else "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.ph_partager_via)))
        } catch (e: Exception) { toast(getString(R.string.ph_partage_ko, e.message)) }
    }

    /**
     * Affiche une étape d'assemblage EN GROS (bandeau visible, écran maintenu allumé —
     * l'assemblage dure des minutes et le pilote regarde de loin). `pct < 0` = pas de
     * pourcentage. Le statut normal reste synchronisé pour l'historique.
     */
    private fun etape(message: String, pct: Int = -1) {
        // Toute nouvelle étape annule un masquage en attente : sinon un compte à rebours
        // posé par l'étape précédente ferait disparaître celle-ci (défaut 2026-07-26).
        masquageConfirmation?.let { etapeGrosse.removeCallbacks(it) }
        val txt = if (pct in 0..100) "$message\n$pct %" else message
        etapeGrosse.text = txt
        etapeGrosse.visibility = View.VISIBLE
        statut.text = message
        // ⚠ Le bandeau vit dans la colonne DÉFILANTE : dès que l'usager descend vers les
        // vignettes, il est HORS ÉCRAN — d'où « le toucher ne fait rien » alors que le
        // téléchargement réussissait (journal 2026-07-26). On double donc l'affichage par
        // un bandeau FLOTTANT, par-dessus tout, impossible à manquer.
        flottant(txt)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var vueFlottante: TextView? = null
    private var masquageFlottant: Runnable? = null

    /** Bandeau de confirmation FLOTTANT (au-dessus de la grille, quelle que soit la position
     *  de défilement). Remplacé à chaque nouvel appel, effacé après [dureeMs]. */
    private fun flottant(texte: String, dureeMs: Long = 4000L) {
        val v = vueFlottante ?: TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF0004D40.toInt())
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER
            addContentView(this, lp)
            vueFlottante = this
        }
        masquageFlottant?.let { v.removeCallbacks(it) }
        v.text = texte
        v.visibility = View.VISIBLE
        val r = Runnable { v.visibility = View.GONE }
        masquageFlottant = r
        v.postDelayed(r, dureeMs)
    }

    /** Dernière étape : reste affichée en gros quelques secondes, puis le bandeau s'efface. */
    private fun etapeFin(message: String) {
        assemblageEnCours = false   // libère le verrou, succès comme échec
        etape(message)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val r = Runnable {
            etapeGrosse.visibility = View.GONE
            statut.text = getString(R.string.ph_sd_compte_av, photos.size, videos.size)
        }
        masquageConfirmation = r
        etapeGrosse.postDelayed(r, 6000)
    }

    /** Telecharge les N photos les plus recentes du drone puis les assemble en 360
     *  cote serveur (Hugin). Reutilise le meme module que la fin de panorama. */
    private fun assemblerPanoramaDepuisSD(nb: Int) {
        // VERROU (constat journal 2026-07-25 : deux assemblages lancés en parallèle, lots
        // de 25 et 49 entrelacés — téléchargements concurrents sur la même carte SD).
        if (assemblageEnCours) { toast(getString(R.string.ph_pano_deja)); return }
        assemblageEnCours = true
        val cibles = photos.sortedBy { it.fileName }.takeLast(nb.coerceAtMost(photos.size))
        android.util.Log.i("CineFlightPano", "Phototheque: ${photos.size} photos, lot=${cibles.size}: ${cibles.joinToString { it.fileName }}")
        if (cibles.size < 2) { assemblageEnCours = false; toast(getString(R.string.ph_pano_pas_assez)); return }
        etape(getString(R.string.ph_pano_prep, cibles.size), 0)
        val fichiers = java.util.ArrayList<File>()
        fun suivant(i: Int) {
            if (i >= cibles.size) {
                if (fichiers.size < 2) { runOnUiThread { etapeFin(getString(R.string.ph_dl_echoue)) }; return }
                Thread {
                    ca.cineflight.stage.cine.PanoramaAssemblage.assembler(
                        this, fichiers, "https://cineflight.ca",
                        onProgres = { msg, pct -> runOnUiThread { etape(msg, pct) } },
                        onFini = { f -> runOnUiThread {
                            if (f != null) {
                                etapeFin(getString(R.string.ph_pano_pret, f.name))
                                // LIEN VR : le vrai intérêt d'un 360 est de le REGARDER en
                                // 360 — casque, téléphone ou PC avec le même lien. On le
                                // propose AVANT le partage de l'image plate.
                                val lien = ca.cineflight.stage.cine.PanoramaAssemblage.dernierLienVr
                                if (lien != null) proposerLienVr(lien, f) else partager(f)
                            } else etapeFin(getString(R.string.ph_pano_ko))
                        } })
                }.start()
                return
            }
            runOnUiThread { etape(getString(R.string.ph_dl_n, i + 1, cibles.size), (i * 20) / cibles.size) }
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
