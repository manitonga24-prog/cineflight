# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()

old = '''        try {
            val km = dji.v5.manager.KeyManager.getInstance()
            val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyVideoResolutionFrameRate)
            Log.i("PontDjiReelCockpit", "reglerResolutionFps: $resNom @ ${fps}fps (cle prete, valeur a confirmer sur materiel)")
            // L'objet VideoResolutionFrameRate se construit selon la version SDK ;
            // a finaliser sur le drone avec l'autocompletion. Cle deja resolue ci-dessus.
        } catch (e: Throwable) {'''

new = '''        try {
            // 1) DECOUVERTE : lister les types/enums dispo pour construire l'objet.
            val clsVRF = Class.forName("dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate")
            // champs et constructeurs de VideoResolutionFrameRate
            Log.i("PontDjiReelCockpit", "VRF champs: " + clsVRF.declaredFields.joinToString { it.name + ":" + it.type.simpleName })
            Log.i("PontDjiReelCockpit", "VRF constructeurs: " + clsVRF.constructors.joinToString { c -> "(" + c.parameterTypes.joinToString { it.simpleName } + ")" })
            // enum resolution
            try {
                val clsRes = Class.forName("dji.sdk.keyvalue.value.camera.CameraVideoResolution")
                Log.i("PontDjiReelCockpit", "RES dispo: " + clsRes.enumConstants.joinToString { (it as Enum<*>).name })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "RES enum: " + e.message) }
            // enum fps
            try {
                val clsFps = Class.forName("dji.sdk.keyvalue.value.camera.CameraVideoFrameRate")
                Log.i("PontDjiReelCockpit", "FPS dispo: " + clsFps.enumConstants.joinToString { (it as Enum<*>).name })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "FPS enum: " + e.message) }
            Log.i("PontDjiReelCockpit", "reglerResolutionFps: demande $resNom @ ${fps}fps (construction a finaliser)")
        } catch (e: Throwable) {'''

if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Decouverte resolution/fps : OK")
elif "VRF champs" in s:
    print("DEJA present")
else:
    print("ANCRE NON TROUVEE")