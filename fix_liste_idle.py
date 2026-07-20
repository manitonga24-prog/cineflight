# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "getMediaFileListState" in s:
    print("DEJA corrige"); raise SystemExit

# 1) ajouter import MediaFileFilter
s = s.replace(
    "import dji.v5.manager.datacenter.media.MediaFileListState",
    "import dji.v5.manager.datacenter.media.MediaFileListState\nimport dji.v5.manager.datacenter.media.MediaFileFilter",
    1)

# 2) remplacer le corps de listerPhotosAvecEssais
vieux = '''    private fun listerPhotosAvecEssais(essaisRestants: Int, onListe: (List<MediaFile>) -> Unit) {
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

neuf = '''    private fun listerPhotosAvecEssais(essaisRestants: Int, onListe: (List<MediaFile>) -> Unit) {
        try {
            // Source = carte SD
            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()
            mgr.setMediaFileDataSource(source)

            // Sur Mini 3 : le pull echoue si le MediaManager n'est pas IDLE. On attend IDLE.
            val etat = try { mgr.mediaFileListState } catch (e: Exception) { null }
            Log.i(TAG, "etat MediaManager: $etat")
            if (etat != MediaFileListState.IDLE && etat != MediaFileListState.UP_TO_DATE) {
                if (essaisRestants > 1) {
                    Log.w(TAG, "manager pas pret ($etat), nouvel essai dans 1000ms... restants=${essaisRestants - 1}")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        listerPhotosAvecEssais(essaisRestants - 1, onListe)
                    }, 1000)
                    return
                }
            }

            // nettoie un pull precedent reste coince
            try { mgr.stopPullMediaFileListFromCamera() } catch (_: Exception) {}

            // param AVEC filtre PHOTO (necessaire sur Mini 3/4)
            val param = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .count(-1)
                .filter(MediaFileFilter.PHOTO)
                .build()
            mgr.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val data = try { mgr.mediaFileListData.data ?: emptyList() } catch (e: Exception) { emptyList() }
                    val photos = data.filter { val n = it.fileName.lowercase(); n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".dng") }
                    Log.i(TAG, "photos trouvees: ${photos.size}")
                    onListe(photos)
                }
                override fun onFailure(error: IDJIError) {
                    if (essaisRestants > 1) {
                        Log.w(TAG, "liste echec (${error.description()}), nouvel essai dans 1000ms... restants=${essaisRestants - 1}")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            listerPhotosAvecEssais(essaisRestants - 1, onListe)
                        }, 1000)
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
    print("listerPhotos corrige (IDLE + stopPull + filtre) OK")
else:
    print("ANCRE NON TROUVEE")