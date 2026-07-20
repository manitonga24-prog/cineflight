f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\YoloSuivi.kt"
s = open(f, encoding="utf-8").read()
if "detectArucoYUV" in s:
    print("DEJA cable"); raise SystemExit
ch = 0
old1 = '''class YoloSuivi(
    private val yolo: YOLO11Ncnn,
    // callback : (trouve, xCentre, yCentre, largeurBoite, hauteurBoite) tous 0..1
    private val onSujet: (Boolean, Float, Float, Float, Float) -> Unit
) {'''
new1 = '''class YoloSuivi(
    private val yolo: YOLO11Ncnn,
    private val onSujet: (Boolean, Float, Float, Float, Float) -> Unit,
    private val onTag: (Int) -> Unit = {}
) {
    private var dernierTagVu = -1
    private var compteurTag = 0
    private var dernierTagDeclenche = -1'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1
else:
    print("ancre1 non trouvee")
old2 = '''                val res = yolo.detectYUV(frameData, width, height)
                traiter(res, width, height)'''
new2 = '''                val res = yolo.detectYUV(frameData, width, height)
                traiter(res, width, height)
                try {
                    val tags = yolo.detectArucoYUV(frameData, width, height)
                    traiterTags(tags)
                } catch (e: Throwable) { Log.e("YoloSuivi", "aruco: $e") }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1
else:
    print("ancre2 non trouvee")
fonc = '''
    private fun traiterTags(tags: IntArray?) {
        if (tags == null || tags.isEmpty() || tags[0] == 0) {
            compteurTag = 0
            dernierTagVu = -1
            return
        }
        val id = tags[1]
        if (id == dernierTagVu) compteurTag++ else { dernierTagVu = id; compteurTag = 1 }
        if (compteurTag >= 5 && id != dernierTagDeclenche) {
            dernierTagDeclenche = id
            onTag(id)
        }
    }
'''
idx = s.rstrip().rfind("}")
s = s[:idx] + fonc + "\n}\n"
ch+=1
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("YoloSuivi ArUco :", ch, "/ 3")