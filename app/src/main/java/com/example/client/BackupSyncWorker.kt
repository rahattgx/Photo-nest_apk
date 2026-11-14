package com.example.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.core.data.BackupJobEntity
import com.example.core.data.FirestoreCoordinatorRepository
import com.example.core.data.PhotoNestDatabase
import com.example.core.data.SettingsDataStore
import com.example.core.media.SafStorageManager
import com.example.core.model.BackupScope
import com.example.core.model.BackupStatus
import com.example.core.model.UploadQuality
import com.example.core.security.TlsCertManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class BackupSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val ownerUid = Firebase.auth.currentUser?.uid.orEmpty()
        if (ownerUid.isBlank()) return Result.success()

        val settingsStore = SettingsDataStore(context)
        val settings = settingsStore.settingsFlow.first()
        if (settings.isPaused) return Result.success()

        val db = PhotoNestDatabase.getInstance(context)
        val storedServer = if (settings.activeServerId.isNotBlank()) {
            db.serverDao().getById(settings.activeServerId)?.takeIf { it.ownerUid == ownerUid }
        } else {
            db.serverDao().getPrimaryServerSync(ownerUid)
        } ?: return Result.success()

        if (!meetsNetworkAndPowerPolicy(
                context = context,
                wifiOnly = settings.wifiOnly,
                mobileDataAllowed = settings.mobileDataAllowed,
                chargingOnly = settings.chargingOnly,
                screenOffBackup = settings.screenOffBackup
            )
        ) {
            return Result.retry()
        }

        val storageManager = SafStorageManager(context)
        val apiClient = PhotoNestApiClient(context, TlsCertManager(context), storageManager)

        // Verify server reachability and self-heal DHCP IP changes from Firestore coordinator if needed
        var effectiveServer = storedServer
        if (apiClient.checkHealth(effectiveServer).isFailure) {
            val remote = FirestoreCoordinatorRepository(context)
                .getUserServersOnce(ownerUid)
                .firstOrNull { it.id == storedServer.serverId && it.hostAddress.isNotBlank() }
            if (remote != null && (remote.hostAddress != storedServer.hostAddress || remote.port.toInt() != storedServer.port)) {
                val healed = storedServer.copy(hostAddress = remote.hostAddress, port = remote.port.toInt())
                if (apiClient.checkHealth(healed).isSuccess) {
                    effectiveServer = healed
                    db.serverDao().upsert(healed)
                } else {
                    return Result.retry()
                }
            } else {
                return Result.retry()
            }
        }

        // 1. Scan SAF-granted source folders if auto-backup is enabled
        if (settings.autoBackupEnabled) {
            scanSafFoldersIntoQueue(
                context = context,
                db = db,
                storageManager = storageManager,
                ownerUid = ownerUid,
                folderUris = settings.selectedSourceFolderUris,
                excludedUris = settings.excludedFolderUris,
                scope = settings.backupScope
            )
        }

        // 2. Process pending / failed jobs
        val pendingUploadsDir = File(context.filesDir, "pending_uploads")
        val pendingJobs = db.backupQueueDao().getPendingOrFailedSync(ownerUid)
        for (job in pendingJobs) {
            val latestSettings = settingsStore.settingsFlow.first()
            if (latestSettings.isPaused) {
                db.backupQueueDao().updateStatus(job.id, BackupStatus.PAUSED.name)
                return Result.success()
            }

            db.backupQueueDao().updateStatus(job.id, BackupStatus.UPLOADING.name)
            val tempFile = materializeUriToTemp(context, job, latestSettings.uploadQuality)
            if (tempFile == null) {
                db.backupQueueDao().updateStatus(job.id, BackupStatus.FAILED.name, "Source file inaccessible")
                continue
            }

            val uploadResult = apiClient.uploadFileResumable(
                server = effectiveServer,
                uploadId = job.id,
                sourceFile = tempFile,
                fileName = job.fileName,
                mimeType = job.mimeType,
                sha256 = job.sha256Hash,
                sourceBucket = job.sourceBucket,
                takenAtMillis = job.takenAtMillis,
                onProgress = { uploaded, _ ->
                    db.backupQueueDao().upsert(
                        job.copy(
                            ownerUid = ownerUid,
                            status = BackupStatus.UPLOADING.name,
                            uploadedBytes = uploaded,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                }
            )
            tempFile.delete()

            if (uploadResult.isSuccess) {
                // Clean up durable staging file if this came from Photo Picker copy
                if (job.localUri.startsWith(pendingUploadsDir.absolutePath)) {
                    runCatching { File(job.localUri).delete() }
                }
                db.backupQueueDao().upsert(
                    job.copy(
                        ownerUid = ownerUid,
                        status = BackupStatus.COMPLETED.name,
                        uploadedBytes = job.sizeBytes,
                        errorMessage = null,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            } else {
                db.backupQueueDao().upsert(
                    job.copy(
                        ownerUid = ownerUid,
                        status = BackupStatus.FAILED.name,
                        errorMessage = uploadResult.exceptionOrNull()?.localizedMessage ?: "Upload failed",
                        retryCount = job.retryCount + 1,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }

        // 3. Refresh catalog from server while preserving cached thumbnails and local file URIs
        apiClient.fetchServerCatalog(effectiveServer).getOrNull()?.let { remoteItems ->
            val merged = remoteItems.map { remote ->
                val existing = db.mediaDao().getById(remote.id)
                if (existing != null) {
                    remote.copy(
                        thumbLocalPath = existing.thumbLocalPath,
                        localFileUri = existing.localFileUri.takeIf {
                            it.isNotBlank() && !it.startsWith("http")
                        } ?: remote.localFileUri,
                        aiTags = existing.aiTags
                    )
                } else {
                    remote
                }
            }
            db.mediaDao().upsertAll(merged)
        }

        return Result.success()
    }

    private suspend fun scanSafFoldersIntoQueue(
        context: Context,
        db: PhotoNestDatabase,
        storageManager: SafStorageManager,
        ownerUid: String,
        folderUris: Set<String>,
        excludedUris: Set<String>,
        scope: BackupScope
    ) {
        val excludedDocIds = excludedUris.mapNotNull { uriStr ->
            val u = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@mapNotNull null
            runCatching { DocumentsContract.getTreeDocumentId(u) }.getOrNull()
        }
        for (folderUriStr in folderUris) {
            if (excludedUris.contains(folderUriStr)) continue
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr)) ?: continue
            val bucketName = tree.name ?: "Folder"
            traverseDocumentTree(context, db, storageManager, ownerUid, tree, bucketName, excludedUris, excludedDocIds, scope)
        }
    }

    private suspend fun traverseDocumentTree(
        context: Context,
        db: PhotoNestDatabase,
        storageManager: SafStorageManager,
        ownerUid: String,
        dir: DocumentFile,
        bucketName: String,
        excludedUris: Set<String>,
        excludedDocIds: List<String>,
        scope: BackupScope
    ) {
        for (child in dir.listFiles()) {
            val childUriStr = child.uri.toString()
            if (excludedUris.contains(childUriStr)) continue
            val childDocId = runCatching { DocumentsContract.getDocumentId(child.uri) }.getOrNull()
            if (childDocId != null && excludedDocIds.any { ex -> childDocId == ex || childDocId.startsWith("$ex/") }) {
                continue
            }
            if (child.isDirectory) {
                traverseDocumentTree(
                    context,
                    db,
                    storageManager,
                    ownerUid,
                    child,
                    child.name ?: bucketName,
                    excludedUris,
                    excludedDocIds,
                    scope
                )
            } else if (child.isFile) {
                val mime = child.type ?: guessMime(child.name.orEmpty())
                val isMedia = mime.startsWith("image/") || mime.startsWith("video/")
                val isDoc = mime.contains("pdf") || mime.startsWith("application/")
                if (scope == BackupScope.GALLERY_ONLY && !isMedia) continue
                if (scope == BackupScope.AUTO_BACKUP_ALL && !isMedia && !isDoc) continue

                if (db.backupQueueDao().findByUri(childUriStr) != null) continue
                val hash = runCatching { storageManager.computeSha256(child.uri) }.getOrNull() ?: continue
                if (db.mediaDao().getByHash(ownerUid, hash) != null || db.backupQueueDao().findByHash(hash) != null) {
                    continue
                }

                db.backupQueueDao().upsert(
                    BackupJobEntity(
                        id = "job_${UUID.randomUUID().toString().replace("-", "").take(14)}",
                        ownerUid = ownerUid,
                        localUri = childUriStr,
                        fileName = child.name ?: "media_${System.currentTimeMillis()}",
                        mimeType = mime,
                        sizeBytes = child.length(),
                        sha256Hash = hash,
                        sourceBucket = bucketName,
                        takenAtMillis = child.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        status = BackupStatus.PENDING.name
                    )
                )
            }
        }
    }

    private fun materializeUriToTemp(
        context: Context,
        job: BackupJobEntity,
        quality: UploadQuality
    ): File? {
        return try {
            val tempDir = File(context.cacheDir, "upload_temp").apply { mkdirs() }
            val safeName = job.fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val out = File(tempDir, "${job.id}_$safeName")
            val input = if (job.localUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(job.localUri))
            } else {
                File(job.localUri).takeIf { it.exists() }?.inputStream()
            } ?: return null

            if (quality == UploadQuality.HIGH_EFFICIENCY && job.mimeType == "image/jpeg") {
                val bmp = input.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    FileOutputStream(out).use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos) }
                    bmp.recycle()
                    return out
                }
            } else {
                input.use { src ->
                    FileOutputStream(out).use { dst -> src.copyTo(dst, 32 * 1024) }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun meetsNetworkAndPowerPolicy(
        context: Context,
        wifiOnly: Boolean,
        mobileDataAllowed: Boolean,
        chargingOnly: Boolean,
        screenOffBackup: Boolean
    ): Boolean {
        if (chargingOnly) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm?.isCharging == false) return false
        }
        if (!screenOffBackup) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive == false) return false
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val isUnmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (wifiOnly && !mobileDataAllowed && !isUnmetered) return false
        return true
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") -> "video/mp4"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".pdf") -> "application/pdf"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val IMMEDIATE_WORK = "photonest_immediate_backup"
        private const val PERIODIC_WORK = "photonest_periodic_backup"

        fun enqueueImmediate(context: Context) {
            val req = OneTimeWorkRequestBuilder<BackupSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun schedulePeriodic(
            context: Context,
            wifiOnly: Boolean,
            mobileDataAllowed: Boolean = false,
            chargingOnly: Boolean
        ) {
            val netType = if (!wifiOnly || mobileDataAllowed) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(netType)
                .setRequiresCharging(chargingOnly)
                .build()
            val req = PeriodicWorkRequestBuilder<BackupSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
