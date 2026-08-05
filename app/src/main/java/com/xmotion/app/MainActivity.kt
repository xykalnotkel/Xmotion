package com.xmotion.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.xmotion.app.databinding.ActivityMainBinding
import com.xmotion.app.native.NativeLib
import com.xmotion.app.transcode.VideoTranscoder
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null

    private var originalBitmap: Bitmap? = null

    private val resOptions = arrayOf(
        "Original", "480p (SD)", "720p (HD)",
        "1080p (Full HD)", "2K (1440p)", "4K (2160p)"
    )
    private val resHeights = arrayOf(0, 480, 720, 1080, 1440, 2160)

    private val qualityOptions = arrayOf("Rendah (file kecil)", "Sedang", "Tinggi (kualitas maks)")
    private val qualityIndex = arrayOf(0, 1, 2)

    private val fpsOptions = arrayOf("Auto (ikuti asli)", "24 FPS", "30 FPS", "60 FPS", "120 FPS")
    private val fpsValues = arrayOf(0, 24, 30, 60, 120)

    private val bitrateTable = mapOf(
        480 to intArrayOf(1_000_000, 2_000_000, 3_500_000),
        720 to intArrayOf(2_000_000, 3_500_000, 6_000_000),
        1080 to intArrayOf(4_000_000, 7_000_000, 12_000_000),
        1440 to intArrayOf(8_000_000, 14_000_000, 24_000_000),
        2160 to intArrayOf(16_000_000, 28_000_000, 45_000_000)
    )

    private var inputUri: Uri? = null
    private var inputPath: String? = null
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtNativeInfo.text = NativeLib.getNativeVersion()
        setupSpinners()
        setupNativeSliders()
        setupButtons()
    }

    private fun setupSpinners() {
        binding.spRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        binding.spQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualityOptions)
        binding.spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        binding.spRes.setSelection(2)      // 720p
        binding.spQuality.setSelection(1)  // sedang
        binding.spFps.setSelection(0)      // auto
    }

    private fun setupButtons() {
        binding.btnPick.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnCompress.setOnClickListener { startCompress() }
        binding.btnPreview.setOnClickListener { togglePreview() }
    }

    private fun togglePreview() {
        val path = inputPath ?: return
        if (player == null) {
            binding.playerView.visibility = View.VISIBLE
            binding.imgPreview.visibility = View.GONE
            val p = ExoPlayer.Builder(this).build()
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            p.prepare()
            binding.playerView.player = p
            player = p
            binding.btnPreview.text = "Stop Preview"
        } else {
            stopPreview()
        }
    }

    private fun stopPreview() {
        player?.release()
        player = null
        binding.playerView.player = null
        binding.playerView.visibility = View.GONE
        if (originalBitmap != null) binding.imgPreview.visibility = View.VISIBLE
        binding.btnPreview.text = getString(R.string.preview)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun setupNativeSliders() {
        fun refresh() {
            val src = originalBitmap ?: return
            val work = src.copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(work.width * work.height)
            work.getPixels(px, 0, work.width, 0, 0, work.width, work.height)
            NativeLib.processPixels(px, px.size, binding.slBright.value, binding.slContrast.value)
            work.setPixels(px, 0, work.width, 0, 0, work.width, work.height)
            binding.imgPreview.setImageBitmap(work)
        }
        binding.slBright.addOnChangeListener { _, _, _ -> refresh() }
        binding.slContrast.addOnChangeListener { _, _, _ -> refresh() }
    }

    private fun onVideoPicked(uri: Uri) {
        inputUri = uri
        stopPreview()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime

            binding.txtVideoInfo.text = "Resolusi: ${sourceWidth}x${sourceHeight} · Durasi: ${durMs / 1000}s"

            if (frame != null) {
                originalBitmap = frame
                binding.imgPreview.visibility = View.VISIBLE
                binding.imgPreview.setImageBitmap(frame)
            }
            binding.btnPreview.isEnabled = true

            lifecycleScope.launch {
                inputPath = copyUriToCache(uri)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membaca video: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            retriever.release()
        }
    }

    private fun copyUriToCache(uri: Uri): String? {
        return try {
            val file = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyalin file: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun startCompress() {
        val path = inputPath
        if (path == null) {
            Toast.makeText(this, "Pilih video dulu", Toast.LENGTH_LONG).show()
            return
        }

        val targetHeight = resHeights[binding.spRes.selectedItemPosition]
        val q = qualityIndex[binding.spQuality.selectedItemPosition]
        val useHevc = binding.swHevc.isChecked
        val targetFps = fpsValues[binding.spFps.selectedItemPosition]

        val effHeight = if (targetHeight == 0) sourceHeight else targetHeight
        val nearestH = findNearestHeight(effHeight)
        val bitrate = bitrateTable[nearestH]?.get(q) ?: 6_000_000

        val outFile = File(getExternalFilesDir(null), "xmotion_output.mp4")
        outFile.delete()

        binding.btnCompress.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.txtProgressLabel.visibility = View.VISIBLE
        binding.txtResult.text = ""
        stopPreview()

        lifecycleScope.launch {
            try {
                val result = VideoTranscoder.transcode(
                    inputPath = path,
                    outputPath = outFile.absolutePath,
                    targetWidth = 0,
                    targetHeight = targetHeight,
                    targetFps = targetFps,
                    bitrate = bitrate,
                    useHevc = useHevc,
                    onProgress = { p ->
                        val pct = (p * 100).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            binding.progress.progress = pct
                            binding.txtProgressLabel.text = "Memproses… $pct%"
                        }
                    }
                )

                val savedUri = addToMediaStore(outFile)
                binding.txtProgressLabel.text = "Selesai ✓"
                binding.progress.progress = 100

                val sizeMB = "%.2f".format(result.outputBytes / 1024.0 / 1024.0)
                val inMB = "%.2f".format(result.inputBytes / 1024.0 / 1024.0)
                val savedStr = if (savedUri != null) "Tersimpan di galeri (Movies/XMotion)."
                    else "Tersimpan: ${outFile.absolutePath}"
                val audioStr = if (result.audioKept) "Audio: tersimpan" else "Audio: (tidak tersimpan)"
                binding.txtResult.text =
                    "Output: ${result.outputWidth}x${result.outputHeight} @ ${result.outputFps}fps\n" +
                    "Ukuran: $inMB MB → $sizeMB MB\n" +
                    "Codec: ${if (useHevc) "HEVC (H.265)" else "H.264"} · $audioStr\n" +
                    savedStr

                Toast.makeText(this@MainActivity, "Kompresi selesai!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.txtResult.text = "Error: ${e.message ?: "Terjadi kesalahan"}"
                Toast.makeText(this@MainActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnCompress.isEnabled = true
            }
        }
    }

    private fun findNearestHeight(h: Int): Int {
        if (h <= 480) return 480
        if (h <= 720) return 720
        if (h <= 1080) return 1080
        if (h <= 1440) return 1440
        return 2160
    }

    private fun addToMediaStore(file: File): Uri? {
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "xmotion_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/XMotion")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }
}
