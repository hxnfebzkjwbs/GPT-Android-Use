package com.hxnfebzkjwbs.gptandroiduse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayKeepAliveService : Service() {
    private var windowManager: WindowManager? = null
    private var statusView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        showStatusOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showStatusOverlay()
        return START_STICKY
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AI overlay control",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
    }

    private fun startInForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GPT Android Use")
            .setContentText("AI control overlay is active")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showStatusOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            removeStatusOverlay()
            return
        }
        if (statusView != null) return

        val wm = getSystemService(WindowManager::class.java)
        val view = TextView(this).apply {
            text = "AI"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xAA202124.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }

        val params = WindowManager.LayoutParams(
            dp(34),
            dp(24),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6)
            y = dp(28)
        }

        runCatching {
            wm.addView(view, params)
            windowManager = wm
            statusView = view
        }
    }

    private fun removeStatusOverlay() {
        val view = statusView ?: return
        runCatching { windowManager?.removeViewImmediate(view) }
        statusView = null
    }

    override fun onDestroy() {
        removeStatusOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val CHANNEL_ID = "ai_overlay_control"
        private const val NOTIFICATION_ID = 1102
    }
}
