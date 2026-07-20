# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "finMouvement" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) enrichir l'appel : passer les vitesses de mouvement
old1 = "if (captureAutoActive) try { evaluerBeauMoment(errX, errY, h) } catch (_: Exception) {}"
new1 = "if (captureAutoActive) try { evaluerBeauMoment(errX, errY, h, vxMouv, vyMouv, vzMouv, yawMouv) } catch (_: Exception) {}"
if old1 in s:
    s = s.replace(old1, new1, 1); ch += 1
else:
    print("ANCRE 1 (appel) NON TROUVEE")

# 2) nouvelle signature + critere fin de mouvement
old2 = "    private fun evaluerBeauMoment(errX: Float, errY: Float, h: Float) {"
new2 = "    private fun evaluerBeauMoment(errX: Float, errY: Float, h: Float, vx: Float, vy: Float, vz: Float, yaw: Float) {"
if old2 in s:
    s = s.replace(old2, new2, 1); ch += 1
else:
    print("ANCRE 2 (signature) NON TROUVEE")

# 3) ajouter le critere : beau moment AUSSI en fin de mouvement cinematique
old3 = '''        if (centreOk && tailleOk) {
            pont.declencherPhoto()'''
new3 = '''        // fin de mouvement : un mouvement cinematique (orbite/revelation/approche)
        // qui se stabilise (vitesses quasi nulles) = souvent le plus beau cadre
        val enMouvementCine = mouvementActuel == 1 || mouvementActuel == 3 || mouvementActuel == 4
        val vitesseFaible = kotlin.math.abs(vx) < 0.08f && kotlin.math.abs(vy) < 0.08f &&
                            kotlin.math.abs(vz) < 0.08f && kotlin.math.abs(yaw) < 4f
        val finMouvement = enMouvementCine && vitesseFaible && kotlin.math.abs(errX) < 0.20f && tailleOk
        if ((centreOk && tailleOk) || finMouvement) {
            pont.declencherPhoto()'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch += 1
else:
    print("ANCRE 3 (critere) NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Critere fin de mouvement :", ch, "/ 3")