package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovers Android Wireless ADB mDNS services and accepts only services whose
 * resolved host address belongs to this device.
 */
class ShizukuStyleAdbDiscovery(
    context: Context,
    private val serviceType: String,
    private val onPort: (Int) -> Unit = {},
    private val onEndpoint: (Endpoint) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    data class Endpoint(val host: String, val port: Int)

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    @Volatile private var running = false
    @Volatile private var registered = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            registered = true
            if (!running) stop()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
            running = false
            onError("mDNS discovery failed to start: $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            registered = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!running) return
            runCatching {
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                        ) = Unit

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (!running) return
                            val address = serviceInfo.host ?: return
                            val port = serviceInfo.port
                            if (port !in 1..65535) return
                            if (!isAddressOnThisDevice(address)) return

                            val endpoint = Endpoint(address.hostAddress.orEmpty(), port)
                            if (endpoint.host.isBlank()) return
                            if (!isEndpointReachable(endpoint)) return

                            onEndpoint(endpoint)
                            onPort(port)
                        }
                    }
                )
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    fun start() {
        if (running) return
        running = true
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        running = false
        if (!registered) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    private fun isEndpointReachable(endpoint: Endpoint): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(endpoint.host, endpoint.port),
                    ENDPOINT_PROBE_TIMEOUT_MS
                )
                true
            }
        }.getOrDefault(false)
    }

    private fun isAddressOnThisDevice(address: InetAddress): Boolean {
        return runCatching {
            val target = address.address
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.any { candidate ->
                    candidate.address.contentEquals(target)
                } == true
        }.getOrDefault(false)
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        private const val ENDPOINT_PROBE_TIMEOUT_MS = 700

        fun discoverEndpointBlocking(
            context: Context,
            serviceType: String,
            timeoutMillis: Long = 15_000
        ): Result<Endpoint> = runCatching {
            val latch = CountDownLatch(1)
            val endpoint = AtomicReference<Endpoint?>(null)
            val error = AtomicReference<String?>(null)

            val discovery = ShizukuStyleAdbDiscovery(
                context,
                serviceType,
                onEndpoint = {
                    if (endpoint.compareAndSet(null, it)) latch.countDown()
                },
                onError = {
                    error.compareAndSet(null, it)
                    latch.countDown()
                }
            )

            discovery.start()
            try {
                if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    error("Timed out while searching for $serviceType")
                }
                error.get()?.let { error(it) }
                endpoint.get() ?: error("No valid Wireless ADB endpoint found")
            } finally {
                discovery.stop()
            }
        }

        fun discoverPortBlocking(
            context: Context,
            serviceType: String,
            timeoutMillis: Long = 15_000
        ): Result<Int> = discoverEndpointBlocking(
            context,
            serviceType,
            timeoutMillis
        ).map { it.port }
    }
}
