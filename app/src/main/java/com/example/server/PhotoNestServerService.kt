package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.data.FirestoreCoordinatorRepository
import com.example.core.data.PhotoNestDatabase
import com.example.core.data.SettingsDataStore
import com.example.core.media.SafStorageManager
import com.example.core.media.StorageInspector
import com.example.core.model.PairingPayload
import com.example.core.security.PairingQrCodec
import com.example.core.security.TlsCertManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ServerRuntimeSnapshot(
    val isOnline: Boolean = false,
    val serverId: String = "",
    val serverName: String = "",
    val hostAddress: String = "",
    val port: Int = 8443,
    val certFingerprint: String = "",
    val pairingQrContent: String = "",
    val connectedClients: Int = 0,
    val recentLogs: List<String> = emptyList()
)

object ServerRuntimeBus {
    private val _state = MutableStateFlow(ServerRuntimeSnapshot())
    val state: StateFlow<ServerRuntimeSnapshot> = _state.asStateFlow()

    fun update(transform: (ServerRuntimeSnapshot) -> ServerRuntimeSnapshot) {
        _state.update(transform)
    }

    fun appendLog(msg: String) {
        _state.update { current ->
            current.copy(recentLogs = (listOf(msg) + current.recentLogs).take(20))
        }
    }
}

class PhotoNestServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: PhotoNestHttpServer? = null
    private var nsdAdvertiser: NsdAdvertiser? = null
    private var heartbeatJob: Job? = null
    private var revocationSyncJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndService()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting PhotoNest vault server…"))
        startServerEngine()
        return START_STICKY
    }

    private fun startServerEngine() {
        if (httpServer?.isRunning == true) return
        val uid = Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            ServerRuntimeBus.appendLog("Sign in with Google before starting server")
            stopSelf()
            return
        }

        serviceScope.launch {
            val settingsStore = SettingsDataStore(applicationContext)
            val settings = settingsStore.settingsFlow.first()
            val serverId = settingsStore.getOrCreateServerId()
            val pairingSecret = settingsStore.getOrCreatePairingSecret()
            val db = PhotoNestDatabase.getInstance(applicationContext)
            val storageManager = SafStorageManager(applicationContext)
            val tlsManager = TlsCertManager(applicationContext)
            val firestoreRepo = FirestoreCoordinatorRepository(applicationContext)

            storageManager.ensureServerDirectories(settings.serverStorageUri)

            val server = PhotoNestHttpServer(
                context = applicationContext,
                db = db,
                settingsStore = settingsStore,
                storageManager = storageManager,
                tlsCertManager = tlsManager,
                firestoreRepo = firestoreRepo,
                onEventLog = { ServerRuntimeBus.appendLog(it) }
            )
            server.start(settings.serverPort, serverId, uid)
            httpServer = server
            acquireLocks()

            val localIp = settings.remoteEndpointOverride.ifBlank { StorageInspector.getLocalIpAddress() }
            val nsd = NsdAdvertiser(applicationContext).also {
                it.register(settings.serverName, server.boundPort, serverId)
            }
            nsdAdvertiser = nsd

            val payload = PairingPayload(
                serverId = serverId,
                ownerUid = uid,
                serverName = settings.serverName,
                hostAddress = localIp,
                port = server.boundPort,
                certFingerprint = server.certFingerprint,
                pairingSecret = pairingSecret,
                expiresAtMillis = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
            )

            val initialClients = server.activeSessionCount(uid)
            ServerRuntimeBus.update {
                it.copy(
                    isOnline = true,
                    serverId = serverId,
                    serverName = settings.serverName,
                    hostAddress = localIp,
                    port = server.boundPort,
                    certFingerprint = server.certFingerprint,
                    pairingQrContent = PairingQrCodec.encodeToString(payload),
                    connectedClients = initialClients
                )
            }

            updateNotification("Online • $localIp:${server.boundPort} • TLS Active")

            revocationSyncJob?.cancel()
            revocationSyncJob = launch {
                firestoreRepo.observePairedDevices(uid)
                    .catch { /* keep running offline even if Firestore listener drops */ }
                    .collect { devices ->
                        devices.forEach { dev ->
                            if (dev.status == "revoked") {
                                db.authorizedTokenDao().revokeDeviceTokens(dev.id, uid)
                            } else if (dev.status == "active") {
                                db.authorizedTokenDao().activateDeviceTokens(dev.id, uid)
                            }
                        }
                        val activeCount = server.activeSessionCount(uid)
                        ServerRuntimeBus.update { snap ->
                            snap.copy(connectedClients = activeCount)
                        }
                    }
            }

            heartbeatJob?.cancel()
            heartbeatJob = launch {
                while (isActive && server.isRunning) {
                    val latestSettings = settingsStore.settingsFlow.first()
                    val ip = latestSettings.remoteEndpointOverride.ifBlank { StorageInspector.getLocalIpAddress() }
                    val vol = StorageInspector.inspectSelectedStorage(
                        applicationContext,
                        latestSettings.serverStorageUri,
                        latestSettings.serverStorageLabel
                    )
                    val relayMode = when {
                        latestSettings.remoteEndpointOverride.isNotBlank() -> "vpn"
                        latestSettings.remoteRelayEnabled -> "relay"
                        else -> "lan"
                    }
                    firestoreRepo.registerOrHeartbeatServer(
                        serverId = serverId,
                        serverName = latestSettings.serverName,
                        hostAddress = ip,
                        port = server.boundPort,
                        certFingerprint = server.certFingerprint,
                        pairingCodeHash = TlsCertManager.sha256Hex(pairingSecret.toByteArray()),
                        isOnline = true,
                        storageLabel = vol.label,
                        totalBytes = vol.totalBytes,
                        usedBytes = vol.usedBytes,
                        availableBytes = vol.availableBytes,
                        relayMode = relayMode
                    )
                    val activeCount = server.activeSessionCount(uid)
                    ServerRuntimeBus.update { snap ->
                        snap.copy(
                            hostAddress = ip,
                            connectedClients = activeCount
                        )
                    }
                    delay(45_000L)
                }
            }
        }
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null) {
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoNest:VaultServerWakeLock")
                    ?.apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12L * 60 * 60 * 1000)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "PhotoNest:VaultWifiLock")
                    ?.apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    private fun stopServerAndService() {
        revocationSyncJob?.cancel()
        heartbeatJob?.cancel()
        nsdAdvertiser?.unregister()
        httpServer?.stop()
        releaseLocks()
        val uid = Firebase.auth.currentUser?.uid
        val snap = ServerRuntimeBus.state.value
        if (!uid.isNullOrBlank() && snap.serverId.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                val store = SettingsDataStore(applicationContext)
                val settings = store.settingsFlow.first()
                val secret = store.getOrCreatePairingSecret()
                val vol = StorageInspector.inspectSelectedStorage(
                    applicationContext,
                    settings.serverStorageUri,
                    settings.serverStorageLabel
                )
                val relayMode = when {
                    settings.remoteEndpointOverride.isNotBlank() -> "vpn"
                    settings.remoteRelayEnabled -> "relay"
                    else -> "lan"
                }
                FirestoreCoordinatorRepository(applicationContext).registerOrHeartbeatServer(
                    serverId = snap.serverId,
                    serverName = settings.serverName,
                    hostAddress = snap.hostAddress.ifBlank { "127.0.0.1" },
                    port = snap.port,
                    certFingerprint = snap.certFingerprint.ifBlank { "offline_cert_fingerprint" },
                    pairingCodeHash = TlsCertManager.sha256Hex(secret.toByteArray()),
                    isOnline = false,
                    storageLabel = vol.label,
                    totalBytes = vol.totalBytes,
                    usedBytes = vol.usedBytes,
                    availableBytes = vol.availableBytes,
                    relayMode = relayMode
                )
            }
        }
        ServerRuntimeBus.update { it.copy(isOnline = false, connectedClients = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        revocationSyncJob?.cancel()
        heartbeatJob?.cancel()
        nsdAdvertiser?.unregister()
        httpServer?.stop()
        releaseLocks()
        ServerRuntimeBus.update { it.copy(isOnline = false, connectedClients = 0) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_server),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PhotoNest local & remote storage server active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PhotoNestServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_photonest)
            .setContentTitle("PhotoNest Vault Server")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Server", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "photonest_server_channel"
        const val NOTIFICATION_ID = 4001
        const val ACTION_STOP = "com.example.server.ACTION_STOP_SERVER"

        fun start(context: Context) {
            val intent = Intent(context, PhotoNestServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PhotoNestServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
