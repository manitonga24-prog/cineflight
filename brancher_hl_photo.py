# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "hyperlapse.tempsPourPhoto" in s:
    print("DEJA branche"); raise SystemExit

anc = '''                                        mode = "actif", recuA = System.currentTimeMillis(),
                                        gimbalYaw = if (v.size > 4) v[4] else 0f))'''

ajout = anc + '''
                                    // HYPERLAPSE : photo periodique pendant le glissement
                                    if (hyperlapse.tempsPourPhoto()) {
                                        pont.declencherPhoto()
                                        findViewById<Button>(R.id.btnPhoto)?.text = "\\uD83D\\uDCF8 ${hyperlapse.nbPhotos}"
                                    }'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("declenchement photo hyperlapse OK")
else:
    print("ANCRE NON TROUVEE")