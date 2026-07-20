# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
ch = 0

remap = [
    ('<h2 id="commandes">4. Les trois facons de commander</h2>', '<h2 id="commandes">5. Les trois facons de commander</h2>'),
    ('<h2 id="tags">5. Les tags ArUco</h2>', '<h2 id="tags">6. Les tags ArUco</h2>'),
    ('<h2 id="macros">6. Creer ses propres macros</h2>', '<h2 id="macros">7. Creer ses propres macros</h2>'),
    ('<h2 id="chien">7. Le mode chien fidele</h2>', '<h2 id="chien">8. Le mode chien fidele</h2>'),
    ('<h2 id="securite">8. Securite</h2>', '<h2 id="securite">9. Securite</h2>'),
    ('<h2 id="depannage">9. Depannage</h2>', '<h2 id="depannage">10. Depannage</h2>'),
    ('<h2 id="recettes">10. Recettes de tournage</h2>', '<h2 id="recettes">11. Recettes de tournage</h2>'),
]
for old, new in remap:
    if old in s:
        s = s.replace(old, new, 1); ch+=1

# sommaire : ajouter l'entree reglages + decaler les numeros
a_toc = '    <a href="#boutons">3. Tous les boutons de l\'ecran</a>'
if a_toc in s and 'href="#reglages"' not in s:
    s = s.replace(a_toc, a_toc + '\n    <a href="#reglages">4. Les reglages que vous pouvez ajuster</a>', 1); ch+=1

remap_toc = [
    ('<a href="#commandes">4. Les trois facons de commander</a>', '<a href="#commandes">5. Les trois facons de commander</a>'),
    ('<a href="#tags">5. Les tags ArUco</a>', '<a href="#tags">6. Les tags ArUco</a>'),
    ('<a href="#macros">6. Creer ses propres macros</a>', '<a href="#macros">7. Creer ses propres macros</a>'),
    ('<a href="#chien">7. Le mode chien fidele</a>', '<a href="#chien">8. Le mode chien fidele</a>'),
    ('<a href="#securite">8. Securite</a>', '<a href="#securite">9. Securite</a>'),
    ('<a href="#depannage">9. Depannage</a>', '<a href="#depannage">10. Depannage</a>'),
    ('<a href="#recettes">10. Recettes de tournage</a>', '<a href="#recettes">11. Recettes de tournage</a>'),
]
for old, new in remap_toc:
    if old in s:
        s = s.replace(old, new, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Renumerotation :", ch, "/ 15")