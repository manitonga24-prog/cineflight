# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\build.gradle"
s = open(f, encoding="utf-8").read()
vieux = 'ndkVersion "29.0.13599879"'
neuf = 'ndkVersion "25.1.8937393"'
if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("ndkVersion -> 25.1.8937393 OK")
elif neuf in s:
    print("DEJA sur 25.1")
else:
    print("ANCRE NON TROUVEE - ligne ndkVersion differente")