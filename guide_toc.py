# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if 'href="#boutons"' in s:
    print("DEJA dans le sommaire"); raise SystemExit
anc = '    <a href="#demarrage">2. Mise en route (pas a pas)</a>'
new = anc + '\n    <a href="#boutons">3. Tous les boutons de l\'ecran</a>'
if anc in s:
    s = s.replace(anc, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Sommaire : entree boutons ajoutee")
else:
    print("ANCRE NON TROUVEE")