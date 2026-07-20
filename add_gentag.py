f = r"C:\cineflight_android\CineFlightSolo\app\src\main\jni\yolo11ncnn.cpp"
s = open(f, encoding="utf-8").read()
if "genererTag" in s:
    print("DEJA present"); raise SystemExit

func = """
// public native int[] genererTag(int id, int taille);
// Genere l'image d'un marqueur ArUco (dict 4x4_50) de taille x taille pixels.
// Retour : [taille, pix0, pix1, ...] niveaux de gris (0 ou 255), taille*taille pixels.
JNIEXPORT jintArray JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_genererTag(JNIEnv* env, jobject thiz, jint id, jint taille)
{
    cv::aruco::Dictionary dico = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
    cv::Mat img;
    cv::aruco::generateImageMarker(dico, id, taille, img, 1);
    int n = taille * taille;
    jintArray result = env->NewIntArray(1 + n);
    std::vector<jint> buf(1 + n);
    buf[0] = taille;
    for (int i = 0; i < n; i++) buf[1 + i] = (jint)img.data[i];
    env->SetIntArrayRegion(result, 0, 1 + n, buf.data());
    return result;
}
"""
idx = s.rstrip().rfind("}")
s = s[:idx] + func + "\n}\n"
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("genererTag insere :", "genererTag" in s)