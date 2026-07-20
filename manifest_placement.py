# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(f, encoding="utf-8").read()
old = '''        <activity
            android:name=".PlacementActivity"
            android:exported="false"
            android:configChanges="orientation|keyboardHidden|screenSize" />'''
new = '''        <activity
            android:name=".PlacementActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:configChanges="orientation|keyboardHidden|screenSize" />'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("PlacementActivity passee en paysage : OK")
else:
    print("ANCRE NON TROUVEE")