package com.hxnfebzkjwbs.gptandroiduse

object CommandPolicy {
    private val allowedPrefixes = listOf(
        "input tap ", "input swipe ", "input text ", "am start ", "am force-stop ",
        "pm list packages", "pm path ", "dumpsys ", "settings get ",
        "uiautomator dump", "screencap "
    )
    private val deniedFragments = listOf(
        "rm ", "reboot", "shutdown", "wipe", "factory_reset", "dd ", "mkfs",
        "su ", "chmod 777", "pm uninstall", "settings put secure"
    )

    fun validate(command: String): ValidationResult {
        val normalized = command.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return ValidationResult(false, "Command is empty")
        val lower = normalized.lowercase()
        if (deniedFragments.any { lower.contains(it) })
            return ValidationResult(false, "Blocked by safety policy")
        if (allowedPrefixes.none { lower.startsWith(it) })
            return ValidationResult(false, "Command is not in the current allowlist")
        return ValidationResult(true, "Allowed")
    }
}

data class ValidationResult(val allowed: Boolean, val reason: String)
