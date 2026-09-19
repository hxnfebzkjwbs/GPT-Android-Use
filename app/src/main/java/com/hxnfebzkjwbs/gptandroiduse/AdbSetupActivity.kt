package com.hxnfebzkjwbs.gptandroiduse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityAdbSetupBinding
import java.util.concurrent.Executors

class AdbSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdbSetupBinding
    private lateinit var adb: AndroidAdbBridge
    private val executor = Executors.newSingleThreadExecutor()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchPairingAssistant()
            } else {
                showResult("Notification permission is required for Shizuku-style pairing code input.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdbSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(this, binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Wireless ADB"

        adb = AndroidAdbBridge(applicationContext)
        binding.statusText.text = "Wireless ADB: not connected"
        binding.deviceText.text = "Device: " + Build.MANUFACTURER + " " + Build.MODEL +
            " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        binding.hostInput.setText(ShizukuStyleAdbDiscovery.LOOPBACK)
        updateSelfHealButton()

        val advice = OemCompatibility.current()
        binding.oemText.text = advice.level.toString() + ": " + advice.message

        binding.startPairingAssistantButton.setOnClickListener {
            requestNotificationThenPair()
        }

        binding.discoverConnectButton.setOnClickListener {
            runTask("Searching for _adb-tls-connect._tcp…", "Wireless ADB: ready") {
                val endpoint = ShizukuStyleAdbDiscovery.discoverEndpointBlocking(
                    applicationContext,
                    ShizukuStyleAdbDiscovery.TLS_CONNECT,
                    15_000
                ).getOrThrow()

                adb.connect(endpoint.host, endpoint.port).map {
                    binding.hostInput.post {
                        binding.hostInput.setText(endpoint.host)
                    }
                    binding.connectionPortInput.post {
                        binding.connectionPortInput.setText(endpoint.port.toString())
                    }
                    "Connected to " + endpoint.host + ":" + endpoint.port
                }
            }
        }

        binding.enableSelfHealButton.setOnClickListener {
            if (AdbSelfHeal.isEnabled(applicationContext) && adb.hasSelfHealPermission()) {
                AdbSelfHeal.setEnabled(applicationContext, false)
                updateSelfHealButton()
                binding.statusText.text = "Wireless ADB self-heal: OFF"
                showResult(
                    "Automatic Wireless ADB re-enable is disabled. " +
                        "The WRITE_SECURE_SETTINGS grant remains until the app is uninstalled."
                )
            } else {
                runTask(
                    "Granting WRITE_SECURE_SETTINGS through current ADB…",
                    "Wireless ADB self-heal: ON"
                ) {
                    runCatching {
                        adb.autoConnect().getOrThrow()
                        adb.grantSelfHealPermission().getOrThrow()
                        AdbSelfHeal.setEnabled(applicationContext, true)
                        if (adb.isWifiConnected()) {
                            adb.enableWirelessDebugging().getOrThrow()
                        }
                        runOnUiThread { updateSelfHealButton() }
                        "Self-heal enabled. Future Wireless ADB dropouts will auto-reconnect; " +
                            "if Android turns Wireless debugging off, the app can turn it back on."
                    }
                }
            }
        }

        binding.manualPairButton.setOnClickListener {
            val host = binding.hostInput.text.toString().trim()
            val port = binding.pairingPortInput.text.toString().toIntOrNull()
            val code = binding.pairingCodeInput.text.toString()
            if (host.isBlank() || port == null) {
                showResult("Enter a host and pairing port.")
                return@setOnClickListener
            }
            runTask("Manual pairing…", "Wireless ADB: paired") {
                adb.pair(host, port, code).map { "Paired with " + host + ":" + port }
            }
        }

        binding.manualConnectButton.setOnClickListener {
            val host = binding.hostInput.text.toString().trim()
            val port = binding.connectionPortInput.text.toString().toIntOrNull()
            if (host.isBlank() || port == null) {
                showResult("Enter a host and connection port.")
                return@setOnClickListener
            }
            runTask("Connecting to " + host + ":" + port + "…", "Wireless ADB: ready") {
                adb.connect(host, port).map { "Connected to " + host + ":" + port }
            }
        }

        binding.executeCommandButton.setOnClickListener {
            val command = binding.commandInput.text.toString()
            runTask("Executing…", "Wireless ADB: ready") { adb.execute(command) }
        }
    }

    private fun updateSelfHealButton() {
        val active = AdbSelfHeal.isEnabled(applicationContext) &&
            adb.hasSelfHealPermission()
        binding.enableSelfHealButton.text =
            if (active) "ADB self-heal: ON (tap to disable)"
            else "3. Enable ADB self-heal (one time)"
    }

    private fun requestNotificationThenPair() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchPairingAssistant()
        }
    }

    private fun launchPairingAssistant() {
        ContextCompat.startForegroundService(this, PairingForegroundService.startIntent(this))
        binding.statusText.text = "Wireless ADB: pairing assistant running"
        showResult(
            "In Wireless debugging, tap 'Pair device with pairing code'. " +
                "Keep that system dialog open, then enter the 6-digit code from this app's notification."
        )
        DeveloperSettings.openWirelessDebugging(this)
    }

    private fun runTask(progress: String, successStatus: String, task: () -> Result<String>) {
        binding.statusText.text = progress
        executor.submit {
            val result = try { task() } catch (t: Throwable) { Result.failure(t) }
            runOnUiThread {
                if (result.isSuccess) {
                    binding.statusText.text = successStatus
                    showResult(result.getOrNull().orEmpty())
                } else {
                    binding.statusText.text = "Wireless ADB: error"
                    showResult(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            }
        }
    }

    private fun showResult(text: String) {
        binding.commandResultText.text = text
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
