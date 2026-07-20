# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontCockpitImpl.kt"
s = open(f, encoding="utf-8").read()
if "class PontDjiSimuleCockpit" in s:
    print("DEJA presente"); raise SystemExit

classe = '''

// COCKPIT SIMULE - enveloppe un PontDjiSimule (test sans drone)
class PontDjiSimuleCockpit(
    latDepart: Double = 45.5455,
    lonDepart: Double = -73.6868,
    private val base: PontDjiSimule = PontDjiSimule(latDepart, lonDepart)
) : PontCockpit, PiloteDrone.PontDji by base {

    private val latHome = latDepart
    private val lonHome = lonDepart
    @Volatile private var gimbalPitchSim = 0f
    @Volatile private var rthSim = false

    override fun lireEtat(enVol: Boolean): EtatCockpit {
        val vh = hypot(base.dernierPitch.toDouble(), base.dernierRoll.toDouble())
        val vv = base.dernierThrottle.toDouble()
        val la = latitudeDrone(); val lo = longitudeDrone()
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(latHome))
        val dist = hypot((lo - lonHome) * mLon, (la - latHome) * mLat)
        return EtatCockpit(
            connecte = true, enVol = enVol, batteriePct = 85,
            altitudeAgl = altitudeDrone(), capDeg = capDroneDeg(),
            latitude = la, longitude = lo, satellites = 13, gpsValide = true,
            distanceDecollageM = dist, vitesseHorizM = vh, vitesseVertM = vv,
            signalRcPct = 96, signalVideoPct = 92,
            enregistre = enregistreEnCours(),
            gimbalPitchDeg = gimbalPitchSim, rthEnCours = rthSim
        )
    }

    override fun declencherPhoto() {}
    override fun reglerGimbalPitch(pitchDeg: Float) { gimbalPitchSim = pitchDeg }
    override fun lancerRth(onFini: (Boolean) -> Unit) { rthSim = true; onFini(true) }
    override fun annulerRth(onFini: (Boolean) -> Unit) { rthSim = false; onFini(true) }
    override fun reglerIso(iso: Int) {}
    override fun reglerEv(ev: Float) {}
    override fun reglerResolutionFps(resNom: String, fps: Int) {}
}
'''

# ajouter a la toute fin du fichier
s = s.rstrip() + "\n" + classe
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("classe PontDjiSimuleCockpit restauree OK")