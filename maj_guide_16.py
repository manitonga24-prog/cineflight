# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\assets\guide.html"
s = open(f, encoding="utf-8").read()
if "Importer des videos" in s and "adaptateur microSD vers USB-C</strong>, puis branchez" in s:
    print("DEJA present section 16"); raise SystemExit

anc = '''  <h3>Creer le montage</h3>
  <p>Ouvrez l'engrenage puis <strong>Montage</strong>.'''

ajout = '''  <h3>Autre methode : importer directement (carte SD, dossiers)</h3>
  <p>Dans l'ecran Montage, le bouton <strong>&#128194; Importer des videos</strong> ouvre le selecteur de fichiers du telephone. Vous pouvez y choisir des videos depuis n'importe quelle source : la galerie, le dossier Telechargements, ou une <strong>carte SD branchee</strong>.</p>
  <p>Pour de longues videos ou beaucoup de fichiers, c'est souvent plus rapide que le sans-fil :</p>
  <ul>
    <li>Eteignez le drone, sortez la <strong>carte microSD</strong>.</li>
    <li>Glissez-la dans un <strong>adaptateur microSD vers USB-C</strong>, puis branchez-le sur le telephone.</li>
    <li>Dans Montage, touchez <strong>Importer des videos</strong>, naviguez vers la carte, choisissez vos clips.</li>
  </ul>
  <p>Vous pouvez aussi passer par un <strong>ordinateur</strong> : carte SD sur l'ordinateur via un adaptateur, copiez les fichiers, transferez-les vers le telephone par cable, puis importez-les.</p>
  <div class="astuce"><strong>Astuce :</strong> un petit adaptateur microSD vers USB-C coute quelques dollars et evite tout transfert lent. Pratique a garder dans le sac.</div>

  <h3>Creer le montage</h3>
  <p>Ouvrez l'engrenage puis <strong>Montage</strong>.'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("section 16 : carte SD + bouton Import ajoutes OK")
else:
    print("ANCRE NON TROUVEE")