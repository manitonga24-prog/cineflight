# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "FORMAT signature" in s:
    print("DEJA en mode decouverte"); raise SystemExit

# inserer un log de decouverte au debut de formaterCarteSD
anc = '''    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        try {'''

neuf = '''    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        try {
            // DECOUVERTE : inspecter la cle KeyFormatStorage
            try {
                val champ = Class.forName("dji.sdk.keyvalue.key.CameraKey").getField("KeyFormatStorage")
                val typeCle = champ.genericType.toString()
                Log.i(TAG, "FORMAT signature KeyFormatStorage: $typeCle")
            } catch (e: Throwable) { Log.w(TAG, "FORMAT signature err: " + e.message) }'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("decouverte signature ajoutee OK")
else:
    print("ANCRE NON TROUVEE")