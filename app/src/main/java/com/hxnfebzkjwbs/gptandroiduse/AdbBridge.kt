package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.BufferedReader
import java.io.InputStreamReader

interface AdbBridge {
    fun pair(host: String, port: Int, pairingCode: String): Result<Unit>
    fun connect(host: String, port: Int): Result<Unit>
    fun autoConnect(): Result<Unit>
    fun execute(command: String): Result<String>
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
        try {
            check(manager.autoConnect(context, 10_000)) {
                "No paired Wireless ADB endpoint was found"
            }
        } catch (e: AdbPairingRequiredException) {
            throw IllegalStateException("Pairing is required", e)
        }
    }

    override fun execute(command: String): Result<String> = runCatching {
        val policy = CommandPolicy.validate(command)
        require(policy.allowed) { policy.reason }

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
                output.toString().ifBlank { "(command completed with no output)" }
            }
        } finally {
            stream.close()
        }
    }

    override fun disconnect() {
        runCatching { manager().disconnect() }
    }
}
