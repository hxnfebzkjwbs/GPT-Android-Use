package com.hxnfebzkjwbs.gptandroiduse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

class PairingForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var discovery: ShizukuStyleAdbDiscovery? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wireless ADB pairing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_CODE)
                    ?.toString()
                    .orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                startInForeground(workingNotification())
                if (port in 1..65535) pair(port, code) else publishFailure("Pairing port was lost.")
            }
            ACTION_STOP -> {
                stopPairing()
            }
            else -> {
                startInForeground(searchingNotification())
                startDiscovery()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        discovery?.stop()
        discovery = ShizukuStyleAdbDiscovery(
            this,
            ShizukuStyleAdbDiscovery.TLS_PAIRING,
            onPort = { port ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, inputNotification(port))
            },
            onError = { publishFailure(it) }
        ).also { it.start() }
    }

    private fun pair(port: Int, code: String) {
        discovery?.stop()
        executor.execute {
            val result = AndroidAdbBridge(applicationContext)
                .pair(ShizukuStyleAdbDiscovery.LOOPBACK, port, code)

            if (result.isSuccess) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_PAIR_SUCCESS, System.currentTimeMillis())
                    .apply()
                publishSuccess("Paired successfully. Return to the app and tap Discover & connect.")
            } else {
                publishFailure(result.exceptionOrNull()?.message ?: "Pairing failed.")
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun searchingNotification(): Notification {
        return baseNotification("Searching for Wireless ADB pairing service…")
            .addAction(stopAction())
            .build()
    }

    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_CODE)
            .setLabel("Enter 6-digit pairing code")
            .build()

        val pendingIntent = PendingIntent.getService(
            this,
            REQUEST_REPLY,
            Intent(this, PairingForegroundService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val action = Notification.Action.Builder(
            null,
            "Enter pairing code",
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        return baseNotification("Pairing service found on port $port")
            .setContentText("Enter the 6-digit code without closing Android's pairing dialog.")
            .addAction(action)
            .addAction(stopAction())
            .build()
    }

    private fun workingNotification(): Notification =
        baseNotification("Pairing with this phone…").build()

    private fun publishSuccess(message: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, baseNotification("Wireless ADB paired").setContentText(message).build())
        stopSelf()
    }

    private fun publishFailure(message: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, baseNotification("Wireless ADB pairing failed").setContentText(message).build())
        stopSelf()
    }

    private fun stopAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, PairingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, "Stop", pi).build()
    }

    private fun baseNotification(title: String): Notification.Builder {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
    }

    private fun stopPairing() {
        discovery?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        discovery?.stop()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wireless_adb_pairing"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_REPLY = 11
        private const val REQUEST_STOP = 12
        private const val ACTION_START = "pair.start"
        private const val ACTION_REPLY = "pair.reply"
        private const val ACTION_STOP = "pair.stop"
        private const val EXTRA_PORT = "pair.port"
        private const val REMOTE_INPUT_CODE = "pair.code"

        const val PREFS = "wireless_adb"
        const val KEY_LAST_PAIR_SUCCESS = "last_pair_success"

        fun startIntent(context: Context): Intent =
            Intent(context, PairingForegroundService::class.java).setAction(ACTION_START)
    }
}
