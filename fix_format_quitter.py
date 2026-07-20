# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "quitte le mode media avant de formater" in s:
    print("DEJA corrige"); raise SystemExit

# remplacer le corps de formaterCarteSD pour quitter le media d'abord
vieux = '''    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        try {
            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()
            try { mgr.setMediaFileDataSource(source) } catch (_: Exception) {}
            val cle = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyFormatStorage)'''

neuf = '''    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        // Le format est une operation CAMERA : il faut quitter le mode media avant
        // (en mode media la camera ne peut ni filmer ni formater -> erreur null).
        try { mgr.disable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { Log.i(TAG, "quitte le mode media avant de formater") }
            override fun onFailure(error: IDJIError) { Log.w(TAG, "disable avant format: ${error.description()}") }
        }) } catch (_: Exception) {}
        // petit delai pour laisser la camera revenir en mode normal, puis formater
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            formaterMaintenant(onFini)
        }, 1500)
    }

    private fun formaterMaintenant(onFini: (Boolean, String) -> Unit) {
        try {
            val cle = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyFormatStorage)'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("format : quitte media d'abord OK")
else:
    print("ANCRE NON TROUVEE")