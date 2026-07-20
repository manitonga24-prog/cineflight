# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
if "REQ_IMPORT" in s:
    print("DEJA present"); raise SystemExit

# 1) constante de requete a cote de REQ_PERM
s = s.replace(
    "    private val REQ_PERM = 801",
    "    private val REQ_PERM = 801\n    private val REQ_IMPORT = 802",
    1)

# 2) bouton apres l'encadre QuickTransfer (apres le carte(...) qui finit par '))' suivi du commentaire SECTION MODE IA)
ancBouton = '''        // === SECTION MODE IA ==='''
boutonUI = '''        // === BOUTON IMPORTER (carte SD / dossiers / galerie) ===
        col.addView(Button(this).apply {
            text = "\\uD83D\\uDCC2  Importer des videos"
            setBackgroundColor(ACCENT); setTextColor(Color.WHITE)
            textSize = 15f; setPadding(0, dp(12), 0, dp(12))
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also { it.topMargin = dp(6); it.bottomMargin = dp(8); layoutParams = it }
            setOnClickListener { importerVideos() }
        })
        col.addView(TextView(this).apply {
            text = "Ou importez directement depuis une carte SD (adaptateur USB-C), le dossier Telechargements, ou la galerie."
            textSize = 12f; setTextColor(0xFF8E9AA6.toInt()); setPadding(0, 0, 0, dp(10))
        })
        // === SECTION MODE IA ==='''

if ancBouton in s:
    s = s.replace(ancBouton, boutonUI, 1)
    print("bouton importer ajoute")
else:
    print("ANCRE BOUTON NON TROUVEE")

# 3) methode importerVideos() + onActivityResult, juste avant listerVideos()
ancMethode = '''    private fun listerVideos() {'''
methodes = '''    private fun importerVideos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Choisir des videos"), REQ_IMPORT)
        } catch (e: Exception) {
            statut.text = "Impossible d'ouvrir le selecteur de fichiers."
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_IMPORT && res == Activity.RESULT_OK && data != null) {
            var ajout = 0
            // selection multiple
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
                // selection unique
                data.data?.let { uri ->
                    if (!videos.contains(uri)) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        videos.add(0, uri); ajout++
                    }
                }
            }
            if (ajout > 0) {
                statut.text = "$ajout video(s) importee(s). Total : ${videos.size}. Cochez celles a monter."
                construireGrille()
            } else {
                statut.text = "Aucune nouvelle video importee."
            }
        }
    }

    private fun listerVideos() {'''

if ancMethode in s:
    s = s.replace(ancMethode, methodes, 1)
    print("methodes import ajoutees")
else:
    print("ANCRE METHODE NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("MontageActivity sauvegarde OK")