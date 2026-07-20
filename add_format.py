# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "fun formaterCarteSD" in s:
    print("DEJA present"); raise SystemExit

# remplacer la methode de decouverte par la vraie methode de formatage
anc = '''    /** DECOUVERTE : liste les cles/enums de formatage dispo dans ce SDK (a retirer apres). */
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
'''

neuf = '''    /** Formate la carte SD du drone (EFFACE TOUT). onFini(true) si succes.
     *  Cle confirmee par decouverte : CameraKey.KeyFormatStorage, location SDCARD. */
    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        try {
            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()
            try { mgr.setMediaFileDataSource(source) } catch (_: Exception) {}
            val cle = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyFormatStorage)
            KeyManager.getInstance().performAction(cle, CameraStorageLocation.SDCARD,
                object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        Log.i(TAG, "carte SD formatee")
                        onFini(true, "Carte SD formatee.")
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "format echec: ${error.description()}")
                        onFini(false, "Echec du formatage : ${error.description()}")
                    }
                })
        } catch (e: Throwable) {
            Log.e(TAG, "format ex: ${e.message}")
            onFini(false, "Erreur : ${e.message}")
        }
    }
'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("methode formaterCarteSD ajoutee OK")
else:
    print("ANCRE NON TROUVEE")