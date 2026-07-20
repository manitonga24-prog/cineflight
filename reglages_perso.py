# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Reglages.kt"
s = open(f, encoding="utf-8").read()
if "getClassesPerso" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) elargir la borne setSujetASuivre 0..3 -> 0..4 (4 = personnalise)
old1 = '''    fun getSujetASuivre(): Int = prefs.getInt("sujet_suivi", 0)
    fun setSujetASuivre(mode: Int) = prefs.edit().putInt("sujet_suivi", mode.coerceIn(0, 3)).apply()'''
new1 = '''    fun getSujetASuivre(): Int = prefs.getInt("sujet_suivi", 0)
    fun setSujetASuivre(mode: Int) = prefs.edit().putInt("sujet_suivi", mode.coerceIn(0, 4)).apply()

    // --- Mode personnalise (4) : ensemble de classes COCO cochees a la main ---
    // Classes proposees a l'utilisateur (id COCO -> nom FR), voir CLASSES_PERSO ci-dessous.
    fun getClassesPerso(): Set<Int> {
        val brut = prefs.getStringSet("classes_perso", null) ?: return setOf(0)
        return brut.mapNotNull { it.toIntOrNull() }.toSet().ifEmpty { setOf(0) }
    }
    fun setClassesPerso(ids: Set<Int>) =
        prefs.edit().putStringSet("classes_perso", ids.map { it.toString() }.toSet()).apply()
    fun toggleClassePerso(id: Int) {
        val cur = getClassesPerso().toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        if (cur.isEmpty()) cur.add(0)   // jamais vide : au minimum personne
        setClassesPerso(cur)
    }'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch += 1
else:
    print("ANCRE 1 NON TROUVEE")

# 2) classesPourSujet : ajouter le cas mode 4 = perso
old2 = '''        3 -> setOf(0, 1, 2, 3, 5, 7, 15, 16, 17)  // tout
        else -> setOf(0)                 // personne seule
    }'''
new2 = '''        3 -> setOf(0, 1, 2, 3, 5, 7, 15, 16, 17)  // tout
        4 -> getClassesPerso()           // personnalise (cases a cocher)
        else -> setOf(0)                 // personne seule
    }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch += 1
else:
    print("ANCRE 2 NON TROUVEE")

# 3) table des classes proposees dans le mode perso, dans le companion object
old3 = '''        // groupes pour l'affichage de la page'''
new3 = '''        // Classes COCO proposees dans le mode "Personnalise" (id -> nom FR)
        val CLASSES_PERSO = listOf(
            0 to "Personne", 1 to "Velo", 2 to "Voiture", 3 to "Moto",
            5 to "Bus", 7 to "Camion", 8 to "Bateau",
            15 to "Chat", 16 to "Chien", 17 to "Cheval"
        )

        // groupes pour l'affichage de la page'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch += 1
else:
    print("ANCRE 3 NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Reglages.kt mode perso :", ch, "/ 3")