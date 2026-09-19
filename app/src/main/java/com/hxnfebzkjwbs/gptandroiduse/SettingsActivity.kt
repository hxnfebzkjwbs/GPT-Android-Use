package com.hxnfebzkjwbs.gptandroiduse

import android.content.Intent
import android.net.Uri
import android.graphics.Color
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

        configureFloatingIconStyles()
    }

    private fun configureFloatingIconStyles() {
        binding.previewRing.setIconStyle(OverlaySettings.STYLE_RING)
        binding.previewOrbit.setIconStyle(OverlaySettings.STYLE_ORBIT)
        binding.previewRobot.setIconStyle(OverlaySettings.STYLE_ROBOT)
        binding.previewMinimal.setIconStyle(OverlaySettings.STYLE_MINIMAL)

        val choices = listOf(
            Triple(
                binding.styleRingCard,
                OverlaySettings.STYLE_RING,
                binding.previewRing
            ),
            Triple(
                binding.styleOrbitCard,
                OverlaySettings.STYLE_ORBIT,
                binding.previewOrbit
            ),
            Triple(
                binding.styleRobotCard,
                OverlaySettings.STYLE_ROBOT,
                binding.previewRobot
            ),
            Triple(
                binding.styleMinimalCard,
                OverlaySettings.STYLE_MINIMAL,
                binding.previewMinimal
            )
        )

        fun renderSelection(selected: String) {
            choices.forEach { (card, style, _) ->
                val active = style == selected
                card.strokeWidth = if (active) dp(2) else dp(1)
                card.strokeColor =
                    if (active) Color.rgb(65, 93, 190)
                    else Color.rgb(226, 229, 234)
                card.setCardBackgroundColor(
                    if (active) Color.rgb(245, 247, 255)
                    else Color.WHITE
                )
            }
        }

        var selected = OverlaySettings.getIconStyle(this)
        renderSelection(selected)

        choices.forEach { (card, style, _) ->
            card.setOnClickListener {
                selected = style
                OverlaySettings.setIconStyle(this, style)
                renderSelection(selected)
                WebViewOverlayHost.refreshFloatingStyle(this)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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
