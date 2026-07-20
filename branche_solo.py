# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "yoloSuivi" in s:
    print("DEJA branche (yoloSuivi present). Rien fait."); raise SystemExit
changes = 0
anchor1 = "import ca.cineflight.stage.control.OverlayYolo"
add1 = anchor1 + "\nimport com.tencent.yolo11ncnn.YOLO11Ncnn\nimport ca.cineflight.stage.control.YoloSuivi"
if anchor1 in s:
    s = s.replace(anchor1, add1, 1); changes += 1
anchor2 = "private lateinit var overlayYolo: OverlayYolo"
add2 = anchor2 + "\n    private val yolo = YOLO11Ncnn()\n    private var yoloSuivi: YoloSuivi? = null"
if anchor2 in s:
    s = s.replace(anchor2, add2, 1); changes += 1
anchor3 = "overlayYolo = findViewById(R.id.overlayYolo)"
bloc3 = anchor3 + """

        // --- CineFlight Solo : YOLO embarque ---
        if (!MODE_SIMULE) {
            val okModel = yolo.loadModel(assets, 0, 0, 0)
            android.util.Log.i("Solo", "loadModel = $okModel")
            yoloSuivi = YoloSuivi(yolo) { trouve, cx, cy, w, h ->
                runOnUiThread {
                    val boxes = if (trouve)
                        listOf(RecepteurBoxes.Box(cx - w / 2f, cy - h / 2f, w, h, 1f, true))
                    else emptyList()
                    overlayYolo.majBoxes(boxes)
                    if (trouve && modeAuto) {
                        pilote.soumettre(calculerSuivi(cx, cy, w, h))
                    }
                }
            }
        }"""
if anchor3 in s:
    s = s.replace(anchor3, bloc3, 1); changes += 1
anchor4 = "flux?.demarrer()"
idx4 = s.find(anchor4)
if idx4 != -1:
    s = s[:idx4] + "flux?.demarrer()\n                        yoloSuivi?.demarrer()" + s[idx4+len(anchor4):]
    changes += 1
fonc = """
    private fun calculerSuivi(cx: Float, cy: Float, w: Float, h: Float): RecepteurBridge.CommandeBridge {
        val errX = cx - 0.5f
        val yawRate = (errX * 40f).coerceIn(-25f, 25f)
        val cibleH = 0.55f
        val errH = cibleH - h
        val vx = (errH * 1.2f).coerceIn(-0.8f, 0.8f)
        val errY = cy - 0.5f
        val gimbalPitch = (-errY * 25f).coerceIn(-20f, 20f)
        return RecepteurBridge.CommandeBridge(
            vx = vx, vy = 0f, vz = 0f, yawRate = yawRate,
            recuA = System.currentTimeMillis(),
            gimbalPitch = gimbalPitch, gimbalYaw = 0f
        )
    }
"""
idx5 = s.rstrip().rfind("}")
s = s[:idx5] + fonc + "\n}\n"
changes += 1
anchor6 = "flux?.arreter()"
if anchor6 in s:
    s = s.replace(anchor6, "flux?.arreter()\n        yoloSuivi?.arreter()", 1); changes += 1
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Insertions :", changes, "/ 6")
print("yoloSuivi present :", "yoloSuivi" in s)
print("calculerSuivi present :", "calculerSuivi" in s)