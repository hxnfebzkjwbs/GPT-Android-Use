package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wireless ADB service discovery following the same high-level strategy as Shizuku:
 * discover the Android mDNS service, verify that the resolved service belongs to
 * a local interface, then use only its port and connect through loopback.
 */
class ShizukuStyleAdbDiscovery(
    context: Context,
    private val serviceType: String,
    private val onPort: (Int) -> Unit,
    private val onError: (String) -> Unit = {}
) {
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
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (!running) return
                            val host = serviceInfo.host?.hostAddress ?: return
                            if (isAddressOnThisDevice(host) && isPortBoundOnLoopback(serviceInfo.port)) {
                                onPort(serviceInfo.port)
                            }
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

    private fun isAddressOnThisDevice(address: String): Boolean {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.any { it.hostAddress == address } == true
        }.getOrDefault(false)
    }

    private fun isPortBoundOnLoopback(port: Int): Boolean {
        return try {
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(LOOPBACK, port), 1)
            }
            false
        } catch (_: IOException) {
            true
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"

        fun discoverPortBlocking(
            context: Context,
            serviceType: String,
            timeoutMillis: Long = 15_000
        ): Result<Int> = runCatching {
            val latch = CountDownLatch(1)
            val port = AtomicInteger(-1)
            var error: String? = null

            val discovery = ShizukuStyleAdbDiscovery(
                context,
                serviceType,
                onPort = {
                    if (port.compareAndSet(-1, it)) latch.countDown()
                },
                onError = {
                    error = it
                    latch.countDown()
                }
            )

            discovery.start()
            try {
                if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    error("Timed out while searching for $serviceType")
                }
                error?.let { error(it) }
                port.get().takeIf { it in 1..65535 }
                    ?: error("No valid Wireless ADB service port found")
            } finally {
                discovery.stop()
            }
        }
    }
}
