# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) champ pour l'EditText
anc_champ = "    private var descMode: TextView? = null"
add_champ = "    private var descMode: TextView? = null\n    private var champNom: EditText? = null"
if "private var champNom" not in s:
    s = s.replace(anc_champ, add_champ, 1); ch += 1

# 2) ajouter le champ "Nom du montage" juste avant le bouton Creer
anc_creer = '''        // === BOUTON CREER ==='''
add_creer = '''        // === NOM DU MONTAGE ===
        col.addView(TextView(this).apply {
            text = "Nom du montage (optionnel)"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(2))
        })
        val edit = EditText(this).apply {
            hint = "Ex : Vol parc Lafontaine"
            setText(""); setTextColor(Color.WHITE); setHintTextColor(0xFF607D8B.toInt())
            setBackgroundColor(CARTE); setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 14f
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8); layoutParams = lp
        }
        col.addView(edit); champNom = edit

        // === BOUTON CREER ==='''
if "NOM DU MONTAGE" not in s:
    s = s.replace(anc_creer, add_creer, 1); ch += 1

# 3) passer le nom dans montageRapide (assembler)
old3 = """        monteur.assembler(
            clips = choisis.toList(),
            dureeParClipSec = dureeParClip,
            position = position,
            onProgres = { pct -> runOnUiThread { statut.text = "Montage en cours... $pct%" } },"""
new3 = """        monteur.assembler(
            clips = choisis.toList(),
            dureeParClipSec = dureeParClip,
            position = position,
            nom = champNom?.text?.toString() ?: "",
            onProgres = { pct -> runOnUiThread { statut.text = "Montage en cours... $pct%" } },"""
if old3 in s: s = s.replace(old3, new3, 1); ch += 1

# 4) passer le nom dans assemblerSegments
old4 = """                        monteur.assemblerSegments(
                            segments = segments,
                            onProgres = { pct -> runOnUiThread { statut.text = "Montage IA... $pct%" } },"""
new4 = """                        monteur.assemblerSegments(
                            segments = segments,
                            nom = champNom?.text?.toString() ?: "",
                            onProgres = { pct -> runOnUiThread { statut.text = "Montage IA... $pct%" } },"""
if old4 in s: s = s.replace(old4, new4, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Champ nom montage :", ch, "/ 4")