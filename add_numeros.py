# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# garder une ref des coches pour rafraichir les numeros
anc_champ = "    private val miniCache = HashMap<Uri, Bitmap>()"
add_champ = "    private val miniCache = HashMap<Uri, Bitmap>()\n    private val coches = LinkedHashMap<Uri, TextView>()"
if "private val coches" not in s:
    s = s.replace(anc_champ, add_champ, 1); ch += 1

# vider coches au debut de construireGrille
anc_vide = "    private fun construireGrille() {\n        grille.removeAllViews()"
add_vide = "    private fun construireGrille() {\n        grille.removeAllViews()\n        coches.clear()"
if "coches.clear()" not in s:
    s = s.replace(anc_vide, add_vide, 1); ch += 1

# enregistrer chaque coche + clic = numero d'ordre
old_click = '''            case.addView(img); case.addView(coche)
            case.setOnClickListener {
                if (choisis.contains(uri)) { choisis.remove(uri); coche.text = "" }
                else { choisis.add(uri); coche.text = " \\u2713 " }
                statut.text = "${choisis.size} video(s) selectionnee(s)."
            }'''
new_click = '''            case.addView(img); case.addView(coche)
            coches[uri] = coche
            case.setOnClickListener {
                if (choisis.contains(uri)) choisis.remove(uri) else choisis.add(uri)
                rafraichirNumeros()
            }'''
if old_click in s:
    s = s.replace(old_click, new_click, 1); ch += 1
else:
    print("ANCRE click NON TROUVEE")

# ajouter rafraichirNumeros apres construireGrille (avant miniatureVideo)
anc_fct = "    private fun miniatureVideo(uri: Uri): Bitmap? {"
add_fct = '''    /** Met a jour les numeros d'ordre (1,2,3...) sur les vignettes selon l'ordre de selection. */
    private fun rafraichirNumeros() {
        val ordre = choisis.toList()
        for ((uri, tv) in coches) {
            val idx = ordre.indexOf(uri)
            if (idx >= 0) {
                tv.text = " ${idx + 1} "
                tv.setBackgroundColor(ACCENT)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.text = ""
                tv.setBackgroundColor(0x88000000.toInt())
            }
        }
        statut.text = if (choisis.isEmpty()) "Cochez les videos a monter (l'ordre = l'ordre du clip)."
                      else "${choisis.size} video(s). L'ordre des numeros = l'ordre du montage."
    }

    private fun miniatureVideo(uri: Uri): Bitmap? {'''
if anc_fct in s and "fun rafraichirNumeros" not in s:
    s = s.replace(anc_fct, add_fct, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Numeros d'ordre :", ch, "/ 4")