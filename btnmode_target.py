f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# remplacer le texte actif
a1 = 'btnMode.text = "\\u2715 STOP SUIVI"   // suivi actif -> ce bouton l\'arrete'
n1 = 'btnMode.text = "\\u25CF TARGET"   // suivi actif : le drone suit la cible'
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# remplacer le texte inactif
a2 = 'btnMode.text = "\\u25B6 SUIVRE"   // appuyer pour activer le suivi'
n2 = 'btnMode.text = "\\u25CB MANUEL"   // pas de suivi : pilotage manuel'
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Libelles TARGET/MANUEL :", ch, "/ 2")