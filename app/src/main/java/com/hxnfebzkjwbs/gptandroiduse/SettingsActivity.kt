package com.hxnfebzkjwbs.gptandroiduse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(this, binding.root)
        supportActionBar?.hide()

        val adbReady = intent.getBooleanExtra(EXTRA_ADB_READY, false)
        val bridgeReady = intent.getBooleanExtra(EXTRA_BRIDGE_READY, false)

        binding.adbStatusText.text =
            if (adbReady) "已连接" else "未就绪"
        binding.bridgeStatusText.text =
            if (bridgeReady) "已连接" else "未就绪"
        binding.accessibilityStatusText.text =
            if (TextInputAccessibilityService.isConnected()) {
                "已启用"
            } else {
                "未启用"
            }

        val version =
            runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
        binding.versionText.text = version.ifBlank { "?" }

        binding.backButton.setOnClickListener { finish() }

        binding.recheckButton.setOnClickListener {
            returnAction(ACTION_RECHECK)
        }

        binding.webDebugButton.setOnClickListener {
            returnAction(ACTION_WEB_DEBUG)
        }

        binding.reloadWebButton.setOnClickListener {
            returnAction(ACTION_RELOAD_WEB)
        }

        binding.adbSetupButton.setOnClickListener {
            startActivity(Intent(this, AdbSetupActivity::class.java))
        }

        binding.adbTestButton.setOnClickListener {
            returnAction(ACTION_ADB_TEST)
        }

        binding.bridgeTestButton.setOnClickListener {
            returnAction(ACTION_BRIDGE_TEST)
        }

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }

        binding.logsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.accessibilityStatusText.text =
                if (TextInputAccessibilityService.isConnected()) {
                    "已启用"
                } else {
                    "未启用"
                }
        }
    }

    private fun returnAction(action: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_ACTION, action)
        )
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "settings_action"
        const val EXTRA_ADB_READY = "adb_ready"
        const val EXTRA_BRIDGE_READY = "bridge_ready"

        const val ACTION_WEB_DEBUG = "web_debug"
        const val ACTION_RECHECK = "recheck"
        const val ACTION_RELOAD_WEB = "reload_web"
        const val ACTION_ADB_TEST = "adb_test"
        const val ACTION_BRIDGE_TEST = "bridge_test"
    }
}
