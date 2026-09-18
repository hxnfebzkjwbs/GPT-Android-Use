package com.hxnfebzkjwbs.gptandroiduse

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val wirelessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        binding.statusText.text = if (wirelessSupported) {
            "Android 11+ detected. Wireless ADB can be used if the ROM exposes it."
        } else {
            "Android version is below 11. Native adb pair support is unavailable."
        }

        binding.deviceText.text = "Device: " + Build.MANUFACTURER + " " + Build.MODEL +
            " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"

        val advice = OemCompatibility.current()
        binding.oemText.text = advice.level.toString() + ": " + advice.message

        binding.openDeveloperOptionsButton.setOnClickListener {
            DeveloperSettings.openDeveloperOptions(this)
        }
        binding.openWirelessDebuggingButton.setOnClickListener {
            if (!DeveloperSettings.openWirelessDebugging(this)) {
                binding.commandResultText.text =
                    "Wireless debugging settings screen is not exposed by this ROM."
            }
        }
        binding.validateCommandButton.setOnClickListener {
            val result = CommandPolicy.validate(binding.commandInput.text.toString())
            binding.commandResultText.text =
                (if (result.allowed) "Allowed: " else "Blocked: ") + result.reason
        }
    }
}
