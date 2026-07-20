f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "import ca.cineflight.stage.control.Reglages" in s:
    print("DEJA present"); raise SystemExit
ch = 0
a1 = "import ca.cineflight.stage.control.Macros"
if a1 in s:
    s = s.replace(a1, a1 + "\nimport ca.cineflight.stage.control.Reglages", 1); ch+=1
a2 = "private val macros by lazy { Macros(this) }"
if a2 in s:
    s = s.replace(a2, a2 + "\n    val reglages by lazy { Reglages(this) }", 1); ch+=1
old_orb = '''            1 -> {
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
new_orb = '''            1 -> {
                val rayonCible = reglages.get(Reglages.ORBITE_RAYON)
                val hautCible = reglages.get(Reglages.ORBITE_HAUTEUR)
                val vitRot = reglages.get(Reglages.ORBITE_VITESSE)
                val distEstimee = if (h > 0.01f) calibK / h else rayonCible
                val errRayon = distEstimee - rayonCible
                vxMouv = (errRayon * 0.5f).coerceIn(-0.7f, 0.7f)
                val alt = pont.lireEtat(pilote.enVol).altitudeAgl
                if (!alt.isNaN()) {
                    val errAlt = (hautCible - alt).toFloat()
                    vzMouv = (errAlt * 0.4f).coerceIn(-0.5f, 0.5f)
                }
                vyMouv = vitRot
            }'''
if old_orb in s:
    s = s.replace(old_orb, new_orb, 1); ch+=1
else:
    print("ancre orbite KO")
old_trv = '''            2 -> { vyMouv = (errX * 1.0f).coerceIn(-0.6f, 0.6f) }  // TRAVEL : accompagne le sujet lateralement'''
new_trv = '''            2 -> { vyMouv = (errX * reglages.get(Reglages.TRAVEL_REACTIV)).coerceIn(-0.6f, 0.6f) }'''
if old_trv in s:
    s = s.replace(old_trv, new_trv, 1); ch+=1
else:
    print("ancre travel KO")
old_rev = '''            3 -> { vxMouv = (vx - 0.4f).coerceIn(-0.8f, 0.8f); vzMouv = 0.3f }  // REVELATION : recule + monte'''
new_rev = '''            3 -> { vxMouv = (vx - reglages.get(Reglages.REVEL_RECUL)).coerceIn(-0.8f, 0.8f); vzMouv = reglages.get(Reglages.REVEL_MONTEE) }'''
if old_rev in s:
    s = s.replace(old_rev, new_rev, 1); ch+=1
else:
    print("ancre revel KO")
old_app = '''            4 -> { vxMouv = 0.6f }                    // APPROCHE : avance vers le sujet'''
new_app = '''            4 -> { vxMouv = reglages.get(Reglages.APP_VITESSE) }'''
if old_app in s:
    s = s.replace(old_app, new_app, 1); ch+=1
else:
    print("ancre approche KO")
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Reglages branches :", ch, "/ 6")