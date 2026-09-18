package com.hxnfebzkjwbs.gptandroiduse

import android.os.Build

object OemCompatibility {
    fun current(): OemAdvice {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                OemAdvice(CompatibilityLevel.NEEDS_EXTRA_SETUP,
                    "Xiaomi/Redmi/POCO detected. If input or privileged ADB actions fail, enable 'USB debugging (Security settings)' in Developer options.")
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") ->
                OemAdvice(CompatibilityLevel.NEEDS_EXTRA_SETUP,
                    "OPPO/OnePlus/realme detected. Some ROMs restrict ADB through Permission Monitoring/System Optimization settings.")
            manufacturer.contains("huawei") ->
                OemAdvice(CompatibilityLevel.LIMITED,
                    "Huawei detected. Many EMUI/HarmonyOS 2–4 devices do not expose standard Android Wireless debugging.")
            else -> OemAdvice(CompatibilityLevel.STANDARD,
                "No known OEM-specific Wireless ADB restriction detected.")
        }
    }
}

enum class CompatibilityLevel { STANDARD, NEEDS_EXTRA_SETUP, LIMITED }
data class OemAdvice(val level: CompatibilityLevel, val message: String)
