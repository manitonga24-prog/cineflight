# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()

old = '''                val km = dji.v5.manager.KeyManager.getInstance()
                val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyExposureCompensation)
                val m = km.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size >= 2 }
                if (m != null) {
                    m.invoke(km, cle, choisi)
                    Log.i("PontDjiReelCockpit", "reglerEv: applique " + (choisi as Enum<*>).name + " (ev=$ev)")
                } else Log.w("PontDjiReelCockpit", "reglerEv: setValue introuvable")'''

new = '''                val km = dji.v5.manager.KeyManager.getInstance()
                val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyExposureCompensation)
                // setValue(cle, valeur, callback) : 3 arguments attendus par le SDK
                val m = km.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 3 }
                if (m != null) {
                    val cbType = m.parameterTypes[2]
                    val cb = java.lang.reflect.Proxy.newProxyInstance(
                        cbType.classLoader, arrayOf(cbType)
                    ) { _, methode, args ->
                        when (methode.name) {
                            "onSuccess" -> Log.i("PontDjiReelCockpit", "reglerEv OK: " + (choisi as Enum<*>).name)
                            "onFailure" -> Log.w("PontDjiReelCockpit", "reglerEv echec drone: " + (args?.getOrNull(0)?.toString() ?: ""))
                        }
                        null
                    }
                    m.invoke(km, cle, choisi, cb)
                    Log.i("PontDjiReelCockpit", "reglerEv: envoye " + (choisi as Enum<*>).name + " (ev=$ev)")
                } else Log.w("PontDjiReelCockpit", "reglerEv: setValue(3 args) introuvable")'''

if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("setValue 3 args corrige : OK")
elif "setValue(3 args)" in s:
    print("DEJA corrige")
else:
    print("ANCRE NON TROUVEE")