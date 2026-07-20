# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()

old = '''        try {
            val nom = convertirEv(ev)
            val cls = Class.forName("dji.sdk.keyvalue.value.camera.CameraExposureCompensation")
            @Suppress("UNCHECKED_CAST")
            val valeur = java.lang.Enum.valueOf(cls as Class<out Enum<*>>, nom)
            val km = dji.v5.manager.KeyManager.getInstance()
            val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyExposureCompensation)
            // setValue generique (la signature exacte sera confirmee sur matériel)
            val m = km.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size >= 2 }
            if (m != null) {
                m.invoke(km, cle, valeur)
                Log.i("PontDjiReelCockpit", "reglerEv: applique $nom (ev=$ev)")
            } else {
                Log.w("PontDjiReelCockpit", "reglerEv: setValue introuvable")
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "reglerEv($ev) non applique: " + e.message)
        }'''

new = '''        try {
            val cls = Class.forName("dji.sdk.keyvalue.value.camera.CameraExposureCompensation")
            val constantes = cls.enumConstants  // toutes les valeurs reelles de CETTE version SDK
            // 1) LOG : lister tous les noms disponibles (a lire une fois dans Logcat)
            Log.i("PontDjiReelCockpit", "EV dispo: " + constantes.joinToString { (it as Enum<*>).name })
            // 2) choisir la constante dont le nom correspond le mieux a ev
            val cible = nomEvCible(ev)
            var choisi: Any? = constantes.firstOrNull { (it as Enum<*>).name == cible }
            if (choisi == null) {
                // repli : cherche une constante contenant la valeur (ex "0_3", "1_0")
                val frac = nomFractionEv(ev)
                choisi = constantes.firstOrNull { (it as Enum<*>).name.contains(frac) && (ev >= 0) == !(it).toString().startsWith("N") }
            }
            if (choisi == null) choisi = constantes.firstOrNull { (it as Enum<*>).name.contains("0_0") }  // 0 par defaut
            if (choisi != null) {
                val km = dji.v5.manager.KeyManager.getInstance()
                val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyExposureCompensation)
                val m = km.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size >= 2 }
                if (m != null) {
                    m.invoke(km, cle, choisi)
                    Log.i("PontDjiReelCockpit", "reglerEv: applique " + (choisi as Enum<*>).name + " (ev=$ev)")
                } else Log.w("PontDjiReelCockpit", "reglerEv: setValue introuvable")
            } else {
                Log.w("PontDjiReelCockpit", "reglerEv: aucune constante trouvee pour ev=$ev")
            }
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "reglerEv($ev) non applique: " + e.message)
        }'''

if old in s:
    s = s.replace(old, new, 1)
    # ajouter les helpers nomEvCible / nomFractionEv apres convertirEv
    anc_h = '''        return signe + "_" + ent + "_" + dec   // ex P_0_3, N_1_0, P_2_0
    }'''
    helpers = '''        return signe + "_" + ent + "_" + dec
    }

    private fun nomFractionEv(ev: Float): String {
        val a = Math.abs(ev)
        val ent = a.toInt()
        val dec = Math.round((a - ent) * 10).toInt()
        return ent.toString() + "_" + dec
    }
    private fun nomEvCible(ev: Float): String {
        if (Math.abs(ev) < 0.05f) return "N_0_0"
        return (if (ev < 0) "N_" else "P_") + nomFractionEv(ev)
    }'''
    if anc_h in s:
        s = s.replace(anc_h, helpers, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("reglerEv corrige (auto-detection enum) : OK")
else:
    print("ANCRE NON TROUVEE")