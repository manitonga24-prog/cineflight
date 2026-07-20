# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()

# remplacer tout le corps de miniature() par la version CompletionCallbackWithParam<Bitmap>
import re
debut = s.find("    fun miniature(")
if debut == -1:
    print("miniature() NON TROUVEE"); raise SystemExit
# trouver la fin : juste avant le companion object
fin = s.find("    companion object", debut)
if fin == -1:
    print("companion NON TROUVE"); raise SystemExit

nouvelle = '''    fun miniature(mf: MediaFile, onMini: (Bitmap?) -> Unit) {
        try {
            mf.pullThumbnailFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<Bitmap> {
                override fun onSuccess(bmp: Bitmap?) { onMini(bmp) }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "mini echec: ${error.description()}"); onMini(null)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "miniature ex: ${e.message}"); onMini(null) }
    }

'''
s = s[:debut] + nouvelle + s[fin:]
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("miniature() reecrite OK")