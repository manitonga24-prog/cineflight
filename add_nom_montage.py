# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MonteurVideo.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) signature de assembler : ajouter nom
old1 = """    fun assembler(
        clips: List<Uri>,
        dureeParClipSec: Int = 4,
        position: Position = Position.DEBUT,
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {"""
new1 = """    fun assembler(
        clips: List<Uri>,
        dureeParClipSec: Int = 4,
        position: Position = Position.DEBUT,
        nom: String = "",
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {"""
if old1 in s: s = s.replace(old1, new1, 1); ch += 1

# 2) signature de assemblerSegments : ajouter nom
old2 = """    fun assemblerSegments(
        segments: List<SelecteurSegments.Segment>,
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {"""
new2 = """    fun assemblerSegments(
        segments: List<SelecteurSegments.Segment>,
        nom: String = "",
        onProgres: (Int) -> Unit,
        onFini: (File?) -> Unit
    ) {"""
if old2 in s: s = s.replace(old2, new2, 1); ch += 1

# 3) remplacer les deux lignes val sortie par version avec nom
old3 = 'val sortie = File(dossier, "montage_${System.currentTimeMillis()}.mp4")'
new3 = 'val sortie = File(dossier, nomFichier(nom))'
nb = s.count(old3)
s = s.replace(old3, new3)
ch += nb

# 4) ajouter la fonction nomFichier avant dureeVideoMs
anc = "    /** Lit la duree d'une video en ms (0 si echec). */"
fct = '''    /** Construit un nom de fichier sur (nettoye), ou un nom horodate par defaut. */
    private fun nomFichier(nom: String): String {
        val propre = nom.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(" ", "_")
        return if (propre.isNotEmpty()) "$propre.mp4" else "montage_${System.currentTimeMillis()}.mp4"
    }

    /** Lit la duree d'une video en ms (0 si echec). */'''
if "fun nomFichier" not in s:
    s = s.replace(anc, fct, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("MonteurVideo nom :", ch)