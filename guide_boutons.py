# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if 'id="boutons"' in s:
    print("DEJA present"); raise SystemExit

anc = '  <h2 id="commandes">3. Les trois facons de commander</h2>'

sect = '''  <h2 id="boutons">3. Tous les boutons de l'ecran</h2>
  <p>Voici chaque bouton de l'interface de vol, regroupe par fonction. Prenez le temps de les reperer avant le premier vol.</p>

  <div class="warn"><strong>Les deux boutons a connaitre par coeur :</strong><br>
  <strong>STOP</strong> (rouge) - arret d'urgence : coupe immediatement le suivi automatique et stabilise le drone sur place. Le reflexe des qu'une situation vous inquiete.<br>
  <strong>RTH</strong> (jaune) - retour maison : le drone revient se poser tout seul au point de decollage. A utiliser si vous avez un doute, si le sujet est perdu, ou pour rappeler le drone.</div>

  <h3>Le vol</h3>
  <table>
    <tr><th>Bouton</th><th>Ce qu'il fait</th><th>Quand l'utiliser</th></tr>
    <tr><td><strong>DEC</strong></td><td>Fait decoller le drone, qui monte et reste en vol stationnaire.</td><td>Au debut, une fois le drone connecte.</td></tr>
    <tr><td><strong>ATT</strong></td><td>Fait atterrir le drone a l'endroit ou il se trouve.</td><td>Pour poser le drone a la fin, en zone degagee.</td></tr>
    <tr><td><strong>RTH</strong> (jaune)</td><td>Retour automatique au point de decollage, puis atterrissage.</td><td>En cas de doute, sujet perdu, ou pour rappeler le drone.</td></tr>
    <tr><td><strong>STOP</strong> (rouge)</td><td>Arret d'urgence : coupe le suivi auto et stabilise sur place.</td><td>Des qu'une situation vous inquiete. Le reflexe de securite.</td></tr>
  </table>

  <h3>Le suivi</h3>
  <table>
    <tr><th>Bouton</th><th>Ce qu'il fait</th><th>Quand l'utiliser</th></tr>
    <tr><td><strong>&#9679; TARGET</strong> (orange)</td><td>Affiche quand le drone suit la cible automatiquement. Appuyer arrete le suivi.</td><td>Apparait quand le suivi est actif. Appuyez pour repasser en manuel.</td></tr>
    <tr><td><strong>&#9675; MANUEL</strong> (gris)</td><td>Affiche quand le drone ne suit personne. Appuyer active le suivi.</td><td>Etat de repos. Le suivi s'active aussi en touchant une personne a l'ecran ou avec le tag Presentation.</td></tr>
  </table>

  <h3>Le cadrage (taille de la personne a l'image)</h3>
  <table>
    <tr><th>Bouton</th><th>Cadrage</th></tr>
    <tr><td><strong>GP</strong></td><td>Gros plan - le visage et les epaules.</td></tr>
    <tr><td><strong>AM</strong></td><td>Plan americain - des cuisses a la tete.</td></tr>
    <tr><td><strong>PD</strong></td><td>Plan pied - la personne en entier.</td></tr>
    <tr><td><strong>ENS</strong></td><td>Plan d'ensemble - la personne et tout le decor.</td></tr>
  </table>

  <h3>Les mouvements</h3>
  <table>
    <tr><th>Bouton</th><th>Mouvement</th></tr>
    <tr><td><strong>&#9635;</strong></td><td>Statique - le drone reste fixe et vous garde cadre.</td></tr>
    <tr><td><strong>&#9678;</strong></td><td>Orbite - le drone tourne autour de vous.</td></tr>
    <tr><td><strong>&#8596;</strong></td><td>Travelling - le drone vous accompagne lateralement.</td></tr>
    <tr><td><strong>&#8593;F</strong></td><td>Approche - le drone se rapproche doucement de vous.</td></tr>
    <tr><td><strong>&#10530;</strong></td><td>Revelation - le drone recule et monte pour devoiler le decor.</td></tr>
  </table>

  <h3>La camera</h3>
  <table>
    <tr><th>Bouton</th><th>Ce qu'il fait</th></tr>
    <tr><td><strong>PHOT</strong></td><td>Prend une photo.</td></tr>
    <tr><td><strong>REC</strong></td><td>Demarre / arrete l'enregistrement video.</td></tr>
    <tr><td><strong>RTSP</strong></td><td>Diffuse le flux video en direct (pour un ecran ou un live).</td></tr>
    <tr><td><strong>Fleches gimbal</strong></td><td>Inclinent la camera vers le haut ou le bas.</td></tr>
  </table>

  <h3>Les outils</h3>
  <table>
    <tr><th>Bouton</th><th>Ce qu'il fait</th></tr>
    <tr><td><strong>VOIX</strong></td><td>Active la commande vocale (dites "suivi", "orbite", "stop"...).</td></tr>
    <tr><td><strong>TAGS</strong></td><td>Ouvre l'ecran des tags : les voir, les imprimer, gerer vos macros.</td></tr>
    <tr><td><strong>?</strong></td><td>Ouvre ce guide.</td></tr>
  </table>

  <div class="tip"><strong>A savoir :</strong> les boutons de macros que vous creez apparaissent en violet, sous les mouvements, avec le nom que vous leur donnez. Un appui lance la sequence.</div>

''' + anc

if anc in s:
    s = s.replace(anc, sect, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Section boutons ajoutee :", 'id="boutons"' in s)
else:
    print("ANCRE NON TROUVEE")