# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if 'id="reglages"' in s:
    print("DEJA present"); raise SystemExit

anc = '  <h2 id="commandes">4. Les trois facons de commander</h2>'

sect = '''  <h2 id="reglages">4. Les reglages que vous pouvez ajuster</h2>
  <p>Dans l'ecran <strong>Reglages</strong>, vous pouvez personnaliser chaque mouvement avec des curseurs. Toutes les valeurs restent dans des limites sures : vous ne pouvez pas regler quelque chose de dangereux.</p>

  <div class="tip"><strong>Bouton "Valeurs sures" :</strong> en un seul appui, il remet tous les reglages dans une configuration prudente et cinematique. A utiliser si vous avez un doute ou voulez repartir d'une bonne base.</div>

  <h3>Orbite (le drone tourne autour de vous)</h3>
  <table>
    <tr><th>Reglage</th><th>Ce qu'il controle</th></tr>
    <tr><td><strong>Hauteur</strong> (2-30 m)</td><td>Plus haut = vue plongeante. Plus bas = a hauteur d'oeil.</td></tr>
    <tr><td><strong>Rayon</strong> (3-15 m)</td><td>Grand = orbite large, on voit le decor. Petit = serre sur vous.</td></tr>
    <tr><td><strong>Vitesse rotation</strong></td><td>Lente = tres cinematique. Rapide = dynamique.</td></tr>
  </table>

  <h3>Travelling (le drone vous accompagne)</h3>
  <table>
    <tr><th>Reglage</th><th>Ce qu'il controle</th></tr>
    <tr><td><strong>Distance suivi</strong></td><td>Grande = plan large. Petite = proche et intime.</td></tr>
    <tr><td><strong>Hauteur</strong></td><td>Plus haut = vue d'ensemble du parcours.</td></tr>
    <tr><td><strong>Reactivite laterale</strong></td><td>Elevee = colle a vos mouvements. Basse = plus doux.</td></tr>
  </table>

  <h3>Revelation (le drone recule et monte)</h3>
  <table>
    <tr><th>Reglage</th><th>Ce qu'il controle</th></tr>
    <tr><td><strong>Vitesse recul</strong></td><td>Lente = revelation progressive et elegante.</td></tr>
    <tr><td><strong>Vitesse montee</strong></td><td>Elevee = effet vertigineux plus marque.</td></tr>
  </table>

  <h3>Approche (le drone se rapproche)</h3>
  <table>
    <tr><th>Reglage</th><th>Ce qu'il controle</th></tr>
    <tr><td><strong>Vitesse approche</strong></td><td>Lente = maitrisee et securitaire.</td></tr>
    <tr><td><strong>Distance d'arret</strong></td><td>Distance ou le drone s'arrete en approchant (minimum 2 m).</td></tr>
  </table>

  <h3>Suivi general</h3>
  <table>
    <tr><th>Reglage</th><th>Ce qu'il controle</th></tr>
    <tr><td><strong>Douceur recentrage</strong></td><td>Elevee = vous recadre vite. Basse = mouvements plus doux.</td></tr>
  </table>

  <h3>Sujet a suivre</h3>
  <p>Choisissez ce que le drone cherche en mode automatique : <strong>Personne</strong>, <strong>Personne + Animal</strong>, <strong>Personne + Vehicule</strong>, ou <strong>Tout</strong>. Le toucher de l'ecran reste universel quel que soit ce choix.</p>

  <div class="warn"><strong>Conseil :</strong> commencez toujours avec les "valeurs sures". Augmentez les vitesses progressivement, seulement une fois a l'aise et dans un espace bien degage.</div>

''' + anc

if anc in s:
    s = s.replace(anc, sect, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Section reglages ajoutee :", 'id="reglages"' in s)
else:
    print("ANCRE NON TROUVEE")