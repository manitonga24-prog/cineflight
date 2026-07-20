# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
ch = 0

# titres <h2> : on decale 3->4 ... 9->10 pour les sections APRES "boutons"
# (on ne touche pas aux id=, juste au numero affiche)
remap = [
    ('<h2 id="commandes">3. Les trois facons de commander</h2>', '<h2 id="commandes">4. Les trois facons de commander</h2>'),
    ('<h2 id="tags">4. Les tags ArUco</h2>', '<h2 id="tags">5. Les tags ArUco</h2>'),
    ('<h2 id="macros">5. Creer ses propres macros</h2>', '<h2 id="macros">6. Creer ses propres macros</h2>'),
    ('<h2 id="chien">6. Le mode chien fidele</h2>', '<h2 id="chien">7. Le mode chien fidele</h2>'),
    ('<h2 id="securite">7. Securite</h2>', '<h2 id="securite">8. Securite</h2>'),
    ('<h2 id="depannage">8. Depannage</h2>', '<h2 id="depannage">9. Depannage</h2>'),
    ('<h2 id="recettes">9. Recettes de tournage</h2>', '<h2 id="recettes">10. Recettes de tournage</h2>'),
]
for old, new in remap:
    if old in s:
        s = s.replace(old, new, 1); ch+=1

# sommaire : memes decalages
remap_toc = [
    ('<a href="#commandes">3. Les trois facons de commander</a>', '<a href="#commandes">4. Les trois facons de commander</a>'),
    ('<a href="#tags">4. Les tags ArUco</a>', '<a href="#tags">5. Les tags ArUco</a>'),
    ('<a href="#macros">5. Creer ses propres macros</a>', '<a href="#macros">6. Creer ses propres macros</a>'),
    ('<a href="#chien">6. Le mode chien fidele</a>', '<a href="#chien">7. Le mode chien fidele</a>'),
    ('<a href="#securite">7. Securite</a>', '<a href="#securite">8. Securite</a>'),
    ('<a href="#depannage">8. Depannage</a>', '<a href="#depannage">9. Depannage</a>'),
    ('<a href="#recettes">9. Recettes de tournage</a>', '<a href="#recettes">10. Recettes de tournage</a>'),
]
for old, new in remap_toc:
    if old in s:
        s = s.replace(old, new, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Renumerotation :", ch, "/ 14")