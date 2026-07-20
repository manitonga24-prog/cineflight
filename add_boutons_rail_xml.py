# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if 'btnRailA' in s:
    print("DEJA present"); raise SystemExit

anc = '''        </LinearLayout>
        <HorizontalScrollView android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginTop="4dp" android:scrollbars="none">
            <LinearLayout android:id="@+id/rangeeMacros" android:layout_width="wrap_content"'''

ajout = '''        </LinearLayout>
        <LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:orientation="horizontal" android:layout_marginTop="4dp">
            <Button android:id="@+id/btnRailA" android:layout_width="48dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="Rail A" android:textSize="11sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnRailB" android:layout_width="48dp" android:layout_height="36dp"
                android:layout_marginEnd="3dp" android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="Rail B" android:textSize="11sp" android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
            <Button android:id="@+id/btnRailGo" android:layout_width="48dp" android:layout_height="36dp"
                android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
                android:text="Go" android:textSize="12sp" android:backgroundTint="#263238" android:textColor="#FFFFFF" />
        </LinearLayout>
        <HorizontalScrollView android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginTop="4dp" android:scrollbars="none">
            <LinearLayout android:id="@+id/rangeeMacros" android:layout_width="wrap_content"'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("rangee boutons Rail ajoutee au XML OK")
else:
    print("ANCRE NON TROUVEE")