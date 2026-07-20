# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()

vieux = '''            // enum resolution
            try {
                val clsRes = Class.forName("dji.sdk.keyvalue.value.camera.CameraVideoResolution")
                Log.i("PontDjiReelCockpit", "RES dispo: " + clsRes.enumConstants.joinToString { (it as Enum<*>).name })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "RES enum: " + e.message) }
            // enum fps
            try {
                val clsFps = Class.forName("dji.sdk.keyvalue.value.camera.CameraVideoFrameRate")
                Log.i("PontDjiReelCockpit", "FPS dispo: " + clsFps.enumConstants.joinToString { (it as Enum<*>).name })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "FPS enum: " + e.message) }'''

neuf = '''            // enum resolution (vrai nom : VideoResolution, sans prefixe Camera)
            try {
                val clsRes = Class.forName("dji.sdk.keyvalue.value.camera.VideoResolution")
                Log.i("PontDjiReelCockpit", "RES dispo: " + clsRes.enumConstants.joinToString { (it as Enum<*>).name })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "RES enum: " + e.message) }
            // enum fps (vrai nom : VideoFrameRate)
            try {
                val clsFps = Class.forName("dji.sdk.keyvalue.value.camera.VideoFrameRate")
                Log.i("PontDjiReelCockpit", "FPS dispo: " + clsFps.enumConstants.joinToString { (it as Enum<*>).name })
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "FPS enum: " + e.message) }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("noms d'enums corriges OK")
else:
    print("ANCRE NON TROUVEE")