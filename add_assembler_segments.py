# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MonteurVideo.kt"
s = open(f, encoding="utf-8").read()
if "fun assemblerSegments" in s:
    print("DEJA present"); raise SystemExit

anc = "    /** Lit la duree d'une video en ms (0 si echec). */"
methode = '''    /**
     * Assemble une liste de segments precis (clip + debut + fin) choisis par l'analyse.
     * @param segments segments a enchainer dans l'ordre donne
     */
    fun assemblerSegments(
        segments: List<SelecteurSegments.Segment>,
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {
        if (segments.isEmpty()) { onFini(null); return }
        try {
            val dossier = File(ctx.getExternalFilesDir(null), "CineFlight/Montages")
            if (!dossier.exists()) dossier.mkdirs()
            val sortie = File(dossier, "montage_${System.currentTimeMillis()}.mp4")

            val items = segments.map { seg ->
                val mediaItem = MediaItem.Builder()
                    .setUri(seg.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(seg.debutMs)
                            .setEndPositionMs(seg.finMs)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem).build()
            }

            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(sequence).build()
            val transformer = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        Log.i(TAG, "export segments OK: ${sortie.absolutePath}")
                        onProgres(100); onFini(sortie)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        Log.e(TAG, "export segments echec: ${e.message}")
                        onFini(null)
                    }
                })
                .build()
            onProgres(0)
            transformer.start(composition, sortie.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "assemblerSegments ex: ${e.message}"); onFini(null)
        }
    }

    /** Lit la duree d'une video en ms (0 si echec). */'''
if anc in s:
    s = s.replace(anc, methode, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("assemblerSegments ajoutee OK")
else:
    print("ANCRE NON TROUVEE")