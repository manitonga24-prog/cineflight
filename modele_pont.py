f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontDji.kt"
s = open(f, encoding="utf-8").read()
if "private var modele" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) variable modele a cote des autres @Volatile
anc1 = "    @Volatile private var connecte: Boolean = false"
if anc1 in s:
    s = s.replace(anc1, anc1 + '\n    @Volatile private var modele: String = ""', 1); ch+=1

# 2) ecoute du modele dans initialiserListeners (avant le Log final)
anc2 = '        Log.i("PontDjiReel", "Listeners SDK c'
if anc2 in s:
    insert = '''        // --- MODELE du drone (type de produit) ---
        try {
            val cleType = KeyTools.createKey(dji.sdk.keyvalue.key.ProductKey.KeyProductType)
            km.listen(cleType, this) { _, t ->
                if (t != null) modele = t.toString()
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReel", "Cle ProductType indisponible: " + e.message)
        }
'''
    s = s.replace(anc2, insert + anc2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("PontDjiReel modele :", ch, "/ 2")