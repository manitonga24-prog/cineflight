package ca.cineflight.stage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.tencent.yolo11ncnn.YOLO11Ncnn

class TagsActivity : AppCompatActivity() {

    private val yolo = YOLO11Ncnn()

    private val FOND = 0xFFF2F2F7.toInt()
    private val CARTE = 0xFFFFFFFF.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val TEXTE = 0xFF1C1C1E.toInt()
    private val TEXTE_DOUX = 0xFF8E8E93.toInt()

    // Un tag : id, nom, description courte (grille a l'ecran) et texte pedagogique
    // complet (verso imprime, gros caracteres).
    private data class TagInfo(val id: Int, val nom: String, val courte: String, val pedago: String)

    private val legende by lazy { listOf(
        TagInfo(0, getString(R.string.tag_0_nom), getString(R.string.tag_0_court), getString(R.string.tag_0_long)),
        TagInfo(31, getString(R.string.tag_31_nom), getString(R.string.tag_31_court), getString(R.string.tag_31_long)),
        TagInfo(32, getString(R.string.tag_32_nom), getString(R.string.tag_32_court), getString(R.string.tag_32_long)),
        TagInfo(33, getString(R.string.tag_33_nom), getString(R.string.tag_33_court), getString(R.string.tag_33_long)),
        TagInfo(34, getString(R.string.tag_34_nom), getString(R.string.tag_34_court), getString(R.string.tag_34_long)),
        TagInfo(40, getString(R.string.tag_40_nom), getString(R.string.tag_40_court), getString(R.string.tag_40_long)),
        TagInfo(41, getString(R.string.tag_41_nom), getString(R.string.tag_41_court), getString(R.string.tag_41_long)),
        TagInfo(42, getString(R.string.tag_42_nom), getString(R.string.tag_42_court), getString(R.string.tag_42_long)),
        TagInfo(43, getString(R.string.tag_43_nom), getString(R.string.tag_43_court), getString(R.string.tag_43_long)),
        TagInfo(44, getString(R.string.tag_44_nom), getString(R.string.tag_44_court), getString(R.string.tag_44_long)),
        TagInfo(45, getString(R.string.tag_45_nom), getString(R.string.tag_45_court), getString(R.string.tag_45_long)),
        TagInfo(46, getString(R.string.tag_46_nom), getString(R.string.tag_46_court), getString(R.string.tag_46_long)),
        TagInfo(47, getString(R.string.tag_47_nom), getString(R.string.tag_47_court), getString(R.string.tag_47_long)),
        TagInfo(9, getString(R.string.tag_9_nom), getString(R.string.tag_9_court), getString(R.string.tag_9_long)),
        TagInfo(10, getString(R.string.tag_10_nom), getString(R.string.tag_10_court), getString(R.string.tag_10_long))
    ) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { yolo.loadModel(assets, 0, 0, 0) } catch (_: Exception) {}

        val scroll = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        scroll.addView(col)

        // Titre
        val titres = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(10)) }
        titres.addView(TextView(this).apply { text = getString(R.string.tags_menu); textSize = 24f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        titres.addView(TextView(this).apply { text = getString(R.string.tags_sous_titre); textSize = 13f; setTextColor(TEXTE_DOUX) })
        col.addView(titres)

        // Menu en sections verticales (lignes claires : emoji + libellé + chevron)
        fun sectionTitre(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), dp(14), 0, dp(6))
        }
        fun ligneMenu(emoji: String, libelle: String, action: () -> Unit): CardView {
            val card = CardView(this).apply {
                radius = dp(12).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); layoutParams = lp
                isClickable = true; setOnClickListener { action() }
            }
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            l.addView(TextView(this).apply { text = emoji; textSize = 18f; setPadding(0, 0, dp(12), 0) })
            l.addView(TextView(this).apply { text = libelle; textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            l.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(TEXTE_DOUX) })
            card.addView(l); return card
        }
        fun ouvrir(cls: Class<*>) = startActivity(Intent(this@TagsActivity, cls))

        col.addView(sectionTitre(getString(R.string.tags_sec_creations)))
        col.addView(ligneMenu("📸", getString(R.string.tags_album)) { ouvrir(PhototequeActivity::class.java) })
        col.addView(ligneMenu("🎞️", getString(R.string.tags_mes_videos)) { ouvrir(VideosSDActivity::class.java) })
        col.addView(ligneMenu("✂️", getString(R.string.tags_montage)) { ouvrir(MontageActivity::class.java) })
        col.addView(ligneMenu("🎬", getString(R.string.tags_mes_montages)) { ouvrir(MesMontagesActivity::class.java) })

        // Le compteur est affiché DANS le libellé : une file oubliée, c'est un vol perdu.
        val enAttente = ca.cineflight.stage.cine.AssemblagesEnAttente.nombre(this)
        col.addView(ligneMenu("🧩", getString(R.string.tags_assemblages) +
            if (enAttente > 0) "  ($enAttente)" else "") { ouvrir(AssemblagesActivity::class.java) })

        col.addView(sectionTitre(getString(R.string.tags_sec_drone)))
        col.addView(ligneMenu("💾", getString(R.string.tags_carte_drone)) { ouvrir(CarteDroneActivity::class.java) })
        col.addView(ligneMenu("📍", getString(R.string.tags_find_drone)) { ouvrir(FindMyDroneActivity::class.java) })

        col.addView(sectionTitre(getString(R.string.tags_sec_reglages)))
        col.addView(ligneMenu("⚙️", getString(R.string.tags_reglages)) { ouvrir(ReglagesActivity::class.java) })
        col.addView(ligneMenu("📖", getString(R.string.tags_guide)) { ouvrir(GuideActivity::class.java) })
        col.addView(ligneMenu("🧭", getString(R.string.tags_aide_debutant)) {
            startActivity(Intent(this@TagsActivity, GuideActivity::class.java)
                .putExtra("asset", "aidememoire_debutant").putExtra("titre", getString(R.string.tags_aide_debutant)))
        })
        col.addView(ligneMenu("🛟", getString(R.string.tags_securite)) {
            startActivity(Intent(this@TagsActivity, GuideActivity::class.java)
                .putExtra("asset", "aidememoire_securite").putExtra("titre", getString(R.string.tags_securite)))
        })

        col.addView(ligneMenu("💬", getString(R.string.tags_feedback)) { ouvrir(FeedbackActivity::class.java) })
        col.addView(sectionTitre(getString(R.string.tags_sec_tags)))
        col.addView(ligneMenu("🎛️", getString(R.string.tags_macros)) { ouvrir(EditeurMacrosActivity::class.java) })
        col.addView(ligneMenu("🖨️", getString(R.string.tags_export_set)) { choisirTailleExport() })

        val zoneTags = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        zoneTags.addView(carteAide())
        val grille = GridLayout(this).apply { columnCount = 3; setPadding(0, dp(4), 0, 0) }
        val ecran = resources.displayMetrics.widthPixels
        val largeurCol = (ecran - dp(16) * 2 - dp(10) * 3) / 3
        for (t in legende) grille.addView(carteTag(t.id, t.nom, t.courte, t.pedago, largeurCol))
        zoneTags.addView(grille)
        col.addView(ligneMenu("🏷️", getString(R.string.tags_grille)) {
            val ouvre = zoneTags.visibility == android.view.View.GONE
            zoneTags.visibility = if (ouvre) android.view.View.VISIBLE else android.view.View.GONE
            if (ouvre) scroll.post { scroll.smoothScrollTo(0, zoneTags.top) }
        })
        col.addView(zoneTags)

        setContentView(scroll)
    }

    private fun carteAide(): CardView {
        val card = CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(12); layoutParams = lp
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.tags_aide_titre); textSize = 15f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(6)) })
        box.addView(TextView(this).apply {
            text = getString(R.string.tags_aide_p1)
            textSize = 13f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(3).toFloat(), 1f)
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.tags_aide_p2)
            textSize = 13f; setTextColor(ACCENT); setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(8), 0, 0)
        })
        card.addView(box); return card
    }

    private fun carteTag(id: Int, nom: String, explication: String, pedago: String, largeur: Int): CardView {
        val bmp = genererBitmap(id, 280)
        val card = CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = GridLayout.LayoutParams().apply { width = largeur; height = GridLayout.LayoutParams.WRAP_CONTENT; setMargins(dp(5), dp(5), dp(5), dp(5)) }
            isClickable = true; setOnClickListener { imprimer(id, nom, pedago, bmp) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val haut = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val imgWrap = CardView(this).apply { radius = dp(6).toFloat(); cardElevation = 0f; setCardBackgroundColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)) }
        imgWrap.addView(ImageView(this).apply { setImageBitmap(bmp); val p = dp(4); setPadding(p, p, p, p) })
        haut.addView(imgWrap)
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        txt.addView(TextView(this).apply { text = "$id"; textSize = 11f; setTextColor(TEXTE_DOUX) })
        txt.addView(TextView(this).apply { text = nom; textSize = 15f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        haut.addView(txt); box.addView(haut)
        box.addView(TextView(this).apply { text = explication; textSize = 12f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(8), 0, dp(8)) })
        box.addView(TextView(this).apply { text = getString(R.string.tags_imprimer); textSize = 13f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(2)) })
        card.addView(box); return card
    }

    private fun genererBitmap(id: Int, taille: Int): Bitmap {
        val data = yolo.genererTag(id, taille)
        val t = if (data.isNotEmpty()) data[0] else taille
        val marge = t / 5; val total = t + 2 * marge
        val bmp = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        for (y in 0 until total) for (x in 0 until total) bmp.setPixel(x, y, Color.WHITE)
        for (y in 0 until t) for (x in 0 until t) { val v = data[1 + y * t + x]; bmp.setPixel(x + marge, y + marge, if (v < 128) Color.BLACK else Color.WHITE) }
        return bmp
    }

    // Tailles de tag proposees a l'impression (cote du carre, en cm).
    private val TAILLES_CM = intArrayOf(10, 15, 20, 25, 30)

    private fun imprimer(id: Int, nom: String, description: String, bmp: Bitmap) {
        // Menu de choix de la taille, puis lancement de l'impression 2 pages.
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tags_dlg_taille))
            .setItems(TAILLES_CM.map { "$it cm" }.toTypedArray()) { _, which ->
                lancerImpression(id, nom, description, bmp, TAILLES_CM[which])
            }
            .show()
    }

    /**
     * Imprime une carte sur 2 pages (recto = tag a la taille exacte choisie,
     * verso = type + nom + description). L'utilisateur active "recto-verso" dans
     * le dialogue d'impression Android pour avoir le tag et son explication sur
     * les deux faces de la meme feuille.
     */
    private fun lancerImpression(id: Int, nom: String, description: String, tag: Bitmap, tailleCm: Int) {
        val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        val adapter = object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                old: android.print.PrintAttributes?, new: android.print.PrintAttributes?,
                cancel: android.os.CancellationSignal?, cb: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancel?.isCanceled == true) { cb?.onLayoutCancelled(); return }
                val info = android.print.PrintDocumentInfo.Builder("CineFlight_Tag_$id")
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(2).build()
                cb?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                dest: android.os.ParcelFileDescriptor?,
                cancel: android.os.CancellationSignal?,
                cb: WriteResultCallback?
            ) {
                val pdf = android.graphics.pdf.PdfDocument()
                // Page A4 en points : 595 x 842 (72 ppp).
                val largeurPt = 595; val hauteurPt = 842
                val ptParCm = 72f / 2.54f
                val cotePt = (tailleCm * ptParCm)

                // --- PAGE 1 : le tag a la taille exacte, centre ---
                val p1 = pdf.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(largeurPt, hauteurPt, 1).create()
                )
                run {
                    val c = p1.canvas
                    val tagCarre = Bitmap.createScaledBitmap(tag, cotePt.toInt(), cotePt.toInt(), false)
                    val x = (largeurPt - cotePt) / 2f
                    val y = (hauteurPt - cotePt) / 2f
                    c.drawBitmap(tagCarre, x, y, null)
                    val pNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#90A4AE"); textAlign = Paint.Align.CENTER; textSize = 14f
                    }
                    c.drawText("Tag $id  -  $nom  ($tailleCm cm)", largeurPt / 2f, y + cotePt + 24f, pNum)
                }
                pdf.finishPage(p1)

                // --- PAGE 2 : verso CONTENU DANS LE MEME CARRE que le tag recto ---
                // Le verso occupe exactement le carre (x,y,cotePt) du recto : en decoupant
                // le tag, tout le texte du verso est sur le morceau decoupe. Polices
                // proportionnelles au cote du carre + auto-reduction si depassement.
                val p2 = pdf.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(largeurPt, hauteurPt, 2).create()
                )
                run {
                    val c = p2.canvas
                    val type = when (id) {
                        in 31..34 -> getString(R.string.tags_cat_plan)
                        in 40..47 -> getString(R.string.tags_cat_mouvement)
                        else -> getString(R.string.tags_cat_commande)
                    }
                    val couleurType = when (id) {
                        in 31..34 -> Color.parseColor("#00897B")
                        in 40..47 -> Color.parseColor("#1565C0")
                        else -> Color.parseColor("#C62828")
                    }
                    // Carre identique au recto
                    val cote = cotePt
                    val gx = (largeurPt - cote) / 2f
                    val gy = (hauteurPt - cote) / 2f
                    val marge = cote * 0.06f          // marge interne proportionnelle
                    val xTexte = gx + marge
                    val largeurTexte = cote - marge * 2

                    // Cadre leger = repere de decoupe (meme carre que le tag)
                    val pCadre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#CFD8DC"); style = Paint.Style.STROKE; strokeWidth = 1f
                    }
                    c.drawRect(gx, gy, gx + cote, gy + cote, pCadre)

                    // Fonction de rendu a une echelle donnee : retourne le y final atteint.
                    // dessiner=false -> simulation (mesure) ; true -> dessin reel.
                    fun rendre(echelle: Float, dessiner: Boolean): Float {
                        val tType = cote * 0.075f * echelle
                        val tNom  = cote * 0.115f * echelle
                        val tCorps = cote * 0.052f * echelle
                        val interl = tCorps * 1.35f
                        val pType = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleurType; textAlign = Paint.Align.CENTER; textSize = tType; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                        val pNom = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C1E"); textAlign = Paint.Align.CENTER; textSize = tNom; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                        val pCorps = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#263238"); textAlign = Paint.Align.LEFT; textSize = tCorps }
                        val pTitreP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleurType; textAlign = Paint.Align.LEFT; textSize = tCorps; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                        val cx = gx + cote / 2f
                        var y = gy + marge + tType
                        if (dessiner) c.drawText(type, cx, y, pType)
                        y += tNom * 1.1f
                        if (dessiner) c.drawText(nom, cx, y, pNom)
                        y += tCorps * 0.4f
                        if (dessiner) { val pTrait = Paint().apply { color = couleurType; strokeWidth = 2f }; c.drawLine(xTexte, y, gx + cote - marge, y, pTrait) }
                        y += interl
                        for (para in description.split("\\n\\n")) {
                            if (para.isBlank()) continue
                            val sep = para.indexOf(" : ")
                            if (sep > 0 && sep < 24) {
                                val titre = para.substring(0, sep + 2)
                                val reste = para.substring(sep + 3)
                                if (dessiner) c.drawText(titre, xTexte, y, pTitreP)
                                y += interl
                                y = mesurerOuDessiner(c, reste, xTexte, y, largeurTexte, pCorps, interl, dessiner)
                            } else {
                                y = mesurerOuDessiner(c, para, xTexte, y, largeurTexte, pCorps, interl, dessiner)
                            }
                            y += interl * 0.5f
                        }
                        return y
                    }

                    // Trouver l'echelle max qui fait tenir le texte dans le carre
                    var echelle = 1f
                    val basMax = gy + cote - marge
                    var yFinal = rendre(echelle, false)
                    var garde = 0
                    while (yFinal > basMax && echelle > 0.4f && garde < 20) {
                        echelle -= 0.05f; yFinal = rendre(echelle, false); garde++
                    }
                    rendre(echelle, true)   // dessin reel a l'echelle qui tient
                }
                pdf.finishPage(p2)

                try {
                    pdf.writeTo(java.io.FileOutputStream(dest!!.fileDescriptor))
                    cb?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb?.onWriteFailed(e.message)
                } finally {
                    pdf.close()
                }
            }
        }
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
            .build()
        pm.print("CineFlight_Tag_${id}_$nom", adapter, attrs)
    }

    /** Dessine un paragraphe aligne a gauche, retourne le y apres la derniere ligne. */
    private fun dessinerParaGauche(c: Canvas, texte: String, x: Float, y0: Float, largeurMax: Float, p: Paint, interligne: Float): Float {
        val mots = texte.split(" ")
        var courante = ""
        var y = y0
        for (mot in mots) {
            val essai = if (courante.isEmpty()) mot else "$courante $mot"
            if (p.measureText(essai) > largeurMax && courante.isNotEmpty()) {
                c.drawText(courante, x, y, p); y += interligne; courante = mot
            } else courante = essai
        }
        if (courante.isNotEmpty()) { c.drawText(courante, x, y, p); y += interligne }
        return y
    }

    /** Comme dessinerParaGauche, mais ne dessine que si dessiner=true.
     *  En simulation (false) retourne juste le y final, pour mesurer la hauteur. */
    private fun mesurerOuDessiner(c: Canvas, texte: String, x: Float, y0: Float, largeurMax: Float, p: Paint, interligne: Float, dessiner: Boolean): Float {
        val mots = texte.split(" ")
        var courante = ""
        var y = y0
        for (mot in mots) {
            val essai = if (courante.isEmpty()) mot else "$courante $mot"
            if (p.measureText(essai) > largeurMax && courante.isNotEmpty()) {
                if (dessiner) c.drawText(courante, x, y, p); y += interligne; courante = mot
            } else courante = essai
        }
        if (courante.isNotEmpty()) { if (dessiner) c.drawText(courante, x, y, p); y += interligne }
        return y
    }
    // ============================================================
    //  EXPORT du SET COMPLET en PDF (pour imprimeur)
    //  Page carree = tag + 4 cm de marge. Recto = ArUco, verso = texte.
    //  15 tags -> 30 pages. Fichier dans CineFlight/Tags/ + partage.
    //  Autonome : ne touche pas a lancerImpression.
    // ============================================================
    /** Choix de la taille (20/25/30 cm) puis export du set complet en PDF. */
    private fun choisirTailleExport() {
        val tailles = intArrayOf(20, 25, 30)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tags_dlg_export))
            .setItems(tailles.map { "$it cm" }.toTypedArray()) { _, which -> exporterSet(tailles[which]) }
            .setNegativeButton(getString(R.string.tags_annuler), null)
            .show()
    }

    private fun exporterSet(tailleCm: Int) {
        statutExport(getString(R.string.tags_gen_encours, tailleCm))
        // Travail en arriere-plan : 30 pages, ca peut prendre quelques secondes.
        Thread {
            try {
                val fichier = genererSetPdf(tailleCm)
                runOnUiThread {
                    statutExport(getString(R.string.tags_gen_pret, tailleCm, legende.size * 2))
                    partagerPdf(fichier)
                }
            } catch (e: Throwable) {
                runOnUiThread { statutExport(getString(R.string.tags_gen_echec, e.message)) }
            }
        }.start()
    }

    /** Construit le PDF complet et retourne le fichier. */
    private fun genererSetPdf(tailleCm: Int): File {
        val ptParCm = 72f / 2.54f
        val cotePt = tailleCm * ptParCm                 // cote du tag (pt)
        val margePt = 2f * ptParCm                       // 2 cm de marge
        val pagePt = (cotePt + 2 * margePt)              // page carree
        val pageInt = pagePt.toInt()

        val pdf = android.graphics.pdf.PdfDocument()
        var noPage = 1
        for (tag in legende) {
            // --- RECTO : ArUco centre dans le carre ---
            val pInfoR = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageInt, pageInt, noPage++).create()
            val pageR = pdf.startPage(pInfoR)
            run {
                val c = pageR.canvas
                // Le bitmap genererBitmap contient deja sa propre bordure blanche ;
                // on l'etale sur le carre du tag (cotePt), centre dans la page.
                val bmp = genererBitmap(tag.id, 360)     // 360 px source : large assez, ArUco 4x4 net
                val carre = Bitmap.createScaledBitmap(bmp, cotePt.toInt(), cotePt.toInt(), false)
                val x = (pagePt - cotePt) / 2f
                val y = (pagePt - cotePt) / 2f
                c.drawBitmap(carre, x, y, null)
                // petit numero de tag discret en bas (hors zone de decoupe du tag)
                val pNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#90A4AE"); textAlign = Paint.Align.CENTER; textSize = margePt * 0.5f
                }
                c.drawText("Tag ${tag.id} - ${tag.nom} (${tailleCm} cm)", pagePt / 2f, pagePt - margePt * 0.5f, pNum)
            }
            pdf.finishPage(pageR)

            // --- VERSO : texte explicatif dans la zone du carre (auto-fit) ---
            val pInfoV = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageInt, pageInt, noPage++).create()
            val pageV = pdf.startPage(pInfoV)
            run {
                val c = pageV.canvas
                dessinerVersoCarre(c, tag, pagePt, cotePt, margePt)
            }
            pdf.finishPage(pageV)
        }

        val dossier = File(getExternalFilesDir(null), "CineFlight/Tags")
        if (!dossier.exists()) dossier.mkdirs()
        val fichier = File(dossier, "tags_set_${tailleCm}cm.pdf")
        java.io.FileOutputStream(fichier).use { pdf.writeTo(it) }
        pdf.close()
        return fichier
    }

    /** Dessine le verso (type + nom + description) dans le carre du tag, avec auto-reduction. */
    private fun dessinerVersoCarre(c: Canvas, tag: TagInfo, pagePt: Float, cotePt: Float, margePt: Float) {
        val type = when (tag.id) {
            in 31..34 -> getString(R.string.tags_cat_plan)
            in 40..47 -> getString(R.string.tags_cat_mouvement)
            else -> getString(R.string.tags_cat_commande)
        }
        val couleurType = when (tag.id) {
            in 31..34 -> Color.parseColor("#00897B")
            in 40..47 -> Color.parseColor("#1565C0")
            else -> Color.parseColor("#C62828")
        }
        val cote = cotePt
        val gx = (pagePt - cote) / 2f
        val gy = (pagePt - cote) / 2f
        val marge = cote * 0.06f
        val xTexte = gx + marge
        val largeurTexte = cote - marge * 2

        // cadre leger = repere de decoupe (meme carre que le tag recto)
        val pCadre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CFD8DC"); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        c.drawRect(gx, gy, gx + cote, gy + cote, pCadre)

        fun rendre(echelle: Float, dessiner: Boolean): Float {
            val tType = cote * 0.075f * echelle
            val tNom = cote * 0.115f * echelle
            val tCorps = cote * 0.052f * echelle
            val interl = tCorps * 1.35f
            val pType = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleurType; textAlign = Paint.Align.CENTER; textSize = tType; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val pNom = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C1E"); textAlign = Paint.Align.CENTER; textSize = tNom; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val pCorps = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#263238"); textAlign = Paint.Align.LEFT; textSize = tCorps }
            val cx = gx + cote / 2f
            var y = gy + marge + tType
            if (dessiner) c.drawText(type, cx, y, pType)
            y += tNom * 1.1f
            if (dessiner) c.drawText(tag.nom, cx, y, pNom)
            y += tCorps * 0.4f
            if (dessiner) { val pT = Paint().apply { color = couleurType; strokeWidth = 2f }; c.drawLine(xTexte, y, gx + cote - marge, y, pT) }
            y += interl
            for (para in tag.pedago.split("\n\n")) {
                if (para.isBlank()) continue
                y = mesurerOuDessinerExport(c, para, xTexte, y, largeurTexte, pCorps, interl, dessiner)
                y += interl * 0.5f
            }
            return y
        }

        var echelle = 1f
        val basMax = gy + cote - marge
        var yFinal = rendre(echelle, false)
        var garde = 0
        while (yFinal > basMax && echelle > 0.4f && garde < 20) {
            echelle -= 0.05f; yFinal = rendre(echelle, false); garde++
        }
        rendre(echelle, true)
    }

    /** Retour a la ligne par mesure ; ne dessine que si dessiner=true. */
    private fun mesurerOuDessinerExport(c: Canvas, texte: String, x: Float, y0: Float, largeurMax: Float, p: Paint, interligne: Float, dessiner: Boolean): Float {
        val mots = texte.split(" ")
        var courante = ""
        var y = y0
        for (mot in mots) {
            val essai = if (courante.isEmpty()) mot else "$courante $mot"
            if (p.measureText(essai) > largeurMax && courante.isNotEmpty()) {
                if (dessiner) c.drawText(courante, x, y, p); y += interligne; courante = mot
            } else courante = essai
        }
        if (courante.isNotEmpty()) { if (dessiner) c.drawText(courante, x, y, p); y += interligne }
        return y
    }

    /** Partage le PDF genere (mail, drive, etc.) pour l'envoyer a l'imprimeur. */
    private fun partagerPdf(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.tags_partager_set)))
        } catch (e: Exception) { statutExport(getString(R.string.tags_partage_echec, e.message)) }
    }

    private fun statutExport(msg: String) {
        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
    }
}

