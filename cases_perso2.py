# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\ReglagesActivity.kt"
s = open(f, encoding="utf-8").read()
if "box.addView(zonePerso)" in s:
    print("DEJA present"); raise SystemExit

# ancre minimale : le maj() final suivi de card.addView(box) dans carteSujetASuivre
old = '''        maj()
        card.addView(box)
        return card'''
new = '''        box.addView(zonePerso)
        maj()
        card.addView(box)
        return card'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("zonePerso ajoutee OK")
else:
    print("ANCRE NON TROUVEE - montrer le contexte")