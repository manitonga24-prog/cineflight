f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\EditeurMacrosActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# Spinner TAG
old_tag = 'val spinTag = Spinner(this).apply { adapter = ArrayAdapter(this@EditeurMacrosActivity, android.R.layout.simple_spinner_dropdown_item, (11..30).map { n -> "Tag $n" }) }'
new_tag = '''val spinTag = Spinner(this).apply {
                adapter = adapterNoir((11..30).map { n -> "Tag $n" })
            }'''
if old_tag in s:
    s = s.replace(old_tag, new_tag, 1); ch+=1

# Spinner ACTION
old_act = 'val spinAction = Spinner(this).apply { adapter = ArrayAdapter(this@EditeurMacrosActivity, android.R.layout.simple_spinner_dropdown_item, cles.map { c -> nomsActions[c] }) }'
new_act = '''val spinAction = Spinner(this).apply {
                adapter = adapterNoir(cles.map { c -> nomsActions[c] ?: c })
            }'''
if old_act in s:
    s = s.replace(old_act, new_act, 1); ch+=1

# ajouter la fonction helper adapterNoir avant chargerExistant
anc = "    private fun chargerExistant() {"
helper = '''    /** Adapter Spinner avec texte NOIR visible sur fond clair (vue fermee + liste). */
    private fun adapterNoir(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                (v as? android.widget.TextView)?.setTextColor(0xFF1C1C1E.toInt())
                return v
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? android.widget.TextView)?.setTextColor(0xFF1C1C1E.toInt())
                (v as? android.widget.TextView)?.setBackgroundColor(0xFFFFFFFF.toInt())
                return v
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

'''
if anc in s and "adapterNoir" not in s.split("private fun adapterNoir")[0] if "private fun adapterNoir" in s else anc in s:
    if "private fun adapterNoir" not in s:
        s = s.replace(anc, helper + anc, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Fix Spinners :", ch, "/ 3")