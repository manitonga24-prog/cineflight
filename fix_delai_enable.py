# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\MediaDrone.kt"
s = open(f, encoding="utf-8").read()
if "media active (pret apres delai)" in s:
    print("DEJA corrige"); raise SystemExit

vieux = '''                override fun onSuccess() { Log.i(TAG, "media active"); onFait(true) }'''
neuf = '''                override fun onSuccess() {
                    Log.i(TAG, "media active, attente 3s que le manager soit pret...")
                    // Le SDK media (Mini 3) a besoin de ~3s apres enable avant d'accepter liste/action.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "media active (pret apres delai)"); onFait(true)
                    }, 3000)
                }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("delai 3s apres enable OK")
else:
    print("ANCRE NON TROUVEE")