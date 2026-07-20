# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\build.gradle"
s = open(f, encoding="utf-8").read()
import re
s2 = re.sub(r'ndkVersion "[^"]+"', 'ndkVersion "29.0.14206865"', s, count=1)
if s2 != s:
    open(f, "w", encoding="utf-8", newline="\n").write(s2)
    print("ndkVersion -> 29.0.14206865 OK")
else:
    print("ANCRE NON TROUVEE")