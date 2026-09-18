package com.hxnfebzkjwbs.gptandroiduse

object CommandPolicy {
    private val allowedPrefixes = listOf(
        "input tap ",
        "input swipe ",
        "input text ",
        "input keyevent ",
        "am start ",
        "am force-stop ",
        "pm list packages",
        "pm path ",
        "dumpsys ",
        "settings get ",
        "uiautomator dump",
        "screencap "
    )

    private val deniedFragments = listOf(
        "rm ",
        "reboot",
        "shutdown",
        "wipe",
        "factory_reset",
        "dd ",
        "mkfs",
        "su ",
        "chmod 777",
        "pm uninstall",
        "settings put secure"
    )

    private val shellControlPatterns = listOf(
        Regex("[;\\n\\r\\x60]"),
        Regex("&&|\\|\\|"),
        Regex("(^|[^\\\\])\\|"),
        Regex("[<>]"),
        Regex("\\$\\(")
    )

    fun validate(command: String): ValidationResult {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return ValidationResult(false, "Command is empty")
        if (trimmed.length > 1_000) return ValidationResult(false, "Command is too long")

        if (shellControlPatterns.any { it.containsMatchIn(trimmed) }) {
            return ValidationResult(false, "Shell chaining, pipes, redirects, or substitution are not allowed")
        }

        val normalized = trimmed.replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase()

        if (deniedFragments.any { lower.contains(it) }) {
            return ValidationResult(false, "Blocked by safety policy")
        }

        if (allowedPrefixes.none { lower.startsWith(it) }) {
            return ValidationResult(false, "Command is not in the current allowlist")
        }

        return ValidationResult(true, "Allowed")
    }
}

data class ValidationResult(val allowed: Boolean, val reason: String)
