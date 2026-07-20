# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "dernierCxSujet" in s:
    print("DEJA present"); raise SystemExit

# 1) champs de memorisation du dernier sujet, a cote de hyperlapseArme
anc1 = "    private var hyperlapseArme = false   // mode hyperlapse arme pour le prochain rail"
s = s.replace(anc1, anc1 + '''
    @Volatile private var dernierCxSujet = 0.5f   // derniere position horizontale du sujet (0..1)
    @Volatile private var dernierSujetTrouve = false''', 1)

# 2) memoriser le sujet a chaque frame, tout en haut du runOnUiThread
anc2 = '''                onSujet = { trouve, cx, cy, w, h ->
                    runOnUiThread {
                        // === CABLE-CAM : si un rail est en cours, il prend le controle (ignore le suivi) ==='''
neuf2 = '''                onSujet = { trouve, cx, cy, w, h ->
                    runOnUiThread {
                        // memorise le dernier sujet vu (pour le bouton "Rail vers sujet")
                        dernierSujetTrouve = trouve
                        if (trouve) dernierCxSujet = cx
                        // === CABLE-CAM : si un rail est en cours, il prend le controle (ignore le suivi) ==='''
s = s.replace(anc2, neuf2, 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("memorisation sujet ajoutee OK")