package com.hxnfebzkjwbs.gptandroiduse

import android.content.ComponentName
import android.content.Context
import android.content.Intent

class AccessibilityCommandExecutor(
    context: Context
) {
    private val appContext = context.applicationContext

    @Volatile
    private var targetPackage: String? = null

    @Volatile
    private var pendingScreenshot: ScreenshotAttachment? = null

    fun probe(): Result<Unit> = runCatching {
        check(TextInputAccessibilityService.isConnected()) {
            "Accessibility service is not connected"
        }
    }

    fun execute(
        command: String,
        userApproved: Boolean = false
    ): Result<String> = runCatching {
        val policy = CommandPolicy.validate(command)
        require(policy.canExecute(userApproved)) { policy.reason }

        val normalized =
            command.trim().replace(Regex("\\s+"), " ")

        when {
            normalized.startsWith(
                "uiautomator dump",
                ignoreCase = true
            ) -> {
                requireTargetForeground()
                TextInputAccessibilityService
                    .snapshotUiNow()
                    .getOrThrow()
            }

            normalized.equals(
                "screencap -p",
                ignoreCase = true
            ) -> {
                requireTargetForeground()
                val hidden =
                    WebViewOverlayHost.setHiddenForScreenshot(
                        appContext,
                        true
                    )
                if (hidden) Thread.sleep(80L)

                val shot = try {
                    TextInputAccessibilityService
                        .takeScreenshotNow()
                        .getOrThrow()
                } finally {
                    if (hidden) {
                        WebViewOverlayHost
                            .setHiddenForScreenshot(
                                appContext,
                                false
                            )
                    }
                }

                pendingScreenshot = shot
                buildString {
                    appendLine("SCREENSHOT_ATTACHED")
                    appendLine(
                        "image_size: " +
                            shot.width +
                            "x" +
                            shot.height
                    )
                    appendLine(
                        "attachment: " +
                            shot.fileName
                    )
                    append(
                        "Use the attached image itself to choose the next coordinate."
                    )
                }
            }

            normalized.startsWith(
                "input tap ",
                ignoreCase = true
            ) -> {
                requireTargetForeground()
                val parts = normalized.split(" ")
                require(parts.size >= 4) {
                    "input tap requires x y"
                }

                TextInputAccessibilityService
                    .tapNow(
                        parts[2].toFloat(),
                        parts[3].toFloat()
                    )
                    .getOrThrow()
            }

            normalized.startsWith(
                "input swipe ",
                ignoreCase = true
            ) -> {
                requireTargetForeground()
                val parts = normalized.split(" ")
                require(parts.size >= 6) {
                    "input swipe requires x1 y1 x2 y2"
                }

                TextInputAccessibilityService
                    .swipeNow(
                        parts[2].toFloat(),
                        parts[3].toFloat(),
                        parts[4].toFloat(),
                        parts[5].toFloat(),
                        parts.getOrNull(6)
                            ?.toLongOrNull()
                            ?: 350L
                    )
                    .getOrThrow()
            }

            normalized.startsWith(
                "input text ",
                ignoreCase = true
            ) -> {
                requireTargetForeground()
                TextInputAccessibilityService
                    .setTextNow(
                        extractInputTextPayload(command)
                    )
                    .getOrThrow()
            }

            normalized.startsWith(
                "input keyevent ",
                ignoreCase = true
            ) -> {
                requireTargetForeground()
                val token =
                    normalized
                        .substringAfter(
                            "input keyevent ",
                            ""
                        )
                        .trim()

                val keyCode = when (
                    token.uppercase()
                ) {
                    "KEYCODE_BACK" -> 4
                    "KEYCODE_HOME" -> 3
                    "KEYCODE_APP_SWITCH",
                    "KEYCODE_RECENTS" -> 187
                    else ->
                        token.toIntOrNull()
                            ?: error(
                                "unsupported keyevent: " +
                                    token
                            )
                }

                TextInputAccessibilityService
                    .performKeyEventNow(keyCode)
                    .getOrThrow()
            }

            normalized.startsWith(
                "monkey ",
                ignoreCase = true
            ) -> {
                val pkg =
                    Regex(
                        """(?:^|\s)-p\s+([A-Za-z0-9._]+)"""
                    ).find(normalized)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: error(
                            "monkey command requires -p package"
                        )

                launchPackage(pkg)
            }

            normalized.startsWith(
                "am start ",
                ignoreCase = true
            ) -> {
                launchFromAmStart(normalized)
            }

            else -> {
                error(
                    "Command is not supported by the Accessibility backend: " +
                        normalized
                )
            }
        }
    }

    fun observeUi(): Result<String> = runCatching {
        requireTargetForeground()
        TextInputAccessibilityService
            .snapshotUiNow()
            .getOrThrow()
    }

    fun isReady(): Boolean =
        TextInputAccessibilityService.isConnected()

    fun diagnostics(): String = buildString {
        appendLine("backend: accessibility")
        appendLine(
            "service_enabled: " +
                TextInputAccessibilityService.isEnabled(
                    appContext
                )
        )
        appendLine(
            "service_connected: " +
                TextInputAccessibilityService.isConnected()
        )
        appendLine(
            "target_package: " +
                (targetPackage ?: "none")
        )
        append(
            "foreground_package: " +
                TextInputAccessibilityService
                    .currentPackageName()
        )
    }

    fun consumePendingScreenshot(): ScreenshotAttachment? {
        val value = pendingScreenshot
        pendingScreenshot = null
        return value
    }

    fun disconnect() = Unit

    private fun launchPackage(pkg: String): String {
        val intent =
            appContext.packageManager
                .getLaunchIntentForPackage(pkg)
                ?: error(
                    "No launchable activity for package " +
                        pkg
                )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        targetPackage = pkg
        waitForTarget(pkg)

        return "launched " + pkg
    }

    private fun launchFromAmStart(
        command: String
    ): String {
        val componentText =
            Regex(
                """(?:^|\s)-n\s+([^\s]+)"""
            ).find(command)
                ?.groupValues
                ?.getOrNull(1)

        if (!componentText.isNullOrBlank()) {
            val component =
                ComponentName.unflattenFromString(
                    componentText
                ) ?: error(
                    "invalid component " +
                        componentText
                )

            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            appContext.startActivity(intent)
            targetPackage = component.packageName
            waitForTarget(component.packageName)

            return "launched " +
                component.flattenToShortString()
        }

        val pkg =
            Regex(
                """(?:^|\s)-p\s+([A-Za-z0-9._]+)"""
            ).find(command)
                ?.groupValues
                ?.getOrNull(1)

        if (!pkg.isNullOrBlank()) {
            return launchPackage(pkg)
        }

        error(
            "Accessibility backend requires am start -n package/activity"
        )
    }

    private fun waitForTarget(pkg: String) {
        repeat(8) {
            if (
                TextInputAccessibilityService
                    .currentPackageName() == pkg
            ) {
                return
            }
            Thread.sleep(120L)
        }
    }

    private fun requireTargetForeground() {
        val expected =
            targetPackage ?: error(
                "No external target app has been established. " +
                    "Launch the target first."
            )

        val foreground =
            TextInputAccessibilityService
                .currentPackageName()

        check(foreground == expected) {
            "Target app is not foreground. target_package=" +
                expected +
                ", foreground_package=" +
                foreground
        }
    }

    private fun extractInputTextPayload(
        command: String
    ): String {
        val match =
            Regex(
                "^input\\s+text\\s+(.+)$",
                RegexOption.IGNORE_CASE
            ).find(command.trim())
                ?: error(
                    "input text requires text after the command"
                )

        var value = match.groupValues[1].trim()
        if (
            value.length >= 2 &&
            (
                (
                    value.startsWith(""") &&
                        value.endsWith(""")
                ) ||
                    (
                        value.startsWith("'") &&
                            value.endsWith("'")
                    )
            )
        ) {
            value =
                value.substring(
                    1,
                    value.length - 1
                )
        }

        return value
            .replace("%s", " ")
            .replace("\\ ", " ")
    }
}
