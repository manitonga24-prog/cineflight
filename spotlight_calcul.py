# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "// SPOTLIGHT" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# ajouter le cas 5 (spotlight) dans le when, apres le cas 4
a1 = "            4 -> { vxMouv = reglages.get(Reglages.APP_VITESSE) }"
n1 = '''            4 -> { vxMouv = reglages.get(Reglages.APP_VITESSE) }
            5 -> {
                // SPOTLIGHT : le pilote vole, le drone NE bouge PAS tout seul.
                // Seule la camera (gimbal) suit le sujet pour le garder cadre.
                vxMouv = 0f; vyMouv = 0f; vzMouv = 0f; yawMouv = 0f
            }'''
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# en spotlight, recentrage horizontal par gimbalYaw (pas par yaw drone)
a2 = '''            gimbalPitch = gimbalPitch, gimbalYaw = 0f
        )
    }'''
n2 = '''            gimbalPitch = gimbalPitch,
            gimbalYaw = if (mouvementActuel == 5) (errX * 25f).coerceIn(-20f, 20f) else 0f
        )
    }'''
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Brique 1 spotlight (calculerSuivi) :", ch, "/ 2")