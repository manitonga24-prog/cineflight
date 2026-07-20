# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
old = '            android:text="TAGS" android:textSize="10sp"'
new = '            android:text="\\u2699" android:textSize="18sp"'
if '\\u2699' in s:
    print("DEJA engrenage")
elif old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Bouton TAGS -> engrenage : OK")
else:
    print("ANCRE NON TROUVEE")