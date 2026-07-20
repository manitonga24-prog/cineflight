f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "designerCible" in s:
    print("DEJA present"); raise SystemExit
anc = "        txtSujet = findViewById(R.id.txtSujet)"
if anc in s:
    add = anc + '''
        // --- TOUCHER POUR DESIGNER LA CIBLE (mode pilote + sujet) ---
        overlayYolo.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                val cx = (ev.x / v.width).coerceIn(0f, 1f)
                val cy = (ev.y / v.height).coerceIn(0f, 1f)
                yoloSuivi?.designerCible(cx, cy)
                verrouillerCible()
                txtCommandeVoc.text = "Cible designee"
                txtCommandeVoc.visibility = android.view.View.VISIBLE
                txtCommandeVoc.postDelayed({ txtCommandeVoc.visibility = android.view.View.GONE }, 1500)
                v.performClick()
            }
            true
        }'''
    s = s.replace(anc, add, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("Brique B OK :", "designerCible" in s)
else:
    print("ANCRE NON TROUVEE")