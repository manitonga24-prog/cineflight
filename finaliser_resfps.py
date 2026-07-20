# -*- coding: utf-8 -*-
import re
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()

# On remplace tout le corps de la methode reelle (de la signature jusqu'au } qui precede la version simulateur).
# Ancre debut = signature reelle (ligne 238). Ancre fin = juste avant "override fun reglerResolutionFps(resNom: String, fps: Int) {}" (simu).
debut = "    override fun reglerResolutionFps(resNom: String, fps: Int) {\n        // Resolution + fps via CameraKey.KeyVideoResolutionFrameRate"
idx0 = s.find(debut)
if idx0 == -1:
    print("ANCRE DEBUT NON TROUVEE"); raise SystemExit

# trouver la version simulateur pour borner
simu = "    override fun reglerResolutionFps(resNom: String, fps: Int) {}"
idx1 = s.find(simu)
if idx1 == -1:
    print("ANCRE SIMU NON TROUVEE"); raise SystemExit

# le bloc reel a remplacer va de idx0 jusqu'au dernier "}" avant idx1
avant = s[:idx0]
apres = s[idx1:]

nouveau = '''    override fun reglerResolutionFps(resNom: String, fps: Int) {
        // Applique resolution + fps sur le drone reel via CameraKey.KeyVideoResolutionFrameRate.
        // PRO multi-drones : on lit la PLAGE reellement supportee par le drone connecte
        // (KeyVideoResolutionFrameRateRange) et on choisit la valeur supportee la plus proche
        // de la demande. Ainsi aucun reglage n'est refuse : 60fps sur Mini 3, 200fps sur Mini 4 Pro.
        try {
            // resolution demandee -> nom d'enum VideoResolution
            val resCible = when (resNom) {
                "4K" -> "RESOLUTION_3840x2160"
                "2.7K" -> "RESOLUTION_2688x1512"
                else -> "RESOLUTION_1920x1080"   // FHD
            }
            val fpsCible = "RATE_${fps}FPS"

            val clsRes = Class.forName("dji.sdk.keyvalue.value.camera.VideoResolution")
            val clsFps = Class.forName("dji.sdk.keyvalue.value.camera.VideoFrameRate")
            val clsVRF = Class.forName("dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate")

            // 1) Mettre la camera en mode VIDEO_NORMAL (requis avant de regler res/fps)
            try {
                val clsMode = Class.forName("dji.sdk.keyvalue.value.camera.CameraMode")
                val videoNormal = clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "VIDEO_NORMAL" }
                if (videoNormal != null) {
                    val cleMode = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyCameraMode)
                    KeyManager.getInstance().setValue(cleMode, videoNormal as dji.sdk.keyvalue.value.camera.CameraMode,
                        object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {}
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {}
                        })
                }
            } catch (e: Throwable) { Log.w("PontDjiReelCockpit", "mode video: " + e.message) }

            // 2) Lire la plage REELLE supportee par le drone connecte
            val cleRange = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyVideoResolutionFrameRateRange)
            val plage = KeyManager.getInstance().getValue(cleRange) as? List<*>

            // helper : extraire res+fps d'un objet VideoResolutionFrameRate
            fun nomRes(o: Any?): String? = try { clsVRF.getMethod("getResolution").invoke(o)?.let { (it as Enum<*>).name } } catch (e: Throwable) { null }
            fun nomFps(o: Any?): String? = try { clsVRF.getMethod("getFrameRate").invoke(o)?.let { (it as Enum<*>).name } } catch (e: Throwable) { null }
            fun fpsNum(nom: String?): Int = nom?.let { Regex("RATE_(\\\\d+)FPS").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

            // 3) Choisir la meilleure correspondance dans la plage reelle
            var choix: Any? = null
            if (plage != null && plage.isNotEmpty()) {
                // d'abord match exact res+fps
                choix = plage.firstOrNull { nomRes(it) == resCible && nomFps(it) == fpsCible }
                // sinon : meme resolution, fps supporte le plus proche (<=) du demande
                if (choix == null) {
                    val memeRes = plage.filter { nomRes(it) == resCible && fpsNum(nomFps(it)) > 0 }
                    choix = memeRes.minByOrNull { kotlin.math.abs(fpsNum(nomFps(it)) - fps) }
                }
                // sinon : n'importe quelle combinaison au fps demande
                if (choix == null) {
                    choix = plage.filter { fpsNum(nomFps(it)) > 0 }.minByOrNull { kotlin.math.abs(fpsNum(nomFps(it)) - fps) }
                }
            }

            // 4) Construire l'objet a appliquer (depuis la plage si trouve, sinon construit a la demande)
            val objVRF: Any = choix ?: run {
                val resVal = clsRes.enumConstants?.firstOrNull { (it as Enum<*>).name == resCible }
                val fpsVal = clsFps.enumConstants?.firstOrNull { (it as Enum<*>).name == fpsCible }
                    ?: clsFps.enumConstants?.firstOrNull { (it as Enum<*>).name == "RATE_30FPS" }
                clsVRF.getConstructor(clsRes, clsFps).newInstance(resVal, fpsVal)
            }

            // 5) Appliquer
            val cleSet = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyVideoResolutionFrameRate)
            @Suppress("UNCHECKED_CAST")
            KeyManager.getInstance().setValue(cleSet, objVRF as dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate,
                object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i("PontDjiReelCockpit", "Resolution/fps applique: ${nomRes(objVRF)} @ ${nomFps(objVRF)}")
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        Log.w("PontDjiReelCockpit", "set res/fps echoue: " + error.description())
                    }
                })
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "reglerResolutionFps non applique: " + e.message)
        }
    }
'''

s = avant + nouveau + "\n" + apres
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("reglerResolutionFps finalise OK")