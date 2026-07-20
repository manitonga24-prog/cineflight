# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\CableCam.kt"
s = open(f, encoding="utf-8").read()
if "fun distanceVersB" in s:
    print("DEJA presente"); raise SystemExit

# inserer avant le dernier } de la classe
anc = "        return floatArrayOf(vx, vy, vz, yawRate, gimbalPitch)\n    }\n}"
ajout = '''        return floatArrayOf(vx, vy, vz, yawRate, gimbalPitch)
    }

    /** Distance restante (m) jusqu'a B depuis la position courante. -1 si pas de B. */
    fun distanceVersB(latActuel: Double, lonActuel: Double): Double {
        val b = pointB ?: return -1.0
        val lat1 = Math.toRadians(latActuel)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - lonActuel)
        val R = 6371000.0
        val dx = dLon * Math.cos((lat1 + lat2) / 2)
        val dy = lat2 - lat1
        return Math.sqrt(dx * dx + dy * dy) * R
    }
}'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("distanceVersB ajoutee OK")
else:
    print("ANCRE NON TROUVEE")