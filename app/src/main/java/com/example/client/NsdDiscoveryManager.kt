package com.example.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.server.NsdAdvertiser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredLanServer(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
    val serverId: String
)

class NsdDiscoveryManager(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    fun discoverServers(): Flow<List<DiscoveredLanServer>> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val found = linkedMapOf<String, DiscoveredLanServer>()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_photonest")) {
                    @Suppress("DEPRECATION")
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            val sid = serviceInfo.attributes["sid"]?.let { String(it) } ?: serviceInfo.serviceName
                            found[serviceInfo.serviceName] = DiscoveredLanServer(
                                serviceName = serviceInfo.serviceName,
                                hostAddress = host,
                                port = serviceInfo.port,
                                serverId = sid
                            )
                            trySend(found.values.toList())
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }

        runCatching {
            manager.discoverServices(NsdAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
    }

    private companion object {
        const val TAG = "PhotoNestNsdDisc"
    }
}
