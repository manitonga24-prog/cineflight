f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Reglages.kt"
s = open(f, encoding="utf-8").read()
if "val aide:" in s:
    print("DEJA present"); raise SystemExit
ch = 0

old = '''    data class Param(val cle: String, val nom: String, val unite: String,
                     val defaut: Float, val min: Float, val max: Float, val pas: Float)'''
new = '''    data class Param(val cle: String, val nom: String, val unite: String,
                     val defaut: Float, val min: Float, val max: Float, val pas: Float,
                     val aide: String = "")'''
if old in s:
    s = s.replace(old, new, 1); ch+=1

remplacements = [
    ('Param("orb_h", "Hauteur", "m", 3f, 2f, 30f, 0.5f)',
     'Param("orb_h", "Hauteur", "m", 3f, 2f, 30f, 0.5f, "Hauteur du drone pendant orbite. Plus haut = vue plongeante, plus de decor. Plus bas = plan a hauteur oeil, plus intime.")'),
    ('Param("orb_r", "Rayon", "m", 6f, 3f, 15f, 0.5f)',
     'Param("orb_r", "Rayon", "m", 6f, 3f, 15f, 0.5f, "Distance entre le drone et vous. Grand rayon = orbite large, fond lent, majestueux. Petit rayon = drone proche, fond rapide, dynamique mais peut donner le tournis.")'),
    ('Param("orb_v", "Vitesse rotation", "m/s", 0.6f, 0.2f, 1.5f, 0.1f)',
     'Param("orb_v", "Vitesse rotation", "m/s", 0.6f, 0.2f, 1.5f, 0.1f, "Vitesse de rotation autour de vous. Lente = tres cinematique et stable. Rapide = energique mais image precipitee.")'),
    ('Param("trv_d", "Distance suivi", "m", 6f, 3f, 15f, 0.5f)',
     'Param("trv_d", "Distance suivi", "m", 6f, 3f, 15f, 0.5f, "Distance de suivi. Grande = plan large, vous situe dans le decor. Petite = plan serre, plus intime mais moins de marge.")'),
    ('Param("trv_h", "Hauteur", "m", 3f, 2f, 30f, 0.5f)',
     'Param("trv_h", "Hauteur", "m", 3f, 2f, 30f, 0.5f, "Hauteur pendant le suivi. Plus haut = vue ensemble du parcours. Plus bas = sensation de vitesse et de proximite.")'),
    ('Param("trv_x", "Reactivite laterale", "", 1.0f, 0.3f, 2.0f, 0.1f)',
     'Param("trv_x", "Reactivite laterale", "", 1.0f, 0.3f, 2.0f, 0.1f, "Suivi de vos mouvements de cote. Eleve = colle a vos deplacements. Faible = suivi doux et fluide.")'),
    ('Param("rev_r", "Vitesse recul", "m/s", 0.4f, 0.2f, 1.2f, 0.1f)',
     'Param("rev_r", "Vitesse recul", "m/s", 0.4f, 0.2f, 1.2f, 0.1f, "Vitesse de recul pour reveler la scene. Lente = revelation progressive et dramatique. Rapide = devoilement express.")'),
    ('Param("rev_m", "Vitesse montee", "m/s", 0.3f, 0.1f, 1.0f, 0.1f)',
     'Param("rev_m", "Vitesse montee", "m/s", 0.3f, 0.1f, 1.0f, 0.1f, "Vitesse de montee pendant la revelation. Elevee = on prend de la hauteur vite, grandiose. Faible = montee douce.")'),
    ('Param("app_v", "Vitesse approche", "m/s", 0.6f, 0.2f, 1.2f, 0.1f)',
     'Param("app_v", "Vitesse approche", "m/s", 0.6f, 0.2f, 1.2f, 0.1f, "Vitesse approche vers vous. Lente = maitrisee et securitaire. Rapide = impact dramatique, prevoyez de espace.")'),
    ('Param("app_d", "Distance d\\u0027arret", "m", 3f, 2f, 10f, 0.5f)',
     'XXX_APP_D'),
    ('Param("sui_g", "Douceur recentrage", "", 1.0f, 0.5f, 2.0f, 0.1f)',
     'Param("sui_g", "Douceur recentrage", "", 1.0f, 0.5f, 2.0f, 0.1f, "Vitesse de correction pour vous garder centre. Eleve = recentrage vif mais image saccadee. Faible = mouvements doux et cinematiques.")'),
]
for old_p, new_p in remplacements:
    if old_p in s:
        s = s.replace(old_p, new_p, 1); ch+=1
    else:
        print("non trouve:", old_p[:40])

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Aides ajoutees :", ch)