# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()
if "bascule mode PHOTO avant le shoot" in s:
    print("DEJA corrige"); raise SystemExit

vieux = '''    override fun declencherPhoto() {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartShootPhoto), null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    Log.i("PontDjiReelCockpit", "Photo prise")
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReelCockpit", "Échec photo: $error")
                }
            })
    }'''

neuf = '''    override fun declencherPhoto() {
        // bascule mode PHOTO avant le shoot (sinon -511 si la camera est en mode video,
        // ce qui arrive apres un reglage resolution/fps qui force VIDEO_NORMAL).
        try {
            val clsMode = Class.forName("dji.sdk.keyvalue.value.camera.CameraMode")
            val photoMode = clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "PHOTO_NORMAL" }
                ?: clsMode.enumConstants?.firstOrNull { (it as Enum<*>).name == "PHOTO" }
            if (photoMode != null) {
                val cleMode = KeyTools.createKey(CameraKey.KeyCameraMode)
                KeyManager.getInstance().setValue(cleMode, photoMode as dji.sdk.keyvalue.value.camera.CameraMode,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() { shootPhotoMaintenant() }
                        override fun onFailure(error: dji.v5.common.error.IDJIError) {
                            Log.w("PontDjiReelCockpit", "mode photo echoue (${error.description()}), tentative directe")
                            shootPhotoMaintenant()
                        }
                    })
            } else {
                shootPhotoMaintenant()
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "bascule mode photo ex: ${e.message}")
            shootPhotoMaintenant()
        }
    }

    private fun shootPhotoMaintenant() {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartShootPhoto), null,
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    Log.i("PontDjiReelCockpit", "Photo prise")
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.e("PontDjiReelCockpit", "Échec photo: $error")
                }
            })
    }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("declencherPhoto bascule mode PHOTO OK")
else:
    print("ANCRE NON TROUVEE")