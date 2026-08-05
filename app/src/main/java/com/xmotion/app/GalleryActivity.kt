package com.xmotion.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.xmotion.app.databinding.ActivityGalleryBinding
import com.xmotion.app.databinding.ItemMediaBinding

/**
 * Galeri: menampilkan video & foto dengan tab + dropdown folder/album.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private var isVideoTab = true
    private val adapter = MediaAdapter()
    private var currentAlbum: String? = null
    private val albumNames = mutableListOf("Semua media")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) loadMedia() else binding.txtPermission.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFolder.setOnClickListener { showAlbumDropdown() }

        binding.tabType.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                isVideoTab = tab.position == 0
                loadMedia()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        checkPermission()
    }

    private fun checkPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadMedia()
        } else {
            permLauncher.launch(perm)
        }
    }

    private fun loadMedia() {
        val list = mutableListOf<MediaItem>()
        val uri = if (isVideoTab)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = if (currentAlbum != null) "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME}=?" else null
        val selectionArgs = if (currentAlbum != null) arrayOf(currentAlbum!!) else null
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val bucketCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val contentUri = if (isVideoTab)
                        Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    else
                        Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val bucket = if (bucketCol >= 0) c.getString(bucketCol) ?: "Lainnya" else "Lainnya"
                    val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                    if (bucket !in albumNames) albumNames.add(bucket)
                    list.add(MediaItem(contentUri, dur))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memuat media", Toast.LENGTH_SHORT).show()
        }
        adapter.submit(list)
        binding.txtPermission.visibility = View.GONE
        binding.txtFolder.text = currentAlbum ?: "Semua media"
    }

    private fun showAlbumDropdown() {
        val items = albumNames.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pilih Album / Folder")
            .setItems(items) { _, which ->
                currentAlbum = if (which == 0) null else items[which]
                loadMedia()
            }
            .show()
    }

    data class MediaItem(val uri: Uri, val durationMs: Long)

    inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.VH>() {
        private val data = mutableListOf<MediaItem>()

        fun submit(list: List<MediaItem>) {
            data.clear(); data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = data[pos]
            Glide.with(this@GalleryActivity).load(item.uri).centerCrop().into(h.binding.thumb)
            if (isVideoTab && item.durationMs > 0) {
                h.binding.txtDuration.visibility = View.VISIBLE
                h.binding.txtDuration.text = formatDur(item.durationMs)
            } else {
                h.binding.txtDuration.visibility = View.GONE
            }
            h.binding.root.setOnClickListener {
                openInEditor(item)
            }
        }

        override fun getItemCount() = data.size

        class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun openInEditor(item: MediaItem) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.data = item.uri
        intent.putExtra("isVideo", isVideoTab)
        startActivity(intent)
    }

    private fun formatDur(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
