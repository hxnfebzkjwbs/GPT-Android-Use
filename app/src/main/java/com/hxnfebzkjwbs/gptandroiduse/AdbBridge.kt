package com.hxnfebzkjwbs.gptandroiduse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.BufferedReader
import java.io.InputStreamReader

interface AdbBridge {
    fun pair(host: String, port: Int, pairingCode: String): Result<Unit>
    fun connect(host: String, port: Int): Result<Unit>
    fun autoConnect(): Result<Unit>
    fun execute(command: String): Result<String>
    fun probe(): Result<Unit>
    fun isConnected(): Boolean
    fun hasSelfHealPermission(): Boolean
    fun grantSelfHealPermission(): Result<Unit>
    fun isWirelessDebuggingEnabled(): Boolean
    fun enableWirelessDebugging(): Result<Unit>
    fun isWifiConnected(): Boolean
    fun disconnect()
}

class AndroidAdbBridge(private val context: Context) : AdbBridge {
    private fun manager(): AbsAdbConnectionManager =
        AdbConnectionManager.getInstance(context)

    override fun pair(host: String, port: Int, pairingCode: String): Result<Unit> = runCatching {
        require(host.isNotBlank()) { "Wireless debugging host is required" }
        require(pairingCode.matches(Regex("\\d{6}"))) { "Pairing code must be 6 digits" }
        require(port in 1..65535) { "Invalid pairing port" }
        check(manager().pair(host.trim(), port, pairingCode)) { "ADB pairing failed" }
    }

    override fun connect(host: String, port: Int): Result<Unit> = runCatching {
        require(host.isNotBlank()) { "Wireless debugging host is required" }
        require(port in 1..65535) { "Invalid connection port" }
        val manager = manager()
        if (!manager.isConnected) {
            check(manager.connect(host.trim(), port)) { "ADB connection failed" }
        }
    }

    override fun autoConnect(): Result<Unit> = runCatching {
        val manager = manager()
        if (manager.isConnected) return@runCatching

        val endpoint = ShizukuStyleAdbDiscovery.discoverEndpointBlocking(
            context,
            ShizukuStyleAdbDiscovery.TLS_CONNECT,
            AUTO_CONNECT_TIMEOUT_MS
        ).getOrThrow()

        check(manager.connect(endpoint.host, endpoint.port)) {
            "Wireless ADB TLS connection failed at ${endpoint.host}:${endpoint.port}"
        }
    }

    override fun execute(command: String): Result<String> = runCatching {
        val policy = CommandPolicy.validate(command)
        require(policy.allowed) { policy.reason }
        runShellUnchecked(command.trim())
    }

    override fun probe(): Result<Unit> = runCatching {
        runShellUnchecked(PROBE_COMMAND)
        Unit
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
        runCatching { manager().disconnect() }
    }

    private fun runShellUnchecked(command: String): String {
        val stream = manager().openStream("shell:" + command.trim())
        try {
            BufferedReader(InputStreamReader(stream.openInputStream())).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
                return output.toString().ifBlank { "(command completed with no output)" }
            }
        } finally {
            stream.close()
        }
    }

    companion object {
        private const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"
        private const val PROBE_COMMAND = "settings get global development_settings_enabled"
        private const val AUTO_CONNECT_TIMEOUT_MS = 5_000L
    }
}
