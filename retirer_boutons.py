import re
fl = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(fl, encoding="utf-8").read()
for bid in ["btnMission", "btnPlacement"]:
    pat = re.compile(r'\s*<Button android:id="@\+id/' + bid + r'".*?/>', re.DOTALL)
    s2 = pat.sub("", s, count=1)
    if s2 != s:
        print("Layout: retire", bid); s = s2
    else:
        print("Layout: NON trouve", bid)
open(fl, "w", encoding="utf-8", newline="\n").write(s)

fk = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
k = open(fk, encoding="utf-8").read()
for bid in ["btnMission", "btnPlacement"]:
    pat = re.compile(r'\s*findViewById<Button>\(R\.id\.' + bid + r'\)\.setOnClickListener \{.*?\n        \}', re.DOTALL)
    k2 = pat.sub("", k, count=1)
    if k2 != k:
        print("Code: retire bloc", bid); k = k2
    else:
        print("Code: NON trouve bloc", bid)
open(fk, "w", encoding="utf-8", newline="\n").write(k)
print("btnMission restant layout:", "btnMission" in open(fl,encoding="utf-8").read())
print("btnMission restant code:", "btnMission" in open(fk,encoding="utf-8").read())
print("btnPlacement restant layout:", "btnPlacement" in open(fl,encoding="utf-8").read())
print("btnPlacement restant code:", "btnPlacement" in open(fk,encoding="utf-8").read())