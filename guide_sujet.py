# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "Que peut suivre le drone" in s:
    print("DEJA present"); raise SystemExit

anc = '''    <tr><td><strong>&#9675; MANUEL</strong> (gris)</td><td>Affiche quand le drone ne suit personne. Appuyer active le suivi.</td><td>Etat de repos. Le suivi s'active aussi en touchant une personne a l'ecran ou avec le tag Presentation.</td></tr>
  </table>'''

ajout = anc + '''

  <h3>Que peut suivre le drone</h3>
  <p>Par defaut, le drone suit <strong>les personnes</strong>. Cela couvre la plupart des situations : un marcheur, un sportif, un skieur, un cycliste ou un cavalier sont tous reconnus comme des personnes.</p>
  <p>Vous pouvez elargir ce que le drone cherche tout seul dans <strong>Reglages &rarr; Sujet a suivre</strong> :</p>
  <table>
    <tr><th>Mode</th><th>Le drone cherche</th></tr>
    <tr><td><strong>Personne</strong></td><td>Les personnes uniquement (par defaut).</td></tr>
    <tr><td><strong>Personne + Animal</strong></td><td>Personnes, mais aussi chiens, chats et chevaux.</td></tr>
    <tr><td><strong>Personne + Vehicule</strong></td><td>Personnes, mais aussi velos, voitures et motos.</td></tr>
    <tr><td><strong>Tout</strong></td><td>Personnes, animaux et vehicules.</td></tr>
  </table>
  <div class="tip"><strong>A savoir :</strong> peu importe ce reglage, vous pouvez toujours <strong>toucher n'importe quel sujet a l'ecran</strong> pour le designer comme cible. Le toucher fonctionne sur tout ce que le drone detecte.</div>
  <div class="warn"><strong>A garder en tete :</strong> le suivi est le plus fiable sur un sujet <strong>bien visible, vertical, a vitesse moderee et bien detache du decor</strong>. Un sujet tres rapide, sur fond uniforme (neige, eau), a contre-jour ou dans un sous-bois dense est plus difficile a suivre - le drone peut le perdre. Dans ce cas, il s'arrete et vous attend (il ne part jamais a l'aveugle).</div>'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Section 'Que peut suivre' ajoutee :", "Que peut suivre le drone" in s)
else:
    print("ANCRE NON TROUVEE")