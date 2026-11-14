package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.R
import com.example.core.media.StorageInspector
import com.example.core.model.AppThemePreference
import com.example.core.model.BackupSettings
import com.example.core.model.OperatingMode
import com.example.core.model.PairedDeviceDoc
import com.example.core.model.StorageVolumeStats
import com.example.core.model.UploadQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userDisplayName: String,
    userEmail: String,
    userPhotoUrl: String?,
    settings: BackupSettings,
    selectedStorage: StorageVolumeStats,
    pairedDevices: List<PairedDeviceDoc>,
    tlsFingerprint: String,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onSelectMode: (OperatingMode) -> Unit,
    onUpdateServerConfig: (name: String, port: Int, relayEnabled: Boolean, remoteEndpoint: String) -> Unit,
    onSelectSafFolder: (android.net.Uri?) -> Unit,
    onOpenBackupSettings: () -> Unit,
    onOpenPairing: () -> Unit,
    onUpdateUploadQuality: (UploadQuality) -> Unit,
    onUpdateNetworkPrefs: (wifiOnly: Boolean, mobileData: Boolean) -> Unit,
    onRevokeDevice: (String) -> Unit,
    onUpdatePrivacyAi: (faceGrouping: Boolean, aiSearch: Boolean) -> Unit,
    onUpdateTheme: (AppThemePreference) -> Unit,
    onToggleNotifications: (Boolean) -> Unit
) {
    BackHandler(onBack = onBack)

    var serverNameInput by remember(settings.serverName) { mutableStateOf(settings.serverName) }
    var serverPortInput by remember(settings.serverPort) { mutableStateOf(settings.serverPort.toString()) }
    var remoteEndpointInput by remember(settings.remoteEndpointOverride) { mutableStateOf(settings.remoteEndpointOverride) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSelectSafFolder(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Google Account
            item {
                SettingsSectionCard(title = "Google Account") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!userPhotoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = userPhotoUrl,
                                contentDescription = userDisplayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(userDisplayName.ifBlank { "PhotoNest User" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = userEmail.ifBlank { "Signed in via Google" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "Used strictly for identity verification and server-client authorization. No photos are uploaded to Google Drive or Google Photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onSignOut,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Sign Out")
                    }
                }
            }

            // 2. Operating Mode
            item {
                SettingsSectionCard(title = "Operating Mode") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = settings.operatingMode == OperatingMode.SERVER,
                            onClick = { onSelectMode(OperatingMode.SERVER) },
                            leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Photo Server") },
                            modifier = Modifier.weight(1f).testTag("switch_mode_server_chip")
                        )
                        FilterChip(
                            selected = settings.operatingMode == OperatingMode.CLIENT,
                            onClick = { onSelectMode(OperatingMode.CLIENT) },
                            leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Access Photos") },
                            modifier = Modifier.weight(1f).testTag("switch_mode_client_chip")
                        )
                    }
                }
            }

            // 3. Server Settings
            item {
                SettingsSectionCard(title = "Server Settings") {
                    OutlinedTextField(
                        value = serverNameInput,
                        onValueChange = { serverNameInput = it },
                        label = { Text("Server Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = serverPortInput,
                        onValueChange = { serverPortInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("HTTPS Server Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remoteEndpointInput,
                        onValueChange = { remoteEndpointInput = it },
                        label = { Text("Remote Relay / Tailscale / VPN Host (Optional)") },
                        placeholder = { Text("e.g. 100.x.y.z or photos.mydomain.net") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val parsedPort = serverPortInput.toIntOrNull()?.coerceIn(1024, 65535) ?: 8443
                            onUpdateServerConfig(
                                serverNameInput.trim().ifBlank { "My PhotoNest Server" },
                                parsedPort,
                                settings.remoteRelayEnabled,
                                remoteEndpointInput.trim()
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Save Server Config")
                    }
                }
            }

            // 4. Storage Location & microSD Management
            item {
                SettingsSectionCard(title = "Storage Location & SD Card") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedStorage.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Free: ${StorageInspector.formatBytes(selectedStorage.availableBytes)} of ${StorageInspector.formatBytes(selectedStorage.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { selectedStorage.usageFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("Choose Folder / SD")
                        }
                        if (settings.serverStorageUri.isNotBlank()) {
                            TextButton(onClick = { onSelectSafFolder(null) }, modifier = Modifier.height(48.dp)) {
                                Text("Reset Internal")
                            }
                        }
                    }
                }
            }

            // 5. Backup Settings & 6. Upload Quality & 7. Network Preferences
            item {
                SettingsSectionCard(title = "Backup, Quality & Network") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup Configuration", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${settings.backupScope.label} • ${if (settings.autoBackupEnabled) "Enabled" else "Off"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = onOpenBackupSettings, shape = RoundedCornerShape(12.dp)) {
                            Text("Configure")
                        }
                    }

                    HorizontalDivider()

                    Text("Upload Quality", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UploadQuality.entries.forEach { q ->
                            FilterChip(
                                selected = settings.uploadQuality == q,
                                onClick = { onUpdateUploadQuality(q) },
                                label = { Text(q.label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Wi-Fi only backups", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = settings.wifiOnly,
                            onCheckedChange = { onUpdateNetworkPrefs(it, settings.mobileDataAllowed) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Allow mobile data", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = settings.mobileDataAllowed,
                            onCheckedChange = { onUpdateNetworkPrefs(settings.wifiOnly, it) }
                        )
                    }
                }
            }

            // 8. Connected Devices
            item {
                SettingsSectionCard(title = "Connected & Paired Devices") {
                    if (pairedDevices.isEmpty()) {
                        Text(
                            text = "No connected client devices registered yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        pairedDevices.forEach { dev ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dev.deviceName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${dev.deviceModel} • ${dev.status}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (dev.status == "active") {
                                    IconButton(onClick = { onRevokeDevice(dev.id) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Revoke access")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenPairing,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Pair / Manage Server Connection")
                    }
                }
            }

            // 9. Privacy & Security
            item {
                SettingsSectionCard(title = "Privacy & Security") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Pinned Self-Signed TLS Certificate", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "SHA-256: ${tlsFingerprint.ifBlank { "Generated when server starts" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("On-Device Face Grouping", style = MaterialTheme.typography.bodyMedium)
                            Text("Runs locally via ML Kit; never uploads faces", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.onDeviceFaceGroupingEnabled,
                            onCheckedChange = { onUpdatePrivacyAi(it, settings.onDeviceAiSearchEnabled) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("On-Device Smart AI Search", style = MaterialTheme.typography.bodyMedium)
                            Text("Semantic keyword & scene filtering locally", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.onDeviceAiSearchEnabled,
                            onCheckedChange = { onUpdatePrivacyAi(settings.onDeviceFaceGroupingEnabled, it) }
                        )
                    }
                }
            }

            // 10. App Theme & 11. Notifications
            item {
                SettingsSectionCard(title = "Appearance & Notifications") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("App Theme", style = MaterialTheme.typography.titleSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemePreference.entries.forEach { pref ->
                            FilterChip(
                                selected = settings.appTheme == pref,
                                onClick = { onUpdateTheme(pref) },
                                label = { Text(pref.label) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Backup & Server Notifications", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = settings.notificationsEnabled,
                            onCheckedChange = onToggleNotifications
                        )
                    }
                }
            }

            // 12. About PhotoNest
            item {
                SettingsSectionCard(title = "About PhotoNest") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("PhotoNest v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.server_always_on_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
