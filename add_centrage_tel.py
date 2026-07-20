# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "centrerSurTelephone" in s:
    print("DEJA present"); raise SystemExit

# 1) appeler le centrage telephone a la fin de placerDecollage
vieux = '''            carte.overlays.add(m)
            marqueurDecollage = m
        }
    }
    private fun majPosition() {'''
neuf = '''            carte.overlays.add(m)
            marqueurDecollage = m
            carte.controller.setCenter(GeoPoint(lat, lon))
        } else {
            // pas de position drone : centrer sur le telephone (utile pour preparer une carte hors ligne)
            centrerSurTelephone()
        }
    }

    private fun centrerSurTelephone() {
        try {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val perm = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 909)
                carte.controller.setZoom(6.0)
                return
            }
            // derniere position connue (instantane, pas besoin d'attendre un fix)
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER)
            var loc: android.location.Location? = null
            for (p in providers) {
                try { val l = lm.getLastKnownLocation(p); if (l != null && (loc == null || l.time > loc!!.time)) loc = l } catch (_: Exception) {}
            }
            if (loc != null) {
                carte.controller.setCenter(GeoPoint(loc!!.latitude, loc!!.longitude))
                carte.controller.setZoom(15.0)
                infoTexte.text = "Position du telephone - naviguez vers votre zone"
            } else {
                carte.controller.setZoom(6.0)   // vue large, l'utilisateur navigue
                infoTexte.text = "Naviguez vers votre zone de tournage"
            }
        } catch (e: Exception) {
            carte.controller.setZoom(6.0)
            infoTexte.text = "Naviguez vers votre zone de tournage"
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 909 && res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            centrerSurTelephone()
        }
    }

    private fun majPosition() {'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("centrage telephone OK")
else:
    print("ANCRE NON TROUVEE")