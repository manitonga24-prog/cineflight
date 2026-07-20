# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "btnEv" in s:
    print("DEJA present"); raise SystemExit
anc = '''        <Button android:id="@+id/btnGuide"
            android:layout_width="44dp" android:layout_height="40dp"
            android:layout_marginStart="4dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="?" android:textSize="16sp"
            android:textColor="#FFFFFF" android:backgroundTint="#455A64" />'''
add = anc + '''
        <Button android:id="@+id/btnEv"
            android:layout_width="44dp" android:layout_height="40dp"
            android:layout_marginStart="4dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="EV0" android:textSize="11sp"
            android:textColor="#FFFFFF" android:backgroundTint="#37474F" />'''
if anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Bouton EV ajoute :", "btnEv" in s)
else:
    print("ANCRE NON TROUVEE")