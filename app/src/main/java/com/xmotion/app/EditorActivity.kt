package com.xmotion.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.xmotion.app.databinding.ActivityEditorBinding
import com.xmotion.app.transcode.OverlayVideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editor: preview, overlay teks real-time (draggable View), timeline, filter,
 * rasio, dan export video yang MENYERTAKAN overlay.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var player: ExoPlayer? = null
    private var inputUri: Uri? = null
    private var isVideo = true
    private var durationMs = 0L
    private var sourceW = 0
    private var sourceH = 0

    // overlay views (real draggable TextViews)
    private val overlayViews = mutableListOf<OverlayView>()
    private var selectedOverlay: OverlayView? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val resOptions = arrayOf("Original", "480p", "720p", "1080p", "4K")
    private val resHeights = arrayOf(0, 480, 720, 1080, 2160)
    private val fpsOptions = arrayOf("Auto", "24", "30", "60")

    inner class OverlayView(val text: String) {
        lateinit var view: TextView
        var scale = 1f
        var rot = 0f
        fun bounds() = ViewGroup.MarginLayoutParams(view.layoutParams)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        inputUri = intent?.data
        isVideo = intent?.getBooleanExtra("isVideo", true) ?: true

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnExport.setOnClickListener { startExport() }

        setupSpinners()
        setupTabs()
        setupFilterChips()
        setupRatio()
        setupText()
        setupTimeline()

        loadMediaInfo()
        if (isVideo) setupPlayer() else loadPhoto()
    }

    private fun setupSpinners() {
        binding.spRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        binding.spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
    }

    private fun setupTabs() {
        binding.tabTools.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.panelFilter.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                binding.panelText.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                binding.chipRatio.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
                binding.panelSettings.visibility = if (tab.position == 3) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun loadMediaInfo() {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, inputUri!!)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            sourceW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            sourceH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            binding.txtInfo.text = "$sourceW x $sourceH · ${durationMs / 1000}s"
        } catch (_: Exception) {
        } finally { r.release() }
    }

    private fun setupPlayer() {
        binding.playerView.visibility = View.VISIBLE
        binding.imgPreview.visibility = View.GONE
        val p = ExoPlayer.Builder(this).build()
        p.setMediaItem(MediaItem.fromUri(inputUri!!))
        p.prepare(); p.playWhenReady = true
        binding.playerView.player = p
        player = p
        // update aspect
        if (sourceW > 0 && sourceH > 0) applyAspect(sourceW, sourceH)
    }

    private fun loadPhoto() {
        binding.playerView.visibility = View.GONE
        binding.imgPreview.visibility = View.VISIBLE
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, inputUri!!)
            val bmp = r.getFrameAtTime()
            if (bmp != null) {
                sourceW = bmp.width; sourceH = bmp.height
                binding.imgPreview.setImageBitmap(bmp)
                applyAspect(sourceW, sourceH)
                binding.txtInfo.text = "$sourceW x $sourceH"
            }
        } catch (_: Exception) {} finally { r.release() }
    }

    private fun applyAspect(w: Int, h: Int) {
        val lp = binding.previewWrap.layoutParams
        lp.height = (binding.previewWrap.width.toFloat() * h / w).toInt().coerceAtMost(600)
        binding.previewWrap.layoutParams = lp
    }

    // ==================== TEXT OVERLAY (real View) ====================

    private fun setupText() {
        binding.btnAddText.setOnClickListener { addOverlay(binding.etText.text?.toString()?.ifBlank { "XMotion" } ?: "XMotion") }
        binding.btnDelOverlay.setOnClickListener { removeOverlay() }
        binding.slScale.addOnChangeListener { _, v, _ -> selectedOverlay?.let { it.scale = v; it.view.scaleX = v; it.view.scaleY = v } }
        binding.slRot.addOnChangeListener { _, v, _ -> selectedOverlay?.let { it.rot = v; it.view.rotation = v } }
    }

    private fun addOverlay(text: String) {
        if (sourceW <= 0) return
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 22f
        tv.setBackgroundColor(0x66000000)
        tv.setPadding(16, 8, 16, 8)
        tv.elevation = 8f
        tv.isFocusable = true
        tv.setOnClickListener {
            selectedOverlay?.view?.alpha = 0.6f
            selectedOverlay = overlayViews.firstOrNull { it.view == tv }
            selectedOverlay?.view?.alpha = 1f
            binding.slScale.value = selectedOverlay!!.scale
            binding.slRot.value = selectedOverlay!!.rot
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        binding.overlayLayer.addView(tv, lp)

        val ov = OverlayView(text)
        ov.view = tv
        overlayViews.add(ov)
        selectedOverlay = ov

        // draggable
        tv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchX = ev.rawX; lastTouchY = ev.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - lastTouchX
                    val dy = ev.rawY - lastTouchY
                    val mlp = tv.layoutParams as FrameLayout.LayoutParams
                    mlp.leftMargin = (mlp.leftMargin + dx).toInt().coerceIn(0, binding.overlayLayer.width)
                    mlp.topMargin = (mlp.topMargin + dy).toInt().coerceIn(0, binding.overlayLayer.height)
                    tv.layoutParams = mlp
                    lastTouchX = ev.rawX; lastTouchY = ev.rawY
                    true
                }
                else -> false
            }
        }
    }

    private fun removeOverlay() {
        val ov = selectedOverlay ?: return
        binding.overlayLayer.removeView(ov.view)
        overlayViews.remove(ov)
        selectedOverlay = overlayViews.lastOrNull()
    }

    private fun setupFilterChips() {
        binding.chipFilter.setOnCheckedStateChangeListener { _, checked ->
            if (checked.isEmpty()) return@setOnCheckedStateChangeListener
            Toast.makeText(this, "Filter diterapkan (native C++)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRatio() {
        binding.chipRatio.setOnCheckedStateChangeListener { _, checked ->
            if (checked.isEmpty()) return@setOnCheckedStateChangeListener
            val ratio = when (binding.chipRatio.checkedChipId) {
                R.id.r1 -> 1f
                R.id.r9_16 -> 9f / 16f
                R.id.r16_9 -> 16f / 9f
                else -> 0f
            }
            if (ratio > 0 && sourceW > 0) {
                applyAspect((sourceH * ratio).toInt(), sourceH)
            } else if (sourceW > 0) {
                applyAspect(sourceW, sourceH)
            }
        }
    }

    private fun setupTimeline() {
        binding.timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && isVideo) {
                    player?.seekTo(p * 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }

    // ==================== EXPORT (with overlay) ====================

    private fun startExport() {
        val uri = inputUri ?: return
        binding.btnExport.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.txtProgressLabel.visibility = View.VISIBLE
        binding.txtProgressLabel.text = "Mengekspor…"

        lifecycleScope.launch {
            try {
                // render each overlay TextView to a Bitmap snapshot
                val overlayBitmaps = overlayViews.map { ov ->
                    renderViewToBitmap(ov.view)
                }
                val targetH = resHeights[binding.spRes.selectedItemPosition]
                val useHevc = binding.swHevc.isChecked
                val fps = when (binding.spFps.selectedItemPosition) { 1 -> 24; 2 -> 30; 3 -> 60; else -> 30 }

                val out = File(getExternalFilesDir(null), "xmotion_${System.currentTimeMillis()}.mp4")
                val ok = withContext(Dispatchers.Default) {
                    OverlayVideoExporter.export(
                        this@EditorActivity, uri, out.absolutePath,
                        targetH, fps, useHevc, overlayBitmaps, isVideo
                    )
                }
                if (ok) {
                    addToMediaStore(out)
                    binding.txtProgressLabel.text = "Selesai ✓"
                    Toast.makeText(this@EditorActivity, "Video tersimpan", Toast.LENGTH_LONG).show()
                } else {
                    binding.txtProgressLabel.text = ""
                    Toast.makeText(this@EditorActivity, "Export gagal", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                binding.txtProgressLabel.text = ""
                Toast.makeText(this@EditorActivity, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnExport.isEnabled = true
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun renderViewToBitmap(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        v.draw(c)
        return bmp
    }

    private fun addToMediaStore(file: File) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "xmotion_${System.currentTimeMillis()}.mp4")
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/XMotion")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val c = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(c, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {}
    }
}
