package io.github.zhzy0077.katadroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * User-started, user-visible floating assistant shell.
 *
 * This first milestone only provides the overlay and lifecycle. Screen capture,
 * board recognition and KataGo hand-off are deliberately not implied yet.
 */
class FloatingAssistantService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (overlay == null) showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xEE20242B.toInt())
        }
        val bubble = Button(this).apply {
            text = getString(R.string.floating_assistant_bubble)
            contentDescription = getString(R.string.floating_assistant_description)
            setOnTouchListener(DragTouchListener())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.floating_assistant_prototype)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        })
        panel.addView(Button(this).apply {
            text = getString(R.string.floating_assistant_close_panel)
            setOnClickListener { panel.visibility = View.GONE }
        })
        panel.addView(Button(this).apply {
            text = getString(R.string.floating_assistant_stop)
            setOnClickListener { stopSelf() }
        })
        bubble.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        root.addView(bubble)
        root.addView(panel)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(180)
        }
        params = windowParams
        overlay = root
        windowManager.addView(root, windowParams)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) dragged = true
                    if (dragged) {
                        p.x = (startX - dx).coerceAtLeast(0)
                        p.y = (startY + dy).coerceAtLeast(0)
                        overlay?.let { windowManager.updateViewLayout(it, p) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) view.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPending = launchIntent?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(getString(R.string.floating_assistant_notification_title))
                .setContentText(getString(R.string.floating_assistant_notification_text))
                .setContentIntent(contentPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.floating_assistant_stop), stopPending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(getString(R.string.floating_assistant_notification_title))
                .setContentText(getString(R.string.floating_assistant_notification_text))
                .setContentIntent(contentPending)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.floating_assistant_notification_title),
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        params = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "io.github.zhzy0077.katadroid.action.STOP_FLOATING_ASSISTANT"
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIFICATION_ID = 4107
    }
}
