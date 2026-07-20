# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if 'id="cablecam"' in s:
    print("DEJA presente"); raise SystemExit

anc = '''  <p style="margin-top:32px; color:#8e8e93; font-size:12px; text-align:center;">CineFlight Solo - Guide de l'utilisateur</p>'''

section = '''  <h2 id="cablecam">17. Rail Cable-Cam et Hyperlapse</h2>
  <p>Le <strong>Rail Cable-Cam</strong> fait glisser le drone en ligne droite entre deux points que vous choisissez, comme s'il etait suspendu a un cable invisible tendu d'un point A a un point B. C'est l'outil ideal pour un <strong>travelling</strong> regulier et repetable : un mouvement lisse impossible a obtenir a la main.</p>

  <h3>Les trois boutons du rail</h3>
  <table>
    <tr><td><strong>Rail A</strong></td><td>Memorise le point de DEPART. Placez le drone ou vous voulez que le mouvement commence, puis touchez Rail A : il devient vert.</td></tr>
    <tr><td><strong>Rail B</strong></td><td>Memorise le point d'ARRIVEE. Deplacez le drone jusqu'a la fin voulue, puis touchez Rail B : il devient vert.</td></tr>
    <tr><td><strong>Go</strong></td><td>Lance le mouvement : le drone glisse tout seul de A vers B. Le bouton devient rouge (Stop). Touchez-le a nouveau pour arreter a tout moment.</td></tr>
  </table>

  <h3>Le suivi pendant le rail</h3>
  <p>Si une personne (ou le sujet choisi) est <strong>detectee a l'image</strong> pendant que le drone glisse, il la garde automatiquement <strong>cadree</strong> : il tourne son nez et incline la camera pour la suivre, tout en continuant son trajet vers B. Vous obtenez un travelling lateral cinematographique ou le sujet reste au centre. L'ecran affiche alors <strong>RAIL + SUIVI</strong>. Sans sujet, il affiche <strong>RAIL A&#8594;B</strong> et pointe simplement vers B.</p>

  <h3>La distance affichee</h3>
  <p>Pendant le rail, l'ecran indique la <strong>distance restante</strong> jusqu'a B (par exemple <em>RAIL + SUIVI &#183; 12 m</em>). Le nombre descend a mesure que le drone approche. Le drone <strong>ralentit tout seul</strong> dans les derniers metres et s'arrete en douceur a l'arrivee (<strong>RAIL TERMINE</strong>).</p>

  <h3>L'Hyperlapse (timelapse en mouvement)</h3>
  <p>L'Hyperlapse prend une serie de <strong>photos a intervalle regulier</strong> pendant que le drone glisse sur le rail. Accelerees ensuite en video, ces photos donnent l'effet spectaculaire du <strong>monde qui s'anime</strong> autour de votre sujet.</p>
  <table>
    <tr><td><strong>Armer</strong></td><td>Faites un appui <strong>long</strong> sur Go : il devient violet et l'Hyperlapse est arme pour le prochain rail.</td></tr>
    <tr><td><strong>Lancer</strong></td><td>Touchez Go (appui court) : le rail demarre ET une photo est prise toutes les 2 secondes. Le compteur s'affiche sur le bouton photo (par ex. 24).</td></tr>
    <tr><td><strong>Recuperer</strong></td><td>Les photos sont sur la carte du drone. Ouvrez l'<strong>Album</strong> pour les telecharger sur le telephone.</td></tr>
  </table>
  <p>Pour assembler le timelapse final, utilisez une app gratuite comme <strong>CapCut</strong> ou l'app Photos de votre telephone : selectionnez la serie de photos, choisissez la vitesse, et exportez la video. Ces apps font le timelapse en quelques clics, avec stabilisation.</p>

  <h3 style="color:#E65100;">Securite : le retour automatique (RTH)</h3>
  <p>Si le <strong>retour automatique</strong> s'enclenche pendant un rail (declenche par vous, ou par batterie faible), le rail et l'Hyperlapse <strong>s'arretent immediatement</strong> et le drone reprend sa route vers la maison sans interference. L'ecran affiche <strong>RTH - rail interrompu</strong>. C'est une securite : le retour a la maison a toujours la priorite.</p>

  <div class="fiche"><h3>Recette : le travelling de presentation</h3>
  <p>Placez le drone a une extremite de la scene &#8594; <strong>Rail A</strong>. Deplacez-le a l'autre extremite &#8594; <strong>Rail B</strong>. Cadrez votre sujet. <strong>Go</strong> : le drone glisse lentement en le gardant au centre. Resultat : un plan d'ouverture digne du cinema.</p></div>

'''

if anc in s:
    s = s.replace(anc, section + anc, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("section 17 Cable-Cam/Hyperlapse ajoutee OK")
else:
    print("ANCRE NON TROUVEE")