f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
if "grpPlan" in s:
    print("DEJA present"); raise SystemExit
# Ancre : le commentaire de la barre du bas (5). On insere juste avant.
ancre = '    <!-- (5) BARRE DU BAS'
barre = '''    <!-- (R) BARRE REALISATEUR SOLO : indicateur sujet + selection du PLAN -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="72dp"
        android:orientation="vertical"
        android:padding="8dp"
        android:gravity="center"
        android:background="#80000000">

        <TextView android:id="@+id/txtSujet"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="\u25CF AUCUN SUJET" android:textColor="#C62828"
            android:textSize="14sp" android:textStyle="bold"
            android:fontFamily="monospace" android:layout_marginBottom="6dp" />

        <LinearLayout
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:orientation="horizontal" android:gravity="center">

            <Button android:id="@+id/btnPlanGros"
                android:layout_width="wrap_content" android:layout_height="44dp"
                android:layout_marginEnd="4dp" android:padding="0dp"
                android:paddingStart="10dp" android:paddingEnd="10dp"
                android:insetTop="0dp" android:insetBottom="0dp"
                android:text="GROS" android:textSize="12sp"
                android:backgroundTint="#37474F" android:textColor="#FFFFFF" />

            <Button android:id="@+id/btnPlanAmericain"
                android:layout_width="wrap_content" android:layout_height="44dp"
                android:layout_marginEnd="4dp" android:padding="0dp"
                android:paddingStart="10dp" android:paddingEnd="10dp"
                android:insetTop="0dp" android:insetBottom="0dp"
                android:text="AMERIC." android:textSize="12sp"
                android:backgroundTint="#2E7D32" android:textColor="#FFFFFF" />

            <Button android:id="@+id/btnPlanPied"
                android:layout_width="wrap_content" android:layout_height="44dp"
                android:layout_marginEnd="4dp" android:padding="0dp"
                android:paddingStart="10dp" android:paddingEnd="10dp"
                android:insetTop="0dp" android:insetBottom="0dp"
                android:text="PIED" android:textSize="12sp"
                android:backgroundTint="#37474F" android:textColor="#FFFFFF" />

            <Button android:id="@+id/btnPlanEnsemble"
                android:layout_width="wrap_content" android:layout_height="44dp"
                android:padding="0dp"
                android:paddingStart="10dp" android:paddingEnd="10dp"
                android:insetTop="0dp" android:insetBottom="0dp"
                android:text="ENSEMBLE" android:textSize="12sp"
                android:backgroundTint="#37474F" android:textColor="#FFFFFF" />
        </LinearLayout>
    </LinearLayout>

'''
s = s.replace(ancre, barre + ancre, 1)
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Barre PLAN ajoutee :", "grpPlan" in s or "btnPlanGros" in s)