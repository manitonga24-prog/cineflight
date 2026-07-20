# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "fun miniature(" in s:
    print("DEJA present"); raise SystemExit

# import Bitmap
anc_imp = "import java.io.FileOutputStream\n"
add_imp = "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\n"
if "import android.graphics.Bitmap" not in s and anc_imp in s:
    s = s.replace(anc_imp, anc_imp + add_imp, 1)

# methode miniature avant le companion object
anc = "    companion object { private const val TAG = \"MediaDrone\" }"
fct = '''    /** Recupere la miniature (thumbnail) d'une photo sous forme de Bitmap. onMini(bitmap ou null). */
    fun miniature(mf: MediaFile, onMini: (Bitmap?) -> Unit) {
        try {
            mf.pullThumbnailFromCamera(object : dji.v5.manager.datacenter.media.MediaFileDownloadListener {
                private val buffer = java.io.ByteArrayOutputStream()
                override fun onStart() {}
                override fun onProgress(t: Long, c: Long) {}
                override fun onRealtimeDataUpdate(data: ByteArray, offset: Long) {
                    try { buffer.write(data) } catch (_: Exception) {}
                }
                override fun onFinish() {
                    try {
                        val bytes = buffer.toByteArray()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        onMini(bmp)
                    } catch (e: Exception) { Log.e(TAG, "mini decode: ${e.message}"); onMini(null) }
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "mini echec: ${error.description()}"); onMini(null)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "miniature ex: ${e.message}"); onMini(null) }
    }

    companion object { private const val TAG = "MediaDrone" }'''
if anc in s:
    s = s.replace(anc, fct, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("methode miniature ajoutee OK")
else:
    print("ANCRE companion NON TROUVEE")