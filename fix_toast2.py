# -*- coding: utf-8 -*-
import re
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

# Remplace tous les toast("...") par Toast.makeText(this, "...", LENGTH_SHORT).show()
# Capture le contenu entre toast(" et ") en gerant les apostrophes internes
motif = re.compile(r'toast\((".*?")\)')
def remp(m):
    return 'android.widget.Toast.makeText(this, ' + m.group(1) + ', android.widget.Toast.LENGTH_SHORT).show()'
s2, n = motif.subn(remp, s)

open(f, "w", encoding="utf-8", newline="\n").write(s2)
print("toasts remplaces :", n)
print("reste de toast( :", s2.count("toast("))