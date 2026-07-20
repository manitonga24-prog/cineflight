f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

old = '''        EnregistrementSdk.onConnexionProduit = null
        flux?.arreter()
        yoloSuivi?.arreter()
        vocal?.arreter()
        recepteur.arreter()
        recepteurBoxes.arreter()
        pilote.arreter()'''
new = '''        EnregistrementSdk.onConnexionProduit = null
        flux?.arreter()
        yoloSuivi?.arreter()
        vocal?.arreter()
        if (::recepteur.isInitialized) recepteur.arreter()
        if (::recepteurBoxes.isInitialized) recepteurBoxes.arreter()
        if (::pilote.isInitialized) pilote.arreter()'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("onDestroy protege")
else:
    print("ANCRE NON TROUVEE")