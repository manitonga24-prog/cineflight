f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
remplacements = [
    # boutons de vol (bas-droite) : emojis -> texte court
    ('android:text="\\uD83D\\uDEEB" android:textSize="18sp"', 'android:text="DEC" android:textSize="11sp"'),    # decoller
    ('android:text="\\uD83D\\uDEEC" android:textSize="18sp"', 'android:text="ATT" android:textSize="11sp"'),    # atterrir
    ('android:text="\\uD83C\\uDFE0" android:textSize="16sp"', 'android:text="RTH" android:textSize="11sp"'),    # rth
    ('android:text="\\u26D4" android:textSize="18sp"', 'android:text="STOP" android:textSize="10sp"'),          # urgence
    # photo (haut-droite)
    ('android:text="\\uD83D\\uDCF7" android:textSize="16sp"', 'android:text="PHOT" android:textSize="10sp"'),   # photo
    ('android:text="\\u23FA" android:textSize="16sp"', 'android:text="REC" android:textSize="11sp"'),           # rec
]
ch = 0
for old, new in remplacements:
    if old in s:
        s = s.replace(old, new, 1); ch += 1
        print("OK:", new.split('"')[1])
    else:
        print("non trouve:", old[:45])
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Total:", ch, "/", len(remplacements))