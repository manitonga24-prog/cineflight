# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MonteurVideo.kt"
s = open(f, encoding="utf-8").read()
if "fun publierDansGalerie" in s:
    print("DEJA present"); raise SystemExit

# imports necessaires
anc_imp = "import java.io.File"
add_imp = """import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File"""
if "import android.provider.MediaStore" not in s:
    s = s.replace(anc_imp, add_imp, 1)

# methode avant le companion object
anc = "    companion object { private const val TAG = \"MonteurVideo\" }"
fct = '''    /**
     * Copie le montage dans la galerie publique (Movies/CineFlight) pour qu'il
     * apparaisse dans l'app Galerie. Renvoie l'Uri public (ou null si echec).
     */
    fun publierDansGalerie(fichier: File): Uri? {
        return try {
            val resolver = ctx.contentResolver
            val nom = fichier.name
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, nom)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineFlight")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= 29)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                fichier.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "publie dans galerie: $uri")
            uri
        } catch (e: Exception) { Log.e(TAG, "publier ex: ${e.message}"); null }
    }

    companion object { private const val TAG = "MonteurVideo" }'''
if anc in s:
    s = s.replace(anc, fct, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("publierDansGalerie ajoutee OK")
else:
    print("ANCRE companion NON TROUVEE")