f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "ancreLat" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) declarations du point d'ancrage GPS
a1 = "private var cibleVerrouillee = false   // le drone ne suit QUE si une cible est verrouillee"
if a1 in s:
    s = s.replace(a1, a1 + '''
    // --- Point d'ancrage GPS facon "chien fidele" ---
    private var ancreLat = Double.NaN      // position du drone quand il voit bien la cible
    private var ancreLon = Double.NaN
    private var ancreValide = false
    private val SEUIL_BIEN_VU = 0.30f      // hauteur de boite mini pour considerer "bien vu"''', 1); ch+=1

# 2) dans onSujet : quand bien vu + gps valide, memoriser l'ancre
#    on insere juste apres le suivi (dans le bloc trouve && modeAuto && cibleVerrouillee)
a2 = '''                        if (trouve && modeAuto && cibleVerrouillee) {
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))'''
new2 = '''                        if (trouve && modeAuto && cibleVerrouillee) {
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))
                            // ancrage GPS : si la cible est BIEN vue (assez grande) et GPS fiable,
                            // memoriser la position du drone (le point d'ou il voit bien la cible)
                            if (h >= SEUIL_BIEN_VU) {
                                val e = pont.lireEtat(pilote.enVol)
                                if (e.gpsValide) {
                                    ancreLat = e.latitude
                                    ancreLon = e.longitude
                                    ancreValide = true
                                }
                            }'''
if a2 in s:
    s = s.replace(a2, new2, 1); ch+=1
else:
    print("ancre onSujet non trouvee")

# 3) affichage de debug du point d'ancrage (temporaire, pour verifier)
#    on l'ajoute dans le verrouillerCible pour voir l'ancre courante
a3 = '''        txtCommandeVoc.text = "\\uD83D\\uDD12 CIBLE VERROUILLEE"'''
new3 = '''        val ancreTxt = if (ancreValide) " | ancre %.5f,%.5f".format(ancreLat, ancreLon) else " | pas d'ancre"
        txtCommandeVoc.text = "\\uD83D\\uDD12 CIBLE VERROUILLEE" + ancreTxt'''
if a3 in s:
    s = s.replace(a3, new3, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Ancrage GPS :", ch, "/ 3")