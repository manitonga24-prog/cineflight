f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
old = '''                            pilote.soumettre(RecepteurBridge.CommandeBridge(
                                System.currentTimeMillis(), 0f, 0f, 0f, 0f, "actif", false, 0f, 0f))'''
new = '''                            pilote.soumettre(RecepteurBridge.CommandeBridge(
                                System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))'''
if old in s:
    s = s.replace(old, new, 1)
    print("Appel hover corrige")
else:
    print("ANCRE NON TROUVEE")
open(f, "w", encoding="utf-8", newline="\n").write(s)