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

interface AdbBridge {
    fun pair(host: String, port: Int, pairingCode: String): Result<Unit>
    fun connect(host: String, port: Int): Result<Unit>
    fun autoConnect(): Result<Unit>
    fun execute(command: String, userApproved: Boolean = false): Result<String>
    fun probe(): Result<Unit>
    fun isConnected(): Boolean
    fun hasSelfHealPermission(): Boolean
    fun grantSelfHealPermission(): Result<Unit>
    fun isWirelessDebuggingEnabled(): Boolean
    fun enableWirelessDebugging(): Result<Unit>
    fun isWifiConnected(): Boolean
    fun disconnect()
    fun diagnostics(): String
}

class AndroidAdbBridge(private val context: Context) : AdbBridge {
    @Volatile private var lastEndpoint = "none"
    @Volatile private var lastStage = "init"
    @Volatile private var lastError = "none"
    @Volatile private var lastCommand = "none"
    @Volatile private var lastConnectSuccessAt = 0L
    @Volatile private var lastProbeSuccessAt = 0L

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

            val endpoint = ShizukuStyleAdbDiscovery.discoverEndpointBlocking(
                context,
                ShizukuStyleAdbDiscovery.TLS_CONNECT,
                AUTO_CONNECT_TIMEOUT_MS
            ).getOrThrow()

            lastEndpoint = endpoint.host + ":" + endpoint.port
            lastStage = "tls_connect"
            check(manager.connect(endpoint.host, endpoint.port)) {
                "Wireless ADB TLS connection failed at " + endpoint.host + ":" + endpoint.port
            }
            lastConnectSuccessAt = System.currentTimeMillis()
            lastStage = "connected"
            lastError = "none"
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
            runShellUnchecked(command.trim())
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

                return output.toString()
                    .trimEnd('\n', '\r')
                    .ifBlank { "(command completed with no output)" }
            } catch (t: Throwable) {
                invalidateBrokenSession()
                throw t
            }
        }
    }

    companion object {
        private const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"
        private const val PROBE_COMMAND = "settings get global development_settings_enabled"
        private const val AUTO_CONNECT_TIMEOUT_MS = 5_000L
    }
}
