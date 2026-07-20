fl = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(fl, encoding="utf-8").read()
if "btnTags" not in s:
    anc = 'android:text="VOIX" android:textSize="11sp"\n            android:textColor="#FFFFFF" android:backgroundTint="#5C6BC0" />'
    add = anc + '''
        <Button android:id="@+id/btnTags"
            android:layout_width="44dp" android:layout_height="40dp"
            android:layout_marginStart="4dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="TAGS" android:textSize="10sp"
            android:textColor="#FFFFFF" android:backgroundTint="#00897B" />'''
    s = s.replace(anc, add, 1)
    open(fl, "w", encoding="utf-8", newline="\n").write(s)
    print("Bouton TAGS layout:", "btnTags" in s)
else:
    print("btnTags deja present")
fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
if "TagsActivity" not in k:
    anc = "findViewById<Button>(R.id.btnMicro).setOnClickListener {"
    add = '''findViewById<Button>(R.id.btnTags).setOnClickListener {
            startActivity(android.content.Intent(this, TagsActivity::class.java))
        }
        findViewById<Button>(R.id.btnMicro).setOnClickListener {'''
    k = k.replace(anc, add, 1)
    open(fk, "w", encoding="utf-8", newline="\n").write(k)
    print("Bouton TAGS cable:", "TagsActivity" in k)
else:
    print("TagsActivity deja cable")