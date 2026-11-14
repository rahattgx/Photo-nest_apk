package com.example.core.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.core.model.StorageVolumeStats
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object StorageInspector {

    fun inspectAvailableVolumes(context: Context): List<StorageVolumeStats> {
        val volumes = mutableListOf<StorageVolumeStats>()

        // 1. Internal App Vault / Internal Shared Storage
        val internalDir = context.filesDir
        val internalStat = StatFs(internalDir.absolutePath)
        val intTotal = internalStat.totalBytes
        val intAvail = internalStat.availableBytes
        volumes += StorageVolumeStats(
            label = "Internal Storage",
            pathOrUri = internalDir.absolutePath,
            isRemovableSdCard = false,
            totalBytes = intTotal,
            usedBytes = (intTotal - intAvail).coerceAtLeast(0L),
            availableBytes = intAvail
        )

        // 2. Detect external microSD / removable volumes via getExternalFilesDirs + StorageManager
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val externalDirs = context.getExternalFilesDirs(null)
        externalDirs.drop(1).filterNotNull().forEachIndexed { idx, dir ->
            if (Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED) {
                runCatching {
                    val stat = StatFs(dir.absolutePath)
                    val total = stat.totalBytes
                    val avail = stat.availableBytes
                    val desc = storageManager?.getStorageVolume(dir)?.getDescription(context)
                        ?: "Removable SD Card ${idx + 1}"
                    volumes += StorageVolumeStats(
                        label = desc,
                        pathOrUri = dir.absolutePath,
                        isRemovableSdCard = true,
                        totalBytes = total,
                        usedBytes = (total - avail).coerceAtLeast(0L),
                        availableBytes = avail
                    )
                }
            }
        }
        return volumes
    }

    fun inspectSelectedStorage(context: Context, safTreeUri: String, label: String): StorageVolumeStats {
        if (safTreeUri.isBlank()) {
            return inspectAvailableVolumes(context).first()
        }
        val uri = runCatching { Uri.parse(safTreeUri) }.getOrNull()
        if (uri != null && uri.scheme == "content") {
            runCatching {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val isPrimary = docId.startsWith("primary:")
                context.contentResolver.openFileDescriptor(
                    DocumentsContract.buildDocumentUriUsingTree(uri, docId),
                    "r"
                )?.use { pfd ->
                    val stats = android.system.Os.fstatvfs(pfd.fileDescriptor)
                    val total = stats.f_blocks * stats.f_frsize
                    val avail = stats.f_bavail * stats.f_frsize
                    return StorageVolumeStats(
                        label = label.ifBlank { if (isPrimary) "Internal Folder" else "microSD Card" },
                        pathOrUri = safTreeUri,
                        isRemovableSdCard = !isPrimary,
                        totalBytes = total,
                        usedBytes = (total - avail).coerceAtLeast(0L),
                        availableBytes = avail
                    )
                }
            }
        }
        // Fallback: match primary external storage or specific removable SD card UUID
        val volumes = inspectAvailableVolumes(context)
        val docId = uri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }.orEmpty()
        val isPrimary = docId.startsWith("primary:")
        if (isPrimary) {
            runCatching {
                @Suppress("DEPRECATION")
                val extDir = Environment.getExternalStorageDirectory()
                val stat = StatFs(extDir.absolutePath)
                val total = stat.totalBytes
                val avail = stat.availableBytes
                return StorageVolumeStats(
                    label = label.ifBlank { "Internal Shared Storage" },
                    pathOrUri = safTreeUri,
                    isRemovableSdCard = false,
                    totalBytes = total,
                    usedBytes = (total - avail).coerceAtLeast(0L),
                    availableBytes = avail
                )
            }
        }
        val volumeUuid = docId.substringBefore(':').takeIf { it.isNotBlank() && !isPrimary }
        if (volumeUuid != null) {
            context.getExternalFilesDirs(null).filterNotNull().firstOrNull {
                it.absolutePath.contains(volumeUuid, ignoreCase = true)
            }?.let { matchingDir ->
                runCatching {
                    val stat = StatFs(matchingDir.absolutePath)
                    val total = stat.totalBytes
                    val avail = stat.availableBytes
                    return StorageVolumeStats(
                        label = label.ifBlank { "microSD Card ($volumeUuid)" },
                        pathOrUri = safTreeUri,
                        isRemovableSdCard = true,
                        totalBytes = total,
                        usedBytes = (total - avail).coerceAtLeast(0L),
                        availableBytes = avail
                    )
                }
            }
        }
        val sd = volumes.firstOrNull { it.isRemovableSdCard && !isPrimary }
        val base = sd ?: volumes.first()
        return base.copy(label = label.ifBlank { base.label }, pathOrUri = safTreeUri)
    }

    fun describeSafUri(context: Context, uriString: String): String {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return "Selected Folder"
        val doc = DocumentFile.fromTreeUri(context, uri)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val name = doc?.name ?: docId?.substringAfter(':') ?: "Folder"
        val prefix = when {
            docId == null -> ""
            docId.startsWith("primary:") -> "Internal • "
            else -> "microSD • "
        }
        return "$prefix$name"
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
            candidates.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
                ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }
}
