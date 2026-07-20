# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "calculerAvecSuivi" in s:
    print("DEJA branche"); raise SystemExit

vieux = '''                        if (cableCam.enCours) {
                            val e = pont.lireEtat(pilote.enVol)
                            if (e.gpsValide) {
                                val v = cableCam.calculer(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                                if (v != null) {
                                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                                        t = System.currentTimeMillis() / 1000.0,
                                        vx = v[0], vy = v[1], vz = v[2], yawRate = v[3],
                                        mode = "actif", recuA = System.currentTimeMillis()))
                                } else {'''

neuf = '''                        if (cableCam.enCours) {
                            val e = pont.lireEtat(pilote.enVol)
                            if (e.gpsValide) {
                                // Si un sujet est detecte : on glisse sur le rail TOUT EN gardant le sujet cadre.
                                // Sinon : rail GPS simple (le drone pointe vers B).
                                val v = if (trouve)
                                    cableCam.calculerAvecSuivi(e.latitude, e.longitude, e.altitudeAgl, e.capDeg, cx, cy)
                                else
                                    cableCam.calculer(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                                if (v != null) {
                                    pilote.soumettre(RecepteurBridge.CommandeBridge(
                                        t = System.currentTimeMillis() / 1000.0,
                                        vx = v[0], vy = v[1], vz = v[2], yawRate = v[3],
                                        mode = "actif", recuA = System.currentTimeMillis(),
                                        gimbalYaw = if (v.size > 4) v[4] else 0f))
                                    // affiche l'etat du rail + suivi eventuel
                                    overlayYolo.majBoxes(if (trouve)
                                        listOf(RecepteurBoxes.Box(cx - w / 2f, cy - h / 2f, w, h, 1f, true))
                                        else emptyList())
                                    txtSujet.text = if (trouve) "\\u25CF RAIL + SUIVI" else "\\u25CF RAIL A\\u2192B"
                                    txtSujet.setTextColor(0xFF1565C0.toInt())
                                } else {'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("branchement suivi OK")
else:
    print("ANCRE NON TROUVEE")