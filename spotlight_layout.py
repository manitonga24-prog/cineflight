# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "btnMouvSpotlight" in s:
    print("DEJA present"); raise SystemExit
anc = '''            <Button android:id="@+id/btnMouvRevel" android:layout_width="40dp" android:layout_height="36dp"
                android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u2922" android:textSize="16sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />'''
add = anc + '''
            <Button android:id="@+id/btnMouvSpotlight" android:layout_width="40dp" android:layout_height="36dp"
                android:layout_marginStart="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="\\u25C9" android:textSize="16sp" android:backgroundTint="#5C6BC0" android:textColor="#FFFFFF" />'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Bouton spotlight ajoute :", "btnMouvSpotlight" in s)
else:
    print("ANCRE NON TROUVEE")