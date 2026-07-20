# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) corriger "+ Vehicule" + ajouter ligne Personnalise dans le tableau "Que peut suivre"
old1 = '''    <tr><td><strong>Personne + Vehicule</strong></td><td>Personnes, mais aussi velos, voitures et motos.</td></tr>
    <tr><td><strong>Tout</strong></td><td>Personnes, animaux et vehicules.</td></tr>
  </table>'''
new1 = '''    <tr><td><strong>Personne + Vehicule</strong></td><td>Personnes, mais aussi velos, voitures, motos, bus, camions et bateaux.</td></tr>
    <tr><td><strong>Tout</strong></td><td>Personnes, animaux et vehicules.</td></tr>
    <tr><td><strong>Personnalise</strong></td><td>Vous cochez exactement les types voulus (moto, bateau, velo...). Voir section 15.</td></tr>
  </table>'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch += 1
else:
    print("ANCRE 1 (suivre) NON TROUVEE")

# 2) PHOT : double-usage (court = photo, long = Capture Auto)
old2 = '    <tr><td><strong>PHOT</strong></td><td>Prend une photo.</td></tr>'
new2 = '    <tr><td><strong>PHOT</strong></td><td>Appui court : prend une photo. Appui <strong>long</strong> : active/desactive la Capture Auto Intelligente (section 12).</td></tr>'
if old2 in s:
    s = s.replace(old2, new2, 1); ch += 1
else:
    print("ANCRE 2 (PHOT) NON TROUVEE")

# 3) remplacer la ligne TAGS par engrenage + ajouter MAP
old3 = "    <tr><td><strong>TAGS</strong></td><td>Ouvre l'ecran des tags : les voir, les imprimer, gerer vos macros.</td></tr>"
new3 = '''    <tr><td><strong>&#9881; (engrenage)</strong></td><td>Ouvre les outils : tags (voir/imprimer), macros, reglages, et l'Album du drone (section 14).</td></tr>
    <tr><td><strong>MAP</strong></td><td>Affiche / cache la mini-carte sur l'ecran de vol. La fleche en coin l'ouvre en plein ecran (section 13).</td></tr>'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch += 1
else:
    print("ANCRE 3 (TAGS) NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Section 3 mise a jour :", ch, "/ 3")