package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.client.BackupSyncWorker
import com.example.client.DiscoveredLanServer
import com.example.client.NsdDiscoveryManager
import com.example.client.PhotoNestApiClient
import com.example.core.data.AlbumEntity
import com.example.core.data.AlbumMediaCrossRef
import com.example.core.data.BackupJobEntity
import com.example.core.data.FirestoreCoordinatorRepository
import com.example.core.data.MediaFaceTagEntity
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.core.data.PersonClusterEntity
import com.example.core.data.PhotoNestDatabase
import com.example.core.data.SettingsDataStore
import com.example.core.media.OnDeviceFaceIndexer
import com.example.core.media.SafStorageManager
import com.example.core.media.StorageInspector
import com.example.core.model.AppThemePreference
import com.example.core.model.BackupScope
import com.example.core.model.BackupSettings
import com.example.core.model.BackupStatus
import com.example.core.model.OperatingMode
import com.example.core.model.PairedDeviceDoc
import com.example.core.model.PairingPayload
import com.example.core.model.PersonCategory
import com.example.core.model.PhotoServerDoc
import com.example.core.model.StorageVolumeStats
import com.example.core.model.UploadQuality
import com.example.core.security.PairingQrCodec
import com.example.core.security.PhotoNestImageLoader
import com.example.core.security.TlsCertManager
import com.example.server.PhotoNestServerService
import com.example.server.ServerRuntimeBus
import com.example.server.ServerRuntimeSnapshot
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class PhotoNestViewModel(
    application: Application,
    val currentUserId: String
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val db = PhotoNestDatabase.getInstance(context)
    val settingsStore = SettingsDataStore(context)
    val storageManager = SafStorageManager(context)
    val tlsCertManager = TlsCertManager(context)
    val firestoreRepo = FirestoreCoordinatorRepository(context)
    val apiClient = PhotoNestApiClient(context, tlsCertManager, storageManager)
    private val nsdDiscovery = NsdDiscoveryManager(context)

    val settingsLoaded: StateFlow<BackupSettings?> = settingsStore.settingsFlow
        .map<BackupSettings, BackupSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<BackupSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupSettings())

    val activeMedia: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeActiveMedia(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allNonTrashedMedia: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeAllNonTrashed(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeFavorites(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val videos: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeVideos(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val screenshots: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeScreenshots(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeDocuments(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archived: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeArchived(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashed: StateFlow<List<MediaItemEntity>> = db.mediaDao().observeTrashed(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<AlbumEntity>> = db.albumDao().observeAlbums(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumCrossRefs: StateFlow<List<AlbumMediaCrossRef>> = db.albumDao().observeAllCrossRefs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val people: StateFlow<List<PersonClusterEntity>> = db.personDao().observePeople(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val faceTags: StateFlow<List<MediaFaceTagEntity>> = db.personDao().observeAllFaceTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backupQueue: StateFlow<List<BackupJobEntity>> = db.backupQueueDao().observeQueue(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pairedServers: StateFlow<List<PairedServerEntity>> = db.serverDao().observePairedServers(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val firestoreServers: StateFlow<List<PhotoServerDoc>> = firestoreRepo.observeUserServers(currentUserId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pairedDevices: StateFlow<List<PairedDeviceDoc>> = firestoreRepo.observePairedDevices(currentUserId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serverRuntime: StateFlow<ServerRuntimeSnapshot> = ServerRuntimeBus.state

    val lanServers: StateFlow<List<DiscoveredLanServer>> = nsdDiscovery.discoverServers()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _storageVolumes = MutableStateFlow<List<StorageVolumeStats>>(emptyList())
    val storageVolumes: StateFlow<List<StorageVolumeStats>> = _storageVolumes.asStateFlow()

    private val _selectedServerStorage = MutableStateFlow(
        StorageVolumeStats("Internal Storage", "", false, 0L, 0L, 0L)
    )
    val selectedServerStorage: StateFlow<StorageVolumeStats> = _selectedServerStorage.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        refreshStorageVolumes()
        syncProfileToFirestore()
        viewModelScope.launch {
            pairedServers.collect { servers ->
                PhotoNestImageLoader.updateServers(servers)
            }
        }
    }

    private suspend fun getActiveServer(): PairedServerEntity? {
        val activeId = settingsStore.settingsFlow.first().activeServerId
        if (activeId.isNotBlank()) {
            db.serverDao().getById(activeId)?.takeIf { it.ownerUid == currentUserId }?.let { return it }
        }
        return db.serverDao().getPrimaryServerSync(currentUserId)
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun showMessage(msg: String) {
        _statusMessage.value = msg
    }

    private fun syncProfileToFirestore() {
        viewModelScope.launch {
            val user = Firebase.auth.currentUser ?: return@launch
            val currentMode = settingsStore.settingsFlow.first().operatingMode
            firestoreRepo.upsertUserProfile(
                displayName = user.displayName ?: "PhotoNest User",
                email = user.email.orEmpty(),
                photoUrl = user.photoUrl?.toString(),
                preferredMode = currentMode
            )
        }
    }

    fun refreshStorageVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            _storageVolumes.value = StorageInspector.inspectAvailableVolumes(context)
            val s = settingsStore.settingsFlow.first()
            _selectedServerStorage.value = StorageInspector.inspectSelectedStorage(
                context,
                s.serverStorageUri,
                s.serverStorageLabel
            )
        }
    }

    fun selectOperatingMode(mode: OperatingMode) {
        viewModelScope.launch {
            settingsStore.setOperatingMode(mode)
            val user = Firebase.auth.currentUser
            if (user != null) {
                firestoreRepo.upsertUserProfile(
                    displayName = user.displayName ?: "PhotoNest User",
                    email = user.email.orEmpty(),
                    photoUrl = user.photoUrl?.toString(),
                    preferredMode = mode
                )
            }
        }
    }

    fun startServer() {
        PhotoNestServerService.start(context)
        showMessage("Starting PhotoNest foreground server...")
    }

    fun stopServer() {
        PhotoNestServerService.stop(context)
        showMessage("Stopping PhotoNest server...")
    }

    fun setServerStorageFolder(treeUri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (treeUri == null) {
                storageManager.ensureServerDirectories("")
                settingsStore.setServerStorageLocation("", "Internal App Vault")
                refreshStorageVolumes()
                showMessage("Switched server storage to Internal App Vault")
                return@launch
            }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
            val label = StorageInspector.describeSafUri(context, treeUri.toString())
            val ok = storageManager.ensureServerDirectories(treeUri.toString())
            if (ok) {
                settingsStore.setServerStorageLocation(treeUri.toString(), label)
                refreshStorageVolumes()
                showMessage("PhotoNest directories created in $label")
            } else {
                showMessage("Could not create PhotoNest directories in selected folder")
            }
        }
    }

    fun rotateServerPairingCode() {
        viewModelScope.launch {
            val newSecret = settingsStore.rotatePairingSecret()
            val snap = ServerRuntimeBus.state.value
            if (snap.isOnline) {
                val payload = PairingPayload(
                    serverId = snap.serverId,
                    ownerUid = currentUserId,
                    serverName = snap.serverName,
                    hostAddress = snap.hostAddress,
                    port = snap.port,
                    certFingerprint = snap.certFingerprint,
                    pairingSecret = newSecret,
                    expiresAtMillis = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
                )
                ServerRuntimeBus.update {
                    it.copy(pairingQrContent = PairingQrCodec.encodeToString(payload))
                }
            }
            showMessage("Rotated pairing secret & refreshed QR code")
        }
    }

    fun pairFromQrOrJson(rawPayload: String, hostOverride: String? = null) {
        val payload = PairingQrCodec.decodeFromString(rawPayload)
        if (payload == null) {
            showMessage("Invalid PhotoNest pairing payload")
            return
        }
        if (payload.ownerUid != currentUserId) {
            showMessage("Account mismatch: Server belongs to a different Google account")
            return
        }
        if (System.currentTimeMillis() > payload.expiresAtMillis) {
            showMessage("Pairing QR code expired — rotate code on server")
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val deviceId = settingsStore.getOrCreateDeviceId()
            val result = apiClient.pairWithServer(payload, deviceId, hostOverride)
            _isBusy.value = false
            result.onSuccess { paired ->
                db.serverDao().upsert(paired)
                settingsStore.setAppPreferences(activeServerId = paired.serverId)
                PhotoNestImageLoader.updateServerCredentials(
                    paired.authToken,
                    paired.ownerUid,
                    paired.certFingerprint,
                    paired.hostAddress,
                    paired.port
                )
                showMessage("Paired with ${paired.serverName}")
                syncCatalogFromServer(paired)
            }.onFailure { err ->
                showMessage("Pairing failed: ${err.localizedMessage}")
            }
        }
    }

    fun pairWithLocalRunningServer() {
        val snap = ServerRuntimeBus.state.value
        if (!snap.isOnline || snap.pairingQrContent.isBlank()) {
            showMessage("Start PhotoNest Server on this device or scan a server QR code")
            return
        }
        pairFromQrOrJson(snap.pairingQrContent, hostOverride = "127.0.0.1")
    }

    fun setActiveServer(serverId: String) {
        viewModelScope.launch {
            settingsStore.setAppPreferences(activeServerId = serverId)
            db.serverDao().getById(serverId)?.let { syncCatalogFromServer(it) }
        }
    }

    fun syncCatalogFromServer(targetServer: PairedServerEntity? = null) {
        viewModelScope.launch {
            var server = targetServer ?: getActiveServer()
            if (server == null) {
                showMessage("Pair with a PhotoNest server first")
                return@launch
            }
            _isBusy.value = true
            var health = apiClient.checkHealth(server).getOrNull()
            if (health == null) {
                // Self-heal DHCP IP or port changes from Firestore or local mDNS
                val currentSrv = server
                val lanMatch = lanServers.value.firstOrNull { it.serverId == currentSrv.serverId }
                val firestoreMatch = firestoreRepo.getUserServersOnce(currentUserId)
                    .firstOrNull { it.id == currentSrv.serverId && it.hostAddress.isNotBlank() }
                val candidateHost = lanMatch?.hostAddress ?: firestoreMatch?.hostAddress
                val candidatePort = lanMatch?.port ?: firestoreMatch?.port?.toInt() ?: currentSrv.port
                if (!candidateHost.isNullOrBlank() &&
                    (candidateHost != currentSrv.hostAddress || candidatePort != currentSrv.port)
                ) {
                    val healed = currentSrv.copy(hostAddress = candidateHost, port = candidatePort)
                    val healedHealth = apiClient.checkHealth(healed).getOrNull()
                    if (healedHealth != null) {
                        server = healed
                        health = healedHealth
                        db.serverDao().upsert(healed)
                        PhotoNestImageLoader.updateServerCredentials(
                            healed.authToken,
                            healed.ownerUid,
                            healed.certFingerprint,
                            healed.hostAddress,
                            healed.port
                        )
                    }
                }
            }

            if (health != null) {
                val updated = server.copy(
                    serverName = health.serverName,
                    totalBytes = health.totalBytes,
                    usedBytes = health.usedBytes,
                    availableBytes = health.availableBytes,
                    storageLabel = health.storageLabel,
                    isOnline = true,
                    lastSeenMillis = System.currentTimeMillis()
                )
                db.serverDao().upsert(updated)
                server = updated
            } else {
                db.serverDao().upsert(server.copy(isOnline = false))
            }

            val catalogRes = apiClient.fetchServerCatalog(server)
            _isBusy.value = false
            catalogRes.onSuccess { items ->
                val enriched = items.map { item ->
                    val existing = db.mediaDao().getById(item.id)
                    val thumb = apiClient.downloadThumbnailToCache(server, item.id)
                    item.copy(
                        thumbLocalPath = thumb?.absolutePath ?: existing?.thumbLocalPath.orEmpty(),
                        localFileUri = existing?.localFileUri?.takeIf {
                            it.isNotBlank() && !it.startsWith("http")
                        } ?: item.localFileUri,
                        aiTags = existing?.aiTags.orEmpty()
                    )
                }
                db.mediaDao().upsertAll(enriched)
                showMessage("Synced ${enriched.size} items from ${server.serverName}")
            }.onFailure { err ->
                showMessage("Server unreachable: ${err.localizedMessage}")
            }
        }
    }

    fun addPickedUrisToBackupAndLibrary(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            val currentSettings = settingsStore.settingsFlow.first()
            val paired = getActiveServer()
            val serverOnline = ServerRuntimeBus.state.value.isOnline
            val effectiveServerId = paired?.serverId ?: ServerRuntimeBus.state.value.serverId.ifBlank { "local_vault" }

            var addedCount = 0
            val pendingUploadsDir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
            for (uri in uris) {
                val (fileName, mimeType, size) = queryUriDetails(uri)
                val hash = runCatching { storageManager.computeSha256(uri) }.getOrNull() ?: continue
                if (db.mediaDao().getByHash(currentUserId, hash) != null) continue

                val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                val staged = File(context.cacheDir, "import_${UUID.randomUUID().toString().take(8)}_$safeName")
                val copied = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(staged).use { out -> input.copyTo(out, 32 * 1024) }
                    }
                    staged.exists() && staged.length() > 0L
                }.getOrDefault(false)
                if (!copied) continue

                val isVideo = mimeType.startsWith("video/")
                val meta = storageManager.extractMetadataFromFile(staged, isVideo, System.currentTimeMillis())

                if (paired != null) {
                    // Move copy into durable filesDir so background WorkManager can read even after process restart
                    val jobId = "job_${UUID.randomUUID().toString().replace("-", "").take(14)}"
                    val durableFile = File(pendingUploadsDir, "${jobId}_$safeName")
                    val moved = staged.renameTo(durableFile)
                    if (!moved) {
                        staged.copyTo(durableFile, overwrite = true)
                        staged.delete()
                    }
                    db.backupQueueDao().upsert(
                        BackupJobEntity(
                            id = jobId,
                            ownerUid = currentUserId,
                            localUri = durableFile.absolutePath,
                            fileName = fileName,
                            mimeType = mimeType,
                            sizeBytes = durableFile.length(),
                            sha256Hash = hash,
                            sourceBucket = "Picked Media",
                            takenAtMillis = meta.takenAtMillis,
                            status = BackupStatus.PENDING.name
                        )
                    )
                    addedCount++
                } else if (currentSettings.operatingMode == OperatingMode.SERVER || serverOnline) {
                    val mediaId = "med_${UUID.randomUUID().toString().replace("-", "").take(16)}"
                    val (storedUri, thumbPath) = storageManager.commitStagedFileToVault(
                        stagedFile = staged,
                        safTreeUri = currentSettings.serverStorageUri,
                        subFolder = "Originals",
                        fileName = fileName,
                        mimeType = mimeType,
                        mediaId = mediaId,
                        isVideo = isVideo
                    )
                    staged.delete()
                    val entity = MediaItemEntity(
                        id = mediaId,
                        serverId = effectiveServerId,
                        ownerUid = currentUserId,
                        fileName = fileName,
                        mimeType = mimeType,
                        isVideo = isVideo,
                        sizeBytes = size.coerceAtLeast(1L),
                        width = meta.width,
                        height = meta.height,
                        durationMs = meta.durationMs,
                        takenAtMillis = meta.takenAtMillis,
                        createdAtMillis = System.currentTimeMillis(),
                        sha256Hash = hash,
                        relativePath = "Originals/$fileName",
                        sourceBucket = "Imported",
                        isScreenshot = fileName.contains("screenshot", ignoreCase = true),
                        isDocument = mimeType.contains("pdf"),
                        locationName = meta.locationName,
                        latitude = meta.latitude,
                        longitude = meta.longitude,
                        cameraModel = meta.cameraModel,
                        exifSummary = meta.exifSummary,
                        localFileUri = storedUri,
                        thumbLocalPath = thumbPath,
                        isBackedUpOnServer = true
                    )
                    db.mediaDao().upsert(entity)
                    if (currentSettings.onDeviceFaceGroupingEnabled && !isVideo) {
                        OnDeviceFaceIndexer(context, db.personDao()).indexMediaItem(entity)
                    }
                    addedCount++
                } else {
                    staged.delete()
                    showMessage("Pair with a PhotoNest server first to back up selected photos")
                }
            }
            _isBusy.value = false
            if (paired != null && addedCount > 0) {
                BackupSyncWorker.enqueueImmediate(context)
                showMessage("Queued $addedCount item(s) for server backup")
            } else if (addedCount > 0) {
                showMessage("Stored $addedCount item(s) in PhotoNest Vault")
            }
        }
    }

    private fun queryUriDetails(uri: Uri): Triple<String, String, Long> {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        var name = "IMG_${System.currentTimeMillis()}.jpg"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return Triple(name, mime, size)
    }

    fun addSourceBackupFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch {
            settingsStore.addSourceFolderUri(uri.toString())
            BackupSyncWorker.enqueueImmediate(context)
            showMessage("Added backup folder: ${StorageInspector.describeSafUri(context, uri.toString())}")
        }
    }

    fun removeSourceBackupFolder(uriStr: String) {
        viewModelScope.launch { settingsStore.removeSourceFolderUri(uriStr) }
    }

    fun addExcludedFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch {
            settingsStore.addExcludedFolderUri(uri.toString())
            showMessage("Excluded folder from backup")
        }
    }

    fun removeExcludedFolder(uriStr: String) {
        viewModelScope.launch { settingsStore.removeExcludedFolderUri(uriStr) }
    }

    fun updateBackupSettings(
        autoBackupEnabled: Boolean? = null,
        backupScope: BackupScope? = null,
        wifiOnly: Boolean? = null,
        mobileDataAllowed: Boolean? = null,
        chargingOnly: Boolean? = null,
        screenOffBackup: Boolean? = null,
        isPaused: Boolean? = null,
        uploadQuality: UploadQuality? = null
    ) {
        viewModelScope.launch {
            settingsStore.updateBackupConfig(
                autoBackupEnabled = autoBackupEnabled,
                backupScope = backupScope,
                wifiOnly = wifiOnly,
                mobileDataAllowed = mobileDataAllowed,
                chargingOnly = chargingOnly,
                screenOffBackup = screenOffBackup,
                isPaused = isPaused,
                uploadQuality = uploadQuality
            )
            val updated = settingsStore.settingsFlow.first()
            if (updated.autoBackupEnabled && !updated.isPaused) {
                BackupSyncWorker.schedulePeriodic(
                    context = context,
                    wifiOnly = updated.wifiOnly,
                    mobileDataAllowed = updated.mobileDataAllowed,
                    chargingOnly = updated.chargingOnly
                )
                BackupSyncWorker.enqueueImmediate(context)
            } else {
                BackupSyncWorker.cancelPeriodic(context)
            }
        }
    }

    fun triggerImmediateBackup() {
        BackupSyncWorker.enqueueImmediate(context)
        showMessage("Backup sync started")
    }

    fun retryFailedBackups() {
        viewModelScope.launch {
            db.backupQueueDao().retryAllFailed(currentUserId)
            BackupSyncWorker.enqueueImmediate(context)
            showMessage("Retrying failed backups")
        }
    }

    fun clearCompletedQueue() {
        viewModelScope.launch { db.backupQueueDao().clearCompleted(currentUserId) }
    }

    fun toggleFavorite(item: MediaItemEntity) {
        viewModelScope.launch {
            db.mediaDao().setFavorite(item.id, !item.isFavorite)
            getActiveServer()?.let { server ->
                apiClient.sendMediaAction(server, item.id, "favorite")
            }
        }
    }

    fun toggleArchive(item: MediaItemEntity) {
        viewModelScope.launch {
            db.mediaDao().setArchived(item.id, !item.isArchived)
            getActiveServer()?.let { server ->
                apiClient.sendMediaAction(server, item.id, "archive")
            }
            showMessage(if (!item.isArchived) "Moved to Archive" else "Restored from Archive")
        }
    }

    fun moveToTrash(item: MediaItemEntity) {
        viewModelScope.launch {
            db.mediaDao().setTrashed(item.id, true, System.currentTimeMillis())
            getActiveServer()?.let { server ->
                apiClient.sendMediaAction(server, item.id, "trash")
            }
            showMessage("Moved to Recently Deleted")
        }
    }

    fun restoreFromTrash(item: MediaItemEntity) {
        viewModelScope.launch {
            db.mediaDao().setTrashed(item.id, false, null)
            getActiveServer()?.let { server ->
                apiClient.sendMediaAction(server, item.id, "restore")
            }
            showMessage("Restored ${item.fileName}")
        }
    }

    fun deletePermanently(item: MediaItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            getActiveServer()?.let { server ->
                apiClient.deleteServerMedia(server, item.id)
            }
            storageManager.deleteVaultFile(item.localFileUri, item.thumbLocalPath)
            if (item.serverId.isNotBlank() && item.serverId != "local_vault") {
                firestoreRepo.deleteServerMediaMeta(item.serverId, item.id)
            }
            db.mediaDao().deletePermanently(item.id)
            showMessage("Permanently deleted ${item.fileName}")
        }
    }

    fun downloadOriginal(item: MediaItemEntity) {
        viewModelScope.launch {
            val server = getActiveServer()
            val res = if (server != null) {
                apiClient.downloadOriginalToGallery(server, item)
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = storageManager.openVaultInputStream(item.localFileUri)
                            ?: error("Original file unavailable")
                        stream.use {
                            storageManager.saveStreamToDeviceGallery(it, item.fileName, item.mimeType, item.isVideo)
                        } ?: error("Failed to save to device gallery")
                    }
                }
            }
            res.onSuccess {
                showMessage("Saved original ${item.fileName} to Pictures/PhotoNest")
            }.onFailure { err ->
                showMessage("Download failed: ${err.localizedMessage}")
            }
        }
    }

    fun prepareShareIntent(item: MediaItemEntity, onReady: (Intent) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeName = item.fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val shareFile = File(context.cacheDir, "share_$safeName")
            val stream = storageManager.openVaultInputStream(item.localFileUri)
            val ok = if (stream != null) {
                stream.use { input ->
                    FileOutputStream(shareFile).use { out -> input.copyTo(out) }
                }
                true
            } else {
                val remoteTemp = getActiveServer()?.let { server ->
                    apiClient.downloadToTempFile(server, item.id, item.fileName)
                }
                if (remoteTemp != null && remoteTemp.exists()) {
                    remoteTemp.copyTo(shareFile, overwrite = true)
                    true
                } else {
                    val thumb = item.thumbLocalPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
                    if (thumb != null) {
                        thumb.copyTo(shareFile, overwrite = true)
                        true
                    } else {
                        false
                    }
                }
            }
            if (!ok) {
                showMessage("File not available locally or server is unreachable")
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                onReady(Intent.createChooser(intent, "Share ${item.fileName}"))
            }
        }
    }

    fun saveEditedPhotoCopy(original: MediaItemEntity, editedBitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = "EDITED_${System.currentTimeMillis()}_${original.fileName.substringBeforeLast('.')}.jpg"
            val (file, thumb) = storageManager.writeBitmapToInternalVault(editedBitmap, fileName, "Edits")
            val entity = storageManager.createLocalMediaEntity(
                serverId = original.serverId,
                ownerUid = currentUserId,
                file = file,
                thumbFile = thumb,
                fileName = fileName,
                mimeType = "image/jpeg",
                sourceBucket = "Edits"
            )
            db.mediaDao().upsert(entity)
            getActiveServer()?.let { server ->
                apiClient.uploadFileResumable(
                    server = server,
                    uploadId = entity.id,
                    sourceFile = file,
                    fileName = fileName,
                    mimeType = "image/jpeg",
                    sha256 = entity.sha256Hash,
                    sourceBucket = "Edits",
                    takenAtMillis = entity.takenAtMillis,
                    onProgress = { _, _ -> }
                )
            }
            showMessage("Saved edited copy $fileName")
        }
    }

    fun createAlbum(name: String, mediaIds: List<String>) {
        val cleanName = name.trim().ifBlank { "Untitled Album" }
        viewModelScope.launch {
            val albumId = "alb_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            db.albumDao().upsertAlbum(
                AlbumEntity(
                    id = albumId,
                    ownerUid = currentUserId,
                    name = cleanName,
                    coverMediaId = mediaIds.firstOrNull(),
                    createdAtMillis = System.currentTimeMillis()
                )
            )
            mediaIds.forEach { mid ->
                db.albumDao().addMediaToAlbum(
                    AlbumMediaCrossRef(albumId, mid, System.currentTimeMillis())
                )
            }
            showMessage("Created album \"$cleanName\"")
        }
    }

    fun addMediaToAlbum(albumId: String, mediaIds: List<String>) {
        viewModelScope.launch {
            mediaIds.forEach { mid ->
                db.albumDao().addMediaToAlbum(AlbumMediaCrossRef(albumId, mid, System.currentTimeMillis()))
            }
            showMessage("Added ${mediaIds.size} item(s) to album")
        }
    }

    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            db.albumDao().clearAlbumMedia(albumId)
            db.albumDao().deleteAlbum(albumId)
            showMessage("Deleted album")
        }
    }

    fun createAndSaveCollage(items: List<MediaItemEntity>) {
        if (items.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            val selected = items.take(4)
            val server = getActiveServer()
            val canvasSize = 1440
            val result = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val gap = 18f
            val cols = 2
            val rows = if (selected.size <= 2) 1 else 2
            val cellW = (canvasSize - gap * (cols + 1)) / cols
            val cellH = (canvasSize - gap * (rows + 1)) / rows

            selected.forEachIndexed { idx, item ->
                val bmp = loadBitmapForCreation(item, server)
                if (bmp != null) {
                    val c = idx % cols
                    val r = idx / cols
                    val left = gap + c * (cellW + gap)
                    val top = gap + r * (cellH + gap)
                    val dest = RectF(left, top, left + cellW, top + cellH)
                    canvas.drawBitmap(bmp, null, dest, paint)
                    bmp.recycle()
                }
            }

            val fileName = "COLLAGE_${System.currentTimeMillis()}.jpg"
            val (file, thumb) = storageManager.writeBitmapToInternalVault(result, fileName, "Edits")
            result.recycle()
            val entity = storageManager.createLocalMediaEntity(
                serverId = server?.serverId ?: selected.first().serverId,
                ownerUid = currentUserId,
                file = file,
                thumbFile = thumb,
                fileName = fileName,
                mimeType = "image/jpeg",
                sourceBucket = "Creations"
            )
            db.mediaDao().upsert(entity)
            server?.let { activeSrv ->
                apiClient.uploadFileResumable(
                    server = activeSrv,
                    uploadId = entity.id,
                    sourceFile = file,
                    fileName = fileName,
                    mimeType = "image/jpeg",
                    sha256 = entity.sha256Hash,
                    sourceBucket = "Creations",
                    takenAtMillis = entity.takenAtMillis,
                    onProgress = { _, _ -> }
                )
            }
            _isBusy.value = false
            showMessage("Saved collage $fileName")
        }
    }

    private suspend fun loadBitmapForCreation(
        item: MediaItemEntity,
        server: PairedServerEntity?
    ): Bitmap? {
        val localOrThumb = item.localFileUri.takeIf { it.isNotBlank() && !it.startsWith("http") }
            ?: if (server != null && item.localFileUri.startsWith("http")) {
                apiClient.downloadToTempFile(server, item.id, item.fileName)?.absolutePath
            } else {
                null
            }
            ?: item.thumbLocalPath
        if (localOrThumb.isBlank()) return null
        return runCatching {
            if (localOrThumb.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(localOrThumb))?.use { BitmapFactory.decodeStream(it) }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(localOrThumb, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 1080) sample *= 2
                BitmapFactory.decodeFile(localOrThumb, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }

    fun runOnDeviceFaceScan() {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            val indexer = OnDeviceFaceIndexer(context, db.personDao())
            val media = db.mediaDao().getBackedUpMediaByOwnerSync(currentUserId).filter { !it.isVideo }.take(60)
            var foundFaces = 0
            media.forEach { item ->
                foundFaces += indexer.indexMediaItem(item)
            }
            _isBusy.value = false
            showMessage("On-device scan finished: indexed $foundFaces face(s)")
        }
    }

    fun renamePerson(personId: String, name: String) {
        viewModelScope.launch {
            db.personDao().renamePerson(personId, name.trim().ifBlank { "Add name" })
        }
    }

    fun updatePersonCategory(personId: String, category: PersonCategory) {
        viewModelScope.launch {
            db.personDao().updateCategory(personId, category.name)
        }
    }

    fun addManualPersonTag(name: String, category: PersonCategory, sampleMedia: MediaItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            OnDeviceFaceIndexer(context, db.personDao())
                .createManualPersonOrPetCluster(name, category, sampleMedia)
            showMessage("Added $name to ${category.label}")
        }
    }

    fun updateServerConfig(
        serverName: String? = null,
        port: Int? = null,
        remoteRelayEnabled: Boolean? = null,
        remoteEndpointOverride: String? = null
    ) {
        viewModelScope.launch {
            settingsStore.setServerConfig(serverName, port, remoteRelayEnabled, remoteEndpointOverride)
            showMessage("Server configuration saved")
        }
    }

    fun updateAppPreferences(
        appTheme: AppThemePreference? = null,
        notificationsEnabled: Boolean? = null,
        faceGroupingEnabled: Boolean? = null,
        aiSearchEnabled: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsStore.setAppPreferences(
                appTheme = appTheme,
                notificationsEnabled = notificationsEnabled,
                faceGroupingEnabled = faceGroupingEnabled,
                aiSearchEnabled = aiSearchEnabled
            )
        }
    }

    fun revokePairedDevice(deviceId: String) {
        viewModelScope.launch {
            firestoreRepo.revokeDevice(deviceId)
                .onSuccess { showMessage("Device access revoked") }
                .onFailure { showMessage("Failed to revoke device: ${it.localizedMessage}") }
        }
    }

    companion object {
        fun factory(currentUserId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                PhotoNestViewModel(app, currentUserId)
            }
        }
    }
}
