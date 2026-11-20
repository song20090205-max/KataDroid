package io.github.zhzy0077.katadroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one user-approved screen frame. It does not continuously record.
 * The image stays in this app's private cache and is opened only by the user
 * from the completion notification.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val captured = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildWorkingNotification())
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            worker = HandlerThread("KataGoScreenCapture").also { it.start() }
            handler = Handler(worker!!.looper)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                if (captured.get()) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (captured.compareAndSet(false, true)) saveFrame(image, width, height)
                } finally {
                    image.close()
                }
            }, handler)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseCaptureResources()
                    stopSelf()
                }
            }, handler)
            virtualDisplay = projection!!.createVirtualDisplay(
                "KataGo-single-frame-capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )
            handler!!.postDelayed({
                if (!captured.get()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, CAPTURE_TIMEOUT_MS)
        } catch (_: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun saveFrame(image: android.media.Image, width: Int, height: Int) {
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
            val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bitmap = Bitmap.createBitmap(full, 0, 0, width, height)
            full.recycle()
            val output = File(cacheDir, PREVIEW_FILE)
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            showCompletedNotification(output.absolutePath)
            stopForeground(STOP_FOREGROUND_DETACH)
            projection?.stop()
            stopSelf()
        } catch (_: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            projection?.stop()
            stopSelf()
        }
    }

    private fun buildWorkingNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.screen_capture_title))
            .setContentText(getString(R.string.screen_capture_working))
            .setOngoing(true)
            .build()

    private fun showCompletedNotification(path: String) {
        val previewIntent = Intent(this, ScreenCapturePreviewActivity::class.java)
            .putExtra(ScreenCapturePreviewActivity.EXTRA_IMAGE_PATH, path)
        val pending = PendingIntent.getActivity(
            this, 73, previewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.screen_capture_title))
            .setContentText(getString(R.string.screen_capture_complete))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.screen_capture_title),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun releaseCaptureResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        worker?.quitSafely()
        worker = null
        handler = null
        projection = null
    }

    override fun onDestroy() {
        releaseCaptureResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4108
        private const val PREVIEW_FILE = "screen_capture_preview.jpg"
        private const val CAPTURE_TIMEOUT_MS = 5000L
    }
}
