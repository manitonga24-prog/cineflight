# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "railCarteDefini" in s and "chargerRailDepuisCarte" in s:
    print("DEJA present"); raise SystemExit

anc = '''        try { if (miniVisible) miniCarteVue?.onResume() } catch (_: Exception) {}
    }'''

neuf = '''        try { if (miniVisible) miniCarteVue?.onResume() } catch (_: Exception) {}
        try { chargerRailDepuisCarte() } catch (_: Exception) {}
    }

    /** Au retour de la carte : si un rail A/B a ete defini la-bas, le charger dans le Cable-Cam. */
    private fun chargerRailDepuisCarte() {
        if (!MainActivity.railCarteDefini) return
        MainActivity.railCarteDefini = false
        val e = pont.lireEtat(pilote.enVol)
        // altitude/cap : on prend l'etat actuel du drone (rail horizontal a l'altitude courante)
        val alt = if (e.gpsValide) e.altitudeAgl else 10.0
        val cap = e.capDeg
        cableCam.memoriserA(MainActivity.railACarteLat, MainActivity.railACarteLon, alt, cap)
        cableCam.memoriserB(MainActivity.railBCarteLat, MainActivity.railBCarteLon, alt, cap)
        findViewById<Button>(R.id.btnRailA)?.let {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt()); it.setTextColor(0xFF000000.toInt())
        }
        findViewById<Button>(R.id.btnRailB)?.let {
            it.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt()); it.setTextColor(0xFF000000.toInt())
        }
        android.widget.Toast.makeText(this, "Rail charge depuis la carte. Touchez Go pour lancer.", android.widget.Toast.LENGTH_LONG).show()
    }'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("lecture rail carte dans onResume OK")
else:
    print("ANCRE NON TROUVEE")