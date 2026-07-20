f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0
old = 'vEtat.text = "Drone connecte"'
new = 'vEtat.text = pont.lireEtat(pilote.enVol).modele.let { if (it.isNotEmpty() && it != "Drone connecte" && it != "Simulateur") "$it connecte" else "Drone connecte" }'
if old in s:
    s = s.replace(old, new, 1); ch+=1
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Affichage modele :", ch)