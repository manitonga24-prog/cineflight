# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
lignes = open(f, encoding="utf-8").read().split("\n")

# verifier que la ligne 189 (index 188) est bien le 2e titreSection
# index 0-base : ligne 188 = index 187
if "titreSection" not in lignes[188]:
    print("VERIF ECHOUEE - ligne 189 n'est pas titreSection, voici:", repr(lignes[188])); raise SystemExit

# supprimer lignes 188 a 211 (1-base) = index 187 a 210 inclus
# on garde tout avant index 187 et tout a partir de index 211
nouvelles = lignes[:187] + lignes[211:]
open(f, "w", encoding="utf-8", newline="\n").write("\n".join(nouvelles))
print("doublon supprime - lignes 188-211 retirees")
# verif : compter titreSection restants
contenu = "\n".join(nouvelles)
print("titreSection restants :", contenu.count("private fun titreSection"))
print("carte restants :", contenu.count("private fun carte"))