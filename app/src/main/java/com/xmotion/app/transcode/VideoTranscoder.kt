package com.xmotion.app.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VideoTranscoder — XMotion
 *
 * Transcode video (kompresi / ubah resolusi / rasio / FPS / codec / trim)
 * menggunakan encoder berbasis SURFACE. Ini pendekatan yang paling andal
 * di berbagai device karena encoder menangani konversi warna otomatis —
 * tidak seperti byte-buffer YUV yang sering gagal.
 *
 * Pola ini mengikuti referensi Grafika (Google).
 */
object VideoTranscoder {

    private const val TAG = "XMotionTranscoder"
    private const val TIMEOUT_US = 10000L

    data class Result(
        val outputPath: String,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputFps: Int,
        val inputBytes: Long,
        val outputBytes: Long,
        val audioKept: Boolean,
        val durationMs: Long
    )

    class TranscoderException(message: String) : Exception(message)

    suspend fun transcode(
        inputPath: String,
        outputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        bitrate: Int,
        useHevc: Boolean,
        startMs: Long = 0L,
        endMs: Long = 0L,          // 0 = sampai habis
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        transcodeSync(inputPath, outputPath, targetWidth, targetHeight, targetFps,
            bitrate, useHevc, startMs, endMs, onProgress)
    }

    private fun transcodeSync(
        inputPath: String, outputPath: String,
        targetWidth: Int, targetHeight: Int, targetFps: Int, bitrate: Int,
        useHevc: Boolean, startMs: Long, endMs: Long,
        onProgress: (Float) -> Unit
    ): Result {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var inputBytes = 0L
        var outputBytes = 0L
        var outputWidth = 0
        var outputHeight = 0
        var outputFps = 30
        var audioKept = false
        var outputDurationMs = 0L

        try {
            extractor.setDataSource(inputPath)
            val trackCount = extractor.trackCount
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrackIndex < 0 && mime.startsWith("video/")) { videoTrackIndex = i; videoFormat = f }
                else if (audioTrackIndex < 0 && mime.startsWith("audio/")) { audioTrackIndex = i; audioFormat = f }
            }
            if (videoTrackIndex < 0 || videoFormat == null) throw TranscoderException("Tidak ada track video")

            val fullDurationUs = if (videoFormat!!.containsKey(MediaFormat.KEY_DURATION))
                videoFormat!!.getLong(MediaFormat.KEY_DURATION) else 0L

            val startUs = startMs * 1000L
            var endUs = if (endMs > 0) endMs * 1000L else fullDurationUs
            if (endUs > fullDurationUs && fullDurationUs > 0) endUs = fullDurationUs
            outputDurationMs = (endUs - startUs) / 1000L

            // ---- resolusi target ----
            val srcW = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (videoFormat!!.containsKey(MediaFormat.KEY_ROTATION))
                videoFormat!!.getInteger(MediaFormat.KEY_ROTATION) else 0
            val displayW = if (rotation == 90 || rotation == 270) srcH else srcW
            val displayH = if (rotation == 90 || rotation == 270) srcW else srcH

            val sourceFps = if (videoFormat!!.containsKey(MediaFormat.KEY_FRAME_RATE))
                videoFormat!!.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            outputFps = if (targetFps > 0) targetFps else sourceFps

            val (outW, outH) = computeTarget(displayW, displayH, targetWidth, targetHeight)
            outputWidth = outW; outputHeight = outH

            val outputMime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

            // ---- encoder (surface input) ----
            val encFormat = MediaFormat.createVideoFormat(outputMime, outW, outH)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, maxOf(bitrate, 300_000))
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, outputFps)
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (outputMime == MediaFormat.MIMETYPE_VIDEO_HEVC && Build.VERSION.SDK_INT >= 24) {
                encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            if (outputMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            encoder = MediaCodec.createEncoderByType(outputMime)
            encoder!!.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder!!.createInputSurface()
            encoder!!.start()

            // ---- decoder (output to encoder surface) ----
            val srcMime = videoFormat!!.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder!!.configure(videoFormat!!, inputSurface, null, 0)
            decoder!!.start()

            extractor.selectTrack(videoTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            var sawEncFormat = false

            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var framesOut = 0

            while (!encoderDone) {
                // 1) feed decoder
                if (!inputDone) {
                    val inIdx = decoder!!.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder!!.getInputBuffer(inIdx)
                        val sampleSize = extractor.readSampleData(buf!!, 0)
                        if (sampleSize < 0) {
                            decoder!!.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime
                            if (time >= startUs) {
                                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                                decoder!!.queueInputBuffer(inIdx, 0, sampleSize, time - startUs, flags)
                                inputBytes += sampleSize
                            } else {
                                decoder!!.queueInputBuffer(inIdx, 0, sampleSize, 0, 0)
                            }
                            extractor.advance()
                        }
                    }
                }

                // 2) drain decoder (render to encoder surface)
                if (!decoderDone) {
                    val outIdx = decoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                        }
                        decoder!!.releaseOutputBuffer(outIdx, true) // render to surface
                        framesOut++
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // decoder output format changed (ignored, surface handles it)
                    }
                }

                // 3) drain encoder -> muxer
                var encDrained = false
                while (!encDrained) {
                    val encOutIdx = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        encOutIdx >= 0 -> {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                if (!muxerStarted) {
                                    muxerVideoTrack = muxer.addTrack(encoder!!.outputFormat)
                                    if (audioTrackIndex >= 0 && audioFormat != null) {
                                        try { muxerAudioTrack = muxer.addTrack(audioFormat!!) } catch (_: Exception) { muxerAudioTrack = -1 }
                                    }
                                    muxer.start()
                                    muxerStarted = true
                                }
                                val outBuf = encoder!!.getOutputBuffer(encOutIdx) ?: continue
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, outBuf, bufferInfo)
                                outputBytes += bufferInfo.size
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true
                            encoder!!.releaseOutputBuffer(encOutIdx, false)
                        }
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> encDrained = true
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* use encoder.outputFormat */ }
                    }
                }

                if (endUs > startUs && bufferInfo.presentationTimeUs > 0) {
                    val p = ((bufferInfo.presentationTimeUs + startUs - startUs).toFloat() / (endUs - startUs))
                    onProgress(min(0.99f, p.coerceAtLeast(0f)))
                }
            }

            // ---- audio passthrough (robust, trim-aware) ----
            if (audioTrackIndex >= 0 && muxerStarted && muxerAudioTrack >= 0) {
                try {
                    extractor.unselectTrack(videoTrackIndex)
                    extractor.selectTrack(audioTrackIndex)
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val audioInfo = MediaCodec.BufferInfo()
                    var prevTs = 0L
                    var wrote = false
                    var eos = false
                    while (!eos) {
                        val buf = ByteBuffer.allocate(256 * 1024)
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) { eos = true }
                        else {
                            var ts = extractor.sampleTime - startUs
                            if (ts < 0) ts = 0
                            if (endUs > 0 && extractor.sampleTime > endUs) { eos = true; break }
                            if (ts < prevTs) ts = prevTs
                            val f = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) 1 else 0
                            buf.position(0); buf.limit(size)
                            audioInfo.set(0, size, ts, f)
                            muxer.writeSampleData(muxerAudioTrack, buf, audioInfo)
                            prevTs = ts; wrote = true
                            extractor.advance()
                        }
                    }
                    audioKept = wrote
                } catch (e: Exception) {
                    Log.w(TAG, "Audio passthrough gagal; video tetap disimpan", e)
                    audioKept = false
                }
            }

            onProgress(1f)
            if (muxerStarted) muxer.stop()

            return Result(outputPath, outputWidth, outputHeight, outputFps,
                inputBytes, outputBytes, audioKept, outputDurationMs)
        } catch (e: TranscoderException) {
            Log.e(TAG, "Transcode failed", e)
            try { muxer.stop() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed", e)
            try { muxer.stop() } catch (_: Exception) {}
            throw TranscoderException("Gagal memproses video: ${e.message}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * Menghitung ukuran output dengan dukungan rasio target (aspek).
     * targetWidth/Height dari dropdown; jika 0 -> ikuti asli.
     */
    fun computeTarget(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Pair<Int, Int> {
        var w: Int
        var h: Int
        when {
            targetW <= 0 && targetH <= 0 -> { w = srcW; h = srcH }
            targetW <= 0 -> { // scale to targetH, keep aspect
                h = targetH; w = (srcW.toDouble() * targetH / srcH).roundToInt()
            }
            targetH <= 0 -> { // scale to targetW, keep aspect
                w = targetW; h = (srcH.toDouble() * targetW / srcW).roundToInt()
            }
            else -> { w = targetW; h = targetH }
        }
        w = (w / 2) * 2; h = (h / 2) * 2
        if (w < 2) w = 2; if (h < 2) h = 2
        return Pair(w, h)
    }
}
