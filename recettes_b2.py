f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()

bloc2 = """  <div class="fiche"><h3>9. Le lever / coucher de soleil</h3>
  <div class="row"><span class="lab">Effet :</span> silhouette devant le soleil, orbite lente pendant que la lumiere doree defile. Spectaculaire.</div>
  <div class="row"><span class="lab">Placement :</span> placez-vous entre le drone et le soleil pour la silhouette.</div>
  <div class="row"><span class="lab">Tags :</span> Orbite (8), orbite lente.</div>
  <div class="row"><span class="lab">Reglages :</span> golden hour (juste apres le lever / avant le coucher).</div>
  <div class="astuce"><strong>Astuce :</strong> le contre-jour cree une silhouette nette et dramatique.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> attendre la nuit ; la lumiere chute vite, filmez tot.</div></div>

  <div class="fiche"><h3>10. Le pull-back dramatique</h3>
  <div class="row"><span class="lab">Effet :</span> le drone recule et monte vite pour reveler l'immensite autour de vous. Effet wow en fin de video.</div>
  <div class="row"><span class="lab">Placement :</span> vous seul dans un grand espace (plage, montagne, ville).</div>
  <div class="row"><span class="lab">Tags / macro :</span> Rapproche (7) puis Plan large (5). Macro : Rapproche (2 s) puis Immobilier (8 s).</div>
  <div class="row"><span class="lab">Reglages :</span> montee plus marquee que le dronie pour l'effet vertigineux.</div>
  <div class="astuce"><strong>Astuce :</strong> gardez la pose jusqu'au bout ; le drone s'eloigne, vous restez le point d'ancrage.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> bouger pendant le recul ; ca casse l'effet de grandeur.</div></div>

  <div class="fiche"><h3>11. Le fly-by (passage lateral)</h3>
  <div class="row"><span class="lab">Effet :</span> le drone passe sur le cote en vous gardant cadre. Mouvement et energie.</div>
  <div class="row"><span class="lab">Placement :</span> restez immobile ; le drone glisse lateralement, jamais au-dessus.</div>
  <div class="row"><span class="lab">Tags :</span> Marche (1) ou Danse (2).</div>
  <div class="row"><span class="lab">Reglages :</span> passage fluide et rapide pour le dynamisme.</div>
  <div class="astuce"><strong>Astuce :</strong> repetez le passage 2-3 fois ; gardez le meilleur au montage.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> faire passer le drone au-dessus de vous - toujours sur le cote.</div>
  <div class="erreur"><strong>Securite :</strong> verifiez qu'aucune personne n'est sur la trajectoire laterale.</div></div>

  <div class="fiche"><h3>12. La presentation produit / objet</h3>
  <div class="row"><span class="lab">Effet :</span> mettre en valeur un objet (vehicule, creation, materiel) sous tous les angles.</div>
  <div class="row"><span class="lab">Placement :</span> posez l'objet dans un espace degage ; le drone orbite autour.</div>
  <div class="row"><span class="lab">Tags :</span> Orbite (8) en plan Rapproche (7).</div>
  <div class="row"><span class="lab">Reglages :</span> orbite lente, fond neutre pour ne pas distraire.</div>
  <div class="astuce"><strong>Astuce :</strong> un objet sur fond uni ressort beaucoup mieux.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> un fond encombre qui vole la vedette a l'objet.</div></div>

  <div class="fiche"><h3>13. Le before / after d'un lieu</h3>
  <div class="row"><span class="lab">Effet :</span> meme plan large avant et apres une transformation (chantier, saison, amenagement).</div>
  <div class="row"><span class="lab">Placement :</span> reperez un point de reference fixe pour retrouver le meme cadrage.</div>
  <div class="row"><span class="lab">Tags :</span> Plan large (5) au meme endroit, deux moments differents.</div>
  <div class="row"><span class="lab">Reglages :</span> meme heure et meme meteo si possible pour une comparaison juste.</div>
  <div class="astuce"><strong>Astuce :</strong> notez la position et l'altitude du premier vol pour reproduire le second.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> cadrages differents ; la comparaison perd son impact.</div></div>

  <div class="fiche"><h3>14. Le groupe / l'equipe</h3>
  <div class="row"><span class="lab">Effet :</span> plan large d'un groupe, le drone a distance et de cote.</div>
  <div class="row"><span class="lab">Placement :</span> le groupe rassemble ; le drone bien en retrait, a hauteur securitaire.</div>
  <div class="row"><span class="lab">Tags :</span> Plan large (5).</div>
  <div class="row"><span class="lab">Reglages :</span> distance suffisante pour cadrer tout le monde sans survoler.</div>
  <div class="astuce"><strong>Astuce :</strong> faites un geste collectif (saut, vague) pour animer le plan.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> approcher ou survoler le groupe - interdit.</div>
  <div class="erreur"><strong>Securite :</strong> le drone reste a distance horizontale, jamais au-dessus des personnes.</div></div>

  <div class="fiche"><h3>15. Le suivi velo / rando</h3>
  <div class="row"><span class="lab">Effet :</span> le drone vous accompagne sur un sentier ou une route. Aventure et liberte.</div>
  <div class="row"><span class="lab">Placement :</span> trajectoire degagee et previsible ; le drone vous suit de cote.</div>
  <div class="row"><span class="lab">Tags :</span> Sport (4) pour le velo rapide, Marche (1) pour la rando.</div>
  <div class="row"><span class="lab">Reglages :</span> vitesse adaptee a la votre ; anticipez les virages.</div>
  <div class="astuce"><strong>Astuce :</strong> un sentier qui serpente donne un suivi plus vivant qu'une ligne droite.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> sous-bois dense ; le drone risque de vous perdre ou de heurter une branche.</div></div>

  <div class="fiche"><h3>16. Le teaser reseaux sociaux</h3>
  <div class="row"><span class="lab">Effet :</span> court et punchy pour Reels / TikTok : un mouvement marquant de quelques secondes.</div>
  <div class="row"><span class="lab">Placement :</span> un seul lieu, un seul geste fort.</div>
  <div class="row"><span class="lab">Macro (tag 12) :</span> Rapproche (1 s) puis Orbite (4 s) puis Plan large (2 s) - sept secondes qui accrochent.</div>
  <div class="row"><span class="lab">Reglages :</span> format vertical si destine au mobile.</div>
  <div class="astuce"><strong>Astuce :</strong> mettez le mouvement le plus fort dans les 2 premieres secondes.</div>
  <div class="erreur"><strong>Erreur a eviter :</strong> trop long ; au-dela de 10 s on perd l'attention sur les reseaux.</div></div>

  <div class="tip"><strong>Conseil de pro :</strong> filmez la meme action plusieurs fois avec des tags differents (orbite, suivi, plan large). Au montage, vous aurez plusieurs angles de la meme scene.</div>

"""
s = s.replace("<!--RECETTES_SUITE-->", bloc2, 1)
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Bloc 2 insere. Total fiches:", s.count('class="fiche"'))