package com.example.core.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.AppThemePreference
import com.example.core.model.BackupScope
import com.example.core.model.BackupSettings
import com.example.core.model.OperatingMode
import com.example.core.model.UploadQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.photoNestDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "photonest_preferences"
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val BACKUP_SCOPE = stringPreferencesKey("backup_scope")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val MOBILE_DATA_ALLOWED = booleanPreferencesKey("mobile_data_allowed")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val SCREEN_OFF_BACKUP = booleanPreferencesKey("screen_off_backup")
        val IS_PAUSED = booleanPreferencesKey("is_paused")
        val UPLOAD_QUALITY = stringPreferencesKey("upload_quality")
        val SELECTED_SOURCE_FOLDERS = stringSetPreferencesKey("selected_source_folders")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val SERVER_STORAGE_URI = stringPreferencesKey("server_storage_uri")
        val SERVER_STORAGE_LABEL = stringPreferencesKey("server_storage_label")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val REMOTE_RELAY_ENABLED = booleanPreferencesKey("remote_relay_enabled")
        val REMOTE_ENDPOINT_OVERRIDE = stringPreferencesKey("remote_endpoint_override")
        val APP_THEME = stringPreferencesKey("app_theme")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val FACE_GROUPING_ENABLED = booleanPreferencesKey("face_grouping_enabled")
        val AI_SEARCH_ENABLED = booleanPreferencesKey("ai_search_enabled")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val DEVICE_ID = stringPreferencesKey("local_device_id")
        val SERVER_ID = stringPreferencesKey("local_server_id")
        val PAIRING_SECRET = stringPreferencesKey("server_pairing_secret")
    }

    val settingsFlow: Flow<BackupSettings> = context.photoNestDataStore.data.map { prefs ->
        BackupSettings(
            operatingMode = OperatingMode.fromWire(prefs[Keys.OPERATING_MODE]),
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP_ENABLED] ?: false,
            backupScope = prefs[Keys.BACKUP_SCOPE]?.let { name ->
                BackupScope.entries.firstOrNull { it.name == name }
            } ?: BackupScope.AUTO_BACKUP_ALL,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: true,
            mobileDataAllowed = prefs[Keys.MOBILE_DATA_ALLOWED] ?: false,
            chargingOnly = prefs[Keys.CHARGING_ONLY] ?: false,
            screenOffBackup = prefs[Keys.SCREEN_OFF_BACKUP] ?: true,
            isPaused = prefs[Keys.IS_PAUSED] ?: false,
            uploadQuality = prefs[Keys.UPLOAD_QUALITY]?.let { name ->
                UploadQuality.entries.firstOrNull { it.name == name }
            } ?: UploadQuality.ORIGINAL,
            selectedSourceFolderUris = prefs[Keys.SELECTED_SOURCE_FOLDERS] ?: emptySet(),
            excludedFolderUris = prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            serverStorageUri = prefs[Keys.SERVER_STORAGE_URI] ?: "",
            serverStorageLabel = prefs[Keys.SERVER_STORAGE_LABEL] ?: "Internal App Vault",
            serverPort = prefs[Keys.SERVER_PORT] ?: 8443,
            serverName = prefs[Keys.SERVER_NAME] ?: "${Build.MODEL} PhotoNest",
            remoteRelayEnabled = prefs[Keys.REMOTE_RELAY_ENABLED] ?: true,
            remoteEndpointOverride = prefs[Keys.REMOTE_ENDPOINT_OVERRIDE] ?: "",
            appTheme = prefs[Keys.APP_THEME]?.let { name ->
                AppThemePreference.entries.firstOrNull { it.name == name }
            } ?: AppThemePreference.SYSTEM,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            onDeviceFaceGroupingEnabled = prefs[Keys.FACE_GROUPING_ENABLED] ?: true,
            onDeviceAiSearchEnabled = prefs[Keys.AI_SEARCH_ENABLED] ?: true,
            activeServerId = prefs[Keys.ACTIVE_SERVER_ID] ?: ""
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.photoNestDataStore.data.first()[Keys.DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val generated = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        context.photoNestDataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    suspend fun getOrCreateServerId(): String {
        val current = context.photoNestDataStore.data.first()[Keys.SERVER_ID]
        if (!current.isNullOrBlank()) return current
        val generated = "srv_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        context.photoNestDataStore.edit { it[Keys.SERVER_ID] = generated }
        return generated
    }

    suspend fun getOrCreatePairingSecret(): String {
        val current = context.photoNestDataStore.data.first()[Keys.PAIRING_SECRET]
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString().replace("-", "")
        context.photoNestDataStore.edit { it[Keys.PAIRING_SECRET] = generated }
        return generated
    }

    suspend fun rotatePairingSecret(): String {
        val generated = UUID.randomUUID().toString().replace("-", "")
        context.photoNestDataStore.edit { it[Keys.PAIRING_SECRET] = generated }
        return generated
    }

    suspend fun setOperatingMode(mode: OperatingMode) {
        context.photoNestDataStore.edit { it[Keys.OPERATING_MODE] = mode.wireValue }
    }

    suspend fun updateBackupConfig(
        autoBackupEnabled: Boolean? = null,
        backupScope: BackupScope? = null,
        wifiOnly: Boolean? = null,
        mobileDataAllowed: Boolean? = null,
        chargingOnly: Boolean? = null,
        screenOffBackup: Boolean? = null,
        isPaused: Boolean? = null,
        uploadQuality: UploadQuality? = null
    ) {
        context.photoNestDataStore.edit { prefs ->
            autoBackupEnabled?.let { prefs[Keys.AUTO_BACKUP_ENABLED] = it }
            backupScope?.let { prefs[Keys.BACKUP_SCOPE] = it.name }
            wifiOnly?.let { prefs[Keys.WIFI_ONLY] = it }
            mobileDataAllowed?.let { prefs[Keys.MOBILE_DATA_ALLOWED] = it }
            chargingOnly?.let { prefs[Keys.CHARGING_ONLY] = it }
            screenOffBackup?.let { prefs[Keys.SCREEN_OFF_BACKUP] = it }
            isPaused?.let { prefs[Keys.IS_PAUSED] = it }
            uploadQuality?.let { prefs[Keys.UPLOAD_QUALITY] = it.name }
        }
    }

    suspend fun addSourceFolderUri(uriString: String) {
        context.photoNestDataStore.edit { prefs ->
            val updated = (prefs[Keys.SELECTED_SOURCE_FOLDERS] ?: emptySet()) + uriString
            prefs[Keys.SELECTED_SOURCE_FOLDERS] = updated
        }
    }

    suspend fun removeSourceFolderUri(uriString: String) {
        context.photoNestDataStore.edit { prefs ->
            val updated = (prefs[Keys.SELECTED_SOURCE_FOLDERS] ?: emptySet()) - uriString
            prefs[Keys.SELECTED_SOURCE_FOLDERS] = updated
        }
    }

    suspend fun addExcludedFolderUri(uriString: String) {
        context.photoNestDataStore.edit { prefs ->
            val updated = (prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()) + uriString
            prefs[Keys.EXCLUDED_FOLDERS] = updated
        }
    }

    suspend fun removeExcludedFolderUri(uriString: String) {
        context.photoNestDataStore.edit { prefs ->
            val updated = (prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()) - uriString
            prefs[Keys.EXCLUDED_FOLDERS] = updated
        }
    }

    suspend fun setServerStorageLocation(uriString: String, label: String) {
        context.photoNestDataStore.edit { prefs ->
            prefs[Keys.SERVER_STORAGE_URI] = uriString
            prefs[Keys.SERVER_STORAGE_LABEL] = label
        }
    }

    suspend fun setServerConfig(
        serverName: String? = null,
        port: Int? = null,
        remoteRelayEnabled: Boolean? = null,
        remoteEndpointOverride: String? = null
    ) {
        context.photoNestDataStore.edit { prefs ->
            serverName?.let { prefs[Keys.SERVER_NAME] = it }
            port?.let { prefs[Keys.SERVER_PORT] = it.coerceIn(1024, 65535) }
            remoteRelayEnabled?.let { prefs[Keys.REMOTE_RELAY_ENABLED] = it }
            remoteEndpointOverride?.let { prefs[Keys.REMOTE_ENDPOINT_OVERRIDE] = it.trim() }
        }
    }

    suspend fun setAppPreferences(
        appTheme: AppThemePreference? = null,
        notificationsEnabled: Boolean? = null,
        faceGroupingEnabled: Boolean? = null,
        aiSearchEnabled: Boolean? = null,
        activeServerId: String? = null
    ) {
        context.photoNestDataStore.edit { prefs ->
            appTheme?.let { prefs[Keys.APP_THEME] = it.name }
            notificationsEnabled?.let { prefs[Keys.NOTIFICATIONS_ENABLED] = it }
            faceGroupingEnabled?.let { prefs[Keys.FACE_GROUPING_ENABLED] = it }
            aiSearchEnabled?.let { prefs[Keys.AI_SEARCH_ENABLED] = it }
            activeServerId?.let { prefs[Keys.ACTIVE_SERVER_ID] = it }
        }
    }
}
