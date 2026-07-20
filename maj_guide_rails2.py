# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "Trois facons de definir un rail" in s:
    print("DEJA present"); raise SystemExit

anc = "<h3>Le suivi pendant le rail</h3>"
if anc not in s:
    print("ANCRE COURTE NON TROUVEE"); raise SystemExit

ajout = '''<h3>Trois facons de definir un rail</h3>
  <p>Selon la situation, vous avez trois methodes pour placer les points A et B. Choisissez la plus pratique :</p>

  <h4>1. A la main (precis, sur place)</h4>
  <p>La methode classique : vous <strong>pilotez le drone</strong> jusqu'au point de depart et touchez <strong>Rail A</strong>, puis jusqu'au point d'arrivee et touchez <strong>Rail B</strong>. Ideal quand vous voulez placer le drone exactement, en le voyant. Inconvenient : il faut faire voler le drone deux fois avant de lancer le mouvement.</p>

  <h4>2. Sur la carte (rapide, sans piloter)</h4>
  <p>Beaucoup plus rapide : ouvrez la <strong>carte</strong> (bouton MAP), touchez <strong>Definir rail</strong>, puis <strong>touchez deux points sur la carte</strong> : le premier devient A, le second B. Une ligne bleue relie les deux. Touchez <strong>Utiliser ce rail</strong> : le rail est pret sans avoir pilote le drone. De retour au vol, les boutons Rail A et Rail B sont verts ; touchez <strong>Go</strong>. Parfait pour les grands mouvements (longer une plage, suivre une route) que vous voyez d'en haut.</p>
  <div class="astuce"><strong>Astuce :</strong> si vous vous trompez, touchez un troisieme point pour recommencer.</div>

  <h4>3. Vers le sujet (automatique, intelligent)</h4>
  <p>La methode la plus rapide pour filmer une personne : <strong>cadrez d'abord le sujet</strong> (le drone doit le voir a l'ecran), puis faites un <strong>appui long sur Rail A</strong>. CineFlight Solo calcule tout seul le rail : depart = position actuelle du drone, arrivee = un point en direction du sujet. Les deux boutons passent au vert ; touchez <strong>Go</strong> et le drone glisse vers la personne en la gardant cadree. Unique a CineFlight : aucun autre drone ne calcule le rail automatiquement a partir du sujet detecte.</p>
  <p>La <strong>distance</strong> du glissement se regle dans <strong>Reglages &#8594; Camera &#8594; Distance rail vers sujet</strong> (3 a 30 metres) : courte pour un rapproche intime, plus grande pour un mouvement ample.</p>

  <h3>Le suivi pendant le rail</h3>'''

s = s.replace(anc, ajout, 1)
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("section 17 trois facons ajoutees OK")