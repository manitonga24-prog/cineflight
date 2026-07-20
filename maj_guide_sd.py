# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "adaptateur" in s and "carte SD sur ordinateur" in s:
    print("DEJA present"); raise SystemExit

# 1) Section 16 : ajouter l'alternative carte SD apres le tip QuickTransfer
anc16 = '''  <div class="tip"><strong>Pourquoi pas directement dans CineFlight Solo ?</strong> Le transfert via la radiocommande (seul canal disponible aux apps tierces) est lent pour les grosses videos. DJI Fly a un acces Wi-Fi direct prive que les autres apps n'ont pas. D'ou le passage par DJI Fly pour cette etape.</div>'''

ajout16 = anc16 + '''
  <h3>Autre methode : la carte SD (plus rapide pour les gros volumes)</h3>
  <p>Pour de longues videos ou beaucoup de fichiers, retirer la carte est souvent <strong>plus rapide</strong> que le sans-fil :</p>
  <ul>
    <li>Eteignez le drone et sortez la <strong>carte microSD</strong>.</li>
    <li>Glissez-la dans un <strong>adaptateur microSD vers USB-C</strong>, puis branchez-le directement sur le telephone (port USB-C).</li>
    <li>Ouvrez l'app <strong>Fichiers</strong> du telephone, copiez les videos depuis la carte vers le stockage du telephone.</li>
  </ul>
  <p>Vous pouvez aussi passer par un <strong>ordinateur</strong> : carte SD sur ordinateur via un adaptateur, copiez les fichiers, puis transferez-les vers le telephone par cable. Les videos copiees sur le telephone s'ouvrent ensuite dans CapCut, VN ou InShot pour le montage.</p>
  <div class="astuce"><strong>Astuce :</strong> un petit adaptateur microSD vers USB-C coute quelques dollars et evite tout transfert lent. Pratique a garder dans le sac.</div>'''

if anc16 in s:
    s = s.replace(anc16, ajout16, 1)
    print("section 16 : alternative carte SD ajoutee")
else:
    print("ANCRE 16 NON TROUVEE")

# 2) Section 17 : ajouter une note carte SD pour les photos Hyperlapse, apres la ligne Recuperer
anc17 = '''    <tr><td><strong>Recuperer</strong></td><td>Les photos sont sur la carte du drone. Ouvrez l'<strong>Album</strong> pour les telecharger sur le telephone.</td></tr>
  </table>'''

ajout17 = '''    <tr><td><strong>Recuperer</strong></td><td>Les photos sont sur la carte du drone. Ouvrez l'<strong>Album</strong> pour les telecharger sur le telephone.</td></tr>
  </table>
  <div class="astuce"><strong>Astuce Hyperlapse :</strong> un hyperlapse genere beaucoup de photos. Pour aller vite, sortez la <strong>carte microSD</strong> et branchez-la sur le telephone via un <strong>adaptateur microSD vers USB-C</strong> (app Fichiers), ou sur un ordinateur. C'est bien plus rapide que de telecharger les photos une a une.</div>'''

if anc17 in s:
    s = s.replace(anc17, ajout17, 1)
    print("section 17 : note carte SD Hyperlapse ajoutee")
else:
    print("ANCRE 17 NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("guide sauvegarde OK")