package io.github.zhzy0077.katadroid

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView

/** Displays the single frame explicitly captured with the system consent dialog. */
class ScreenCapturePreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: return finish()
        val bitmap = BitmapFactory.decodeFile(path) ?: return finish()
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.screen_capture_preview_description)
            setBackgroundColor(0xFF101318.toInt())
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        setContentView(image)
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "screen_capture_image_path"
    }
}
