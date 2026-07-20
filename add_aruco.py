f = r"C:\cineflight_android\CineFlightSolo\app\src\main\jni\yolo11ncnn.cpp"
s = open(f, encoding="utf-8").read()
if "detectArucoYUV" in s:
    print("DEJA present"); raise SystemExit
anc_inc = "#include <opencv2/imgproc/imgproc.hpp>"
if anc_inc in s:
    s = s.replace(anc_inc, anc_inc + "\n#include <opencv2/objdetect/aruco_detector.hpp>", 1)
    print("include aruco OK")
else:
    print("include: ancre non trouvee")
anc_g = "static YOLO11* g_yolo11 = 0;"
if anc_g in s:
    s = s.replace(anc_g, anc_g + "\nstatic cv::aruco::ArucoDetector* g_aruco = 0;", 1)
    print("g_aruco OK")
else:
    print("g_aruco: ancre non trouvee")
func = """
JNIEXPORT jintArray JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_detectArucoYUV(JNIEnv* env, jobject thiz, jbyteArray nv21_, jint width, jint height)
{
    jbyte* nv21 = env->GetByteArrayElements(nv21_, 0);
    cv::Mat gris(height, width, CV_8UC1, (unsigned char*)nv21);
    if (!g_aruco) {
        cv::aruco::Dictionary dico = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
        cv::aruco::DetectorParameters params;
        g_aruco = new cv::aruco::ArucoDetector(dico, params);
    }
    std::vector<int> ids;
    std::vector<std::vector<cv::Point2f>> corners, rejected;
    try { g_aruco->detectMarkers(gris, corners, ids, rejected); } catch (...) { ids.clear(); }
    env->ReleaseByteArrayElements(nv21_, nv21, 0);
    int n = (int)ids.size();
    jintArray result = env->NewIntArray(1 + n);
    std::vector<jint> buf(1 + n);
    buf[0] = n;
    for (int i = 0; i < n; i++) buf[1 + i] = ids[i];
    env->SetIntArrayRegion(result, 0, 1 + n, buf.data());
    return result;
}
"""
idx = s.rstrip().rfind("}")
s = s[:idx] + func + "\n}\n"
open(f, "w", encoding="utf-8", newline="\n").write(s)
print("detectArucoYUV insere :", "detectArucoYUV" in s)