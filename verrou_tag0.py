f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# Tag 0 : verrouille la cible EN PLUS de passer en mode presentation
old = '''            0 -> { findViewById<Button>(R.id.btnMouvStatique).performClick(); "PRESENTATION" }'''
new = '''            0 -> { verrouillerCible(); findViewById<Button>(R.id.btnMouvStatique).performClick(); "CIBLE + PRESENTATION" }'''
if old in s:
    s = s.replace(old, new, 1); ch+=1
else:
    print("ancre Tag0 non trouvee")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Verrou cible sur Tag 0 :", ch, "/ 1")