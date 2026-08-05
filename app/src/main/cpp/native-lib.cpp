#include <jni.h>
#include <string>
#include <android/log.h>
#include <cmath>
#include <vector>

#define LOG_TAG "XMotionNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// =========================================================================
//  XMotion C++ / NDK native engine
//  - getNativeVersion()          : info engine
//  - processPixels()             : brightness + contrast (legacy)
//  - applyGrayscale / Sepia / Invert / Brightness / Contrast / Saturation
// =========================================================================

static inline int clampi(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_xmotion_app_native_NativeLib_getNativeVersion(JNIEnv *env, jobject) {
    std::string v = "XMotion Native Engine v2.0 (C++17 / NDK / CMake)";
    return env->NewStringUTF(v.c_str());
}

// brightness + contrast (legacy, used by preview sliders)
extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_processPixels(
        JNIEnv *env, jobject, jintArray pixels, jint length, jfloat brightness, jfloat contrast) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr);
    if (!arr) return;
    float bAdd = brightness * 1.28f;
    float cf = contrast / 100.0f;
    cf = cf * cf;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        r = clampi((int)(((r - 128) * cf) + 128 + bAdd));
        g = clampi((int)(((g - 128) * cf) + 128 + bAdd));
        b = clampi((int)(((b - 128) * cf) + 128 + bAdd));
        arr[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_applyGrayscale(JNIEnv *env, jobject, jintArray pixels, jint length) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr); if (!arr) return;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        int y = (int)(0.299f*r + 0.587f*g + 0.114f*b); r = y; g = y; b = y;
        arr[i] = (a << 24) | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_applySepia(JNIEnv *env, jobject, jintArray pixels, jint length, jfloat intensity) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr); if (!arr) return;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        int tr=(int)(0.393f*r+0.769f*g+0.189f*b), tg=(int)(0.349f*r+0.686f*g+0.168f*b), tb=(int)(0.272f*r+0.534f*g+0.131f*b);
        r=(int)(r*(1-intensity)+tr*intensity); g=(int)(g*(1-intensity)+tg*intensity); b=(int)(b*(1-intensity)+tb*intensity);
        arr[i] = (a << 24) | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_applyInvert(JNIEnv *env, jobject, jintArray pixels, jint length) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr); if (!arr) return;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        r=255-r; g=255-g; b=255-b;
        arr[i] = (a << 24) | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_adjustBrightness(JNIEnv *env, jobject, jintArray pixels, jint length, jfloat amount) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr); if (!arr) return;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        r=(int)(r+amount); g=(int)(g+amount); b=(int)(b+amount);
        arr[i] = (a << 24) | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_adjustContrast(JNIEnv *env, jobject, jintArray pixels, jint length, jfloat factor) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr); if (!arr) return;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        r=(int)((r-128)*factor+128); g=(int)((g-128)*factor+128); b=(int)((b-128)*factor+128);
        arr[i] = (a << 24) | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_adjustSaturation(JNIEnv *env, jobject, jintArray pixels, jint length, jfloat amount) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr); if (!arr) return;
    for (int i = 0; i < length; i++) {
        unsigned int px = (unsigned int)arr[i];
        int a = (px >> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
        float gray = 0.299f*r + 0.587f*g + 0.114f*b;
        r=(int)(gray + (r-gray)*amount); g=(int)(gray + (g-gray)*amount); b=(int)(gray + (b-gray)*amount);
        arr[i] = (a << 24) | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }
    env->ReleaseIntArrayElements(pixels, arr, 0);
}

// simple box blur (needs width & height for neighborhood)
extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_applyBlur(
        JNIEnv *env, jobject, jintArray pixels, jint width, jint height, jint radius) {
    if (!pixels) return;
    jint *arr = env->GetIntArrayElements(pixels, nullptr);
    if (!arr) return;
    int n = width * height;
    int rad = radius < 1 ? 1 : radius;
    std::vector<int> out(n);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            long sumR=0,sumG=0,sumB=0; int cnt=0;
            for (int dy=-rad; dy<=rad; dy++) {
                int yy = y+dy; if (yy<0||yy>=height) continue;
                for (int dx=-rad; dx<=rad; dx++) {
                    int xx = x+dx; if (xx<0||xx>=width) continue;
                    unsigned int p=(unsigned int)arr[yy*width+xx];
                    sumR += (p>>16)&0xFF; sumG += (p>>8)&0xFF; sumB += p&0xFF; cnt++;
                }
            }
            unsigned int orig=(unsigned int)arr[y*width+x];
            int a=(orig>>24)&0xFF;
            int R=(int)(sumR/cnt), G=(int)(sumG/cnt), B=(int)(sumB/cnt);
            out[y*width+x] = (a<<24)|(R<<16)|(G<<8)|B;
        }
    }
    for (int i=0;i<n;i++) arr[i]=out[i];
    env->ReleaseIntArrayElements(pixels, arr, 0);
}
