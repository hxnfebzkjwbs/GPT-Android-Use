package com.hxnfebzkjwbs.gptandroiduse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adb = AndroidAdbBridge(applicationContext)

        binding.statusText.text = "Wireless ADB: not connected"
        binding.deviceText.text = "Device: " + Build.MANUFACTURER + " " + Build.MODEL +
            " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        binding.hostInput.setText(ShizukuStyleAdbDiscovery.LOOPBACK)

        val advice = OemCompatibility.current()
        binding.oemText.text = advice.level.toString() + ": " + advice.message

        binding.startPairingAssistantButton.setOnClickListener {
            requestNotificationThenPair()
        }

        binding.discoverConnectButton.setOnClickListener {
            runTask("Searching for _adb-tls-connect._tcp…", "Wireless ADB: ready") {
                val port = ShizukuStyleAdbDiscovery.discoverPortBlocking(
                    applicationContext,
                    ShizukuStyleAdbDiscovery.TLS_CONNECT,
                    15_000
                ).getOrThrow()

                adb.connect(ShizukuStyleAdbDiscovery.LOOPBACK, port).map {
                    binding.connectionPortInput.post { binding.connectionPortInput.setText(port.toString()) }
                    "Connected through " + ShizukuStyleAdbDiscovery.LOOPBACK + ":" + port + ". " +
                        "The IP shown by Android Settings is not needed for the normal local-device flow."
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
        ContextCompat.startForegroundService(
            this,
            PairingForegroundService.startIntent(this)
        )

        binding.statusText.text = "Wireless ADB: pairing assistant running"
        showResult(
            "In Wireless debugging, tap 'Pair device with pairing code'. " +
                "Keep that system dialog open. When the pairing service is discovered, " +
                "enter the 6-digit code from the GPT Android Use notification."
        )

        if (!DeveloperSettings.openWirelessDebugging(this)) {
            showResult("Pairing assistant started, but this ROM does not expose the Wireless debugging settings screen.")
        }
    }

    private fun runTask(
        progress: String,
        successStatus: String,
        task: () -> Result<String>
    ) {
        binding.statusText.text = progress
        executor.submit {
            val result = try {
                task()
            } catch (t: Throwable) {
                Result.failure(t)
            }
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

    override fun onDestroy() {
        adb.disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }
}
