#include <jni.h>
#include <string>
#include <android/log.h>
#include <cmath>
#include <vector>

#define LOG_TAG "XMotionNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)// =========================================================================
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

// =========================================================================
//  composeOverlay - multi-layer composition dengan affine transform
//
//  Komposisi overlay (bitmap ARGB) ke atas gambar dasar dengan transformasi:
//    posisi pusat (cx, cy) dalam piksel output,
//    scale (faktor), rotation (derajat), alpha (0..1)
//  Menggunakan bilinear + transform balik per piksel.
// =========================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xmotion_app_native_NativeLib_composeOverlay(
        JNIEnv *env, jobject,
        jintArray dst, jint dw, jint dh,
        jintArray ovl, jint ow, jint oh,
        jfloat cx, jfloat cy, jfloat scale, jfloat rotDeg, jfloat alpha) {

    if (!dst || !ovl) return;
    jint *base = env->GetIntArrayElements(dst, nullptr);
    jint *ov = env->GetIntArrayElements(ovl, nullptr);
    if (!base || !ov) { if(base) env->ReleaseIntArrayElements(dst, base, 0); if(ov) env->ReleaseIntArrayElements(ovl, ov, 0); return; }

    double rad = rotDeg * M_PI / 180.0;
    double cosr = cos(rad), sinr = sin(rad);
    double invScale = (scale == 0.0f) ? 0.0 : (1.0 / scale);
    // half overlay dims in output space
    double hw = (ow * scale) * 0.5;
    double hh = (oh * scale) * 0.5;

    for (int y = 0; y < dh; y++) {
        for (int x = 0; x < dw; x++) {
            // translate to overlay center
            double dx = x - cx;
            double dy = y - cy;
            // inverse rotation then inverse scale -> sample coords in overlay (centered)
            double rx = (dx * cosr + dy * sinr) * invScale;
            double ry = (-dx * sinr + dy * cosr) * invScale;
            // centered -> top-left coords
            double sx = rx + (ow * 0.5);
            double sy = ry + (oh * 0.5);
            if (sx < 0 || sy < 0 || sx > ow - 1 || sy > oh - 1) continue; // transparent outside

            int x0 = (int)sx, y0 = (int)sy;
            int x1 = x0 + 1 < ow ? x0 + 1 : x0;
            int y1 = y0 + 1 < oh ? y0 + 1 : y0;
            float tx = (float)(sx - x0), ty = (float)(sy - y0);

            unsigned int p00 = (unsigned int)ov[y0*ow+x0];
            unsigned int p10 = (unsigned int)ov[y0*ow+x1];
            unsigned int p01 = (unsigned int)ov[y1*ow+x0];
            unsigned int p11 = (unsigned int)ov[y1*ow+x1];

            int a00=(p00>>24)&0xFF,r00=(p00>>16)&0xFF,g00=(p00>>8)&0xFF,b00=p00&0xFF;
            int a10=(p10>>24)&0xFF,r10=(p10>>16)&0xFF,g10=(p10>>8)&0xFF,b10=p10&0xFF;
            int a01=(p01>>24)&0xFF,r01=(p01>>16)&0xFF,g01=(p01>>8)&0xFF,b01=p01&0xFF;
            int a11=(p11>>24)&0xFF,r11=(p11>>16)&0xFF,g11=(p11>>8)&0xFF,b11=p11&0xFF;

            // bilinear
            int ovA = (int)((a00*(1-tx)+a10*tx)*(1-ty) + (a01*(1-tx)+a11*tx)*ty);
            int ovR = (int)((r00*(1-tx)+r10*tx)*(1-ty) + (r01*(1-tx)+r11*tx)*ty);
            int ovG = (int)((g00*(1-tx)+g10*tx)*(1-ty) + (g01*(1-tx)+g11*tx)*ty);
            int ovB = (int)((b00*(1-tx)+b10*tx)*(1-ty) + (b01*(1-tx)+b11*tx)*ty);

            float ovAlpha = (ovA/255.0f) * alpha;
            if (ovAlpha <= 0.001f) continue;

            unsigned int bp = (unsigned int)base[y*dw+x];
            int bA=(bp>>24)&0xFF, bR=(bp>>16)&0xFF, bG=(bp>>8)&0xFF, bB=bp&0xFF;

            int r = (int)(ovR*ovAlpha + bR*(1-ovAlpha));
            int g = (int)(ovG*ovAlpha + bG*(1-ovAlpha));
            int b = (int)(ovB*ovAlpha + bB*(1-ovAlpha));
            int a = 255;

            base[y*dw+x] = (a<<24)|(clampi(r)<<16)|(clampi(g)<<8)|clampi(b);
        }
    }

    env->ReleaseIntArrayElements(dst, base, 0);
    env->ReleaseIntArrayElements(ovl, ov, 0);
    LOGI("composeOverlay: %dx%d base, overlay %dx%d at (%.0f,%.0f) s=%.2f r=%.0f a=%.2f", dw,dh,ow,oh,cx,cy,scale,rotDeg,alpha);
}
