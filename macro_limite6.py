f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\EditeurMacrosActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) ajouter le LengthFilter(6) au champ
a1 = "                inputType = android.text.InputType.TYPE_CLASS_TEXT"
if a1 in s and "LengthFilter" not in s:
    s = s.replace(a1, a1 + "\n                filters = arrayOf(android.text.InputFilter.LengthFilter(6))", 1); ch+=1

# 2) tronquer a 6 a la sauvegarde
a2 = "val nomSaisi = champNom?.text?.toString()?.trim().orEmpty()"
if a2 in s and ".take(6)" not in s:
    s = s.replace(a2, "val nomSaisi = champNom?.text?.toString()?.trim().orEmpty().take(6)", 1); ch+=1

# 3) mettre a jour le titre du champ
a3 = '"Nom de la macro"'
if a3 in s:
    s = s.replace(a3, '"Nom de la macro (max 6)"', 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Limite 6 caracteres :", ch)