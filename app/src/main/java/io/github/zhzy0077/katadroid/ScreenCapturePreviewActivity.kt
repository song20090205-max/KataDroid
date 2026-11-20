package io.github.zhzy0077.katadroid

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout

/** Displays the user-approved frame and starts manual board calibration. */
class ScreenCapturePreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: return finish()
        val bitmap = BitmapFactory.decodeFile(path) ?: return finish()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101318.toInt())
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.screen_capture_preview_description)
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val recognize = Button(this).apply {
            text = getString(R.string.board_capture_start)
            setOnClickListener {
                startActivity(Intent(this@ScreenCapturePreviewActivity, BoardCornerPickerActivity::class.java)
                    .putExtra(BoardCornerPickerActivity.EXTRA_IMAGE_PATH, path))
            }
        }
        root.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(recognize, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (56 * resources.displayMetrics.density).toInt()
        ))
        setContentView(root)
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "screen_capture_image_path"
    }
}
