# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "panneauHL = LinearLayout" in s:
    print("DEJA present"); raise SystemExit

anc = '''        carte.setOnTouchListener { _, _ -> suiviAuto = false; false }
        setContentView(racine)
        placerDecollage()'''

neuf = '''        carte.setOnTouchListener { _, _ -> if (!modeRail) suiviAuto = false; false }

        // === CARTE HORS LIGNE : bouton dans la barre ===
        val btnHorsLigne = Button(this).apply {
            text = "Carte hors ligne"; isAllCaps = false
            setOnClickListener { ouvrirPanneauHorsLigne() }
        }
        barre.addView(btnHorsLigne, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))

        // === PANNEAU HORS LIGNE (cache par defaut) ===
        panneauHL = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE101418.toInt())
            setPadding(dpx(20), dpx(20), dpx(20), dpx(20))
            visibility = View.GONE
        }
        panneauHL.addView(TextView(this).apply {
            text = "Telecharger la carte de cette zone"
            setTextColor(Color.WHITE); textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        panneauHL.addView(TextView(this).apply {
            text = "Glissez la carte pour centrer la zone, puis choisissez le rayon."
            setTextColor(0xFFB0BEC5.toInt()); textSize = 12f
            setPadding(0, dpx(4), 0, dpx(12))
        })
        lblEstimation = TextView(this).apply {
            setTextColor(0xFF4FC3F7.toInt()); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panneauHL.addView(lblEstimation)
        // curseur de rayon : 500 m a 2000 m
        val seekRayon = SeekBar(this).apply {
            max = 15   // 500 + prog*100  -> 500..2000 m
            progress = 5   // 1000 m
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    rayonHorsLigneM = (500 + prog * 100).toDouble()
                    majCercleEtEstimation()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panneauHL.addView(seekRayon)
        champNomHL = EditText(this).apply {
            hint = "Nom de la zone (ex : Parc Jean-Drapeau)"
            setTextColor(Color.WHITE); setHintTextColor(0xFF78909C.toInt())
            setPadding(dpx(8), dpx(8), dpx(8), dpx(8))
        }
        panneauHL.addView(champNomHL)
        val ligneBtnHL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpx(12), 0, 0) }
        val btnTelecharger = Button(this).apply {
            text = "Telecharger"; isAllCaps = false
            setBackgroundColor(0xFF00C853.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { lancerTelechargementHorsLigne() }
        }
        val btnAnnulerHL = Button(this).apply {
            text = "Annuler"; isAllCaps = false
            setOnClickListener { fermerPanneauHorsLigne() }
        }
        ligneBtnHL.addView(btnTelecharger, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        ligneBtnHL.addView(btnAnnulerHL, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panneauHL.addView(ligneBtnHL)
        val panneauParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        panneauParams.gravity = android.view.Gravity.BOTTOM
        racine.addView(panneauHL, panneauParams)

        setContentView(racine)
        placerDecollage()'''

if anc in s:
    s = s.replace(anc, neuf, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("panneau hors ligne UI OK")
else:
    print("ANCRE NON TROUVEE")