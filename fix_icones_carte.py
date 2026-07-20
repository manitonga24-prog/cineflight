# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
ch = 0

# btnCarte : 🗺 (tofu) -> texte "MAP" lisible partout, taille reduite
old1 = 'android:text="\\uD83D\\uDDFA" android:textSize="16sp"'
new1 = 'android:text="MAP" android:textSize="12sp"'
if old1 in s:
    s = s.replace(old1, new1, 1); ch += 1
else:
    print("ANCRE btnCarte NON TROUVEE")

# btnAgrandirCarte : ⛶ (tofu) -> symbole plein-ecran sur (U+2197 fleche NE rend partout)
old2 = 'android:text="\\u26F6" android:textSize="13sp"'
new2 = 'android:text="\\u2197" android:textSize="14sp"'
if old2 in s:
    s = s.replace(old2, new2, 1); ch += 1
else:
    print("ANCRE btnAgrandir NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Icones carte corrigees :", ch, "/ 2")