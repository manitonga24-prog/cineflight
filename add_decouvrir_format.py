# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "fun decouvrirFormat" in s:
    print("DEJA present"); raise SystemExit

# inserer une methode de decouverte avant la derniere accolade du fichier
anc = "    fun activer(onFait: (Boolean) -> Unit) {"
methode = '''    /** DECOUVERTE : liste les cles/enums de formatage dispo dans ce SDK (a retirer apres). */
    fun decouvrirFormat() {
        try {
            val clsCamKey = Class.forName("dji.sdk.keyvalue.key.CameraKey")
            val cles = clsCamKey.fields.map { it.name }.filter { it.contains("Format", true) || it.contains("Storage", true) }
            Log.i(TAG, "FORMAT cles: " + cles.joinToString())
        } catch (e: Throwable) { Log.w(TAG, "decouvrirFormat cle: " + e.message) }
        try {
            val clsLoc = Class.forName("dji.sdk.keyvalue.value.camera.CameraStorageLocation")
            Log.i(TAG, "STORAGE loc: " + clsLoc.enumConstants?.joinToString { (it as Enum<*>).name })
        } catch (e: Throwable) { Log.w(TAG, "decouvrirFormat loc: " + e.message) }
    }

    fun activer(onFait: (Boolean) -> Unit) {'''

if anc in s:
    s = s.replace(anc, methode, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("methode decouvrirFormat ajoutee OK")
else:
    print("ANCRE NON TROUVEE")