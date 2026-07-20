# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "import dji.sdk.keyvalue.key.KeyTools" in s:
    print("DEJA importes"); raise SystemExit

anc = "import dji.sdk.keyvalue.value.camera.CameraStorageLocation"
ajout = anc + "\nimport dji.sdk.keyvalue.key.KeyTools\nimport dji.v5.manager.KeyManager"

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("imports KeyTools + KeyManager ajoutes OK")
else:
    print("ANCRE NON TROUVEE")