# -*- coding: utf-8 -*-
import os

# 1) supprimer le fichier TestMontageActivity.kt
kt = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\TestMontageActivity.kt"
if os.path.exists(kt):
    os.remove(kt); print("TestMontageActivity.kt supprime")
else:
    print("fichier deja absent")

# 2) retirer la ligne du Manifest
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
lignes = open(fm, encoding="utf-8").read().split("\n")
avant = len(lignes)
lignes = [l for l in lignes if "TestMontageActivity" not in l]
open(fm, "w", encoding="utf-8", newline="\n").write("\n".join(lignes))
print("lignes Manifest retirees :", avant - len(lignes))