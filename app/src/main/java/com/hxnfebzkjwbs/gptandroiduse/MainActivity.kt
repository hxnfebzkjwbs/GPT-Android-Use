package com.hxnfebzkjwbs.gptandroiduse

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adb: AndroidAdbBridge
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adb = AndroidAdbBridge(applicationContext)

        binding.statusText.text = "Wireless ADB: not connected"
        binding.deviceText.text = "Device: " + Build.MANUFACTURER + " " + Build.MODEL +
            " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"

        val detectedAddress = LocalAddressDetector.detect(this)
        if (detectedAddress.isNotBlank()) {
            binding.hostInput.setText(detectedAddress)
        }

        val advice = OemCompatibility.current()
        binding.oemText.text = advice.level.toString() + ": " + advice.message

        binding.openWirelessDebuggingButton.setOnClickListener {
            if (!DeveloperSettings.openWirelessDebugging(this)) {
                showResult("Wireless debugging settings screen is not exposed by this ROM.")
            }
        }

        binding.pairButton.setOnClickListener {
            val host = binding.hostInput.text.toString().trim()
            val port = binding.pairingPortInput.text.toString().toIntOrNull()
            val code = binding.pairingCodeInput.text.toString()

            if (host.isBlank() || port == null) {
                showResult("Enter the IP and pairing port exactly as shown under 'Pair device with pairing code'.")
                return@setOnClickListener
            }

            runTask("Pairing…", "Wireless ADB: paired") {
                adb.pair(host, port, code).map {
                    "Paired with $host:$port. Now use the separate connection port from the Wireless debugging main page."
                }
            }
        }

        binding.connectManualButton.setOnClickListener {
            val host = binding.hostInput.text.toString().trim()
            val port = binding.connectionPortInput.text.toString().toIntOrNull()

            if (host.isBlank() || port == null) {
                showResult("Enter the IP and the connection port shown on the Wireless debugging main page.")
                return@setOnClickListener
            }

            runTask("Connecting to $host:$port…", "Wireless ADB: ready") {
                adb.connect(host, port).map {
                    "Connected to $host:$port"
                }
            }
        }

        binding.connectAutoButton.setOnClickListener {
            runTask("Discovering ADB connection service…", "Wireless ADB: ready") {
                adb.autoConnect().map {
                    "Connected using mDNS auto-discovery."
                }
            }
        }

        binding.executeCommandButton.setOnClickListener {
            val command = binding.commandInput.text.toString()
            runTask("Executing…", "Wireless ADB: ready") { adb.execute(command) }
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
