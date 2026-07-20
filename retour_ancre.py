f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "retourAncre" in s:
    print("DEJA present"); raise SystemExit
ch = 0

# 1) declarations : compteur de perte + horodatage
a1 = "private val SEUIL_BIEN_VU = 0.30f      // hauteur de boite mini pour considerer \"bien vu\""
if a1 in s:
    s = s.replace(a1, a1 + '''
    private var tPerteCible = 0L           // moment ou la cible a ete perdue (0 = vue)
    private val DELAI_AVANT_RETOUR = 3000L // ms de hover avant de lancer le retour GPS''', 1); ch+=1

# 2) dans onSujet, brancher la logique de perte : remplacer le bloc ATTENTE existant
#    Actuellement : else if (modeAuto && !cibleVerrouillee) { hover }
#    On gere aussi : cible verrouillee mais PERDUE (trouve == false)
a2 = '''                        if (trouve && modeAuto && cibleVerrouillee) {
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))
                            // ancrage GPS : si la cible est BIEN vue (assez grande) et GPS fiable,
                            // memoriser la position du drone (le point d'ou il voit bien la cible)
                            if (h >= SEUIL_BIEN_VU) {
                                val e = pont.lireEtat(pilote.enVol)
                                if (e.gpsValide) {
                                    ancreLat = e.latitude
                                    ancreLon = e.longitude
                                    ancreValide = true
                                }
                            }
                        } else if (modeAuto && !cibleVerrouillee) {
                            // ATTENTE : hover stable, aucun deplacement tant que pas de cible
                            pilote.soumettre(RecepteurBridge.CommandeBridge(
                                System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))'''
new2 = '''                        if (trouve && modeAuto && cibleVerrouillee) {
                            tPerteCible = 0L   // cible revue : reset
                            pilote.soumettre(calculerSuivi(cx, cy, w, h))
                            if (h >= SEUIL_BIEN_VU) {
                                val e = pont.lireEtat(pilote.enVol)
                                if (e.gpsValide) {
                                    ancreLat = e.latitude
                                    ancreLon = e.longitude
                                    ancreValide = true
                                }
                            }
                        } else if (modeAuto && cibleVerrouillee && !trouve) {
                            // CIBLE PERDUE : hover quelques secondes, puis retour GPS facon chien fidele
                            if (tPerteCible == 0L) tPerteCible = System.currentTimeMillis()
                            val perdueDepuis = System.currentTimeMillis() - tPerteCible
                            if (perdueDepuis < DELAI_AVANT_RETOUR || !ancreValide) {
                                pilote.soumettre(RecepteurBridge.CommandeBridge(
                                    System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))
                            } else {
                                pilote.soumettre(retourAncre())
                            }
                        } else if (modeAuto && !cibleVerrouillee) {
                            pilote.soumettre(RecepteurBridge.CommandeBridge(
                                System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis()))'''
if a2 in s:
    s = s.replace(a2, new2, 1); ch+=1
else:
    print("ancre bloc onSujet non trouvee")

# 3) fonction retourAncre : navigue vers le point d'ancrage GPS (doux, en montant)
fonc = '''
    // Retour "chien fidele" : revient vers le point d'ancrage GPS, en montant un peu,
    // a vitesse douce. La vision reprend la main des qu'elle retrouve la cible.
    private fun retourAncre(): RecepteurBridge.CommandeBridge {
        val e = pont.lireEtat(pilote.enVol)
        // si GPS plus fiable : securite -> hover
        if (!e.gpsValide || ancreLat.isNaN()) {
            return RecepteurBridge.CommandeBridge(
                System.currentTimeMillis().toDouble(), 0f, 0f, 0f, 0f, "actif", System.currentTimeMillis())
        }
        // cap vers l'ancre (bearing) a partir des coordonnees GPS
        val lat1 = Math.toRadians(e.latitude)
        val lon1 = Math.toRadians(e.longitude)
        val lat2 = Math.toRadians(ancreLat)
        val lon2 = Math.toRadians(ancreLon)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        // distance approx (m) via equirectangulaire
        val R = 6371000.0
        val dx = dLon * Math.cos((lat1 + lat2) / 2)
        val dy = lat2 - lat1
        val dist = Math.sqrt(dx*dx + dy*dy) * R
        // ecart de cap entre le drone et la direction de l'ancre
        val capDrone = if (e.capDeg.isNaN()) 0.0 else e.capDeg.toDouble()
        var ecartCap = bearing - capDrone
        while (ecartCap > 180) ecartCap -= 360
        while (ecartCap < -180) ecartCap += 360
        // yaw doux pour s'orienter vers l'ancre
        val yawRate = (ecartCap * 0.6).coerceIn(-20.0, 20.0).toFloat()
        // avance douce seulement si on est globalement oriente vers l'ancre et pas arrive
        val vx = if (dist > 2.0 && Math.abs(ecartCap) < 45) 0.6f else 0f
        // monte un peu pour elargir la vue (plafonne via vz doux), arret de montee si proche
        val vz = if (dist > 3.0) 0.3f else 0f
        return RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = vx, vy = 0f, vz = vz, yawRate = yawRate,
            mode = "actif", recuA = System.currentTimeMillis())
    }
'''
idx = s.rstrip().rfind("}")
s = s[:idx] + fonc + "\n}\n"
ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Retour chien fidele :", ch, "/ 3")