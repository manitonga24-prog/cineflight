# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# import MediaMetadataRetriever
anc_imp = "import android.provider.MediaStore"
if "import android.media.MediaMetadataRetriever" not in s:
    s = s.replace(anc_imp, "import android.media.MediaMetadataRetriever\n" + anc_imp, 1); ch += 1

# remplacer miniatureVideo par version avec secours getFrameAtTime
old = '''    private fun miniatureVideo(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(256, 256), null)
            else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, ContentUris.parseId(uri),
                    MediaStore.Video.Thumbnails.MINI_KIND, null)
            }
        } catch (e: Exception) { null }
    }'''
new = '''    private fun miniatureVideo(uri: Uri): Bitmap? {
        // 1) methode rapide : loadThumbnail (cache systeme)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val b = contentResolver.loadThumbnail(uri, Size(256, 256), null)
                if (b != null) return b
            } catch (_: Exception) {}
        }
        // 2) secours fiable : extraire une frame de la video
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            // frame a 1 seconde (ou debut si plus court)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) Bitmap.createScaledBitmap(frame, 256, 256, true) else null
        } catch (e: Exception) { null }
        finally { try { r.release() } catch (_: Exception) {} }
    }'''
if old in s:
    s = s.replace(old, new, 1); ch += 1
else:
    print("ANCRE miniatureVideo NON TROUVEE")

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("miniatureVideo amelioree :", ch, "/ 2")