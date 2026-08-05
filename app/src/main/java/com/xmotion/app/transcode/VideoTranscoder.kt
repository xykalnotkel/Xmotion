package com.xmotion.app.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VideoTranscoder — XMotion
 *
 * Re-encode video (kompresi / ubah resolusi / atur FPS / ubah codec) pakai
 * MediaCodec + MediaMuxer (native Android). Ditulis agar ROBUST:
 *  - audio passthrough dibungkus try/catch (kalau audio gagal, video tetap jadi)
 *  - error dijamin tidak bikin crash (selalu dibungkus exception)
 *  - menangani rotasi & frame rate dengan benar
 */
object VideoTranscoder {

    private const val TAG = "XMotionTranscoder"
    private const val TIMEOUT_US = 10000L
    private const val KEY_FRAME_INTERVAL = 2

    data class Result(
        val outputPath: String,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputFps: Int,
        val inputBytes: Long,
        val outputBytes: Long,
        val audioKept: Boolean
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
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        transcodeSync(inputPath, outputPath, targetWidth, targetHeight, targetFps, bitrate, useHevc, onProgress)
    }

    private fun transcodeSync(
        inputPath: String,
        outputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        bitrate: Int,
        useHevc: Boolean,
        onProgress: (Float) -> Unit
    ): Result {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var inputBytes = 0L
        var outputBytes = 0L
        var outputWidth = 0
        var outputHeight = 0
        var outputFps = 30
        var audioKept = false

        try {
            extractor.setDataSource(inputPath)

            val trackCount = extractor.trackCount
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrackIndex < 0 && mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = f
                } else if (audioTrackIndex < 0 && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = f
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) {
                throw TranscoderException("Tidak ada track video ditemukan")
            }

            val outputMime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val sourceWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            // rotation
            val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION))
                videoFormat.getInteger(MediaFormat.KEY_ROTATION) else 0

            val (displayW, displayH) = if (rotation == 90 || rotation == 270)
                Pair(sourceHeight, sourceWidth) else Pair(sourceWidth, sourceHeight)

            // fps asli
            val sourceFps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            outputFps = if (targetFps > 0) targetFps else sourceFps

            val (outW, outH) = computeTargetSize(displayW, displayH, targetWidth, targetHeight)
            outputWidth = outW
            outputHeight = outH

            val encFormat = MediaFormat.createVideoFormat(outputMime, outW, outH)
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, maxOf(bitrate, 200_000))
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, outputFps)
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL)
            if (outputMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                encFormat.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }

            encoder = MediaCodec.createEncoderByType(outputMime)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            fun ensureMuxerStarted(videoEncFormat: MediaFormat) {
                if (!muxerStarted) {
                    muxerVideoTrack = muxer.addTrack(videoEncFormat)
                    if (audioTrackIndex >= 0 && audioFormat != null) {
                        try { muxerAudioTrack = muxer.addTrack(audioFormat) } catch (_: Exception) { muxerAudioTrack = -1 }
                    }
                    muxer.start()
                    muxerStarted = true
                }
            }

            var inputEos = false
            var decoderEos = false
            var encoderEos = false
            var sawVideoFormat = false
            val totalDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION))
                videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var lastReportedUs = 0L

            var guard = 0
            while (!encoderEos && guard < 100000) {
                guard++

                // feed decoder
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val size = extractor.readSampleData(inBuf!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, flags)
                            inputBytes += size
                            extractor.advance()
                        }
                    }
                }

                // drain decoder -> encoder
                if (!decoderEos) {
                    var drained = false
                    while (!drained) {
                        val outIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (outIdx >= 0) {
                            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                val encInBuf = encoder.getInputBuffer(encInIdx)
                                val size = bufferInfo.size
                                if (outBuf != null && encInBuf != null) {
                                    encInBuf.clear()
                                    if (size > 0) {
                                        outBuf.position(bufferInfo.offset)
                                        outBuf.limit(bufferInfo.offset + size)
                                        encInBuf.put(outBuf)
                                    }
                                    val eosFlag = if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    encoder.queueInputBuffer(encInIdx, 0, size, bufferInfo.presentationTimeUs, eosFlag)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderEos = true
                                } else {
                                    encoder.queueInputBuffer(encInIdx, 0, 0, 0, 0)
                                }
                                decoder.releaseOutputBuffer(outIdx, false)
                            } else {
                                drained = true
                            }
                        } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            drained = true
                        }
                        // INFO_OUTPUT_FORMAT_CHANGED for decoder ignored
                    }
                }

                // drain encoder -> muxer
                var encDrained = false
                while (!encDrained) {
                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIdx >= 0 -> {
                            val encOutBuf = encoder.getOutputBuffer(outIdx)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                if (!sawVideoFormat) {
                                    ensureMuxerStarted(encoder.outputFormat)
                                    sawVideoFormat = true
                                }
                                if (muxerVideoTrack >= 0) {
                                    encOutBuf!!.position(bufferInfo.offset)
                                    encOutBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer.writeSampleData(muxerVideoTrack, encOutBuf, bufferInfo)
                                    outputBytes += bufferInfo.size
                                }
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderEos = true
                            encoder.releaseOutputBuffer(outIdx, false)
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* format grabbed via outputFormat */ }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> encDrained = true
                    }
                }

                if (totalDurationUs > 0 && bufferInfo.presentationTimeUs > lastReportedUs) {
                    lastReportedUs = bufferInfo.presentationTimeUs
                    onProgress(min(0.99f, bufferInfo.presentationTimeUs.toFloat() / totalDurationUs))
                }
            }

            // ---- audio passthrough (robust) ----
            if (audioTrackIndex >= 0 && muxerStarted && muxerAudioTrack >= 0) {
                try {
                    extractor.unselectTrack(videoTrackIndex)
                    extractor.selectTrack(audioTrackIndex)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val audioInfo = MediaCodec.BufferInfo()
                    var prevTs = 0L
                    var audioEos = false
                    var wrote = false
                    while (!audioEos) {
                        val buf = ByteBuffer.allocate(256 * 1024)
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            audioEos = true
                        } else {
                            buf.position(0); buf.limit(size)
                            var ts = extractor.sampleTime
                            if (ts < prevTs) ts = prevTs
                            val f = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) 1 else 0
                            audioInfo.set(0, size, ts, f)
                            muxer.writeSampleData(muxerAudioTrack, buf, audioInfo)
                            prevTs = ts
                            wrote = true
                            extractor.advance()
                        }
                    }
                    audioKept = wrote
                } catch (e: Exception) {
                    Log.w(TAG, "Audio passthrough gagal, video tetap disimpan", e)
                    audioKept = false
                }
            }

            onProgress(1f)
            if (muxerStarted) muxer.stop()

            return Result(
                outputPath = outputPath,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                outputFps = outputFps,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                audioKept = audioKept
            )
        } catch (e: TranscoderException) {
            Log.e(TAG, "Transcode gagal", e)
            try { muxer.stop() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcode gagal", e)
            try { muxer.stop() } catch (_: Exception) {}
            throw TranscoderException("Gagal memproses video: ${e.message}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun computeTargetSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Pair<Int, Int> {
        if (targetW <= 0 || targetH <= 0) {
            var w = srcW / 2 * 2; var h = srcH / 2 * 2
            if (w < 2) w = 2; if (h < 2) h = 2
            return Pair(w, h)
        }
        val ratio = srcW.toFloat() / srcH
        var w = targetW
        var h = (targetW / ratio).roundToInt()
        if (h > targetH) {
            h = targetH
            w = (targetH * ratio).roundToInt()
        }
        w = w / 2 * 2; h = h / 2 * 2
        if (w < 2) w = 2; if (h < 2) h = 2
        return Pair(w, h)
    }
}
