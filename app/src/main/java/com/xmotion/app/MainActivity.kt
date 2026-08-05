package com.xmotion.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.slider.Slider
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
    private var filteredBitmap: Bitmap? = null

    private val resOptions = arrayOf("Original", "480p", "720p", "1080p", "2K", "4K")
    private val resHeights = arrayOf(0, 480, 720, 1080, 1440, 2160)
    private val qualityOptions = arrayOf("Rendah", "Sedang", "Tinggi")
    private val fpsOptions = arrayOf("Auto", "24", "30", "60", "120")
    private val fpsValues = arrayOf(0, 24, 30, 60, 120)

    private val bitrateTable = mapOf(
        480 to intArrayOf(800_000, 1_800_000, 3_000_000),
        720 to intArrayOf(1_800_000, 3_500_000, 6_000_000),
        1080 to intArrayOf(3_500_000, 7_000_000, 12_000_000),
        1440 to intArrayOf(8_000_000, 14_000_000, 24_000_000),
        2160 to intArrayOf(16_000_000, 28_000_000, 45_000_000)
    )

    private var inputUri: Uri? = null
    private var inputPath: String? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var durationMs = 0L

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.txtNativeInfo.text = NativeLib.getNativeVersion()
        setupSpinners()
        setupFilterChips()
        setupTrim()
        setupButtons()
    }

    private fun setupSpinners() {
        binding.spRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        binding.spQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualityOptions)
        binding.spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        binding.spRes.setSelection(2)
        binding.spQuality.setSelection(1)
        binding.spFps.setSelection(0)
    }

    private fun setupFilterChips() {
        binding.chipFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            applySelectedFilter()
        }
        binding.chipRatio.setOnCheckedStateChangeListener { _, _ -> refreshTrimText() }
    }

    private fun setupTrim() {
        binding.slTrimStart.addOnChangeListener { _, _, _ -> refreshTrimText() }
        binding.slTrimEnd.addOnChangeListener { _, _, _ -> refreshTrimText() }
    }

    private fun refreshTrimText() {
        if (durationMs <= 0) return
        val startMs = durationMs * (binding.slTrimStart.value / 100f)
        val endMs = durationMs * (binding.slTrimEnd.value / 100f)
        binding.txtTrim.text = String.format("%.1fs – %.1fs", startMs / 1000f, endMs / 1000f)
    }

    private fun setupButtons() {
        binding.btnPick.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnCompress.setOnClickListener { startCompress() }
        binding.btnConvertPhoto.setOnClickListener { convertToPhoto() }
    }

    /** Dipanggil lewat android:onClick pada preview container */
    fun togglePreview(v: View) {
        val path = inputPath ?: return
        if (player == null) {
            binding.playerView.visibility = View.VISIBLE
            binding.imgPreview.visibility = View.GONE
            binding.txtTapPreview.visibility = View.GONE
            val p = ExoPlayer.Builder(this).build()
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            p.prepare(); p.playWhenReady = true
            binding.playerView.player = p
            player = p
        } else {
            stopPreview()
        }
    }

    private fun stopPreview() {
        player?.release(); player = null
        binding.playerView.player = null
        binding.playerView.visibility = View.GONE
        if (filteredBitmap != null) binding.imgPreview.visibility = View.VISIBLE
        binding.txtTapPreview.visibility = if (filteredBitmap == null) View.VISIBLE else View.GONE
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }

    private fun onVideoPicked(uri: Uri) {
        inputUri = uri
        stopPreview()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
            if (frame != null) {
                originalBitmap = frame
                filteredBitmap = frame
                binding.txtTapPreview.visibility = View.GONE
                binding.imgPreview.visibility = View.VISIBLE
                binding.imgPreview.setImageBitmap(frame)
            }
            binding.txtVideoInfo.text = "${sourceWidth}x${sourceHeight} · ${durationMs / 1000}s"
            refreshTrimText()
            lifecycleScope.launch { inputPath = copyUriToCache(uri) }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal baca video: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            retriever.release()
        }
    }

    private fun copyUriToCache(uri: Uri): String? = try {
        val f = File(cacheDir, "input_video.mp4")
        contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        f.absolutePath
    } catch (e: Exception) {
        Toast.makeText(this, "Gagal salin file: ${e.message}", Toast.LENGTH_LONG).show(); null
    }

    /** Terapkan filter native C++ ke frame preview. */
    private fun applySelectedFilter() {
        val src = originalBitmap ?: return
        val work = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = work.width; val h = work.height
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        val checkedId = binding.chipFilter.checkedChipId
        when (checkedId) {
            R.id.id_0 -> { /* original */ }
            R.id.id_1 -> NativeLib.applyGrayscale(px, px.size)
            R.id.id_2 -> NativeLib.applySepia(px, px.size, 1f)
            R.id.id_3 -> NativeLib.applyInvert(px, px.size)
            R.id.id_4 -> NativeLib.adjustSaturation(px, px.size, 1.6f)
            R.id.id_5 -> NativeLib.applyBlur(px, w, h, 4)
            else -> { /* nothing */ }
        }
        work.setPixels(px, 0, w, 0, 0, w, h)
        filteredBitmap = work
        if (player == null) binding.imgPreview.setImageBitmap(work)
    }

    private fun selectedFilterId(): Int = binding.chipFilter.checkedChipId

    private fun currentRatio(): Pair<Int, Int> {
        return when (binding.chipRatio.checkedChipId) {
            R.id.id_6 -> Pair(1, 1)
            R.id.id_7 -> Pair(9, 16)
            R.id.id_8 -> Pair(16, 9)
            R.id.id_9 -> Pair(4, 5)
            else -> Pair(0, 0)
        }
    }

    private fun startCompress() {
        val path = inputPath
        if (path == null) { Toast.makeText(this, "Pilih video dulu", Toast.LENGTH_LONG).show(); return }

        val targetH = resHeights[binding.spRes.selectedItemPosition]
        val q = binding.spQuality.selectedItemPosition
        val useHevc = binding.swHevc.isChecked
        val targetFps = fpsValues[binding.spFps.selectedItemPosition]
        val effH = if (targetH == 0) sourceHeight else targetH
        val bitrate = bitrateTable[findNearest(effH)]?.get(q) ?: 6_000_000

        val ratio = currentRatio()
        // hitung ukuran output piksel
        val outH = if (targetH == 0) sourceHeight else targetH
        var tw = 0; var th = outH
        if (ratio.first > 0) {
            th = outH
            tw = (outH.toDouble() * ratio.first / ratio.second).toInt()
        }
        val startMs = (durationMs * binding.slTrimStart.value / 100f).toLong()
        var endMs = (durationMs * binding.slTrimEnd.value / 100f).toLong()
        if (endMs <= startMs) endMs = startMs + 100

        val outFile = File(getExternalFilesDir(null), "xmotion_${System.currentTimeMillis()}.mp4")
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
                    targetWidth = tw,
                    targetHeight = th,
                    targetFps = targetFps,
                    bitrate = bitrate,
                    useHevc = useHevc,
                    startMs = startMs,
                    endMs = endMs,
                    onProgress = { p ->
                        val pct = (p * 100).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            binding.progress.progress = pct
                            binding.txtProgressLabel.text = "Memproses… $pct%"
                        }
                    }
                )
                val saved = addToMediaStore(outFile)
                binding.txtProgressLabel.text = "Selesai ✓"
                binding.progress.progress = 100
                val sizeMB = "%.2f".format(result.outputBytes / 1024.0 / 1024.0)
                val inMB = "%.2f".format(result.inputBytes / 1024.0 / 1024.0)
                binding.txtResult.text =
                    "${result.outputWidth}x${result.outputHeight} @ ${result.outputFps}fps\n" +
                    "Ukuran: $inMB MB → $sizeMB MB · Audio: ${if (result.audioKept) "ya" else "tidak"}\n" +
                    (if (saved != null) "Tersimpan di galeri (Movies/XMotion)." else "Tersimpan: ${outFile.absolutePath}")
                Toast.makeText(this@MainActivity, "Selesai!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.txtResult.text = "Error: ${e.message ?: "terjadi kesalahan"}"
                binding.txtProgressLabel.text = ""
                Toast.makeText(this@MainActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnCompress.isEnabled = true
            }
        }
    }

    /** Konversi video -> foto (ambil frame terbaik). */
    private fun convertToPhoto() {
        val uri = inputUri ?: run { Toast.makeText(this, "Pilih video dulu", Toast.LENGTH_LONG).show(); return }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val frame = retriever.getFrameAtTime(durationMs / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime ?: run { Toast.makeText(this, "Gagal ambil frame", Toast.LENGTH_LONG).show(); return }
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "xmotion_photo_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/XMotion")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val c = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ins = contentResolver.insert(c, values) ?: run { Toast.makeText(this, "Gagal simpan", Toast.LENGTH_LONG).show(); return }
            contentResolver.openOutputStream(ins)?.use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(ins, values, null, null)
            Toast.makeText(this, "Foto tersimpan di Pictures/XMotion", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            retriever.release()
        }
    }

    private fun findNearest(h: Int): Int {
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
            val c = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(c, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) { null }
    }
}
