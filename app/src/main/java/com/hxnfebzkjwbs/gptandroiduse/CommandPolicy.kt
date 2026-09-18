package com.hxnfebzkjwbs.gptandroiduse

enum class CommandDecision {
    ALLOW,
    REQUIRE_CONFIRMATION,
    DENY
}

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
        "screencap ",
        "getprop",
        "wm size",
        "wm density"
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
        "settings put secure",
        "settings put global adb_enabled"
    )

    private val shellControlPatterns = listOf(
        Regex("[;\\n\\r\\x60]"),
        Regex("&&|\\|\\|"),
        Regex("(^|[^\\\\])\\|"),
        Regex("[<>]"),
        Regex("\\$\\(")
    )

    private val boundedSleep = Regex("^sleep\\s+(?:[0-9](?:\\.[0-9]+)?|10(?:\\.0+)?)$")
    private val oneShotMonkey = Regex(
        "^monkey\\s+-p\\s+[A-Za-z0-9._]+(?:\\s+-c\\s+[A-Za-z0-9._]+)?\\s+1$",
        RegexOption.IGNORE_CASE
    )

    fun validate(command: String): ValidationResult {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return ValidationResult(CommandDecision.DENY, "Command is empty")
        }
        if (trimmed.length > 1_000) {
            return ValidationResult(CommandDecision.DENY, "Command is too long")
        }

        if (shellControlPatterns.any { it.containsMatchIn(trimmed) }) {
            return ValidationResult(
                CommandDecision.DENY,
                "Shell chaining, pipes, redirects, substitution, and embedded newlines are not allowed"
            )
        }

        val normalized = trimmed.replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase()

        if (deniedFragments.any { lower.contains(it) }) {
            return ValidationResult(CommandDecision.DENY, "Blocked by hard safety policy")
        }

        if (boundedSleep.matches(normalized) || oneShotMonkey.matches(normalized)) {
            return ValidationResult(CommandDecision.ALLOW, "Allowed automation command")
        }

        if (allowedPrefixes.any { lower.startsWith(it) }) {
            return ValidationResult(CommandDecision.ALLOW, "Allowed")
        }

        return ValidationResult(
            CommandDecision.REQUIRE_CONFIRMATION,
            "Command is outside the default allowlist. User approval is required for this one execution."
        )
    }
}

data class ValidationResult(
    val decision: CommandDecision,
    val reason: String
) {
    val allowedByDefault: Boolean
        get() = decision == CommandDecision.ALLOW

    fun canExecute(userApproved: Boolean): Boolean =
        decision == CommandDecision.ALLOW ||
            (decision == CommandDecision.REQUIRE_CONFIRMATION && userApproved)
}
