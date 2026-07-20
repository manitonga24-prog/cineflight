# -*- coding: utf-8 -*-
ch = 0

# 1) INTERFACE dans Telemetrie.kt
ft = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\Telemetrie.kt"
s = open(ft, encoding="utf-8").read()
if "reglerResolutionFps" not in s:
    a = "    fun reglerEv(ev: Float)\n}"
    n = "    fun reglerEv(ev: Float)\n    fun reglerResolutionFps(resNom: String, fps: Int)\n}"
    if a in s:
        s = s.replace(a, n, 1); ch+=1
        open(ft, "w", encoding="utf-8", newline="\n").write(s)
print("Interface :", ch)

# 2) PONT (PontCockpitImpl.kt) : reel (reflexion) + simule (vide)
fp = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
p = open(fp, encoding="utf-8").read()
ch2 = 0

# reel : ajouter apres la fonction convertirEv (fin de la classe reelle, avant le commentaire COCKPIT SIMULE)
if "reglerResolutionFps" not in p:
    anc_reel = '''        return signe + "_" + ent + "_" + dec   // ex P_0_3, N_1_0, P_2_0
    }
}'''
    new_reel = '''        return signe + "_" + ent + "_" + dec   // ex P_0_3, N_1_0, P_2_0
    }

    override fun reglerResolutionFps(resNom: String, fps: Int) {
        // Resolution + fps via CameraKey.KeyVideoResolutionFrameRate (best effort,
        // par reflexion : le type exact (VideoResolutionFrameRate) et les noms
        // d'enum varient selon le drone/version. try/catch -> pas de crash.
        try {
            val km = dji.v5.manager.KeyManager.getInstance()
            val cle = dji.sdk.keyvalue.key.KeyTools.createKey(CameraKey.KeyVideoResolutionFrameRate)
            Log.i("PontDjiReelCockpit", "reglerResolutionFps: $resNom @ ${fps}fps (cle prete, valeur a confirmer sur materiel)")
            // L'objet VideoResolutionFrameRate se construit selon la version SDK ;
            // a finaliser sur le drone avec l'autocompletion. Cle deja resolue ci-dessus.
        } catch (e: Throwable) {
            Log.w("PontDjiReelCockpit", "reglerResolutionFps non applique: " + e.message)
        }
    }
}'''
    if anc_reel in p:
        p = p.replace(anc_reel, new_reel, 1); ch2+=1

# simule : ajouter apres reglerEv vide du simule
if p.count("reglerResolutionFps") < 2:
    anc_sim = "    override fun reglerEv(ev: Float) {}"
    new_sim = "    override fun reglerEv(ev: Float) {}\n    override fun reglerResolutionFps(resNom: String, fps: Int) {}"
    if anc_sim in p:
        p = p.replace(anc_sim, new_sim, 1); ch2+=1

open(fp, "w", encoding="utf-8", newline="\n").write(p)
print("Pont (reel+simule) :", ch2, "/ 2")