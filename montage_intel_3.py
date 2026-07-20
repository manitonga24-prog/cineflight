# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()

debut = s.find("    private fun creerMontage() {")
if debut == -1:
    print("creerMontage NON TROUVEE"); raise SystemExit
# fin = juste avant afficherBoutonsResultat
fin = s.find("    private fun afficherBoutonsResultat", debut)
if fin == -1:
    print("afficherBoutonsResultat NON TROUVE"); raise SystemExit

nouveau = '''    private fun creerMontage() {
        if (choisis.size < 2) { statut.text = "Cochez au moins 2 videos."; return }
        if (modeRythme) { montageRapide() } else { montageIntelligent() }
    }

    /** Montage rapide : decoupe rythme sans analyse (instantane). */
    private fun montageRapide() {
        statut.text = "Creation du montage..."
        val monteur = MonteurVideo(this)
        val t0 = System.currentTimeMillis()
        monteur.assembler(
            clips = choisis.toList(),
            dureeParClipSec = dureeParClip,
            position = position,
            onProgres = { pct -> runOnUiThread { statut.text = "Montage en cours... $pct%" } },
            onFini = { fichier -> runOnUiThread { finaliser(fichier, t0) } }
        )
    }

    /** Montage intelligent : analyse YOLO -> selection des meilleurs segments -> assemblage. */
    private fun montageIntelligent() {
        statut.text = "Analyse intelligente en cours (cela peut prendre une minute)..."
        val t0 = System.currentTimeMillis()
        Thread {
            // charger YOLO si pas deja fait
            if (!yoloPret) {
                try { yoloPret = yolo.loadModel(assets, 0, 0, 0) } catch (e: Exception) { yoloPret = false }
            }
            if (!yoloPret) {
                runOnUiThread { statut.text = "Impossible de charger l'IA (YOLO). Essayez le mode Rapide." }
                return@Thread
            }
            val classes = try { Reglages(this).classesPourSujet() } catch (e: Exception) { setOf(0) }
            val analyseur = AnalyseurMontage(this, yolo, classes)
            analyseur.analyser(
                clips = choisis.toList(),
                onProgres = { pct -> runOnUiThread { statut.text = "Analyse IA... $pct%" } },
                onFini = { resultats ->
                    // selection des segments selon le mode choisi
                    val segments = SelecteurSegments.choisir(
                        scores = resultats,
                        mode = mode,
                        dureeCibleSec = 30,
                        dureePclipSec = dureeParClip,
                        seuil = 0.45f
                    )
                    if (segments.isEmpty()) {
                        runOnUiThread { statut.text = "Aucun bon moment detecte. Essayez le mode Rapide ou Adaptatif." }
                        return@analyser
                    }
                    runOnUiThread { statut.text = "Assemblage de ${segments.size} segment(s)..." }
                    val monteur = MonteurVideo(this)
                    monteur.assemblerSegments(
                        segments = segments,
                        onProgres = { pct -> runOnUiThread { statut.text = "Montage IA... $pct%" } },
                        onFini = { fichier -> runOnUiThread { finaliser(fichier, t0) } }
                    )
                }
            )
        }.start()
    }

    /** Etapes communes apres creation : publier galerie + boutons Lire/Partager. */
    private fun finaliser(fichier: File?, t0: Long) {
        if (fichier == null) { statut.text = "Echec du montage (voir Logcat)."; return }
        val sec = (System.currentTimeMillis() - t0) / 1000
        statut.text = "Montage pret en ${sec}s ! Ajout a la galerie..."
        val monteur = MonteurVideo(this)
        Thread {
            val uriPub = monteur.publierDansGalerie(fichier)
            runOnUiThread {
                uriMontage = uriPub ?: androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", fichier)
                statut.text = if (uriPub != null) "Montage pret (${sec}s) et ajoute a votre galerie !" else "Montage pret en ${sec}s !"
                afficherBoutonsResultat(fichier)
            }
        }.start()
    }

'''

s = s[:debut] + nouveau + s[fin:]
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Etape 3 (pipeline creerMontage) OK")