package com.example.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdAdvertiser(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(serviceName: String, port: Int, serverId: String) {
        unregister()
        val manager = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName.take(40)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("sid", serverId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD registered: ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { nsdManager?.unregisterService(listener) }
    }

    companion object {
        const val SERVICE_TYPE = "_photonest._tcp."
        private const val TAG = "PhotoNestNsdAdv"
    }
}
