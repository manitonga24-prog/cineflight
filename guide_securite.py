f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()

# remplacer la liste actuelle de la section Securite par les 10 regles
old = """  <h2 id="securite">7. Securite</h2>
  <ul>
    <li>Volez dans un espace degage, loin des personnes et des obstacles, surtout pour les premiers essais.</li>
    <li>Gardez la telecommande a portee pour reprendre le controle manuel a tout moment.</li>
    <li>Connaissez le tag Stop (10) et la commande "CineFlight stop" : ils arretent tout.</li>
    <li>Respectez la reglementation locale sur les drones (altitude, zones, vol a vue).</li>
    <li>Surveillez la batterie ; prevoyez la marge pour le retour et l'atterrissage.</li>
  </ul>"""

new = """  <h2 id="securite">7. Securite</h2>
  <div class="warn"><strong>Regle d'or : ne JAMAIS voler au-dessus des personnes.</strong> Le drone passe a cote, jamais au-dessus - ni de vous, ni des spectateurs. C'est une regle de securite et une obligation legale.</div>
  <ol>
    <li><strong>Jamais au-dessus des personnes</strong> - ni le sujet filme, ni les spectateurs. Le drone passe a cote.</li>
    <li><strong>Distance horizontale de securite</strong> avec toute personne non impliquee dans le tournage.</li>
    <li><strong>Le sujet reste sur le cote</strong> : quand le drone vous suit ou vous approche, il vous cadre de face ou de cote, jamais en passant au-dessus de votre tete.</li>
    <li><strong>Espace degage obligatoire</strong> pour le suivi automatique et les macros : pas de public, pas de passants dans la zone.</li>
    <li><strong>Orbite et approche a bonne distance</strong> : a une hauteur ou, en cas de chute, le drone ne toucherait personne.</li>
    <li><strong>Mode chien fidele</strong> (retour GPS) : a n'utiliser que dans une zone sans monde, car le drone se deplace seul.</li>
    <li><strong>Gardez le drone a vue</strong> en permanence (obligation legale au Canada).</li>
    <li><strong>STOP accessible</strong> a tout instant : tag 10 ou "CineFlight stop".</li>
    <li><strong>Verifiez la reglementation locale</strong> (Transports Canada : zones, altitude maximale, distance des gens).</li>
    <li><strong>Batterie avec marge</strong> pour ne jamais finir en vol force ; prevoyez le retour et l'atterrissage.</li>
  </ol>"""

if old in s:
    s = s.replace(old, new, 1)
    print("Section Securite enrichie (10 regles)")
else:
    print("ANCRE NON TROUVEE")
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("regle d'or presente:", "Regle d'or" in s)