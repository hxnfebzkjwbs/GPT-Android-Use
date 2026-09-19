package com.hxnfebzkjwbs.gptandroiduse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityFirstRunSetupBinding

class FirstRunSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFirstRunSetupBinding
    private lateinit var adb: AndroidAdbBridge

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirstRunSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(this, binding.root)
        supportActionBar?.hide()

        adb = AndroidAdbBridge(applicationContext)

        binding.overlaySetupButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }

        binding.accessibilitySetupButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.notificationSetupButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }

        binding.adbSetupButton.setOnClickListener {
            startActivity(Intent(this, AdbSetupActivity::class.java))
        }

        binding.finishSetupButton.setOnClickListener {
            if (allRequiredReady()) {
                markCompleted(this)
            }
            finish()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        val overlayReady = Settings.canDrawOverlays(this)
        val accessibilityReady =
            TextInputAccessibilityService.isConnected()
        val notificationsReady =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        val wirelessReady = adb.isWirelessDebuggingEnabled()
        val adbReady = adb.isSessionReady()

        binding.overlayStatus.text =
            if (overlayReady) "已开启" else "未开启"
        binding.accessibilityStatus.text =
            if (accessibilityReady) "已开启" else "未开启"
        binding.notificationStatus.text =
            if (notificationsReady) "已开启" else "未开启"
        binding.adbStatus.text =
            when {
                adbReady -> "已连接"
                wirelessReady -> "已开启"
                else -> "未配置"
            }

        binding.notificationSetupButton.isEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationsReady
        binding.notificationSetupButton.text =
            if (notificationsReady) "已完成" else "开启"

        binding.finishSetupButton.text =
            if (allRequiredReady()) "进入应用" else "稍后完成"
    }

    private fun allRequiredReady(): Boolean {
        val overlayReady = Settings.canDrawOverlays(this)
        val accessibilityReady =
            TextInputAccessibilityService.isConnected()
        val notificationsReady =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        val adbReady = adb.isSessionReady()

        return overlayReady &&
            accessibilityReady &&
            notificationsReady &&
            adbReady
    }

    companion object {
        private const val PREFS = "first_run_setup"
        private const val KEY_COMPLETED = "completed"

        fun isCompleted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPLETED, false)

        private fun markCompleted(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPLETED, true)
                .apply()
        }
    }
}
