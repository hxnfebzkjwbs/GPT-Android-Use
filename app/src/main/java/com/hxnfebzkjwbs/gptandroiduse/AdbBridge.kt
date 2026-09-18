package com.hxnfebzkjwbs.gptandroiduse

interface AdbBridge {
    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit>
    suspend fun connect(host: String, port: Int): Result<Unit>
    suspend fun execute(command: String): Result<String>
    suspend fun disconnect()
}

class NotImplementedAdbBridge : AdbBridge {
    override suspend fun pair(host: String, port: Int, pairingCode: String) =
        Result.failure<Unit>(UnsupportedOperationException("Wireless ADB pairing is not connected yet"))
    override suspend fun connect(host: String, port: Int) =
        Result.failure<Unit>(UnsupportedOperationException("Wireless ADB transport is not connected yet"))
    override suspend fun execute(command: String) =
        Result.failure<String>(UnsupportedOperationException("Wireless ADB transport is not connected yet"))
    override suspend fun disconnect() = Unit
}
