# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "evaluerBeauMoment" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) proprietes apres "private var miniVisible = false"
a1 = "    private var miniVisible = false"
if a1 in s:
    s = s.replace(a1, a1 + '''
    // Capture Auto Intelligente : photo auto quand le sujet est bien compose
    private var captureAutoActive = false
    private var dernierePhotoAutoMs = 0L
    private var compteurPhotosAuto = 0''', 1); ch += 1

# 2) appel a l'evaluation juste avant le return CommandeBridge
a2 = "        return RecepteurBridge.CommandeBridge(\n            t = System.currentTimeMillis() / 1000.0,"
if a2 in s:
    s = s.replace(a2, "        // Capture Auto Intelligente : evalue si c'est un beau moment\n        if (captureAutoActive) try { evaluerBeauMoment(errX, errY, h) } catch (_: Exception) {}\n" + a2, 1); ch += 1

# 3) fonction evaluerBeauMoment, avant executerCommandeVocale
a3 = "    // Execute une commande vocale reconnue (mappe vers SUIVRE / mouvements / plan)"
fct = '''    /** Capture Auto Intelligente : declenche une photo si le sujet est bien compose.
     *  Beau moment = sujet centre (errX, errY petits) + taille de boite correcte + delai respecte. */
    private fun evaluerBeauMoment(errX: Float, errY: Float, h: Float) {
        // pas pendant un enregistrement video
        if (pont.lireEtat(pilote.enVol).enregistre) return
        // delai minimum entre deux photos auto (3 s) pour ne pas mitrailler
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierePhotoAutoMs < 3000L) return
        // criteres de belle composition
        val centreOk = kotlin.math.abs(errX) < 0.12f && kotlin.math.abs(errY) < 0.15f
        // taille de sujet correcte : ni trop petit (loin) ni trop gros (trop pres)
        val tailleOk = h in 0.25f..0.75f
        if (centreOk && tailleOk) {
            pont.declencherPhoto()
            dernierePhotoAutoMs = maintenant
            compteurPhotosAuto++
            runOnUiThread { try { flashCaptureAuto() } catch (_: Exception) {} }
        }
    }
    private fun flashCaptureAuto() {
        // petit retour visuel : le bouton AUTO clignote + compteur
        val b = findViewById<Button>(R.id.btnCaptureAuto)
        b?.text = "\\uD83D\\uDCF8 $compteurPhotosAuto"
    }
    // Execute une commande vocale reconnue (mappe vers SUIVRE / mouvements / plan)'''
if a3 in s:
    s = s.replace(a3, fct, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique Capture Auto :", ch, "/ 3")