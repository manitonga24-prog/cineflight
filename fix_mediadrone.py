# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) retirer l'import inutile MediaFileFingerPrintListener
old1 = "import dji.v5.manager.datacenter.media.MediaFileFingerPrintListener\n"
if old1 in s:
    s = s.replace(old1, "", 1); ch += 1

# 1b) ajouter l'import du DataSource
anc = "import dji.v5.manager.datacenter.media.PullMediaFileListParam\n"
add = "import dji.v5.manager.datacenter.media.MediaFileListDataSource\n"
if add not in s and anc in s:
    s = s.replace(anc, anc + add, 1); ch += 1

# 2) envelopper la source SDCARD dans MediaFileListDataSource
old2 = "            mgr.setMediaFileDataSource(CameraStorageLocation.SDCARD)"
new2 = "            val source = MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).build()\n            mgr.setMediaFileDataSource(source)"
if old2 in s:
    s = s.replace(old2, new2, 1); ch += 1

# 3) filtrer photos sur extension uniquement (pas de mediaType)
old3 = '                    val photos = data.filter { it.mediaType?.name?.contains("PHOTO", true) == true || it.fileName.endsWith(".jpg", true) || it.fileName.endsWith(".jpeg", true) || it.fileName.endsWith(".dng", true) }'
new3 = '                    val photos = data.filter { val n = it.fileName.lowercase(); n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".dng") }'
if old3 in s:
    s = s.replace(old3, new3, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("MediaDrone corrige :", ch, "/ 4")