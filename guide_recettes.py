f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "Recettes de tournage" in s:
    print("DEJA present"); raise SystemExit

ajout = """  <h2 id="recettes">9. Recettes de tournage</h2>
  <p>Voici des sequences que les videastes drone adorent realiser, et comment les obtenir avec CineFlight Solo. Chacune peut etre faite tag par tag, ou programmee en une seule macro.</p>
  <div class="card"><h3 style="margin-top:0;">Le dronie (le grand classique)</h3>
  <p>Le drone part en gros plan sur vous puis s'eloigne et monte, devoilant tout le paysage. Parfait pour terminer une video.</p>
  <p><strong>Tags :</strong> Rapproche (7) puis Plan large (5).</p>
  <p><strong>En macro :</strong> Rapproche (attente 3 s) puis Immobilier (attente 6 s).</p></div>
  <div class="card"><h3 style="margin-top:0;">Le reveal (entree en matiere)</h3>
  <p>Demarre serre sur un detail puis recule lentement pour reveler le decor entier. Ideal en ouverture ou pour un bien immobilier.</p>
  <p><strong>Tags :</strong> Rapproche (7) puis Immobilier (3).</p>
  <p><strong>Astuce :</strong> a faire au lever ou coucher du soleil pour une lumiere dramatique.</p></div>
  <div class="card"><h3 style="margin-top:0;">L'orbite hero (moment fort)</h3>
  <p>Le drone tourne a 360 degres autour de vous pendant que le decor defile derriere. Le plan signature pour un sommet, un lieu unique.</p>
  <p><strong>Tag :</strong> Orbite (8), ou Danse (2) pour une version plus energique.</p>
  <p><strong>Astuce :</strong> restez immobile, gardez l'orbite lente pour le rendu le plus cinematique.</p></div>
  <div class="card"><h3 style="margin-top:0;">Le suivi de marche (travelling)</h3>
  <p>Le drone vous accompagne pendant que vous marchez vers un point d'interet (cascade, sommet, plage).</p>
  <p><strong>Tag :</strong> Marche (1), en plan Americain (6) ou Plan large (5).</p></div>
  <div class="card"><h3 style="margin-top:0;">La sequence voyage complete (macro)</h3>
  <p>Une mini-histoire en un seul tag. Programmez sur le tag 11 :</p>
  <ul><li>Plan large - attente 4 s (le decor)</li>
  <li>Marche - attente 6 s (vous avancez)</li>
  <li>Orbite - attente 8 s (le moment hero)</li>
  <li>Immobilier - attente 6 s (le dronie final)</li></ul>
  <p>Montrez le tag 11 au depart, rangez la carte, laissez le drone raconter la sequence.</p></div>
  <div class="card"><h3 style="margin-top:0;">Le sport / action</h3>
  <p>Pour le velo, la course, le skate : le drone vous suit de pres et reactif. Espace tres degage obligatoire.</p>
  <p><strong>Tag :</strong> Sport (4), en plan Americain (6).</p></div>
  <div class="tip"><strong>Conseil de pro :</strong> filmez la meme action plusieurs fois avec des tags differents (orbite, suivi, plan large). Au montage, vous aurez plusieurs angles.</div>

"""
anc = '  <p style="margin-top:32px;'
s = s.replace(anc, ajout + anc, 1)
s = s.replace('<a href="#depannage">8. Depannage</a>',
              '<a href="#depannage">8. Depannage</a>\n    <a href="#recettes">9. Recettes de tournage</a>', 1)
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Recettes inserees :", s.count("Recettes de tournage"))