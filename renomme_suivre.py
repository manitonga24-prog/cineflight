f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0
# Texte du bouton en mode auto -> "SUIVI ON"
old1 = 'btnMode.text = "\\uD83E\\uDD16"   // AUTO (bridge)'
new1 = 'btnMode.text = "SUIVI"   // suivi actif'
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1
# Texte du bouton en mode manuel -> "SUIVRE"
old2 = 'btnMode.text = "\\uD83D\\uDD79\\uFE0F"   // MANUEL (sticks RC)'
new2 = 'btnMode.text = "SUIVRE"   // appuyer pour activer le suivi'
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Renommage SUIVRE :", ch, "/ 2")