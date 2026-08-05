package com.xmotion.app.native

/**
 * Jembatan JNI menuju library native C++ (xmotion_native).
 * Berisi pemrosesan piksel untuk filter foto/video di sisi C++.
 */
object NativeLib {
    init {
        System.loadLibrary("xmotion_native")
    }

    external fun getNativeVersion(): String

    // legacy brightness+contrast
    external fun processPixels(pixels: IntArray, length: Int, brightness: Float, contrast: Float)

    // filters
    external fun applyGrayscale(pixels: IntArray, length: Int)
    external fun applySepia(pixels: IntArray, length: Int, intensity: Float)
    external fun applyInvert(pixels: IntArray, length: Int)
    external fun adjustBrightness(pixels: IntArray, length: Int, amount: Float)
    external fun adjustContrast(pixels: IntArray, length: Int, factor: Float)
    external fun adjustSaturation(pixels: IntArray, length: Int, amount: Float)
    external fun applyBlur(pixels: IntArray, width: Int, height: Int, radius: Int)
}
