f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "orbiteHauteur" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) declarations : hauteur cible, rayon cible, constante de calibration distance
a1 = "private var mouvementActuel = 0   // 0=statik 1=orbite 2=travel 3=revel"
if a1 in s:
    s = s.replace(a1, a1 + '''
    // --- Orbite reglable (hauteur fiable via altitude AGL, rayon estime via taille image) ---
    private var orbiteHauteur = 3.0f   // metres AGL cible pendant l'orbite
    private var orbiteRayon = 6.0f     // metres : distance drone-sujet cible
    private var calibK = 1.7f          // constante distance = calibK / h (calibree terrain)''', 1); ch+=1

# 2) ameliorer le cas orbite (mouvementActuel == 1) dans calculerSuivi
#    on a besoin de l'altitude courante -> on lit l'etat
a2 = '''        when (mouvementActuel) {
            1 -> { vyMouv = 0.6f }'''
new2 = '''        when (mouvementActuel) {
            1 -> {
                // ORBITE REGLABLE
                // rayon : distance estimee = calibK / h, on avance/recule pour viser orbiteRayon
                val distEstimee = if (h > 0.01f) calibK / h else orbiteRayon
                val errRayon = distEstimee - orbiteRayon   // >0 = trop loin -> avancer
                vxMouv = (errRayon * 0.5f).coerceIn(-0.7f, 0.7f)
                // hauteur : comparer altitude AGL a orbiteHauteur
                val alt = pont.lireEtat(pilote.enVol).altitudeAgl
                if (!alt.isNaN()) {
                    val errAlt = (orbiteHauteur - alt).toFloat()
                    vzMouv = (errAlt * 0.4f).coerceIn(-0.5f, 0.5f)
                }
                // rotation autour du sujet : translation laterale + yaw recentrage (deja calcule)
                vyMouv = 0.6f
            }'''
if a2 in s:
    s = s.replace(a2, new2, 1); ch+=1
else:
    print("ancre orbite non trouvee - forme differente")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Orbite reglable :", ch, "/ 2")