f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()

import re
# trouver le debut de la section recettes et la fin (avant la signature finale)
debut = s.find('<h2 id="recettes">')
fin = s.find('  <p style="margin-top:32px;')
if debut == -1 or fin == -1:
    print("BORNES NON TROUVEES", debut, fin); raise SystemExit

# ajouter le CSS des fiches dans le <style> (avant la fermeture </style>)
css = """  .fiche { background:#fff; border-radius:14px; padding:16px 18px; margin:12px 0; border:0.5px solid #e5e5ea; }
  .fiche h3 { margin:0 0 10px; font-size:17px; color:#1c1c1e; }
  .fiche .lab { font-weight:600; color:#007aff; font-size:13px; }
  .fiche .row { font-size:13px; margin-bottom:7px; color:#3a3a3c; }
  .fiche .astuce { background:#e8f0fe; border-radius:8px; padding:8px 10px; font-size:12px; color:#1c3d6e; margin-top:8px; }
  .fiche .erreur { background:#fff1f0; border-radius:8px; padding:8px 10px; font-size:12px; color:#8a1f1a; margin-top:6px; }
"""
s = s.replace("  @media print", css + "  @media print", 1)

# retirer l'ancienne section recettes (on la reconstruira)
s = s[:debut] + "<!--RECETTES_ICI-->\n" + s[fin:]
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Anciennes recettes retirees, marqueur pose:", "<!--RECETTES_ICI-->" in s)