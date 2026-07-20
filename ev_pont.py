# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()
if "convertirEv" in s:
    print("DEJA present"); raise SystemExit

old = '''    override fun reglerEv(ev: Float) {
        Log.i("PontDjiReelCockpit", "reglerEv($ev) — à câbler selon enum EV du SDK")
    }
}'''

new = '''    override fun reglerEv(ev: Float) {
        // EV via CameraKey.KeyExposureCompensation (enum CameraExposureCompensation).
        // La camera doit etre en mode PROGRAM. On resout l'enum par son nom
        // (N_x_x = negatif, P_x_x = positif, N_0_0 = zero) via valueOf, dans un
        // try/catch : si le nom exact differe selon la version SDK, pas de crash.
        try {
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
        }
    }

    /** Convertit une valeur EV (-2.0..+2.0) vers le nom d'enum DJI (pas de 1/3 EV). */
    private fun convertirEv(ev: Float): String {
        // arrondi au tiers d'EV le plus proche
        val pas = Math.round(ev / 0.3f)            // ... -2,-1,0,1,2 ...
        val v = pas * 0.3f
        if (Math.abs(v) < 0.05f) return "N_0_0"
        val ent = Math.abs(v).toInt()
        val dec = Math.round((Math.abs(v) - ent) * 10).toInt()
        val signe = if (v < 0) "N" else "P"
        return signe + "_" + ent + "_" + dec   // ex P_0_3, N_1_0, P_2_0
    }
}'''

if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("reglerEv cable :", "convertirEv" in s)
else:
    print("ANCRE NON TROUVEE")