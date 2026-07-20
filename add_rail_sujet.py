# -*- coding: utf-8 -*-
import re
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "Rail vers sujet" in s:
    print("DEJA present"); raise SystemExit

# ancre : la fin du bRailA.setOnClickListener (le toast "Point A memorise...")
anc = '''                android.widget.Toast.makeText(this, "Point A memorise. Pilotez jusqu'au point B.", android.widget.Toast.LENGTH_SHORT).show()
            }
            bRailB.setOnClickListener {'''

neuf = '''                android.widget.Toast.makeText(this, "Point A memorise. Pilotez jusqu'au point B.", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Appui LONG sur Rail A = definir tout le rail AUTOMATIQUEMENT vers le sujet detecte (YOLO)
            bRailA.setOnLongClickListener {
                val e = pont.lireEtat(pilote.enVol)
                if (!e.gpsValide) {
                    android.widget.Toast.makeText(this, "GPS non fiable.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnLongClickListener true
                }
                if (!dernierSujetTrouve) {
                    android.widget.Toast.makeText(this, "Aucun sujet detecte. Cadrez d'abord la personne.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnLongClickListener true
                }
                // A = position actuelle du drone
                cableCam.memoriserA(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                // direction vers le sujet : cap du drone + angle horizontal derive de la position du sujet (errX)
                // errX = dernierCxSujet - 0.5 ; champ de vue horizontal ~ 73 deg sur Mini 3 -> demi-FOV ~36 deg
                val errX = dernierCxSujet - 0.5f
                val angleSujet = errX * 73f   // deg : sujet a droite (+) ou gauche (-) du nez
                val capVersSujet = ((if (e.capDeg.isNaN()) 0f else e.capDeg) + angleSujet + 360f) % 360f
                // B = point a 8 m dans cette direction
                val distM = 8.0
                val R = 6371000.0
                val capRad = Math.toRadians(capVersSujet.toDouble())
                val dLat = (distM * Math.cos(capRad)) / R
                val dLon = (distM * Math.sin(capRad)) / (R * Math.cos(Math.toRadians(e.latitude)))
                val latB = e.latitude + Math.toDegrees(dLat)
                val lonB = e.longitude + Math.toDegrees(dLon)
                cableCam.memoriserB(latB, lonB, e.altitudeAgl, e.capDeg)
                teinte(bRailA, 0xFF00C853.toInt()); bRailA.setTextColor(0xFF000000.toInt())
                teinte(bRailB, 0xFF00C853.toInt()); bRailB.setTextColor(0xFF000000.toInt())
                android.widget.Toast.makeText(this, "Rail vers sujet pret (8 m). Touchez Go.", android.widget.Toast.LENGTH_LONG).show()
                true
            }
            bRailB.setOnClickListener {'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("rail vers sujet (long-press Rail A) OK")
else:
    print("ANCRE NON TROUVEE")