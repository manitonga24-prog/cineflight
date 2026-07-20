# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "Spotlight" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) ajouter la ligne spotlight dans le tableau des mouvements (apres Revelation)
a1 = '    <tr><td><strong>&#10530;</strong></td><td>Revelation - le drone recule et monte pour devoiler le decor.</td></tr>'
n1 = a1 + '\n    <tr><td><strong>&#9673;</strong></td><td>Spotlight - le drone reste sur place, <strong>vous pilotez vous-meme</strong>, la camera garde le sujet cadre.</td></tr>'
if a1 in s:
    s = s.replace(a1, n1, 1); ch+=1

# 2) ajouter un encadre explicatif juste apres le tableau des mouvements (avant <h3>La camera</h3>)
a2 = '  <h3>La camera</h3>'
n2 = '''  <div class="tip"><strong>Le mode Spotlight (&#9673;) est special :</strong> contrairement aux autres mouvements ou le drone se deplace tout seul, ici <strong>c'est vous qui pilotez</strong> avec la manette. Le drone ne bouge pas de lui-meme - il oriente seulement sa camera pour garder le sujet dans l'image. Ideal pour composer votre propre mouvement de vol tout en gardant le sujet cadre, comme les professionnels.</div>

  <h3>La camera</h3>'''
if a2 in s:
    s = s.replace(a2, n2, 1); ch+=1

# 3) ajouter Spotlight dans la section reglages (apres "Suivi general" - avant "Sujet a suivre")
a3 = '  <h3>Sujet a suivre</h3>'
n3 = '''  <h3>Spotlight (vous pilotez)</h3>
  <p>Le drone reste sur place et vous laisse piloter a la manette. La camera suit le sujet automatiquement pour le garder cadre. Aucun reglage de vitesse : c'est vous qui controlez le mouvement du drone.</p>

  <h3>Sujet a suivre</h3>'''
if a3 in s:
    s = s.replace(a3, n3, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Guide spotlight :", ch, "/ 3")