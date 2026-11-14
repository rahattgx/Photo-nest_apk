package com.example.core.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class OperatingMode(val wireValue: String) {
    UNSET("unset"),
    SERVER("server"),
    CLIENT("client");

    companion object {
        fun fromWire(value: String?): OperatingMode =
            entries.firstOrNull { it.wireValue == value } ?: UNSET
    }
}

enum class BackupScope(val label: String) {
    AUTO_BACKUP_ALL("Auto Backup All"),
    GALLERY_ONLY("Backup Gallery Only")
}

enum class UploadQuality(val label: String) {
    ORIGINAL("Original Quality"),
    HIGH_EFFICIENCY("Storage Saver (92% JPEG)")
}

enum class AppThemePreference(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class BackupStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}

enum class PersonCategory(val label: String) {
    PEOPLE("People"),
    PETS("Pets")
}

enum class MediaCategory {
    ALL,
    VIDEOS,
    SCREENSHOTS,
    DOCUMENTS,
    FAVORITES,
    ARCHIVED,
    TRASH
}

data class BackupSettings(
    val operatingMode: OperatingMode = OperatingMode.UNSET,
    val autoBackupEnabled: Boolean = false,
    val backupScope: BackupScope = BackupScope.AUTO_BACKUP_ALL,
    val wifiOnly: Boolean = true,
    val mobileDataAllowed: Boolean = false,
    val chargingOnly: Boolean = false,
    val screenOffBackup: Boolean = true,
    val isPaused: Boolean = false,
    val uploadQuality: UploadQuality = UploadQuality.ORIGINAL,
    val selectedSourceFolderUris: Set<String> = emptySet(),
    val excludedFolderUris: Set<String> = emptySet(),
    val serverStorageUri: String = "",
    val serverStorageLabel: String = "Internal App Vault",
    val serverPort: Int = 8443,
    val serverName: String = "My PhotoNest Server",
    val remoteRelayEnabled: Boolean = true,
    val remoteEndpointOverride: String = "",
    val appTheme: AppThemePreference = AppThemePreference.SYSTEM,
    val notificationsEnabled: Boolean = true,
    val onDeviceFaceGroupingEnabled: Boolean = true,
    val onDeviceAiSearchEnabled: Boolean = true,
    val activeServerId: String = ""
)

data class StorageVolumeStats(
    val label: String,
    val pathOrUri: String,
    val isRemovableSdCard: Boolean,
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long
) {
    val usageFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val isNearCapacity: Boolean
        get() = usageFraction >= 0.85f
}

data class PairingPayload(
    val serverId: String,
    val ownerUid: String,
    val serverName: String,
    val hostAddress: String,
    val port: Int,
    val certFingerprint: String,
    val pairingSecret: String,
    val expiresAtMillis: Long
)

// Firestore Models

@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val preferredMode: String = "unset",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PhotoServerDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val serverName: String = "",
    val hostAddress: String = "",
    val port: Long = 8443L,
    val certFingerprint: String = "",
    val pairingCodeHash: String = "",
    val isOnline: Boolean = false,
    val storageLabel: String = "",
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val relayMode: String = "lan",
    @ServerTimestamp val lastHeartbeat: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PairedDeviceDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val serverId: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val status: String = "active",
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ServerMediaMetaDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val serverId: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val isVideo: Boolean = false,
    val sizeBytes: Long = 0L,
    val width: Long = 0L,
    val height: Long = 0L,
    val durationMs: Long = 0L,
    val takenAtMillis: Long = 0L,
    val sha256Hash: String = "",
    val sourceBucket: String = "",
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val locationName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
