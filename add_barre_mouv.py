f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "btnMouvOrbite" in s:
    print("DEJA present"); raise SystemExit
# inserer la barre MOUVEMENT juste avant la barre REALISATEUR (les plans)
ancre = '    <!-- (R) BARRE REALISATEUR SOLO'
barre = '''    <!-- (M) BARRE MOUVEMENTS SOLO -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="140dp"
        android:orientation="horizontal"
        android:padding="6dp"
        android:gravity="center"
        android:background="#80000000">

        <Button android:id="@+id/btnMouvStatique"
            android:layout_width="wrap_content" android:layout_height="42dp"
            android:layout_marginEnd="4dp" android:padding="0dp"
            android:paddingStart="10dp" android:paddingEnd="10dp"
            android:insetTop="0dp" android:insetBottom="0dp"
            android:text="STATIK" android:textSize="11sp"
            android:backgroundTint="#2E7D32" android:textColor="#FFFFFF" />

        <Button android:id="@+id/btnMouvOrbite"
            android:layout_width="wrap_content" android:layout_height="42dp"
            android:layout_marginEnd="4dp" android:padding="0dp"
            android:paddingStart="10dp" android:paddingEnd="10dp"
            android:insetTop="0dp" android:insetBottom="0dp"
            android:text="ORBITE" android:textSize="11sp"
            android:backgroundTint="#37474F" android:textColor="#FFFFFF" />

        <Button android:id="@+id/btnMouvTravel"
            android:layout_width="wrap_content" android:layout_height="42dp"
            android:layout_marginEnd="4dp" android:padding="0dp"
            android:paddingStart="10dp" android:paddingEnd="10dp"
            android:insetTop="0dp" android:insetBottom="0dp"
            android:text="TRAVEL" android:textSize="11sp"
            android:backgroundTint="#37474F" android:textColor="#FFFFFF" />

        <Button android:id="@+id/btnMouvRevel"
            android:layout_width="wrap_content" android:layout_height="42dp"
            android:padding="0dp"
            android:paddingStart="10dp" android:paddingEnd="10dp"
            android:insetTop="0dp" android:insetBottom="0dp"
            android:text="REVEL" android:textSize="11sp"
            android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
    </LinearLayout>

'''
s = s.replace(ancre, barre + ancre, 1)
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Barre MOUVEMENTS ajoutee :", "btnMouvOrbite" in s)