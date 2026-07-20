fl = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(fl, encoding="utf-8").read()
if "btnGuide" not in s:
    anc = '''android:text="TAGS" android:textSize="10sp"
            android:textColor="#FFFFFF" android:backgroundTint="#00897B" />'''
    add = anc + '''
        <Button android:id="@+id/btnGuide"
            android:layout_width="44dp" android:layout_height="40dp"
            android:layout_marginStart="4dp"
            android:padding="0dp" android:insetTop="0dp" android:insetBottom="0dp"
            android:text="?" android:textSize="16sp"
            android:textColor="#FFFFFF" android:backgroundTint="#455A64" />'''
    s = s.replace(anc, add, 1)
    open(fl, "w", encoding="utf-8", newline="\n").write(s)
    print("Layout bouton Guide:", "btnGuide" in s)
else:
    print("btnGuide deja present")

fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
if "GuideActivity" not in k:
    anc = "findViewById<Button>(R.id.btnTags).setOnClickListener {"
    add = '''findViewById<Button>(R.id.btnGuide).setOnClickListener {
            startActivity(android.content.Intent(this, GuideActivity::class.java))
        }
        findViewById<Button>(R.id.btnTags).setOnClickListener {'''
    k = k.replace(anc, add, 1)
    open(fk, "w", encoding="utf-8", newline="\n").write(k)
    print("MainActivity bouton Guide:", "GuideActivity" in k)
else:
    print("MainActivity deja present")