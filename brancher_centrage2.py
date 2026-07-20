# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
lignes = open(f, encoding="utf-8").read().split("\n")
if any("centrer sur le telephone (preparer" in l for l in lignes):
    print("DEJA present"); raise SystemExit

# trouver la ligne "marqueurDecollage = m" DANS placerDecollage (la 1ere occurrence)
idx = None
for i, l in enumerate(lignes):
    if l.strip() == "marqueurDecollage = m":
        idx = i
        break
if idx is None:
    print("marqueurDecollage = m NON TROUVE"); raise SystemExit

# apres cette ligne il y a "        }" puis "    }" (fin du if, fin de la methode)
# on verifie la structure
print("contexte:")
for j in range(idx, idx+4):
    print(f"  {j+1}: {repr(lignes[j])}")

# inserer apres marqueurDecollage = m : centrage + else
# lignes[idx] = "            marqueurDecollage = m"
# lignes[idx+1] = "        }"   (ferme le if)
# lignes[idx+2] = "    }"       (ferme la methode)
bloc = [
    "            carte.controller.setCenter(GeoPoint(lat, lon))",
    "        } else {",
    "            // pas de position drone : centrer sur le telephone (preparer une carte hors ligne)",
    "            centrerSurTelephone()"
]
# remplacer la ligne idx+1 (le "}" du if) par notre bloc (qui contient le nouveau } else { ... )
if lignes[idx+1].strip() == "}":
    lignes[idx+1:idx+1] = bloc   # inserer le bloc juste avant le "}" existant... 
    # mais on veut remplacer le } simple par "} else { ... }" : on retire l'ancien } et on le recree
    # plus simple : inserer le bloc AVANT le } existant ne marche pas. On reconstruit :
    # retirer l'insertion qu'on vient de faire
    del lignes[idx+1:idx+1+len(bloc)]
    # nouvelle approche : remplacer lignes[idx+1] ("}") par le bloc complet incluant la fermeture
    lignes[idx+1] = "\n".join([
        "            carte.controller.setCenter(GeoPoint(lat, lon))",
        "        } else {",
        "            // pas de position drone : centrer sur le telephone (preparer une carte hors ligne)",
        "            centrerSurTelephone()",
        "        }"
    ])
    open(f, "w", encoding="utf-8", newline="\n").write("\n".join(lignes))
    print("OK insere")
else:
    print(f"structure inattendue ligne {idx+2}: {repr(lignes[idx+1])}")