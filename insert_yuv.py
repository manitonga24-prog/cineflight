f = r'C:\cineflight_android\CineFlightSolo\app\src\main\jni\yolo11ncnn.cpp'
s = open(f, encoding='utf-8').read()
if 'detectYUV' in s:
    print('detectYUV DEJA present'); raise SystemExit
func = """
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
"""
idx = s.rstrip().rfind('}')
s = s[:idx] + func + '\n}\n'
open(f, 'w', encoding='utf-8', newline='\n').write(s)
print('OK detectYUV insere :', 'detectYUV' in s)