f = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\layout\activity_main.xml"
s = open(f, encoding="utf-8").read()
# remplacer l'emoji micro par le texte MIC (fiable, pas de carre)
old = 'android:text="\\uD83C\\uDFA4" android:textSize="16sp"\n            android:textColor="#FFFFFF" android:backgroundTint="#5C6BC0" />'
new = 'android:text="MIC" android:textSize="12sp"\n            android:textColor="#FFFFFF" android:backgroundTint="#5C6BC0" />'
if old in s:
    s = s.replace(old, new, 1)
    print("Micro -> texte MIC")
else:
    # tentative plus souple : juste l'emoji
    if '\\uD83C\\uDFA4' in s:
        s = s.replace('\\uD83C\\uDFA4', 'MIC')
        print("Micro emoji remplace par MIC (souple)")
    else:
        print("ANCRE NON TROUVEE")
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("MIC present :", "MIC" in s)