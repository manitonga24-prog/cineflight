# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "vueSatellite" in s:
    print("DEJA present"); raise SystemExit

# 1) champ d'etat
s = s.replace(
    "    private var rayonHorsLigneM = 1000.0   // rayon en metres (defaut 1 km)",
    "    private var rayonHorsLigneM = 1000.0   // rayon en metres (defaut 1 km)\n    private var vueSatellite = false",
    1)

# 2) bouton bascule dans la barre, apres btnMesCartes
anc = "        barre.addView(btnMesCartes, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))"
neuf = anc + '''
        val btnVue = Button(this).apply {
            text = "Satellite"; isAllCaps = false
            setOnClickListener {
                vueSatellite = !vueSatellite
                if (vueSatellite) {
                    carte.setTileSource(sourceEsriSatellite())
                    text = "Routier"
                } else {
                    carte.setTileSource(TileSourceFactory.MAPNIK)
                    text = "Satellite"
                }
                carte.invalidate()
            }
        }
        barre.addView(btnVue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))'''
s = s.replace(anc, neuf, 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("bascule satellite/routier OK")