# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if 'id="montage"' in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) lien sommaire apres #perso
anc_som = '    <a href="#perso">15. Sujet personnalise</a>'
add_som = anc_som + '\n    <a href="#montage">16. Montage automatique (IA)</a>'
if anc_som in s:
    s = s.replace(anc_som, add_som, 1); ch += 1
else:
    print("ANCRE SOMMAIRE NON TROUVEE")

# 2) section 16 avant la signature finale
anc_fin = '  <p style="margin-top:32px; color:#8e8e93; font-size:12px; text-align:center;">CineFlight Solo - Guide de l\'utilisateur</p>'
section = '''  <h2 id="montage">16. Montage automatique (IA)</h2>
  <p>CineFlight Solo peut monter vos videos tout seul : l'intelligence artificielle analyse vos plans, repere les meilleurs moments (la ou votre sujet est bien present et bien cadre) et assemble un clip rythme. Vous n'avez presque rien a faire.</p>

  <h3>D'abord : recuperez vos videos sur le telephone</h3>
  <p>Les videos du drone sont sur sa carte SD. Le moyen le plus rapide de les amener sur le telephone est <strong>QuickTransfer</strong>, dans l'app DJI Fly : le telephone se connecte directement au drone en Wi-Fi (jusqu'a 25 Mo/s), bien plus rapide que par la radiocommande.</p>
  <p><strong>Comment faire :</strong> drone pose et allume, ouvrez DJI Fly, allez dans l'album, lancez QuickTransfer, telechargez vos videos. Elles sont alors dans la galerie du telephone. Revenez dans CineFlight Solo pour les monter.</p>
  <div class="tip"><strong>Pourquoi pas directement dans CineFlight Solo ?</strong> Le transfert via la radiocommande (seul canal disponible aux apps tierces) est lent pour les grosses videos. DJI Fly a un acces Wi-Fi direct prive que les autres apps n'ont pas. D'ou le passage par DJI Fly pour cette etape.</p>

  <h3>Creer le montage</h3>
  <p>Ouvrez l'engrenage puis <strong>Montage</strong>. Cochez les videos a monter : le <strong>numero</strong> qui apparait indique l'ordre dans le clip final (cochez dans l'ordre voulu). Donnez un nom si vous le souhaitez, choisissez un style, puis <strong>Creer le montage</strong>.</p>

  <h3>Les trois styles</h3>
  <table>
    <tr><th>Style</th><th>Ce qu'il fait</th></tr>
    <tr><td><strong>Best-of</strong></td><td>Les meilleurs moments de tous vos clips, condenses en un clip rythme.</td></tr>
    <tr><td><strong>Un par clip</strong></td><td>Le meilleur passage de chaque clip choisi. Chaque clip est represente.</td></tr>
    <tr><td><strong>Adaptatif</strong></td><td>Garde tout ce qui est de bonne qualite. La duree s'adapte a vos images.</td></tr>
  </table>
  <p>Dans les trois cas, l'IA cherche les passages ou votre sujet (celui que le drone suit, selon vos reglages) est bien visible et bien cadre. L'analyse prend un moment selon la longueur des videos - une barre de progression vous l'indique.</p>

  <h3>Apres le montage</h3>
  <p>Le montage se lit directement (bouton <strong>Lire</strong>), se partage (reseaux sociaux, courriel...) et s'ajoute a votre galerie. Vous retrouvez tous vos montages dans <strong>engrenage &rarr; Mes montages</strong>.</p>

  <h3>Pour un montage personnalise</h3>
  <p>CineFlight Solo fait le montage <strong>automatique</strong>. Si vous voulez retoucher finement (couper a l'image pres, ajouter des transitions, du texte anime, de la musique synchronisee, des effets), partagez votre montage vers une app de montage dediee - gratuites et excellentes :</p>
  <ul>
    <li><strong>CapCut</strong> - le plus complet, tres populaire, gratuit.</li>
    <li><strong>VN Video Editor</strong> - puissant et sans filigrane.</li>
    <li><strong>InShot</strong> - simple et rapide pour les reseaux sociaux.</li>
  </ul>
  <p>Le bouton Partager de CineFlight Solo envoie directement votre montage vers ces apps.</p>

'''
if anc_fin in s:
    s = s.replace(anc_fin, section + anc_fin, 1); ch += 1
else:
    print("ANCRE FIN NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Section 16 Montage :", ch, "/ 2")