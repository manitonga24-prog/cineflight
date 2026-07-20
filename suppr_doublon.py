# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
lignes = open(f, encoding="utf-8").read().split("\n")
# reperer la ligne du doublon vide (override ... {}) APRES la vraie methode
cibles = [i for i,l in enumerate(lignes) if l.strip() == "override fun reglerResolutionFps(resNom: String, fps: Int) {}"]
print("Doublons vides trouves aux lignes (1-index):", [c+1 for c in cibles])
if len(cibles) == 1:
    del lignes[cibles[0]]
    open(f, "w", encoding="utf-8", newline="\n").write("\n".join(lignes))
    print("Ligne doublon supprimee OK")
elif len(cibles) == 0:
    print("AUCUN doublon vide - deja propre")
else:
    print("PLUSIEURS doublons - intervention manuelle requise, ne touche a rien")