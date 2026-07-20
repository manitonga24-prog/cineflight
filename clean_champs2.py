# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
lignes = open(f, encoding="utf-8").read().split("\n")

vues = set()
sortie = []
supprimees = 0
for ln in lignes:
    t = ln.strip()
    # cibler uniquement les declarations de champs problematiques
    cible = t in (
        "private var blocDuree: View? = null",
        "private var blocPosition: View? = null",
        "private var descMode: TextView? = null",
        "private var champNom: EditText? = null",
    )
    if cible:
        if t in ("private var blocDuree: View? = null", "private var blocPosition: View? = null"):
            # inutiles : supprimer toutes les occurrences
            supprimees += 1
            continue
        if t in vues:
            # doublon de descMode/champNom : supprimer
            supprimees += 1
            continue
        vues.add(t)
    sortie.append(ln)

open(f, "w", encoding="utf-8", newline="\n").write("\n".join(sortie))
print("lignes champs supprimees :", supprimees)