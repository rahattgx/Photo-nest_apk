package com.example.server

import android.content.Context
import android.util.Log
import com.example.core.data.AuthorizedTokenEntity
import com.example.core.data.FirestoreCoordinatorRepository
import com.example.core.data.MediaItemEntity
import com.example.core.data.PhotoNestDatabase
import com.example.core.data.SettingsDataStore
import com.example.core.media.OnDeviceFaceIndexer
import com.example.core.media.SafStorageManager
import com.example.core.media.StorageInspector
import com.example.core.security.TlsCertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLServerSocket

/**
 * Embedded TLS HTTPS/1.1 server running directly on the PhotoNest Server Android device.
 * Handles authenticated device pairing, SHA-256 deduplication, resumable chunked uploads,
 * HTTP 206 byte-range video streaming, thumbnail serving, and original file downloads.
 */
class PhotoNestHttpServer(
    private val context: Context,
    private val db: PhotoNestDatabase,
    private val settingsStore: SettingsDataStore,
    private val storageManager: SafStorageManager,
    private val tlsCertManager: TlsCertManager,
    private val firestoreRepo: FirestoreCoordinatorRepository,
    private val onEventLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: SSLServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var cachedOwnerUid: String = ""

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = 8443
        private set

    @Volatile
    var certFingerprint: String = ""
        private set

    fun start(preferredPort: Int, serverId: String, ownerUid: String) {
        if (isRunning) return
        cachedOwnerUid = ownerUid
        val tlsMaterial = tlsCertManager.getOrCreateServerTlsMaterial()
        certFingerprint = tlsMaterial.fingerprintSha256

        val socketFactory = tlsMaterial.sslContext.serverSocketFactory
        val socket = try {
            socketFactory.createServerSocket(preferredPort, 32, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        } catch (_: Exception) {
            socketFactory.createServerSocket(0, 32, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        }
        serverSocket = socket
        boundPort = socket.localPort
        isRunning = true
        onEventLog("TLS server listening on port $boundPort")

        acceptJob = scope.launch {
            while (isRunning && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    launch {
                        handleClient(clientSocket, serverId, ownerUid)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        onEventLog("Server stopped")
    }

    suspend fun activeSessionCount(ownerUid: String = cachedOwnerUid): Int {
        return if (ownerUid.isBlank()) 0 else db.authorizedTokenDao().countActiveTokens(ownerUid)
    }

    private suspend fun handleClient(socket: Socket, serverId: String, ownerUid: String) {
        socket.use { sock ->
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
            val output = sock.getOutputStream()

            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeJsonResponse(output, 400, "Bad Request", JSONObject().put("error", "Malformed request"))
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.US)
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            if (method == "POST" && path == "/api/v1/pair") {
                val bodyBytes = readFixedBytes(input, contentLength.toInt().coerceAtMost(64 * 1024))
                handlePair(bodyBytes, output, serverId, ownerUid)
                return
            }

            if (method == "GET" && path == "/api/v1/health") {
                handleHealth(output, serverId, ownerUid)
                return
            }

            // Authenticate protected endpoints
            if (!isAuthorized(headers, ownerUid)) {
                writeJsonResponse(output, 401, "Unauthorized", JSONObject().put("error", "Unauthorized device or account"))
                return
            }

            when {
                method == "GET" && path == "/api/v1/media" -> handleListMedia(ownerUid, output)
                method == "POST" && path == "/api/v1/media/check-hash" -> {
                    val bodyBytes = readFixedBytes(input, contentLength.toInt().coerceAtMost(32 * 1024))
                    handleCheckHash(ownerUid, bodyBytes, output)
                }
                method == "PUT" && path == "/api/v1/media/upload" -> {
                    handleChunkUpload(headers, input, contentLength, output, serverId, ownerUid)
                }
                method == "GET" && path.startsWith("/api/v1/media/") && path.endsWith("/thumb") -> {
                    val mediaId = path.removePrefix("/api/v1/media/").removeSuffix("/thumb")
                    handleServeThumb(ownerUid, mediaId, output)
                }
                method == "GET" && path.startsWith("/api/v1/media/") && path.endsWith("/file") -> {
                    val mediaId = path.removePrefix("/api/v1/media/").removeSuffix("/file")
                    handleServeFile(ownerUid, mediaId, headers["range"], output)
                }
                method == "POST" && path.startsWith("/api/v1/media/") -> {
                    val segments = path.removePrefix("/api/v1/media/").split("/")
                    if (segments.size == 2) {
                        handleMediaAction(ownerUid, segments[0], segments[1], serverId, output)
                    } else {
                        writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Unknown endpoint"))
                    }
                }
                else -> writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Endpoint not found"))
            }
        }
    }

    private suspend fun isAuthorized(headers: Map<String, String>, serverOwnerUid: String): Boolean {
        val authHeader = headers["authorization"] ?: return false
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return false
        val token = authHeader.substring(7).trim()
        if (token.isBlank()) return false
        val reqUid = headers["x-owner-uid"].orEmpty()
        if (reqUid.isNotBlank() && reqUid != serverOwnerUid) return false

        if (db.authorizedTokenDao().findValidToken(token, serverOwnerUid) != null) {
            return true
        }
        val pairingSecret = settingsStore.getOrCreatePairingSecret()
        return token == pairingSecret && reqUid == serverOwnerUid
    }

    private suspend fun handlePair(
        bodyBytes: ByteArray,
        output: OutputStream,
        serverId: String,
        ownerUid: String
    ) {
        val json = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
        if (json == null) {
            writeJsonResponse(output, 400, "Bad Request", JSONObject().put("error", "Invalid JSON"))
            return
        }
        val reqUid = json.optString("ownerUid")
        val secret = json.optString("pairingSecret")
        val deviceId = json.optString("deviceId", "dev_${UUID.randomUUID().toString().take(8)}")
        val deviceName = json.optString("deviceName", "Android Phone")
        val deviceModel = json.optString("deviceModel", "Android")

        val expectedSecret = settingsStore.getOrCreatePairingSecret()
        if (reqUid != ownerUid || secret != expectedSecret) {
            onEventLog("Rejected unauthorized pairing attempt from $deviceName")
            writeJsonResponse(output, 403, "Forbidden", JSONObject().put("error", "Account or pairing secret mismatch"))
            return
        }

        val token = "pn_${UUID.randomUUID().toString().replace("-", "")}"
        db.authorizedTokenDao().upsert(
            AuthorizedTokenEntity(
                token = token,
                deviceId = deviceId,
                ownerUid = ownerUid,
                deviceName = deviceName,
                isRevoked = false,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        val settings = settingsStore.settingsFlow.first()

        scope.launch {
            firestoreRepo.registerPairedDevice(
                deviceId = deviceId,
                serverId = serverId,
                deviceName = deviceName,
                deviceModel = deviceModel
            )
        }

        onEventLog("Paired device: $deviceName")
        writeJsonResponse(
            output,
            200,
            "OK",
            JSONObject().apply {
                put("ok", true)
                put("serverId", serverId)
                put("serverName", settings.serverName)
                put("authToken", token)
                put("certFingerprint", certFingerprint)
            }
        )
    }

    private suspend fun handleHealth(output: OutputStream, serverId: String, ownerUid: String) {
        val settings = settingsStore.settingsFlow.first()
        val stats = StorageInspector.inspectSelectedStorage(context, settings.serverStorageUri, settings.serverStorageLabel)
        val count = db.mediaDao().getBackedUpMediaByOwnerSync(ownerUid).size
        val activeSessions = db.authorizedTokenDao().countActiveTokens(ownerUid)
        writeJsonResponse(
            output,
            200,
            "OK",
            JSONObject().apply {
                put("serverId", serverId)
                put("ownerUid", ownerUid)
                put("serverName", settings.serverName)
                put("isOnline", true)
                put("port", boundPort)
                put("storageLabel", stats.label)
                put("totalBytes", stats.totalBytes)
                put("usedBytes", stats.usedBytes)
                put("availableBytes", stats.availableBytes)
                put("mediaCount", count)
                put("connectedClients", activeSessions)
            }
        )
    }

    private suspend fun handleListMedia(ownerUid: String, output: OutputStream) {
        val items = db.mediaDao().getBackedUpMediaByOwnerSync(ownerUid)
        val arr = JSONArray()
        items.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("serverId", m.serverId)
                    put("ownerUid", m.ownerUid)
                    put("fileName", m.fileName)
                    put("mimeType", m.mimeType)
                    put("isVideo", m.isVideo)
                    put("sizeBytes", m.sizeBytes)
                    put("width", m.width)
                    put("height", m.height)
                    put("durationMs", m.durationMs)
                    put("takenAtMillis", m.takenAtMillis)
                    put("createdAtMillis", m.createdAtMillis)
                    put("sha256Hash", m.sha256Hash)
                    put("relativePath", m.relativePath)
                    put("sourceBucket", m.sourceBucket)
                    put("isFavorite", m.isFavorite)
                    put("isArchived", m.isArchived)
                    put("isTrashed", m.isTrashed)
                    put("isScreenshot", m.isScreenshot)
                    put("isDocument", m.isDocument)
                    put("locationName", m.locationName)
                    put("cameraModel", m.cameraModel)
                    put("exifSummary", m.exifSummary)
                }
            )
        }
        writeJsonResponse(output, 200, "OK", JSONObject().put("items", arr))
    }

    private suspend fun handleCheckHash(ownerUid: String, bodyBytes: ByteArray, output: OutputStream) {
        val json = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
        val hash = json?.optString("sha256Hash").orEmpty()
        val uploadId = json?.optString("uploadId").orEmpty()
        val existing = if (hash.isNotBlank()) db.mediaDao().getByHash(ownerUid, hash) else null
        val stagedOffset = if (uploadId.isNotBlank()) {
            val part = storageManager.getStagingPartFile(uploadId)
            if (part.exists()) part.length() else 0L
        } else 0L

        writeJsonResponse(
            output,
            200,
            "OK",
            JSONObject().apply {
                put("exists", existing != null)
                put("existingId", existing?.id ?: "")
                put("stagedOffset", stagedOffset)
            }
        )
    }

    private fun decodeHeaderValue(raw: String?, isUrlEncoded: Boolean): String {
        val trimmed = raw.orEmpty().trim()
        if (!isUrlEncoded || trimmed.isEmpty()) return trimmed
        return runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed)
    }

    private suspend fun handleChunkUpload(
        headers: Map<String, String>,
        input: InputStream,
        contentLength: Long,
        output: OutputStream,
        serverId: String,
        ownerUid: String
    ) {
        val urlEncoded = headers["x-header-encoding"] == "url-utf8"
        val uploadId = headers["x-upload-id"] ?: UUID.randomUUID().toString()
        val rawFileName = decodeHeaderValue(headers["x-file-name"], urlEncoded)
            .ifBlank { "IMG_${System.currentTimeMillis()}.jpg" }
        val fileName = rawFileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "IMG_${System.currentTimeMillis()}.jpg" }
        val mimeType = headers["x-mime-type"] ?: "image/jpeg"
        val sha256 = headers["x-sha256"].orEmpty()
        val sourceBucket = decodeHeaderValue(headers["x-source-bucket"], urlEncoded).ifBlank { "Camera" }
        val takenAt = headers["x-taken-at"]?.toLongOrNull() ?: System.currentTimeMillis()
        val totalBytes = headers["x-total-bytes"]?.toLongOrNull() ?: contentLength
        val offset = headers["x-upload-offset"]?.toLongOrNull() ?: 0L

        val partFile = storageManager.getStagingPartFile(uploadId)
        if (offset == 0L && partFile.exists()) {
            partFile.delete()
        }
        val currentPartLen = if (partFile.exists()) partFile.length() else 0L
        if (offset > 0L && currentPartLen != offset) {
            writeJsonResponse(
                output,
                409,
                "Conflict",
                JSONObject().apply {
                    put("error", "Upload offset mismatch")
                    put("expectedOffset", currentPartLen)
                }
            )
            return
        }

        // Stream incoming bytes directly to disk in 32KB buffers
        val append = offset > 0L && partFile.exists() && currentPartLen == offset
        if (!partFile.exists()) {
            partFile.createNewFile()
        }
        if (contentLength > 0L) {
            FileOutputStream(partFile, append).use { fileOut ->
                val buf = ByteArray(32 * 1024)
                var remaining = contentLength
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read <= 0) break
                    fileOut.write(buf, 0, read)
                    remaining -= read
                }
            }
        }

        val currentSize = partFile.length()
        if (currentSize < totalBytes) {
            writeJsonResponse(
                output,
                202,
                "Accepted",
                JSONObject().apply {
                    put("completed", false)
                    put("receivedBytes", currentSize)
                }
            )
            return
        }

        // Finalize upload
        val settings = settingsStore.settingsFlow.first()
        val isVideo = mimeType.startsWith("video/")
        val mediaId = "med_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val computedHash = sha256.ifBlank { storageManager.computeSha256(partFile) }

        val duplicate = db.mediaDao().getByHash(ownerUid, computedHash)
        if (duplicate != null) {
            partFile.delete()
            writeJsonResponse(
                output,
                200,
                "OK",
                JSONObject().apply {
                    put("completed", true)
                    put("duplicate", true)
                    put("mediaId", duplicate.id)
                }
            )
            return
        }

        val meta = storageManager.extractMetadataFromFile(partFile, isVideo, takenAt)
        val (storedUri, thumbPath) = storageManager.commitStagedFileToVault(
            stagedFile = partFile,
            safTreeUri = settings.serverStorageUri,
            subFolder = "Originals",
            fileName = fileName,
            mimeType = mimeType,
            mediaId = mediaId,
            isVideo = isVideo
        )
        val finalSize = partFile.length()
        partFile.delete()

        val entity = MediaItemEntity(
            id = mediaId,
            serverId = serverId,
            ownerUid = ownerUid,
            fileName = fileName,
            mimeType = mimeType,
            isVideo = isVideo,
            sizeBytes = finalSize,
            width = meta.width,
            height = meta.height,
            durationMs = meta.durationMs,
            takenAtMillis = meta.takenAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            sha256Hash = computedHash,
            relativePath = "Originals/$fileName",
            sourceBucket = sourceBucket,
            isScreenshot = fileName.contains("screenshot", ignoreCase = true) ||
                sourceBucket.contains("screenshot", ignoreCase = true),
            isDocument = mimeType.contains("pdf") || sourceBucket.contains("document", ignoreCase = true),
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
        onEventLog("Stored backup: $fileName (${StorageInspector.formatBytes(finalSize)})")

        scope.launch {
            firestoreRepo.upsertServerMediaMeta(serverId, entity)
            if (settings.onDeviceFaceGroupingEnabled && !isVideo) {
                OnDeviceFaceIndexer(context, db.personDao()).indexMediaItem(entity)
            }
        }

        writeJsonResponse(
            output,
            200,
            "OK",
            JSONObject().apply {
                put("completed", true)
                put("duplicate", false)
                put("mediaId", mediaId)
            }
        )
    }

    private suspend fun handleServeThumb(ownerUid: String, mediaId: String, output: OutputStream) {
        val item = db.mediaDao().getById(mediaId)?.takeIf { it.ownerUid == ownerUid }
        if (item == null) {
            writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Media not found"))
            return
        }
        val thumbFile = item.thumbLocalPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
        if (thumbFile != null) {
            writeStreamResponse(output, 200, "OK", "image/jpeg", thumbFile.length(), thumbFile.inputStream())
        } else {
            val stream = storageManager.openVaultInputStream(item.localFileUri)
            if (stream != null) {
                writeStreamResponse(output, 200, "OK", item.mimeType, item.sizeBytes, stream)
            } else {
                writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "File unavailable"))
            }
        }
    }

    private suspend fun handleServeFile(ownerUid: String, mediaId: String, rangeHeader: String?, output: OutputStream) {
        val item = db.mediaDao().getById(mediaId)?.takeIf { it.ownerUid == ownerUid }
        if (item == null) {
            writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Media not found"))
            return
        }
        val stream = storageManager.openVaultInputStream(item.localFileUri)
        if (stream == null) {
            writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "File missing from storage"))
            return
        }
        val total = item.sizeBytes.coerceAtLeast(0L)
        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=") && total > 0L) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").split("-")
            val start = (rangeSpec.getOrNull(0)?.toLongOrNull() ?: 0L).coerceIn(0L, total - 1)
            val end = (rangeSpec.getOrNull(1)?.toLongOrNull() ?: (total - 1)).coerceIn(start, total - 1)
            val length = (end - start + 1).coerceAtLeast(0L)

            stream.use { input ->
                var skipped = 0L
                val discardBuf = ByteArray(8 * 1024)
                while (skipped < start) {
                    val s = input.skip(start - skipped)
                    if (s > 0L) {
                        skipped += s
                    } else {
                        val toRead = minOf(discardBuf.size.toLong(), start - skipped).toInt()
                        val r = input.read(discardBuf, 0, toRead)
                        if (r <= 0) break
                        skipped += r
                    }
                }
                val header = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ${item.mimeType}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Content-Length: $length\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(header.toByteArray(Charsets.US_ASCII))
                val buf = ByteArray(32 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        } else {
            writeStreamResponse(output, 200, "OK", item.mimeType, total, stream)
        }
    }

    private suspend fun handleMediaAction(
        ownerUid: String,
        mediaId: String,
        action: String,
        serverId: String,
        output: OutputStream
    ) {
        val item = db.mediaDao().getById(mediaId)?.takeIf { it.ownerUid == ownerUid }
        if (item == null) {
            writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Media not found"))
            return
        }
        if (action == "delete") {
            storageManager.deleteVaultFile(item.localFileUri, item.thumbLocalPath)
            db.mediaDao().deletePermanently(mediaId)
            scope.launch { firestoreRepo.deleteServerMediaMeta(serverId, mediaId) }
            onEventLog("Deleted file from vault: ${item.fileName}")
            writeJsonResponse(output, 200, "OK", JSONObject().put("ok", true))
            return
        }
        when (action) {
            "favorite" -> db.mediaDao().setFavorite(mediaId, !item.isFavorite)
            "archive" -> db.mediaDao().setArchived(mediaId, !item.isArchived)
            "trash" -> db.mediaDao().setTrashed(mediaId, true, System.currentTimeMillis())
            "restore" -> db.mediaDao().setTrashed(mediaId, false, null)
            else -> {
                writeJsonResponse(output, 400, "Bad Request", JSONObject().put("error", "Unknown action"))
                return
            }
        }
        db.mediaDao().getById(mediaId)?.let { updated ->
            scope.launch { firestoreRepo.upsertServerMediaMeta(serverId, updated) }
        }
        writeJsonResponse(output, 200, "OK", JSONObject().put("ok", true))
    }

    private fun writeJsonResponse(output: OutputStream, code: Int, reason: String, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeStreamResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        contentLength: Long,
        stream: InputStream
    ) {
        stream.use { input ->
            val headers = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (contentLength > 0L) append("Content-Length: $contentLength\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray(Charsets.US_ASCII))
            input.copyTo(output, 32 * 1024)
            output.flush()
        }
    }

    private fun readLineAscii(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("US-ASCII")
            }
            if (b == '\n'.code) {
                val str = baos.toString("US-ASCII")
                return if (prev == '\r'.code && str.endsWith("\r")) str.dropLast(1) else str
            }
            baos.write(b)
            prev = b
        }
    }

    private fun readFixedBytes(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == length) out else out.copyOf(offset)
    }

    private companion object {
        const val TAG = "PhotoNestHttpServer"
    }
}
