# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "essaisRestants" in s:
    print("DEJA present (retry)"); raise SystemExit

vieux = '''    /** Liste les photos de la carte SD du drone. onListe(liste) sur succes. */
    fun listerPhotos(onListe: (List<MediaFile>) -> Unit) {
        try {
            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()
            mgr.setMediaFileDataSource(source)
            val param = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .count(-1)
                .build()
            mgr.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val data = try { mgr.mediaFileListData.data ?: emptyList() } catch (e: Exception) { emptyList() }
                    // ne garder que les photos (pas les videos)
                    val photos = data.filter { val n = it.fileName.lowercase(); n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".dng") }
                    Log.i(TAG, "photos trouvees: ${photos.size}")
                    onListe(photos)
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "liste echec: ${error.description()}"); onListe(emptyList())
                }
            })
        } catch (e: Exception) { Log.e(TAG, "lister ex: ${e.message}"); onListe(emptyList()) }
    }'''

neuf = '''    /** Liste les photos de la carte SD du drone. onListe(liste) sur succes.
     *  Le MediaManager met un instant a etre pret apres enable() : on reessaie
     *  jusqu'a 3 fois avec un court delai (corrige "execution could not be executed"). */
    fun listerPhotos(onListe: (List<MediaFile>) -> Unit) {
        listerPhotosAvecEssais(3, onListe)
    }

    private fun listerPhotosAvecEssais(essaisRestants: Int, onListe: (List<MediaFile>) -> Unit) {
        try {
            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()
            mgr.setMediaFileDataSource(source)
            val param = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .count(-1)
                .build()
            mgr.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val data = try { mgr.mediaFileListData.data ?: emptyList() } catch (e: Exception) { emptyList() }
                    // ne garder que les photos (pas les videos)
                    val photos = data.filter { val n = it.fileName.lowercase(); n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".dng") }
                    Log.i(TAG, "photos trouvees: ${photos.size}")
                    onListe(photos)
                }
                override fun onFailure(error: IDJIError) {
                    if (essaisRestants > 1) {
                        Log.w(TAG, "liste echec (${error.description()}), nouvel essai dans 700ms... restants=${essaisRestants - 1}")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            listerPhotosAvecEssais(essaisRestants - 1, onListe)
                        }, 700)
                    } else {
                        Log.e(TAG, "liste echec definitif: ${error.description()}"); onListe(emptyList())
                    }
                }
            })
        } catch (e: Exception) { Log.e(TAG, "lister ex: ${e.message}"); onListe(emptyList()) }
    }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("retry listerPhotos ajoute OK")
else:
    print("ANCRE NON TROUVEE")