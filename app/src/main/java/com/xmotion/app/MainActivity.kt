package com.xmotion.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xmotion.app.databinding.ActivityMainBinding

/**
 * Layar utama (Home): card "Create Project" + ikon settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardCreate.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            Toast.makeText(this, "Pengaturan (segera hadir)", Toast.LENGTH_SHORT).show()
        }
    }
}
