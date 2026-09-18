package com.hxnfebzkjwbs.gptandroiduse

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityMainBinding
import io.github.muntashirakon.adb.android.AndroidUtils
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

        val advice = OemCompatibility.current()
        binding.oemText.text = advice.level.toString() + ": " + advice.message

        binding.openWirelessDebuggingButton.setOnClickListener {
            if (!DeveloperSettings.openWirelessDebugging(this)) {
                showResult("Wireless debugging settings screen is not exposed by this ROM.")
            }
        }

        binding.pairButton.setOnClickListener {
            val port = binding.pairingPortInput.text.toString().toIntOrNull()
            val code = binding.pairingCodeInput.text.toString()
            if (port == null) {
                showResult("Enter the pairing port shown on Android's pairing screen.")
                return@setOnClickListener
            }
            runTask("Pairing…") {
                val host = AndroidUtils.getHostIpAddress(applicationContext)
                adb.pair(host, port, code).map {
                    adb.autoConnect().getOrThrow()
                    "Paired and connected to $host"
                }
            }
        }

        binding.connectButton.setOnClickListener {
            runTask("Connecting…") {
                adb.autoConnect().map { "Connected to paired Wireless ADB endpoint." }
            }
        }

        binding.executeCommandButton.setOnClickListener {
            val command = binding.commandInput.text.toString()
            runTask("Executing…") { adb.execute(command) }
        }
    }

    private fun runTask(progress: String, task: () -> Result<String>) {
        binding.statusText.text = progress
        executor.submit {
            val result = try {
                task()
            } catch (t: Throwable) {
                Result.failure(t)
            }
            runOnUiThread {
                if (result.isSuccess) {
                    binding.statusText.text = "Wireless ADB: ready"
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
