f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
# popup en francais du Quebec
old = 'putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")\n                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Commande CineFlight")'
new = 'putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")\n                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-CA")\n                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Commande CineFlight")'
if old in s:
    s = s.replace(old, new, 1)
    print("Langue popup -> fr-CA (Quebec)")
else:
    print("ANCRE NON TROUVEE")
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("fr-CA present :", "fr-CA" in s)