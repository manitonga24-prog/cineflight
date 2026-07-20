# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()

# remplacer les helpers par la version au bon format DJI (POS_xPyEV / NEG_xPyEV / NEG_0EV)
old = '''    private fun nomFractionEv(ev: Float): String {
        val a = Math.abs(ev)
        val ent = a.toInt()
        val dec = Math.round((a - ent) * 10).toInt()
        return ent.toString() + "_" + dec
    }
    private fun nomEvCible(ev: Float): String {
        if (Math.abs(ev) < 0.05f) return "N_0_0"
        return (if (ev < 0) "N_" else "P_") + nomFractionEv(ev)
    }'''

new = '''    private fun nomFractionEv(ev: Float): String {
        // format DJI : "0P3", "1P0", "2P0" (P = virgule decimale)
        val a = Math.abs(ev)
        val ent = a.toInt()
        val dec = Math.round((a - ent) * 10).toInt()
        return ent.toString() + "P" + dec
    }
    private fun nomEvCible(ev: Float): String {
        // ex : 0 -> NEG_0EV ; +0.3 -> POS_0P3EV ; -1.0 -> NEG_1P0EV
        if (Math.abs(ev) < 0.05f) return "NEG_0EV"
        return (if (ev < 0) "NEG_" else "POS_") + nomFractionEv(ev) + "EV"
    }'''

if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("nomEvCible corrige (format DJI) : OK")
elif "NEG_0EV" in s:
    print("DEJA corrige")
else:
    print("ANCRE NON TROUVEE")