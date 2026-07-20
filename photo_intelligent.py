# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) bouton photo : clic court = photo ; appui long = bascule Capture Auto
old = "        btnPhoto.setOnClickListener { pont.declencherPhoto() }"
new = '''        btnPhoto.setOnClickListener { pont.declencherPhoto() }
        btnPhoto.setOnLongClickListener {
            captureAutoActive = !captureAutoActive
            compteurPhotosAuto = 0
            if (captureAutoActive) {
                btnPhoto.text = "\\uD83D\\uDCF8 0"
                btnPhoto.setBackgroundColor(0xFF2E7D32.toInt())
                android.widget.Toast.makeText(this, "Capture Auto ON", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                btnPhoto.text = "\\uD83D\\uDCF7"
                btnPhoto.setBackgroundColor(0xFF37474F.toInt())
                android.widget.Toast.makeText(this, "Capture Auto OFF", android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }'''
if "setOnLongClickListener" in s and "captureAutoActive = !captureAutoActive" in s:
    print("DEJA present (clic long)")
elif old in s:
    s = s.replace(old, new, 1); ch += 1
else:
    print("ANCRE CLIC NON TROUVEE")

# 2) corriger flashCaptureAuto : pointer vers btnPhoto au lieu de btnCaptureAuto
oldf = '''        val b = findViewById<Button>(R.id.btnCaptureAuto)
        b?.text = "\\uD83D\\uDCF8 $compteurPhotosAuto"'''
newf = '''        val b = findViewById<Button>(R.id.btnPhoto)
        b?.text = "\\uD83D\\uDCF8 $compteurPhotosAuto"'''
if oldf in s:
    s = s.replace(oldf, newf, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Bouton photo intelligent :", ch, "modifs")