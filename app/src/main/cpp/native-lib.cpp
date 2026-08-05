#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "XMotionNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// XMotion C++ / NDK native engine
// 1) getNativeVersion()     -> string info engine
// 2) processPixels()        -> proses piksel ARGB (brightness + contrast)

extern "C" JNIEXPORT jstring JNICALL
Java_com_xmotion_app_native_NativeLib_getNativeVersion(JNIEnv *env, jobject /*thiz*/) {
    std::string v = "XMotion Native Engine v1.0 (C++17 / NDK / CMake)";
    LOGI("getNativeVersion -> %s", v.c_str());
    return env->NewStringUTF(v.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_processPixels(
        JNIEnv *env, jobject /*thiz*/,
        jintArray pixels, jint length, jfloat brightness, jfloat contrast) {

    if (pixels == nullptr) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr);
    if (arr == nullptr) return;

    float bAdd = brightness * 1.28f;
    float cFactor = (contrast / 100.0f);
    cFactor = cFactor * cFactor;

    for (int i = 0; i < length; i++) {
        unsigned int px = static_cast<unsigned int>(arr[i]);
        int a = static_cast<int>((px >> 24) & 0xFF);
        int r = static_cast<int>((px >> 16) & 0xFF);
        int g = static_cast<int>((px >> 8) & 0xFF);
        int b = static_cast<int>(px & 0xFF);

        r = static_cast<int>(((r - 128) * cFactor) + 128 + bAdd);
        g = static_cast<int>(((g - 128) * cFactor) + 128 + bAdd);
        b = static_cast<int>(((b - 128) * cFactor) + 128 + bAdd);

        r = r < 0 ? 0 : (r > 255 ? 255 : r);
        g = g < 0 ? 0 : (g > 255 ? 255 : g);
        b = b < 0 ? 0 : (b > 255 ? 255 : b);

        arr[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }

    env->ReleaseIntArrayElements(pixels, arr, 0);
    LOGI("processPixels: processed %d pixels (bright=%.1f contrast=%.1f)", length, brightness, contrast);
}
