# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "hyperlapseArme" in s:
    print("DEJA present"); raise SystemExit

# 1. champ d'etat a cote de hyperlapse
ancChamp = "private val hyperlapse = ca.cineflight.stage.control.Hyperlapse()  // capture photo periodique pendant le rail"
s = s.replace(ancChamp, ancChamp + "\n    private var hyperlapseArme = false   // mode hyperlapse arme pour le prochain rail", 1)

# 2. bloc onClick : lancer/arreter aussi l'hyperlapse + ajouter le long-press
vieux = '''            bRailGo.setOnClickListener {
                if (cableCam.enCours) {
                    cableCam.arreter()
                    teinte(bRailGo, 0xFF263238.toInt()); bRailGo.text = "Go"
                    android.widget.Toast.makeText(this, "Rail arrete.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    if (!cableCam.pret()) { android.widget.Toast.makeText(this, "Memorisez d'abord Rail A et Rail B.", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    if (!modeAuto) basculerMode(true)   // le rail a besoin du mode auto pour piloter
                    cableCam.demarrer()
                    teinte(bRailGo, 0xFFD32F2F.toInt()); bRailGo.text = "Stop"
                    android.widget.Toast.makeText(this, "Rail lance : le drone glisse vers B.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }'''

neuf = '''            bRailGo.setOnClickListener {
                if (cableCam.enCours) {
                    cableCam.arreter()
                    hyperlapse.arreter()
                    teinte(bRailGo, 0xFF263238.toInt()); bRailGo.text = "Go"
                    val msg = if (hyperlapse.nbPhotos > 0)
                        "Rail arrete. ${hyperlapse.nbPhotos} photos prises (Hyperlapse)." else "Rail arrete."
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    if (!cableCam.pret()) { android.widget.Toast.makeText(this, "Memorisez d'abord Rail A et Rail B.", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    if (!modeAuto) basculerMode(true)   // le rail a besoin du mode auto pour piloter
                    cableCam.demarrer()
                    if (hyperlapseArme) hyperlapse.demarrer()   // capture photo periodique pendant ce rail
                    teinte(bRailGo, 0xFFD32F2F.toInt()); bRailGo.text = "Stop"
                    val msg = if (hyperlapseArme)
                        "Hyperlapse lance : photos toutes les 2 s pendant le rail." else "Rail lance : le drone glisse vers B."
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // Appui LONG sur Go = armer/desarmer le mode Hyperlapse pour le prochain rail
            bRailGo.setOnLongClickListener {
                hyperlapseArme = !hyperlapseArme
                val msg = if (hyperlapseArme)
                    "Hyperlapse ARME : le prochain rail prendra des photos." else "Hyperlapse desarme."
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                // teinte violette quand arme (au repos), gris sinon
                if (!cableCam.enCours) teinte(bRailGo, if (hyperlapseArme) 0xFF6A1B9A.toInt() else 0xFF263238.toInt())
                true
            }
        }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("bouton Go + hyperlapse OK")
else:
    print("ANCRE NON TROUVEE")