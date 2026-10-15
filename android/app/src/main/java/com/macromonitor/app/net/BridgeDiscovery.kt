package com.macromonitor.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A bridge discovered on the local network via mDNS. */
data class DiscoveredBridge(
    val name: String,
    val host: String,
    val port: Int,
) {
    val baseUrl: String get() = "http://$host:$port"
}

/**
 * Finds the bridge automatically on the LAN using mDNS / DNS-SD, so the user
 * never has to type an address on the same network. Best-effort: if the OS or
 * router blocks multicast the flow simply emits nothing and the user can fall
 * back to QR pairing or manual entry.
 *
 * Note: a full mDNS *service type* is required, but the trailing dot is added by
 * the platform on some API levels, so we normalise it before discovering.
 */
class BridgeDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun discover(serviceType: String = SERVICE_TYPE): Flow<DiscoveredBridge> = callbackFlow {
        val normalized = normalize(serviceType)
        val found = mutableSetOf<String>()

        // Hold a multicast lock so the Wi-Fi stack actually delivers mDNS packets
        // on devices that otherwise filter multicast in power-save mode.
        val multicastLock = runCatching {
            wifiManager?.createMulticastLock("macro_monitor_mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(service: NsdServiceInfo) {
                val host = service.host?.hostAddress ?: return
                val bridge = DiscoveredBridge(
                    name = service.serviceName ?: "Macro Monitor Bridge",
                    host = host,
                    port = service.port,
                )
                if (found.add(bridge.baseUrl)) {
                    trySend(bridge)
                }
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) = Unit
        }

        val resolve: (NsdServiceInfo) -> Unit = { service ->
            // Legacy resolve API (deprecated but the broadly-compatible choice).
            @Suppress("DEPRECATION")
            nsdManager.resolveService(service, resolveListener)
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                // Only handle our own bridge type.
                if (!service.serviceType.contains(SERVICE_TYPE.trimEnd('.'))) return
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            nsdManager.discoverServices(normalized, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
    }

    companion object {
        /** Matches the bridge's `_macromonitor._tcp.` service type. */
        const val SERVICE_TYPE = "_macromonitor._tcp."

        private fun normalize(serviceType: String): String {
            val trimmed = serviceType.trim()
            // NsdManager wants the trailing dot on some API levels; ensure exactly one.
            val without = if (trimmed.endsWith(".")) trimmed.dropLast(1) else trimmed
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) "$without." else "$without"
        }
    }
}
