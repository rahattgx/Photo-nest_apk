package com.example.client

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.core.media.SafStorageManager
import com.example.core.model.PairingPayload
import com.example.core.security.TlsCertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder

class PhotoNestApiClient(
    private val context: Context,
    private val tlsCertManager: TlsCertManager,
    private val storageManager: SafStorageManager
) {
    data class HealthResponse(
        val serverId: String,
        val serverName: String,
        val totalBytes: Long,
        val usedBytes: Long,
        val availableBytes: Long,
        val storageLabel: String,
        val mediaCount: Int
    )

    suspend fun pairWithServer(
        payload: PairingPayload,
        deviceId: String,
        hostOverride: String? = null
    ): Result<PairedServerEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val host = hostOverride?.takeIf { it.isNotBlank() } ?: payload.hostAddress
            val client = tlsCertManager.buildPinnedClient(payload.certFingerprint)
            val bodyJson = JSONObject().apply {
                put("ownerUid", payload.ownerUid)
                put("pairingSecret", payload.pairingSecret)
                put("deviceId", deviceId)
                put("deviceName", Build.MODEL)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
            val request = Request.Builder()
                .url("https://$host:${payload.port}/api/v1/pair")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Pairing failed (${resp.code}): $raw")
                }
                val json = JSONObject(raw)
                val token = json.getString("authToken")
                val serverName = json.optString("serverName", payload.serverName)
                val returnedFingerprint = json.optString("certFingerprint", payload.certFingerprint)
                    .ifBlank { payload.certFingerprint }
                PairedServerEntity(
                    serverId = payload.serverId,
                    ownerUid = payload.ownerUid,
                    serverName = serverName,
                    hostAddress = host,
                    port = payload.port,
                    certFingerprint = returnedFingerprint,
                    authToken = token,
                    lastSeenMillis = System.currentTimeMillis(),
                    storageLabel = "Connected Vault",
                    totalBytes = 0L,
                    usedBytes = 0L,
                    availableBytes = 0L,
                    isOnline = true
                )
            }
        }
    }

    suspend fun checkHealth(server: PairedServerEntity): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
            val request = Request.Builder()
                .url("https://${server.hostAddress}:${server.port}/api/v1/health")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("Health check failed (${resp.code})")
                val json = JSONObject(resp.body?.string().orEmpty())
                HealthResponse(
                    serverId = json.getString("serverId"),
                    serverName = json.optString("serverName", server.serverName),
                    totalBytes = json.optLong("totalBytes", 0L),
                    usedBytes = json.optLong("usedBytes", 0L),
                    availableBytes = json.optLong("availableBytes", 0L),
                    storageLabel = json.optString("storageLabel", server.storageLabel),
                    mediaCount = json.optInt("mediaCount", 0)
                )
            }
        }
    }

    suspend fun fetchServerCatalog(server: PairedServerEntity): Result<List<MediaItemEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
            val request = Request.Builder()
                .url("https://${server.hostAddress}:${server.port}/api/v1/media")
                .header("Authorization", "Bearer ${server.authToken}")
                .header("X-Owner-Uid", server.ownerUid)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("Catalog fetch failed (${resp.code})")
                val root = JSONObject(resp.body?.string().orEmpty())
                val arr = root.getJSONArray("items")
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            MediaItemEntity(
                                id = o.getString("id"),
                                serverId = o.optString("serverId", server.serverId),
                                ownerUid = o.optString("ownerUid", server.ownerUid),
                                fileName = o.getString("fileName"),
                                mimeType = o.optString("mimeType", "image/jpeg"),
                                isVideo = o.optBoolean("isVideo", false),
                                sizeBytes = o.optLong("sizeBytes", 0L),
                                width = o.optInt("width", 0),
                                height = o.optInt("height", 0),
                                durationMs = o.optLong("durationMs", 0L),
                                takenAtMillis = o.optLong("takenAtMillis", 0L),
                                createdAtMillis = o.optLong("createdAtMillis", 0L),
                                sha256Hash = o.optString("sha256Hash", ""),
                                relativePath = o.optString("relativePath", ""),
                                sourceBucket = o.optString("sourceBucket", "Camera"),
                                isFavorite = o.optBoolean("isFavorite", false),
                                isArchived = o.optBoolean("isArchived", false),
                                isTrashed = o.optBoolean("isTrashed", false),
                                isScreenshot = o.optBoolean("isScreenshot", false),
                                isDocument = o.optBoolean("isDocument", false),
                                locationName = o.optString("locationName", ""),
                                cameraModel = o.optString("cameraModel", ""),
                                exifSummary = o.optString("exifSummary", ""),
                                localFileUri = "https://${server.hostAddress}:${server.port}/api/v1/media/${o.getString("id")}/file",
                                thumbLocalPath = "",
                                isBackedUpOnServer = true
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun downloadThumbnailToCache(server: PairedServerEntity, mediaId: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, "remote_thumbs").apply { mkdirs() }
            val target = File(cacheDir, "$mediaId.jpg")
            if (target.exists() && target.length() > 0L) return@runCatching target

            val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
            val request = Request.Builder()
                .url("https://${server.hostAddress}:${server.port}/api/v1/media/$mediaId/thumb")
                .header("Authorization", "Bearer ${server.authToken}")
                .header("X-Owner-Uid", server.ownerUid)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            }
            target
        }.getOrNull()
    }

    suspend fun downloadOriginalToGallery(server: PairedServerEntity, item: MediaItemEntity): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val localStream = storageManager.openVaultInputStream(item.localFileUri)
                if (localStream != null) {
                    val saved = localStream.use {
                        storageManager.saveStreamToDeviceGallery(it, item.fileName, item.mimeType, item.isVideo)
                    } ?: error("Unable to write to device gallery")
                    return@runCatching saved
                }

                val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
                val request = Request.Builder()
                    .url("https://${server.hostAddress}:${server.port}/api/v1/media/${item.id}/file")
                    .header("Authorization", "Bearer ${server.authToken}")
                    .header("X-Owner-Uid", server.ownerUid)
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("Download failed (${resp.code})")
                    val bodyStream = resp.body?.byteStream() ?: error("Empty response stream")
                    bodyStream.use {
                        storageManager.saveStreamToDeviceGallery(it, item.fileName, item.mimeType, item.isVideo)
                    } ?: error("Failed to save original to Pictures/PhotoNest")
                }
            }
        }

    suspend fun uploadFileResumable(
        server: PairedServerEntity,
        uploadId: String,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        sha256: String,
        sourceBucket: String,
        takenAtMillis: Long,
        onProgress: suspend (Long, Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
            // 1. Check duplicate & resume offset
            val checkJson = JSONObject().apply {
                put("sha256Hash", sha256)
                put("uploadId", uploadId)
            }
            val checkReq = Request.Builder()
                .url("https://${server.hostAddress}:${server.port}/api/v1/media/check-hash")
                .header("Authorization", "Bearer ${server.authToken}")
                .header("X-Owner-Uid", server.ownerUid)
                .post(checkJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var offset = 0L
            client.newCall(checkReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Deduplication check failed (${resp.code})")
                val resJson = JSONObject(resp.body?.string().orEmpty())
                if (resJson.optBoolean("exists", false)) {
                    return@runCatching resJson.optString("existingId")
                }
                offset = resJson.optLong("stagedOffset", 0L).coerceIn(0L, sourceFile.length())
            }

            // 2. Upload in 256KB resumable chunks with conflict recovery
            val total = sourceFile.length()
            val chunkSize = 256L * 1024L
            var resultMediaId = ""
            var conflictRetries = 0
            val encodedFileName = URLEncoder.encode(fileName, "UTF-8")
            val encodedBucket = URLEncoder.encode(sourceBucket, "UTF-8")
            while (offset < total || total == 0L) {
                val currentOffset = offset
                val len = minOf(chunkSize, (total - currentOffset).coerceAtLeast(0L))
                val chunkBody = object : RequestBody() {
                    override fun contentType() = mimeType.toMediaType()
                    override fun contentLength() = len
                    override fun writeTo(sink: BufferedSink) {
                        if (len == 0L) return
                        FileInputStream(sourceFile).use { fis ->
                            var skipped = 0L
                            val discardBuf = ByteArray(8 * 1024)
                            while (skipped < currentOffset) {
                                val s = fis.skip(currentOffset - skipped)
                                if (s > 0L) {
                                    skipped += s
                                } else {
                                    val toRead = minOf(discardBuf.size.toLong(), currentOffset - skipped).toInt()
                                    val r = fis.read(discardBuf, 0, toRead)
                                    if (r <= 0) break
                                    skipped += r
                                }
                            }
                            val buf = ByteArray(32 * 1024)
                            var remaining = len
                            while (remaining > 0L) {
                                val read = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (read <= 0) break
                                sink.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                    }
                }

                val uploadReq = Request.Builder()
                    .url("https://${server.hostAddress}:${server.port}/api/v1/media/upload")
                    .header("Authorization", "Bearer ${server.authToken}")
                    .header("X-Owner-Uid", server.ownerUid)
                    .header("X-Upload-Id", uploadId)
                    .header("X-File-Name", encodedFileName)
                    .header("X-Header-Encoding", "url-utf8")
                    .header("X-Mime-Type", mimeType)
                    .header("X-Sha256", sha256)
                    .header("X-Source-Bucket", encodedBucket)
                    .header("X-Taken-At", takenAtMillis.toString())
                    .header("X-Total-Bytes", total.toString())
                    .header("X-Upload-Offset", currentOffset.toString())
                    .put(chunkBody)
                    .build()

                var chunkAccepted = false
                client.newCall(uploadReq).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 409) {
                        val expectedOffset = runCatching {
                            JSONObject(bodyStr).optLong("expectedOffset", -1L)
                        }.getOrDefault(-1L)
                        if (expectedOffset in 0L..total && conflictRetries < 5) {
                            conflictRetries++
                            offset = expectedOffset
                            return@use
                        }
                    }
                    if (!resp.isSuccessful) error("Chunk upload failed (${resp.code}): $bodyStr")
                    val json = JSONObject(bodyStr)
                    offset = (currentOffset + len).coerceAtMost(total)
                    onProgress(offset, total)
                    if (json.optBoolean("completed", false)) {
                        resultMediaId = json.optString("mediaId")
                    }
                    chunkAccepted = true
                }
                if (chunkAccepted && total == 0L) break
            }
            resultMediaId
        }
    }

    suspend fun downloadToTempFile(
        server: PairedServerEntity,
        mediaId: String,
        fileName: String
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "remote_originals").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val target = File(tempDir, "${mediaId}_$safeName")
            if (target.exists() && target.length() > 0L) return@runCatching target

            val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
            val request = Request.Builder()
                .url("https://${server.hostAddress}:${server.port}/api/v1/media/$mediaId/file")
                .header("Authorization", "Bearer ${server.authToken}")
                .header("X-Owner-Uid", server.ownerUid)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val stream = resp.body?.byteStream() ?: return@runCatching null
                val part = File(tempDir, "${mediaId}_$safeName.part")
                stream.use { input ->
                    FileOutputStream(part).use { out -> input.copyTo(out, 64 * 1024) }
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            }
            target.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    suspend fun deleteServerMedia(
        server: PairedServerEntity,
        mediaId: String
    ): Result<Unit> = sendMediaAction(server, mediaId, "delete")

    suspend fun sendMediaAction(
        server: PairedServerEntity,
        mediaId: String,
        action: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = tlsCertManager.buildPinnedClient(server.certFingerprint)
            val req = Request.Builder()
                .url("https://${server.hostAddress}:${server.port}/api/v1/media/$mediaId/$action")
                .header("Authorization", "Bearer ${server.authToken}")
                .header("X-Owner-Uid", server.ownerUid)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Action $action failed (${resp.code})")
            }
        }
    }
}
