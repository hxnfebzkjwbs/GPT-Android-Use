package com.hxnfebzkjwbs.gptandroiduse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class OverlayKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveTicks = 0L

    private val keepAliveTask = object : Runnable {
        override fun run() {
            val ok = WebViewOverlayHost.keepAliveTick()
            keepAliveTicks += 1

            if (keepAliveTicks % KEEPALIVE_LOG_EVERY_TICKS == 0L) {
                AppLog.add(
                    "NATIVE_KEEPALIVE",
                    "tick=" + keepAliveTicks + " webview_tick=" + ok
                )
            }

            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        handler.removeCallbacks(keepAliveTask)
        handler.post(keepAliveTask)
        AppLog.add("NATIVE_KEEPALIVE", "started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!handler.hasCallbacks(keepAliveTask)) {
            handler.post(keepAliveTask)
        }
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

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveTask)
        AppLog.add("NATIVE_KEEPALIVE", "stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ai_overlay_control"
        private const val NOTIFICATION_ID = 1102
        private const val KEEPALIVE_INTERVAL_MS = 1_000L
        private const val KEEPALIVE_LOG_EVERY_TICKS = 5L
        const val ACTION_UPDATE_OPACITY =
            "com.hxnfebzkjwbs.gptandroiduse.UPDATE_OVERLAY_OPACITY"
    }
}
