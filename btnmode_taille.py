f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
old = '''    <Button android:id="@+id/btnMode"
        android:layout_width="wrap_content" android:layout_height="48dp"
        android:layout_gravity="bottom|center_horizontal" android:layout_marginBottom="10dp"
        android:paddingStart="24dp" android:paddingEnd="24dp"
        android:text="SUIVRE" android:textSize="15sp" android:textStyle="bold"
        android:textColor="#FFFFFF" android:backgroundTint="#2E7D32" />'''
new = '''    <Button android:id="@+id/btnMode"
        android:layout_width="wrap_content" android:layout_height="34dp"
        android:layout_gravity="bottom|center_horizontal" android:layout_marginBottom="8dp"
        android:paddingStart="12dp" android:paddingEnd="12dp"
        android:insetTop="0dp" android:insetBottom="0dp"
        android:text="SUIVRE" android:textSize="11sp"
        android:textColor="#FFFFFF" android:backgroundTint="#455A64" />'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Bouton retreci OK")
else:
    print("ANCRE NON TROUVEE")