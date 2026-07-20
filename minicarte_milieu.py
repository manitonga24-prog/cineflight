# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
# quelle que soit la position actuelle, on force center_vertical|start
import re
old1 = '''        android:layout_width="130dp" android:layout_height="130dp"
        android:layout_gravity="top|start" android:layout_marginTop="56dp" android:layout_marginStart="8dp"
        android:visibility="gone">'''
old2 = '''        android:layout_width="150dp" android:layout_height="150dp"
        android:layout_gravity="bottom|start" android:layout_margin="8dp"
        android:visibility="gone">'''
new = '''        android:layout_width="130dp" android:layout_height="130dp"
        android:layout_gravity="center_vertical|start" android:layout_marginStart="8dp"
        android:visibility="gone">'''
if 'layout_gravity="center_vertical|start"' in s:
    print("DEJA au milieu-gauche")
elif old1 in s:
    s = s.replace(old1, new, 1); open(f,"w",encoding="utf-8",newline="\n").write(s); print("Mini-carte placee au milieu-gauche : OK")
elif old2 in s:
    s = s.replace(old2, new, 1); open(f,"w",encoding="utf-8",newline="\n").write(s); print("Mini-carte placee au milieu-gauche : OK")
else:
    print("ANCRE NON TROUVEE")