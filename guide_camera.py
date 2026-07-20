# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "exposition (luminosite)" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) ajouter EV dans le tableau "Les outils" (apres TAGS)
a1 = '    <tr><td><strong>TAGS</strong></td><td>Ouvre l\'ecran des tags : les voir, les imprimer, gerer vos macros.</td></tr>'
n1 = a1 + '\n    <tr><td><strong>EV</strong></td><td>Ajuste l\'exposition (luminosite) de l\'image. Plus = plus clair (utile a contre-jour ou sur la neige), moins = plus sombre. A regler en vol selon la lumiere.</td></tr>'
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# 2) ajouter une sous-section Camera dans les reglages (avant "Sujet a suivre")
a2 = '  <h3>Sujet a suivre</h3>'
n2 = '''  <h3>Camera (resolution et fps)</h3>
  <p>Dans <strong>Reglages</strong>, choisissez avant le vol :</p>
  <table>
    <tr><th>Reglage</th><th>Options</th></tr>
    <tr><td><strong>Resolution</strong></td><td>FHD 1080p (leger), 2.7K, ou 4K (plus net, fichiers plus gros).</td></tr>
    <tr><td><strong>Images par seconde</strong></td><td>24 = rendu cinema, 30 = standard, 60 = tres fluide ou ralenti.</td></tr>
  </table>
  <div class="tip"><strong>A savoir :</strong> ces reglages se choisissent <strong>avant de decoller</strong>, pas pendant le vol. L'exposition (EV), elle, se regle en vol depuis le bouton EV en haut de l'ecran.</div>

  <h3>Sujet a suivre</h3>'''
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Guide EV + camera :", ch, "/ 2")