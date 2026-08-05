package com.xmotion.app.native

/**
 * Jembatan JNI menuju library native C++ (xmotion_native).
 */
object NativeLib {
    init {
        System.loadLibrary("xmotion_native")
    }

    external fun getNativeVersion(): String

    external fun processPixels(pixels: IntArray, length: Int, brightness: Float, contrast: Float)
}
