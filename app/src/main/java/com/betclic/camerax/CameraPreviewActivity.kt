package com.betclic.camerax

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import kotlinx.android.synthetic.main.activity_camera_preview.*

class CameraPreviewActivity : AppCompatActivity() {

    companion object {

        private const val PHOTO_URI = "PHOTO_URI_VALUE"
        fun newIntent(context: Context, photUri: String) =
            Intent(context, CameraPreviewActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(PHOTO_URI, photUri)
            }
    }

    private val photUri: String by lazy { intent.getStringExtra(PHOTO_URI) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_preview)

        Glide
            .with(this)
            .load(photUri)
            .into(preview_photo)
    }

}