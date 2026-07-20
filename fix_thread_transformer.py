# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()

old = '''                    runOnUiThread { statut.text = "Assemblage de ${segments.size} segment(s)..." }
                    val monteur = MonteurVideo(this)
                    monteur.assemblerSegments(
                        segments = segments,
                        onProgres = { pct -> runOnUiThread { statut.text = "Montage IA... $pct%" } },
                        onFini = { fichier -> runOnUiThread { finaliser(fichier, t0) } }
                    )'''
new = '''                    runOnUiThread {
                        statut.text = "Assemblage de ${segments.size} segment(s)..."
                        // Transformer DOIT etre lance sur le thread principal
                        val monteur = MonteurVideo(this)
                        monteur.assemblerSegments(
                            segments = segments,
                            onProgres = { pct -> runOnUiThread { statut.text = "Montage IA... $pct%" } },
                            onFini = { fichier -> runOnUiThread { finaliser(fichier, t0) } }
                        )
                    }'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Fix thread Transformer OK")
else:
    print("ANCRE NON TROUVEE")