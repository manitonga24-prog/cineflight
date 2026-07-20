# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()

anc = '''                onSujet = { trouve, cx, cy, w, h ->
                    runOnUiThread {
                        val boxes = if (trouve)'''
add = '''                onSujet = { trouve, cx, cy, w, h ->
                    runOnUiThread {
                        // === CABLE-CAM : si un rail est en cours, il prend le controle (ignore le suivi) ===
                        if (cableCam.enCours) {
                            val e = pont.lireEtat(pilote.enVol)
                            if (e.gpsValide) {
                                val v = cableCam.calculer(e.latitude, e.longitude, e.altAgl.toDouble(), e.capDeg)
                                if (v != null) {
                                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                                        t = System.currentTimeMillis() / 1000.0,
                                        vx = v[0], vy = v[1], vz = v[2], yawRate = v[3],
                                        mode = "actif", recuA = System.currentTimeMillis()))
                                } else {
                                    // arrive a B : hover + reset
                                    cableCam.arreter()
                                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                                        System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))
                                    txtSujet.text = "\\u25CF RAIL TERMINE"
                                }
                            } else {
                                // pas de GPS fiable : hover de securite
                                pilote.soumettre(RecepteurBridge.CommandeBridge(
                                    System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))
                            }
                            return@runOnUiThread
                        }
                        val boxes = if (trouve)'''
if 'CABLE-CAM : si un rail' in s:
    print("DEJA insere")
elif anc in s:
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("prise de controle Cable-Cam inseree OK")
else:
    print("ANCRE NON TROUVEE")