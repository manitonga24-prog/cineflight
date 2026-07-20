# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if 'href="#cablecam"' in s:
    print("DEJA dans le sommaire"); raise SystemExit

anc = '    <a href="#montage">16. Montage automatique (IA)</a>'
ajout = anc + '\n    <a href="#cablecam">17. Rail Cable-Cam et Hyperlapse</a>'

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("lien sommaire 17 ajoute OK")
else:
    print("ANCRE NON TROUVEE")