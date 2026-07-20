// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <android/bitmap.h>

#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>

#include <platform.h>
#include <benchmark.h>

#include "yolo11.h"

#include "ndkcamera.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/objdetect/aruco_detector.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif // __ARM_NEON

static int draw_unsupported(cv::Mat& rgb)
{
    const char text[] = "unsupported";

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 1.0, 1, &baseLine);

    int y = (rgb.rows - label_size.height) / 2;
    int x = (rgb.cols - label_size.width) / 2;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 0, 0));

    return 0;
}

static int draw_fps(cv::Mat& rgb)
{
    // resolve moving average
    float avg_fps = 0.f;
    {
        static double t0 = 0.f;
        static float fps_history[10] = {0.f};

        double t1 = ncnn::get_current_time();
        if (t0 == 0.f)
        {
            t0 = t1;
            return 0;
        }

        float fps = 1000.f / (t1 - t0);
        t0 = t1;

        for (int i = 9; i >= 1; i--)
        {
            fps_history[i] = fps_history[i - 1];
        }
        fps_history[0] = fps;

        if (fps_history[9] == 0.f)
        {
            return 0;
        }

        for (int i = 0; i < 10; i++)
        {
            avg_fps += fps_history[i];
        }
        avg_fps /= 10.f;
    }

    char text[32];
    sprintf(text, "FPS=%.2f", avg_fps);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

    int y = 0;
    int x = rgb.cols - label_size.width;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));

    return 0;
}

static YOLO11* g_yolo11 = 0;
static cv::aruco::ArucoDetector* g_aruco = 0;
static ncnn::Mutex lock;

class MyNdkCamera : public NdkCameraWindow
{
public:
    virtual void on_image_render(cv::Mat& rgb) const;
};

void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    // yolo11
    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo11)
        {
            std::vector<Object> objects;
            g_yolo11->detect(rgb, objects);

            g_yolo11->draw(rgb, objects);
        }
        else
        {
            draw_unsupported(rgb);
        }
    }

    draw_fps(rgb);
}

static MyNdkCamera* g_camera = 0;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnLoad");

    g_camera = new MyNdkCamera;

    ncnn::create_gpu_instance();

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnUnload");

    {
        ncnn::MutexLockGuard g(lock);

        delete g_yolo11;
        g_yolo11 = 0;
    }

    ncnn::destroy_gpu_instance();

    delete g_camera;
    g_camera = 0;
}

// public native boolean loadModel(AssetManager mgr, int taskid, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint taskid, jint modelid, jint cpugpu)
{
    if (taskid < 0 || taskid > 4 || modelid < 0 || modelid > 8 || cpugpu < 0 || cpugpu > 2)
    {
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "loadModel %p", mgr);

    const char* tasknames[5] =
    {
        "",
        "_seg",
        "_pose",
        "_cls",
        "_obb"
    };

    const char* modeltypes[9] =
    {
        "n",
        "s",
        "m",
        "n",
        "s",
        "m",
        "n",
        "s",
        "m"
    };

    std::string parampath = std::string("yolo11") + modeltypes[(int)modelid] + tasknames[(int)taskid] + ".ncnn.param";
    std::string modelpath = std::string("yolo11") + modeltypes[(int)modelid] + tasknames[(int)taskid] + ".ncnn.bin";
    bool use_gpu = (int)cpugpu == 1;
    bool use_turnip = (int)cpugpu == 2;

    // reload
    {
        ncnn::MutexLockGuard g(lock);

        {
            static int old_taskid = 0;
            static int old_modelid = 0;
            static int old_cpugpu = 0;
            if (taskid != old_taskid || (modelid % 3) != old_modelid || cpugpu != old_cpugpu)
            {
                // taskid or model or cpugpu changed
                delete g_yolo11;
                g_yolo11 = 0;
            }
            old_taskid = taskid;
            old_modelid = modelid % 3;
            old_cpugpu = cpugpu;

            ncnn::destroy_gpu_instance();

            if (use_turnip)
            {
                ncnn::create_gpu_instance("libvulkan_freedreno.so");
            }
            else if (use_gpu)
            {
                ncnn::create_gpu_instance();
            }

            if (!g_yolo11)
            {
                if (taskid == 0) g_yolo11 = new YOLO11_det;
                if (taskid == 1) g_yolo11 = new YOLO11_seg;
                if (taskid == 2) g_yolo11 = new YOLO11_pose;
                if (taskid == 3) g_yolo11 = new YOLO11_cls;
                if (taskid == 4) g_yolo11 = new YOLO11_obb;

                g_yolo11->load(mgr, parampath.c_str(), modelpath.c_str(), use_gpu || use_turnip);
            }
            int target_size = 320;
            if ((int)modelid >= 3)
                target_size = 480;
            if ((int)modelid >= 6)
                target_size = 640;
            g_yolo11->set_det_target_size(target_size);
        }
    }

    return JNI_TRUE;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_openCamera(JNIEnv* env, jobject thiz, jint facing)
{
    if (facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);

    g_camera->open((int)facing);

    return JNI_TRUE;
}

// public native boolean closeCamera();
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_closeCamera(JNIEnv* env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "closeCamera");

    g_camera->close();

    return JNI_TRUE;
}

// public native boolean setOutputWindow(Surface surface);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_setOutputWindow(JNIEnv* env, jobject thiz, jobject surface)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setOutputWindow %p", win);

    g_camera->set_window(win);

    return JNI_TRUE;
}


JNIEXPORT jfloatArray JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_detectBitmap(JNIEnv* env, jobject thiz, jobject bitmap)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;
    void* pixels = 0;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    cv::Mat rgba((int)info.height, (int)info.width, CV_8UC4, pixels);
    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
    AndroidBitmap_unlockPixels(env, bitmap);
    std::vector<Object> objects;
    {
        ncnn::MutexLockGuard g(lock);
        if (g_yolo11) g_yolo11->detect(rgb, objects);
    }
    int n = (int)objects.size();
    jfloatArray result = env->NewFloatArray(1 + n * 6);
    std::vector<float> buf(1 + n * 6);
    buf[0] = (float)n;
    for (int i = 0; i < n; i++) {
        buf[1+i*6+0] = (float)objects[i].label;
        buf[1+i*6+1] = objects[i].prob;
        buf[1+i*6+2] = objects[i].rect.x;
        buf[1+i*6+3] = objects[i].rect.y;
        buf[1+i*6+4] = objects[i].rect.width;
        buf[1+i*6+5] = objects[i].rect.height;
    }
    env->SetFloatArrayRegion(result, 0, 1 + n * 6, buf.data());
    return result;
}


JNIEXPORT jfloatArray JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_detectYUV(JNIEnv* env, jobject thiz, jbyteArray nv21_, jint width, jint height)
{
    jbyte* nv21 = env->GetByteArrayElements(nv21_, 0);
    cv::Mat yuv(height + height / 2, width, CV_8UC1, (unsigned char*)nv21);
    cv::Mat rgb;
    cv::cvtColor(yuv, rgb, cv::COLOR_YUV2RGB_NV21);
    env->ReleaseByteArrayElements(nv21_, nv21, 0);
    std::vector<Object> objects;
    {
        ncnn::MutexLockGuard g(lock);
        if (g_yolo11) g_yolo11->detect(rgb, objects);
    }
    int n = (int)objects.size();
    jfloatArray result = env->NewFloatArray(1 + n * 6);
    std::vector<float> buf(1 + n * 6);
    buf[0] = (float)n;
    for (int i = 0; i < n; i++) {
        buf[1+i*6+0] = (float)objects[i].label;
        buf[1+i*6+1] = objects[i].prob;
        buf[1+i*6+2] = objects[i].rect.x;
        buf[1+i*6+3] = objects[i].rect.y;
        buf[1+i*6+4] = objects[i].rect.width;
        buf[1+i*6+5] = objects[i].rect.height;
    }
    env->SetFloatArrayRegion(result, 0, 1 + n * 6, buf.data());
    return result;
}


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
    g_aruco->detectMarkers(gris, corners, ids, rejected);
    env->ReleaseByteArrayElements(nv21_, nv21, 0);
    int n = (int)ids.size();
    // Retour : [n, id0, taille0, id1, taille1, ...] ; taille = cote moyen du tag en pixels.
    jintArray result = env->NewIntArray(1 + 2 * n);
    std::vector<jint> buf(1 + 2 * n);
    buf[0] = n;
    for (int i = 0; i < n; i++) {
        buf[1 + 2 * i] = ids[i];
        // cote moyen : moyenne des 4 distances entre coins consecutifs
        float perim = 0.0f;
        const std::vector<cv::Point2f>& c = corners[i];
        for (int k = 0; k < 4; k++) {
            const cv::Point2f& p0 = c[k];
            const cv::Point2f& p1 = c[(k + 1) % 4];
            perim += std::sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y));
        }
        buf[2 + 2 * i] = (jint)(perim / 4.0f + 0.5f);   // cote moyen arrondi (px)
    }
    env->SetIntArrayRegion(result, 0, 1 + 2 * n, buf.data());
    return result;
}


// public native int[] genererTag(int id, int taille);
// Genere l'image d'un marqueur ArUco (dict 4x4_50) de taille x taille pixels.
// Retour : [taille, pix0, pix1, ...] niveaux de gris (0 ou 255), taille*taille pixels.
// =====================================================================
//  SCORE D'OUVERTURE V0 â€” port C++ fidÃ¨le de score_ouverture_v0.py
//  (CineFlight Solo, Sentinelle V2). Utilise OpenCV (core + imgproc).
//  Retour JNI : float[3] = { score, confiance, evaluable }
//
//  Ã€ INSÃ‰RER dans yolo11ncnn.cpp, AVANT la derniÃ¨re accolade du bloc
//  extern "C" { ... } (lÃ  oÃ¹ sont les autres JNIEXPORT).
//
//  Les seuils/poids sont IDENTIQUES au Python figÃ© (spec Â§11ter).
// =====================================================================



// ---- paramÃ¨tres de calibration (identiques au Python figÃ©) ----
static const double P_CIEL        = 0.15;
static const double P_HORIZON     = 0.30;
static const double P_CONTOURS    = 0.15;
static const double P_TEXTURE     = 0.15;
static const double P_VERTICALITE = 0.25;

static const double PENALITE_GAIN = 25.0;
static const double BORNE_B       = 0.8;
static const double BORNE_C       = 20.0;

static const double FLOU_MIN_VARLAPLACE  = 40.0;
static const double EXPO_FRAC_SATUREE_MAX = 0.35;

static inline double clip01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }
static inline double clip100(double v) { return v < 0.0 ? 0.0 : (v > 100.0 ? 100.0 : v); }

// ---- indice ciel : moitiÃ© haute, lumineux + peu saturÃ© + peu texturÃ© ----
static double indice_ciel(const cv::Mat& rgb) {
    int h = rgb.rows, w = rgb.cols;
    cv::Mat haut = rgb(cv::Rect(0, 0, w, h / 2));
    cv::Mat hsv; cv::cvtColor(haut, hsv, cv::COLOR_RGB2HSV);
    std::vector<cv::Mat> ch; cv::split(hsv, ch);
    cv::Mat v, s;
    ch[2].convertTo(v, CV_32F, 1.0 / 255.0);   // luminositÃ©
    ch[1].convertTo(s, CV_32F, 1.0 / 255.0);   // saturation
    cv::Mat gris; cv::cvtColor(haut, gris, cv::COLOR_RGB2GRAY);
    cv::Mat grisf; gris.convertTo(grisf, CV_32F);
    cv::Mat lap; cv::Laplacian(grisf, lap, CV_32F);
    cv::Mat texAbs = cv::abs(lap);
    cv::Mat texLoc; cv::blur(texAbs, texLoc, cv::Size(9, 9));
    cv::Mat texNorm = texLoc / 30.0;
    cv::threshold(texNorm, texNorm, 1.0, 1.0, cv::THRESH_TRUNC); // clip haut Ã  1
    // masque ciel = v>0.55 & s<0.45 & texNorm<0.5
    int total = v.rows * v.cols, nb = 0;
    for (int y = 0; y < v.rows; y++) {
        const float* pv = v.ptr<float>(y);
        const float* ps = s.ptr<float>(y);
        const float* pt = texNorm.ptr<float>(y);
        for (int x = 0; x < v.cols; x++)
            if (pv[x] > 0.55f && ps[x] < 0.45f && pt[x] < 0.5f) nb++;
    }
    double frac = total > 0 ? (double)nb / total : 0.0;
    return clip100(frac * 130.0);
}

// ---- indice horizon : pic de gradient vertical (Sobel y) + continuitÃ© ----
static double indice_horizon(const cv::Mat& rgb) {
    cv::Mat gris; cv::cvtColor(rgb, gris, cv::COLOR_RGB2GRAY);
    cv::Mat grisf; gris.convertTo(grisf, CV_32F);
    cv::Mat sy; cv::Sobel(grisf, sy, CV_32F, 0, 1, 3);
    sy = cv::abs(sy);
    int h = sy.rows, w = sy.cols;
    // profil = moyenne par ligne
    std::vector<double> profil(h, 0.0);
    double pmax = 0.0;
    for (int y = 0; y < h; y++) {
        const float* p = sy.ptr<float>(y);
        double s = 0.0; for (int x = 0; x < w; x++) s += p[x];
        profil[y] = s / w;
        if (profil[y] > pmax) pmax = profil[y];
    }
    if (pmax <= 1e-6) return 0.0;
    // mÃ©diane du profil
    std::vector<double> tri = profil; std::sort(tri.begin(), tri.end());
    double fond = tri[tri.size() / 2] + 1e-6;
    double contraste_pic = pmax / fond;
    // ligne du pic
    int ligne = 0; for (int y = 1; y < h; y++) if (profil[y] > profil[ligne]) ligne = y;
    // continuitÃ© : fraction de colonnes au-dessus du 60e percentile de la ligne
    const float* pl = sy.ptr<float>(ligne);
    std::vector<float> row(pl, pl + w); std::sort(row.begin(), row.end());
    float seuil_col = row[(int)(0.60 * (w - 1))];
    int actives = 0; for (int x = 0; x < w; x++) if (pl[x] > seuil_col) actives++;
    double continuite = (double)actives / w;
    double score = clip100((contraste_pic - 1.0) * 18.0) * (0.4 + 0.6 * continuite);
    return clip100(score);
}

// ---- indice contours : densitÃ© Canny (peu de contours = ouvert) ----
static double indice_contours(const cv::Mat& rgb) {
    cv::Mat gris; cv::cvtColor(rgb, gris, cv::COLOR_RGB2GRAY);
    cv::Mat bords; cv::Canny(gris, bords, 80, 160);
    double densite = (double)cv::countNonZero(bords) / (bords.rows * bords.cols);
    double score = 100.0 * (1.0 - clip01(densite / 0.15));
    return clip100(score);
}

// ---- indice texture : hÃ©tÃ©rogÃ©nÃ©itÃ© des variances de blocs 8x8 ----
static double indice_texture(const cv::Mat& rgb) {
    cv::Mat gris; cv::cvtColor(rgb, gris, cv::COLOR_RGB2GRAY);
    cv::Mat grisf; gris.convertTo(grisf, CV_32F);
    int h = grisf.rows, w = grisf.cols, nb = 8;
    int bh = std::max(1, h / nb), bw = std::max(1, w / nb);
    std::vector<double> variances;
    for (int i = 0; i < nb; i++) {
        for (int j = 0; j < nb; j++) {
            int y0 = i * bh, x0 = j * bw;
            if (y0 >= h || x0 >= w) continue;
            int bh2 = std::min(bh, h - y0), bw2 = std::min(bw, w - x0);
            cv::Mat bloc = grisf(cv::Rect(x0, y0, bw2, bh2));
            cv::Scalar mean, stddev; cv::meanStdDev(bloc, mean, stddev);
            variances.push_back(stddev[0] * stddev[0]);  // variance
        }
    }
    if (variances.empty()) return 0.0;
    // Ã©cart-type des variances (hÃ©tÃ©rogÃ©nÃ©itÃ©)
    double m = 0.0; for (double v : variances) m += v; m /= variances.size();
    double s = 0.0; for (double v : variances) s += (v - m) * (v - m);
    double hetero = std::sqrt(s / variances.size());
    double score = 100.0 * (1.0 - clip01(hetero / 1500.0));
    return clip100(score);
}

// ---- indice verticalitÃ© : Hough, fraction de longueur verticale ----
static double indice_verticalite(const cv::Mat& rgb) {
    cv::Mat gris; cv::cvtColor(rgb, gris, cv::COLOR_RGB2GRAY);
    cv::Mat bords; cv::Canny(gris, bords, 80, 160);
    std::vector<cv::Vec4i> lignes;
    int minLen = std::max(20, gris.rows / 8);
    cv::HoughLinesP(bords, lignes, 1, CV_PI / 180.0, 60, (double)minLen, 10.0);
    if (lignes.empty()) return 60.0;
    double long_vert = 0.0, long_horiz = 0.0;
    for (const auto& l : lignes) {
        double dx = std::abs(l[2] - l[0]), dy = std::abs(l[3] - l[1]);
        double longueur = std::hypot(dx, dy);
        double ang = std::atan2(dy, dx + 1e-6) * 180.0 / CV_PI; // 0=horiz, 90=vert
        if (ang >= 60.0) long_vert += longueur;
        else if (ang <= 30.0) long_horiz += longueur;
    }
    double total = long_vert + long_horiz;
    if (total <= 1e-6) return 60.0;
    double frac_vert = long_vert / total;
    return clip100(100.0 * (1.0 - frac_vert));
}

// ---- Ã©valuabilitÃ© : flou (var Laplacien) + exposition ----
static bool est_evaluable(const cv::Mat& rgb) {
    cv::Mat gris; cv::cvtColor(rgb, gris, cv::COLOR_RGB2GRAY);
    cv::Mat lap; cv::Laplacian(gris, lap, CV_64F);
    cv::Scalar mean, stddev; cv::meanStdDev(lap, mean, stddev);
    double var_lap = stddev[0] * stddev[0];
    if (var_lap < FLOU_MIN_VARLAPLACE) return false;
    int total = gris.rows * gris.cols, brule = 0, noir = 0;
    for (int y = 0; y < gris.rows; y++) {
        const uchar* p = gris.ptr<uchar>(y);
        for (int x = 0; x < gris.cols; x++) {
            if (p[x] > 250) brule++;
            else if (p[x] < 5) noir++;
        }
    }
    double frac = total > 0 ? (double)(brule + noir) / total : 1.0;
    if (frac > EXPO_FRAC_SATUREE_MAX) return false;
    return true;
}

// ---- combinaison : moyenne pondÃ©rÃ©e - pÃ©nalitÃ©, bornÃ©e par le pire ----
static double combiner_score(double ciel, double horizon, double contours,
                             double texture, double vert) {
    double vals[5] = { ciel, horizon, contours, texture, vert };
    double poids[5] = { P_CIEL, P_HORIZON, P_CONTOURS, P_TEXTURE, P_VERTICALITE };
    double moyenne = 0.0, vmin = vals[0], vmax = vals[0];
    for (int i = 0; i < 5; i++) {
        moyenne += vals[i] * poids[i];
        if (vals[i] < vmin) vmin = vals[i];
        if (vals[i] > vmax) vmax = vals[i];
    }
    double ecart = vmax - vmin;
    double penalite = PENALITE_GAIN * (ecart / 100.0);
    double score_penalise = moyenne - penalite;
    double borne_pire = BORNE_B * vmin + BORNE_C;
    double score = std::min(score_penalise, borne_pire);
    return clip100(score);
}

// ---- confiance : 0.5*cohÃ©rence + 0.5*lisibilitÃ© (1 image = 1 secteur) ----
static double calculer_confiance(double ciel, double horizon, double contours,
                                 double texture, double vert, bool evaluable) {
    if (!evaluable) return 0.0;
    double vals[5] = { ciel, horizon, contours, texture, vert };
    double vmin = vals[0], vmax = vals[0];
    for (int i = 1; i < 5; i++) { if (vals[i] < vmin) vmin = vals[i]; if (vals[i] > vmax) vmax = vals[i]; }
    double ecart = vmax - vmin;
    double coherence = 100.0 * (1.0 - clip01(ecart / 100.0));
    double lisibilite = 100.0; // une image = un secteur lisible
    double conf = 0.5 * coherence + 0.5 * lisibilite;
    return clip100(conf);
}



// ---------------------------------------------------------------------
//  FONCTION JNI â€” calquÃ©e sur detectYUV (mÃªme gestion mÃ©moire NV21)
// ---------------------------------------------------------------------
JNIEXPORT jfloatArray JNICALL Java_com_tencent_yolo11ncnn_YOLO11Ncnn_scoreOuvertureYUV(
        JNIEnv* env, jobject thiz, jbyteArray nv21_, jint width, jint height)
{
    

    jbyte* nv21 = env->GetByteArrayElements(nv21_, 0);
    cv::Mat yuv(height + height / 2, width, CV_8UC1, (unsigned char*)nv21);
    cv::Mat rgb;
    cv::cvtColor(yuv, rgb, cv::COLOR_YUV2RGB_NV21);
    env->ReleaseByteArrayElements(nv21_, nv21, 0);

    // redimensionner pour cohÃ©rence avec le Python (max cÃ´tÃ© = 1024)
    int hh = rgb.rows, ww = rgb.cols;
    int mx = std::max(hh, ww);
    if (mx > 1024) {
        double ech = 1024.0 / mx;
        cv::resize(rgb, rgb, cv::Size((int)(ww * ech), (int)(hh * ech)));
    }

    bool evaluable = est_evaluable(rgb);
    double ciel    = indice_ciel(rgb);
    double horizon = indice_horizon(rgb);
    double cont    = indice_contours(rgb);
    double tex     = indice_texture(rgb);
    double vert    = indice_verticalite(rgb);
    double score   = combiner_score(ciel, horizon, cont, tex, vert);
    double conf    = calculer_confiance(ciel, horizon, cont, tex, vert, evaluable);

    jfloatArray result = env->NewFloatArray(3);
    float buf[3] = { (float)score, (float)conf, evaluable ? 1.0f : 0.0f };
    env->SetFloatArrayRegion(result, 0, 3, buf);
    return result;
}


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

}
