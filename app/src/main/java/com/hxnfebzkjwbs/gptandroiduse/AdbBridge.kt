package com.hxnfebzkjwbs.gptandroiduse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

interface AdbBridge {
    fun pair(host: String, port: Int, pairingCode: String): Result<Unit>
    fun connect(host: String, port: Int): Result<Unit>
    fun autoConnect(): Result<Unit>
    fun execute(command: String, userApproved: Boolean = false): Result<String>
    fun probe(): Result<Unit>
    fun isConnected(): Boolean
    fun isSessionReady(): Boolean
    fun hasSelfHealPermission(): Boolean
    fun grantSelfHealPermission(): Result<Unit>
    fun isWirelessDebuggingEnabled(): Boolean
    fun enableWirelessDebugging(): Result<Unit>
    fun isWifiConnected(): Boolean
    fun disconnect()
    fun diagnostics(): String
    fun observeUi(verifyTarget: Boolean = true): Result<String>
}

class AndroidAdbBridge(private val context: Context) : AdbBridge {
    @Volatile private var lastEndpoint = "none"
    @Volatile private var lastStage = "init"
    @Volatile private var lastError = "none"
    @Volatile private var lastCommand = "none"
    @Volatile private var lastConnectSuccessAt = 0L
    @Volatile private var lastProbeSuccessAt = 0L
    @Volatile private var targetPackage: String? = null
    @Volatile private var lastForegroundPackage = "unknown"

    private val shellLock = Any()
    private var shellStream: AdbStream? = null
    private var shellReader: BufferedReader? = null
    private var shellWriter: OutputStream? = null

    private fun manager(): AbsAdbConnectionManager =
        AdbConnectionManager.getInstance(context)

    override fun pair(host: String, port: Int, pairingCode: String): Result<Unit> = runCatching {
        require(host.isNotBlank()) { "Wireless debugging host is required" }
        require(pairingCode.matches(Regex("\\d{6}"))) { "Pairing code must be 6 digits" }
        require(port in 1..65535) { "Invalid pairing port" }
        check(manager().pair(host.trim(), port, pairingCode)) { "ADB pairing failed" }
    }

    override fun connect(host: String, port: Int): Result<Unit> {
        lastEndpoint = host.trim() + ":" + port
        lastStage = "manual_connect"
        val result = runCatching {
            require(host.isNotBlank()) { "Wireless debugging host is required" }
            require(port in 1..65535) { "Invalid connection port" }
            val manager = manager()
            if (!manager.isConnected) {
                check(manager.connect(host.trim(), port)) { "ADB connection failed" }
            }
            lastConnectSuccessAt = System.currentTimeMillis()
            lastStage = "connected"
            lastError = "none"
        }
        recordFailure("manual_connect", result.exceptionOrNull())
        return result
    }

    override fun autoConnect(): Result<Unit> {
        lastStage = "tls_discovery"
        val result = runCatching {
            val manager = manager()
            if (manager.isConnected) {
                lastStage = "manager_already_connected"
                return@runCatching
            }

            var lastFailure: Throwable? = null
            repeat(AUTO_CONNECT_ATTEMPTS) { attempt ->
                lastStage = "tls_discovery"
                val endpoint = ShizukuStyleAdbDiscovery.discoverEndpointBlocking(
                    context,
                    ShizukuStyleAdbDiscovery.TLS_CONNECT,
                    AUTO_CONNECT_TIMEOUT_MS
                ).getOrThrow()

                lastEndpoint = endpoint.host + ":" + endpoint.port
                lastStage = "tls_connect"

                val connected = runCatching {
                    check(manager.connect(endpoint.host, endpoint.port)) {
                        "Wireless ADB TLS connection failed at " +
                            endpoint.host + ":" + endpoint.port
                    }
                }

                if (connected.isSuccess) {
                    lastConnectSuccessAt = System.currentTimeMillis()
                    lastStage = "connected"
                    lastError = "none"
                    return@runCatching
                }

                lastFailure = connected.exceptionOrNull()
                runCatching { manager.disconnect() }

                if (attempt + 1 < AUTO_CONNECT_ATTEMPTS) {
                    lastStage = "tls_rediscovery"
                    Thread.sleep(AUTO_CONNECT_RETRY_DELAY_MS)
                }
            }

            throw lastFailure ?: IllegalStateException(
                "Wireless ADB connection failed after endpoint rediscovery"
            )
        }
        recordFailure(lastStage, result.exceptionOrNull())
        return result
    }

    override fun execute(command: String, userApproved: Boolean): Result<String> {
        lastCommand = command.trim()
        lastStage = "shell_execute"
        val result = runCatching {
            val policy = CommandPolicy.validate(command)
            require(policy.canExecute(userApproved)) { policy.reason }

            val normalized = command.trim().replace(Regex("\\s+"), " ")
            when {
                normalized.startsWith("uiautomator dump", ignoreCase = true) -> {
                    observeUi().getOrThrow()
                }

                isTargetInteractionCommand(normalized) -> {
                    requireTargetForeground()
                    runShellUnchecked(normalized)
                }

                isTargetLaunchCommand(normalized) -> {
                    val output = runShellUnchecked(normalized)
                    establishTargetAfterLaunch(normalized)
                    output
                }

                normalized.startsWith("am force-stop ", ignoreCase = true) -> {
                    val output = runShellUnchecked(normalized)
                    clearTargetIfStopped(normalized)
                    output
                }

                else -> runShellUnchecked(normalized)
            }
        }
        recordFailure("shell_execute", result.exceptionOrNull())
        if (result.isSuccess) {
            lastStage = "shell_complete"
            lastError = "none"
        }
        return result
    }

    override fun probe(): Result<Unit> {
        lastStage = "probe"
        val result = runCatching {
            runShellUnchecked(PROBE_COMMAND)
            lastProbeSuccessAt = System.currentTimeMillis()
            Unit
        }
        recordFailure("probe", result.exceptionOrNull())
        if (result.isSuccess) {
            lastStage = "probe_ok"
            lastError = "none"
        }
        return result
    }

    override fun isConnected(): Boolean =
        runCatching { manager().isConnected }.getOrDefault(false)

    override fun isSessionReady(): Boolean =
        synchronized(shellLock) {
            runCatching {
                manager().isConnected &&
                    shellStream?.isClosed == false &&
                    shellReader != null &&
                    shellWriter != null
            }.getOrDefault(false)
        }

    override fun hasSelfHealPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    override fun grantSelfHealPermission(): Result<Unit> = runCatching {
        if (hasSelfHealPermission()) return@runCatching
        autoConnect().getOrThrow()
        val command = "pm grant " + context.packageName + " " +
            Manifest.permission.WRITE_SECURE_SETTINGS
        runShellUnchecked(command)
        check(hasSelfHealPermission()) { "WRITE_SECURE_SETTINGS was not granted" }
    }

    override fun isWirelessDebuggingEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED_KEY, 0) != 0

    override fun enableWirelessDebugging(): Result<Unit> = runCatching {
        check(hasSelfHealPermission()) { "WRITE_SECURE_SETTINGS is not granted" }
        check(isWifiConnected()) { "Wi-Fi is not connected" }
        check(Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED_KEY, 1)) {
            "Android rejected the Wireless debugging setting change"
        }
    }

    override fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    override fun observeUi(verifyTarget: Boolean): Result<String> = runCatching {
        lastStage = "ui_observe"
        if (verifyTarget) requireTargetForeground()
        val raw = runShellUnchecked("uiautomator dump " + UI_DUMP_STDOUT)
        val xml = extractHierarchyXml(raw)
        val summary = summarizeUiXml(xml)
        lastStage = "ui_observe_ok"
        lastError = "none"
        summary
    }.onFailure {
        recordFailure("ui_observe", it)
    }

    override fun disconnect() {
        lastStage = "disconnect"
        synchronized(shellLock) {
            closeShellLocked()
            runCatching { manager().disconnect() }
        }
    }

    override fun diagnostics(): String {
        val now = System.currentTimeMillis()
        val connectAge = ageMillis(now, lastConnectSuccessAt)
        val probeAge = ageMillis(now, lastProbeSuccessAt)
        val shellOpen = synchronized(shellLock) {
            shellStream?.isClosed == false
        }
        return buildString {
            appendLine("stage: " + lastStage)
            appendLine("endpoint: " + lastEndpoint)
            appendLine("manager_is_connected: " + isConnected())
            appendLine("persistent_shell_open: " + shellOpen)
            appendLine("target_package: " + (targetPackage ?: "none"))
            appendLine("last_foreground_package: " + lastForegroundPackage)
            appendLine("wireless_debugging_enabled: " + isWirelessDebuggingEnabled())
            appendLine("wifi_connected: " + isWifiConnected())
            appendLine("self_heal_enabled: " + AdbSelfHeal.isEnabled(context))
            appendLine("self_heal_permission: " + hasSelfHealPermission())
            appendLine("last_connect_success_ms_ago: " + connectAge)
            appendLine("last_probe_success_ms_ago: " + probeAge)
            appendLine("last_command: " + lastCommand)
            append("last_error: " + lastError)
        }
    }

    private fun recordFailure(stage: String, throwable: Throwable?) {
        if (throwable == null) return
        lastStage = stage
        lastError = throwableChain(throwable)
    }

    private fun throwableChain(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 6) {
            val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            val message = current.message?.take(400).orEmpty()
            parts += if (message.isBlank()) name else name + ": " + message
            current = current.cause
            depth += 1
        }
        return parts.joinToString(" <- ")
    }

    private fun ageMillis(now: Long, timestamp: Long): String =
        if (timestamp <= 0L) "never" else (now - timestamp).coerceAtLeast(0L).toString()

    private fun ensurePersistentShellLocked() {
        val current = shellStream
        if (current != null && !current.isClosed && shellReader != null && shellWriter != null) {
            return
        }

        check(manager().isConnected) { "ADB transport is not connected" }
        closeShellLocked()

        val stream = manager().openStream(LocalServices.SHELL)
        shellStream = stream
        shellReader = BufferedReader(InputStreamReader(stream.openInputStream()))
        shellWriter = stream.openOutputStream()
        lastStage = "shell_session_open"
    }

    private fun closeShellLocked() {
        runCatching { shellStream?.close() }
        shellReader = null
        shellWriter = null
        shellStream = null
    }

    private fun invalidateBrokenSession() {
        synchronized(shellLock) {
            closeShellLocked()
            runCatching { manager().disconnect() }
        }
    }

    private fun runShellUnchecked(command: String): String {
        synchronized(shellLock) {
            try {
                ensurePersistentShellLocked()

                val reader = checkNotNull(shellReader) { "ADB shell reader is unavailable" }
                val writer = checkNotNull(shellWriter) { "ADB shell writer is unavailable" }
                val marker = "__GPT_ANDROID_USE_DONE_" +
                    UUID.randomUUID().toString().replace("-", "") + "__"

                writer.write(command.trim().toByteArray(StandardCharsets.UTF_8))
                writer.write("\n".toByteArray(StandardCharsets.UTF_8))
                writer.write(
                    ("printf '\\n" + marker + ":%s\\n' \"\$?\"\n")
                        .toByteArray(StandardCharsets.UTF_8)
                )
                writer.flush()

                val output = StringBuilder()
                while (true) {
                    val line = reader.readLine()
                        ?: throw java.io.IOException(
                            "Persistent ADB shell closed before completion marker"
                        )
                    if (line.startsWith(marker + ":")) {
                        break
                    }
                    output.append(line).append('\n')
                }

                return cleanInteractiveShellOutput(
                    output.toString(),
                    command.trim(),
                    marker
                ).ifBlank { "(command completed with no output)" }
            } catch (t: Throwable) {
                invalidateBrokenSession()
                throw t
            }
        }
    }

    private fun isTargetInteractionCommand(command: String): Boolean {
        val lower = command.lowercase()
        return lower.startsWith("input tap ") ||
            lower.startsWith("input swipe ") ||
            lower.startsWith("input text ") ||
            lower.startsWith("input keyevent ")
    }

    private fun isTargetLaunchCommand(command: String): Boolean {
        val lower = command.lowercase()
        return lower.startsWith("am start ") || lower.startsWith("monkey ")
    }

    private fun establishTargetAfterLaunch(command: String) {
        val declared = extractDeclaredTargetPackage(command)
        var foreground = ""

        for (attempt in 0 until TARGET_LAUNCH_POLL_ATTEMPTS) {
            if (attempt > 0) Thread.sleep(TARGET_LAUNCH_POLL_DELAY_MS)
            foreground = currentForegroundPackage()
            if (foreground.isNotBlank() &&
                foreground != context.packageName
            ) {
                break
            }
        }

        val chosen = foreground
            .takeIf { it.isNotBlank() && it != context.packageName }
            ?: declared
            ?: error(
                "Target app did not become foreground after launch. " +
                    "Current foreground package: " +
                    lastForegroundPackage
            )

        targetPackage = chosen
        lastStage = "target_established"
    }

    private fun requireTargetForeground() {
        val expected = targetPackage ?: error(
            "No external target app has been established. " +
                "For tasks that control another app, the first device command must launch that app " +
                "with am start or monkey -p before using uiautomator dump or input commands."
        )

        val foreground = currentForegroundPackage()
        if (foreground != expected) {
            throw IllegalStateException(
                "Target app is not foreground. target_package=" + expected +
                    ", foreground_package=" + foreground +
                    ". Re-open the target app before inspecting or interacting with its UI."
            )
        }
    }

    private fun currentForegroundPackage(): String {
        val windowDump = runShellUnchecked(
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"
        )
        val windowPatterns = listOf(
            Regex("mCurrentFocus=.*?\\s([A-Za-z0-9._]+)/[A-Za-z0-9.\$_/]+"),
            Regex("mFocusedApp=.*?\\s([A-Za-z0-9._]+)/[A-Za-z0-9.\$_/]+")
        )

        windowPatterns.forEach { pattern ->
            val value = pattern.find(windowDump)?.groupValues?.getOrNull(1).orEmpty()
            if (value.isNotBlank()) {
                lastForegroundPackage = value
                return value
            }
        }

        val activityDump = runShellUnchecked(
            "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'"
        )
        val activityPatterns = listOf(
            Regex("mResumedActivity:.*?\\s([A-Za-z0-9._]+)/[A-Za-z0-9.\$_/]+"),
            Regex("topResumedActivity=.*?\\s([A-Za-z0-9._]+)/[A-Za-z0-9.\$_/]+"),
            Regex("ResumedActivity:.*?\\s([A-Za-z0-9._]+)/[A-Za-z0-9.\$_/]+")
        )

        activityPatterns.forEach { pattern ->
            val value = pattern.find(activityDump)?.groupValues?.getOrNull(1).orEmpty()
            if (value.isNotBlank()) {
                lastForegroundPackage = value
                return value
            }
        }

        lastForegroundPackage = "unknown"
        return ""
    }

    private fun extractDeclaredTargetPackage(command: String): String? {
        val component = Regex(
            """(?:^|\s)-n\s+([A-Za-z0-9._]+)/[^\s]+""",
            RegexOption.IGNORE_CASE
        ).find(command)?.groupValues?.getOrNull(1)
        if (!component.isNullOrBlank()) return component

        val packageArg = Regex(
            """(?:^|\s)-p\s+([A-Za-z0-9._]+)(?:\s|$)""",
            RegexOption.IGNORE_CASE
        ).find(command)?.groupValues?.getOrNull(1)
        if (!packageArg.isNullOrBlank()) return packageArg

        return null
    }

    private fun clearTargetIfStopped(command: String) {
        val stopped = command
            .trim()
            .substringAfter("am force-stop ", "")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()

        if (stopped.isNotBlank() && stopped == targetPackage) {
            targetPackage = null
        }
    }

    private fun cleanInteractiveShellOutput(
        raw: String,
        command: String,
        marker: String
    ): String {
        val normalized = raw
            .replace("\r", "")
            .replace("\u0008", "")

        val promptPattern = Regex("^[^\\n]*[#$]\\s*")
        val markerCommandToken = "printf '\\n" + marker

        return normalized
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed == command ||
                    trimmed.endsWith("$ " + command) ||
                    trimmed.endsWith("# " + command) ||
                    trimmed.contains(markerCommandToken) ||
                    trimmed.matches(Regex("^[^\\n]*[#$]\\s*$"))
            }
            .joinToString("\n")
            .trim()
    }

    private fun extractHierarchyXml(raw: String): String {
        val start = raw.indexOf("<hierarchy")
        val endTag = "</hierarchy>"
        val end = raw.lastIndexOf(endTag)

        if (start < 0 || end < start) {
            val preview = raw
                .replace("\r", "")
                .replace("\u0008", "")
                .take(1200)
            throw IllegalStateException(
                "UI dump did not contain a complete <hierarchy> XML document. Raw preview: " +
                    preview
            )
        }

        return raw.substring(start, end + endTag.length)
    }

    private fun summarizeUiXml(xml: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        val lines = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && lines.size < MAX_UI_NODES) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                fun attr(name: String): String =
                    parser.getAttributeValue(null, name).orEmpty().trim()

                val text = attr("text")
                val desc = attr("content-desc")
                val resourceId = attr("resource-id")
                val className = attr("class")
                val clickable = attr("clickable")
                val enabled = attr("enabled")
                val bounds = attr("bounds")
                val selected = attr("selected")
                val checked = attr("checked")

                val meaningful =
                    text.isNotBlank() ||
                    desc.isNotBlank() ||
                    resourceId.isNotBlank() ||
                    clickable == "true"

                if (meaningful) {
                    val item = buildString {
                        append("node")
                        if (text.isNotBlank()) append(" text=").append(quoteUi(text))
                        if (desc.isNotBlank()) append(" desc=").append(quoteUi(desc))
                        if (resourceId.isNotBlank()) append(" id=").append(resourceId)
                        if (className.isNotBlank()) append(" class=").append(className.substringAfterLast('.'))
                        if (clickable.isNotBlank()) append(" clickable=").append(clickable)
                        if (enabled.isNotBlank()) append(" enabled=").append(enabled)
                        if (selected == "true") append(" selected=true")
                        if (checked == "true") append(" checked=true")
                        if (bounds.isNotBlank()) append(" bounds=").append(bounds)
                    }
                    lines += item.take(MAX_UI_LINE_CHARS)
                }
            }
            event = parser.next()
        }

        return buildString {
            appendLine("UI_SNAPSHOT")
            appendLine("format: uiautomator-summary-v1")
            appendLine("nodes: " + lines.size)
            lines.forEach { appendLine(it) }
            if (event != XmlPullParser.END_DOCUMENT) {
                appendLine("truncated: true")
            }
        }.take(MAX_UI_RESULT_CHARS)
    }

    private fun quoteUi(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .take(MAX_UI_TEXT_CHARS) + "\""

    companion object {
        private const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"
        private const val PROBE_COMMAND = "settings get global development_settings_enabled"
        private const val AUTO_CONNECT_TIMEOUT_MS = 7_000L
        private const val AUTO_CONNECT_ATTEMPTS = 2
        private const val AUTO_CONNECT_RETRY_DELAY_MS = 350L
        private const val UI_DUMP_STDOUT = "/proc/self/fd/1"
        private const val MAX_UI_NODES = 120
        private const val MAX_UI_TEXT_CHARS = 160
        private const val MAX_UI_LINE_CHARS = 420
        private const val MAX_UI_RESULT_CHARS = 10_000
        private const val TARGET_LAUNCH_POLL_ATTEMPTS = 6
        private const val TARGET_LAUNCH_POLL_DELAY_MS = 200L
    }
}
