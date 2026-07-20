# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if 'btnRailA' in s:
    print("DEJA cable"); raise SystemExit

# ancre : la fin du bloc mApp.setOnClickListener (fermeture du run{} des mouvements)
anc = '''                mApp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt())
                mApp.setTextColor(0xFF000000.toInt())
            }
        }'''

ajout = anc + '''

        // === CABLE-CAM : Rail A / Rail B / Go ===
        run {
            val bRailA = findViewById<Button>(R.id.btnRailA)
            val bRailB = findViewById<Button>(R.id.btnRailB)
            val bRailGo = findViewById<Button>(R.id.btnRailGo)
            fun teinte(b: Button, c: Int) { b.backgroundTintList = android.content.res.ColorStateList.valueOf(c) }

            bRailA.setOnClickListener {
                val e = pont.lireEtat(pilote.enVol)
                if (!e.gpsValide) { toast("GPS non fiable : impossible de memoriser le point A"); return@setOnClickListener }
                cableCam.memoriserA(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                teinte(bRailA, 0xFF00C853.toInt()); bRailA.setTextColor(0xFF000000.toInt())
                toast("Point A memorise. Pilotez jusqu'au point B.")
            }
            bRailB.setOnClickListener {
                val e = pont.lireEtat(pilote.enVol)
                if (!e.gpsValide) { toast("GPS non fiable : impossible de memoriser le point B"); return@setOnClickListener }
                cableCam.memoriserB(e.latitude, e.longitude, e.altitudeAgl, e.capDeg)
                teinte(bRailB, 0xFF00C853.toInt()); bRailB.setTextColor(0xFF000000.toInt())
                toast("Point B memorise. Appuyez sur Go pour glisser A vers B.")
            }
            bRailGo.setOnClickListener {
                if (cableCam.enCours) {
                    cableCam.arreter()
                    teinte(bRailGo, 0xFF263238.toInt()); bRailGo.text = "Go"
                    toast("Rail arrete.")
                } else {
                    if (!cableCam.pret()) { toast("Memorisez d'abord Rail A et Rail B."); return@setOnClickListener }
                    if (!modeAuto) basculerMode(true)   // le rail a besoin du mode auto pour piloter
                    cableCam.demarrer()
                    teinte(bRailGo, 0xFFD32F2F.toInt()); bRailGo.text = "Stop"
                    toast("Rail lance : le drone glisse vers B.")
                }
            }
        }'''

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("cablage boutons Rail OK")
else:
    print("ANCRE NON TROUVEE")