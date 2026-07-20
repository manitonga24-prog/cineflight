# -*- coding: utf-8 -*-
# 1) Manifest : Activity + FileProvider
fm = r"C:\cineflight_android\CineFlightSolo\app\src\main\AndroidManifest.xml"
s = open(fm, encoding="utf-8").read()
ch = 0
anc = '        <activity android:name=".CarteActivity" android:exported="false" android:screenOrientation="landscape" />'
add = anc + '''
        <activity android:name=".PhototequeActivity" android:exported="false" android:screenOrientation="landscape" />
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>'''
if "PhototequeActivity" in s:
    print("Manifest deja fait")
elif anc in s:
    s = s.replace(anc, add, 1)
    open(fm, "w", encoding="utf-8", newline="\n").write(s)
    ch += 1
    print("Manifest : Activity + FileProvider OK")
else:
    print("ANCRE Manifest NON TROUVEE")

# 2) creer res/xml/file_paths.xml
import os
dossier = r"C:\cineflight_android\CineFlightSolo\app\src\main\res\xml"
os.makedirs(dossier, exist_ok=True)
fp = os.path.join(dossier, "file_paths.xml")
contenu = '''<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="cineflight" path="CineFlight/" />
    <external-files-path name="racine" path="." />
</paths>
'''
open(fp, "w", encoding="utf-8", newline="\n").write(contenu)
print("file_paths.xml cree")