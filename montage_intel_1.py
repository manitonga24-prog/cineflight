# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MontageActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# imports
anc_imp = "import ca.cineflight.stage.control.MonteurVideo"
add_imp = """import ca.cineflight.stage.control.MonteurVideo
import ca.cineflight.stage.control.AnalyseurMontage
import ca.cineflight.stage.control.SelecteurSegments
import ca.cineflight.stage.control.Reglages
import com.tencent.yolo11ncnn.YOLO11Ncnn"""
if "import ca.cineflight.stage.control.AnalyseurMontage" not in s:
    s = s.replace(anc_imp, add_imp, 1); ch += 1

# champs : mode + yolo
anc_ch = "    private var position = MonteurVideo.Position.DEBUT"
add_ch = """    private var position = MonteurVideo.Position.DEBUT
    private var mode = SelecteurSegments.Mode.BEST_OF
    private var modeRythme = false   // true = montage rapide sans analyse
    private val yolo = YOLO11Ncnn()
    private var yoloPret = false"""
if "private var mode = SelecteurSegments.Mode" not in s:
    s = s.replace(anc_ch, add_ch, 1); ch += 1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Etape 1 (imports + champs) :", ch, "/ 2")