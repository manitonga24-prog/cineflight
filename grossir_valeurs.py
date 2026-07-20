# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
# cibler la ligne txtValeursMouv : 9sp -> 11sp (tres leger)
old = 'android:textSize="9sp" android:textColor="#80FFFFFF"'
new = 'android:textSize="11sp" android:textColor="#80FFFFFF"'
if new in s:
    print("DEJA fait")
elif old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("txtValeursMouv : 9sp -> 11sp OK")
else:
    print("ANCRE NON TROUVEE")