f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# REEL : rthEnCours = rthActif  -> ajouter modele depuis base
a1 = "rthEnCours = rthActif"
if a1 in s and "modele = base.modeleDrone()" not in s:
    s = s.replace(a1, "rthEnCours = rthActif,\n            modele = base.modeleDrone()", 1); ch+=1

# SIMULE : ...rthEnCours = rthSim -> modele = "Simulateur"
a2 = "gimbalPitchDeg = gimbalPitchSim, rthEnCours = rthSim"
if a2 in s and 'modele = "Simulateur"' not in s:
    s = s.replace(a2, 'gimbalPitchDeg = gimbalPitchSim, rthEnCours = rthSim,\n            modele = "Simulateur"', 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("EtatCockpit modele rempli :", ch, "/ 2")