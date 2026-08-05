package com.xmotion.app.transcode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.xmotion.app.native.NativeLib
import java.nio.ByteBuffer

/**
 * OverlayVideoExporter
 *
 * Mengekspor video + overlay (teks/lapisan) menjadi MP4.
 * Pendekatan frame-based yang RELIABLE:
 *   - tiap frame diambil dari MediaMetadataRetriever
 *   - overlay dikomposisikan via native C++ (composeOverlay)
 *   - ARGB -> NV12 -> encoder -> muxer
 *
 * Data class OverlaySpec mendeskripsikan satu lapisan overlay pada video.
 */
object OverlayVideoExporter {

    private const val TAG = "OverlayExporter"
    private const val I_FRAME_INTERVAL = 2

    data class OverlaySpec(
        val bitmap: Bitmap,
        val cx: Float,   // pusat X dalam piksel frame output
        val cy: Float,   // pusat Y dalam piksel frame output
        val scale: Float,
        val rotation: Float,
        val alpha: Float = 1f
    )

    fun export(
        context: Context,
        uri: Uri,
        outputPath: String,
        targetHeight: Int,
        fps: Int,
        useHevc: Boolean,
        overlayBitmaps: List<Bitmap>,
        isVideo: Boolean,
        filterIndex: Int = 0
    ): Boolean {
        var retriever: MediaMetadataRetriever? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val durUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1000) ?: 5_000_000L
            val srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1280
            val srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 720
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            val (dispW, dispH) = if (rotation == 90 || rotation == 270) Pair(srcH, srcW) else Pair(srcW, srcH)

            val outH = if (targetHeight > 0 && targetHeight < dispH) targetHeight else dispH
            val outW = (outH.toDouble() * dispW / dispH).toInt().coerceAtLeast(2)

            val frameCount = ((durUs / 1000f) * fps / 1000f).toInt().coerceIn(2, 6000)
            val outputMime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

            // --- encoder ---
            val format = MediaFormat.createVideoFormat(outputMime, outW, outH)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, outW * outH * 3)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

            encoder = MediaCodec.createEncoderByType(outputMime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()

            // overlay specs dalam koordinat output frame
            val specs = buildOverlaySpecs(overlayBitmaps, outW, outH)

            for (i in 0 until frameCount) {
                val tUs = (i * 1_000_000L) / fps
                if (tUs > durUs) break

                val frame = if (isVideo) {
                    retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    retriever.getFrameAtTime()
                }
                if (frame == null) continue

                // scale frame to output size
                val scaled = scaleBitmap(frame, outW, outH)
                val dst = IntArray(outW * outH)
                scaled.getPixels(dst, 0, outW, 0, 0, outW, outH)

                // apply filter (native C++) ke frame
                if (filterIndex > 0) {
                    when (filterIndex) {
                        1 -> NativeLib.applyGrayscale(dst, dst.size)
                        2 -> NativeLib.applySepia(dst, dst.size, 1f)
                        3 -> NativeLib.applyInvert(dst, dst.size)
                        4 -> NativeLib.adjustSaturation(dst, dst.size, 1.8f)
                    }
                }

                // composite overlays
                for (spec in specs) {
                    val ob = spec.bitmap
                    val opx = IntArray(ob.width * ob.height)
                    ob.getPixels(opx, 0, ob.width, 0, 0, ob.width, ob.height)
                    NativeLib.composeOverlay(dst, outW, outH, opx, ob.width, ob.height,
                        spec.cx, spec.cy, spec.scale, spec.rotation, spec.alpha)
                }
                scaled.setPixels(dst, 0, outW, 0, 0, outW, outH)

                // ARGB -> NV12
                val nv12 = argbToNv12(scaled)

                // feed encoder
                var inputIdx = -1
                repeat(5) {
                    inputIdx = encoder.dequeueInputBuffer(10000)
                    if (inputIdx >= 0) return@repeat
                }
                if (inputIdx < 0) continue
                val inBuf = encoder.getInputBuffer(inputIdx)!!
                inBuf.clear()
                inBuf.put(nv12)
                encoder.queueInputBuffer(inputIdx, 0, nv12.size, tUs, 0)

                // drain encoder
                var done = false
                while (!done) {
                    val outIdx = encoder.dequeueOutputBuffer(info, 10000)
                    when {
                        outIdx >= 0 -> {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0) {
                                if (!muxerStarted) {
                                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                                val ob = encoder.getOutputBuffer(outIdx)!!
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, ob, info)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> done = true
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    }
                }
                scaled.recycle()
                if (frame !== scaled) frame.recycle()
            }

            // signal EOS
            val inIdx = encoder.dequeueInputBuffer(10000)
            if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var flushed = false
            while (!flushed) {
                val outIdx = encoder.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    if (info.size > 0 && muxerStarted) {
                        val ob = encoder.getOutputBuffer(outIdx)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(muxerTrack, ob, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) flushed = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // short sleep handled by loop bound
                    break
                }
            }

            if (muxerStarted) muxer.stop()
            return muxerStarted
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            return false
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun buildOverlaySpecs(overlayBitmaps: List<Bitmap>, outW: Int, outH: Int): List<OverlaySpec> {
        if (overlayBitmaps.isEmpty()) return emptyList()
        // letakkan semua overlay di tengah-tengah dengan skala default
        return overlayBitmaps.map { bmp ->
            val scale = (outW * 0.5f) / bmp.width
            OverlaySpec(bmp, outW / 2f, outH / 2f, scale, 0f, 1f)
        }
    }

    private fun scaleBitmap(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val m = Matrix()
        m.postScale(w.toFloat() / src.width, h.toFloat() / src.height)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Konversi Bitmap ARGB -> byte array NV12 (YUV420sp). */
    private fun argbToNv12(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val size = w * h * 3 / 2
        val nv12 = ByteArray(size)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var yIdx = 0
        var uvIdx = w * h
        for (j in 0 until h step 2) {
            for (i in 0 until w step 2) {
                val a = px[j * w + i]
                val b = px[(j + 1).coerceAtMost(h - 1) * w + i]
                val c = px[j * w + (i + 1).coerceAtMost(w - 1)]
                val d = px[(j + 1).coerceAtMost(h - 1) * w + (i + 1).coerceAtMost(w - 1)]
                val r = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (c shr 16 and 0xFF) + (d shr 16 and 0xFF)) / 4
                val g = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (c shr 8 and 0xFF) + (d shr 8 and 0xFF)) / 4
                val bl = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF)) / 4
                val y1 = (0.257f * r + 0.504f * g + 0.098f * bl + 16f).toInt().coerceIn(0, 255)
                val u1 = (-0.148f * r - 0.291f * g + 0.439f * bl + 128f).toInt().coerceIn(0, 255)
                val v1 = (0.439f * r - 0.368f * g - 0.071f * bl + 128f).toInt().coerceIn(0, 255)
                nv12[uvIdx++] = v1.toByte()
                nv12[uvIdx++] = u1.toByte()
            }
        }
        // Y plane
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = px[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (0.257f * r + 0.504f * g + 0.098f * b + 16f).toInt().coerceIn(0, 255)
                nv12[yIdx++] = y.toByte()
            }
        }
        return nv12
    }
}
