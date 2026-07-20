f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()

bloc1 = """  <h2 id="recettes">9. Recettes de tournage</h2>
  <p>Seize fiches pratiques. Chacune donne l'effet recherche, comment vous placer, les tags ou la macro exacte, les reglages, une astuce de pro et l'erreur a eviter. Toutes respectent la regle d'or : jamais au-dessus des personnes.</p>

  <div class="fiche"><h3>1. Le dronie</h3>
  <div class="row"><span class="lab">Effet :</span> le grand classique. Gros plan sur vous puis le drone s'eloigne et monte, devoilant tout le paysage. Cloture parfaite d'une video.</div>
  <div class="row"><span class="lab">Placement :</span> tenez-vous dans un lieu ouvert et spectaculaire ; le drone demarre face a vous a 2-3 m.</div>
  <div class="row"><span class="lab">Tags / macro :</span> Rapproche (7) puis Plan large (5). En macro : Rapproche (attente 3 s) puis Immobilier (attente 6 s).</div>
  <div class="row"><span class="lab">Reglages :</span> recul lent et regulier ; golden hour pour la lumiere.</div>
  <div class="astuce"><strong>Astuce :</strong> regardez l'horizon, pas le drone - ca rend la scene naturelle.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> reculer trop vite ; on perd l'effet de devoilement progressif.</div></div>

  <div class="fiche"><h3>2. Le reveal</h3>
  <div class="row"><span class="lab">Effet :</span> demarre serre sur un detail (vous, un objet) puis recule pour reveler le decor entier. Ideal en ouverture.</div>
  <div class="row"><span class="lab">Placement :</span> placez le sujet au premier plan, le decor a reveler derriere.</div>
  <div class="row"><span class="lab">Tags / macro :</span> Rapproche (7) puis Immobilier (3).</div>
  <div class="row"><span class="lab">Reglages :</span> lever ou coucher de soleil pour une lumiere dramatique.</div>
  <div class="astuce"><strong>Astuce :</strong> commencez sur un detail intrigant pour donner envie de voir la suite.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> reveler trop tot ; gardez le mystere quelques secondes.</div></div>

  <div class="fiche"><h3>3. L'orbite hero</h3>
  <div class="row"><span class="lab">Effet :</span> le drone tourne a 360 degres autour de vous, le decor defile derriere. Le plan signature des sommets et lieux uniques.</div>
  <div class="row"><span class="lab">Placement :</span> tenez-vous au centre, espace degage tout autour sur le cercle d'orbite.</div>
  <div class="row"><span class="lab">Tags :</span> Orbite (8), ou Danse (2) pour une version plus energique.</div>
  <div class="row"><span class="lab">Reglages :</span> orbite lente et constante = rendu le plus cinematique.</div>
  <div class="astuce"><strong>Astuce :</strong> restez immobile ou faites un geste lent ; laissez le drone faire le travail.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> orbiter trop pres ; le decor defile trop vite et donne le tournis.</div></div>

  <div class="fiche"><h3>4. Le suivi de marche (travelling)</h3>
  <div class="row"><span class="lab">Effet :</span> le drone vous accompagne pendant que vous marchez vers un point d'interet. Sensation de voyage.</div>
  <div class="row"><span class="lab">Placement :</span> un chemin degage devant vous ; le drone vous suit de cote ou de face.</div>
  <div class="row"><span class="lab">Tags :</span> Marche (1), en plan Americain (6) ou Plan large (5).</div>
  <div class="row"><span class="lab">Reglages :</span> marche reguliere, sans a-coups.</div>
  <div class="astuce"><strong>Astuce :</strong> marchez vers quelque chose (cascade, sommet) pour donner une direction a la scene.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> changer brusquement de direction ; le suivi devient saccade.</div></div>

  <div class="fiche"><h3>5. La sequence voyage complete (macro)</h3>
  <div class="row"><span class="lab">Effet :</span> une mini-histoire en un seul tag : decor, marche, moment hero, dronie final.</div>
  <div class="row"><span class="lab">Placement :</span> un lieu ouvert qui se prete a plusieurs mouvements.</div>
  <div class="row"><span class="lab">Macro (tag 11) :</span> Plan large (4 s) puis Marche (6 s) puis Orbite (8 s) puis Immobilier (6 s).</div>
  <div class="row"><span class="lab">Reglages :</span> ajustez les durees selon le rythme voulu.</div>
  <div class="astuce"><strong>Astuce :</strong> montrez le tag au depart, rangez la carte, et jouez la scene.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> des durees trop courtes ; chaque plan a besoin de respirer.</div></div>

  <div class="fiche"><h3>6. Le sport / action</h3>
  <div class="row"><span class="lab">Effet :</span> le drone vous suit de pres et reactif pendant une activite rapide.</div>
  <div class="row"><span class="lab">Placement :</span> espace tres degage, trajectoire previsible.</div>
  <div class="row"><span class="lab">Tags :</span> Sport (4) en plan Americain (6).</div>
  <div class="row"><span class="lab">Reglages :</span> reservez aux environnements sans obstacles ni public.</div>
  <div class="astuce"><strong>Astuce :</strong> repetez le parcours une fois a vide pour verifier le suivi.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> filmer en zone frequentee ; le suivi rapide exige un espace vide.</div></div>

  <div class="fiche"><h3>7. Tour immobilier (facade)</h3>
  <div class="row"><span class="lab">Effet :</span> montrer un bien sous son meilleur jour : facade puis revelation montante du terrain.</div>
  <div class="row"><span class="lab">Placement :</span> placez-vous a l'entree comme reference ; le drone face a la facade.</div>
  <div class="row"><span class="lab">Tags :</span> Plan large (5) puis Immobilier (3).</div>
  <div class="row"><span class="lab">Reglages :</span> lumiere rasante du matin ou du soir pour le relief.</div>
  <div class="astuce"><strong>Astuce :</strong> un bien revele en montant parait plus grand et plus prestigieux.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> filmer a midi ; la lumiere plate aplatit le bati.</div></div>

  <div class="fiche"><h3>8. Le portrait cinematique</h3>
  <div class="row"><span class="lab">Effet :</span> gros plan stable sur vous, parfait pour une intro de chaine ou un message a la camera.</div>
  <div class="row"><span class="lab">Placement :</span> fond degage et esthetique derriere vous ; drone fixe a hauteur du visage.</div>
  <div class="row"><span class="lab">Tags :</span> Rapproche (7) puis Presentation (0) pour stabiliser.</div>
  <div class="row"><span class="lab">Reglages :</span> drone immobile, cadrage serre mais pas etouffant.</div>
  <div class="astuce"><strong>Astuce :</strong> laissez un peu d'espace au-dessus de la tete pour un cadrage propre.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> un fond charge qui detourne l'attention de vous.</div></div>

"""
s = s.replace("<!--RECETTES_ICI-->", bloc1 + "<!--RECETTES_SUITE-->", 1)
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Bloc 1 (fiches 1-8) insere:", "<!--RECETTES_SUITE-->" in s)