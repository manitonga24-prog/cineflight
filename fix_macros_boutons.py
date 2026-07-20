f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) ajouter l'import LinearLayout (apres l'import de Button)
if "import android.widget.LinearLayout" not in s:
    a = "import android.widget.Button"
    if a in s:
        s = s.replace(a, a + "\nimport android.widget.LinearLayout", 1); ch+=1

# 2) retirer les setInsetTop/setInsetBottom (inexistants sur ce Button)
old = "                setPadding(dpx(6), 0, dpx(6), 0)\n                setInsetTop(0); setInsetBottom(0)"
new = "                setPadding(dpx(6), 0, dpx(6), 0)"
if old in s:
    s = s.replace(old, new, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Corrections :", ch, "/ 2")