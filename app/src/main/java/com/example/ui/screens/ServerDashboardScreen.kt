package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.MediaItemEntity
import com.example.core.media.StorageInspector
import com.example.core.model.BackupSettings
import com.example.core.model.OperatingMode
import com.example.core.model.PairedDeviceDoc
import com.example.core.model.StorageVolumeStats
import com.example.core.security.PairingQrCodec
import com.example.server.ServerRuntimeSnapshot
import com.example.ui.theme.StatusOnlineContainerDark
import com.example.ui.theme.StatusOnlineContainerLight
import com.example.ui.theme.StatusOnlineGreen
import com.example.ui.theme.StatusWarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDashboardScreen(
    settings: BackupSettings,
    runtime: ServerRuntimeSnapshot,
    selectedStorage: StorageVolumeStats,
    availableVolumes: List<StorageVolumeStats>,
    pairedDevices: List<PairedDeviceDoc>,
    backedUpMedia: List<MediaItemEntity>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSelectSafFolder: (android.net.Uri?) -> Unit,
    onRotatePairingCode: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onOpenClientGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickTestPhotosToVault: (List<android.net.Uri>) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSelectSafFolder(uri)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onStartServer()
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) onPickTestPhotosToVault(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PhotoNest Server", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (runtime.isOnline) "Online • ${runtime.hostAddress}:${runtime.port}" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (runtime.isOnline) StatusOnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenClientGallery,
                        modifier = Modifier.testTag("switch_to_gallery_btn")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Browse stored photos")
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("server_settings_btn")
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Server Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Server Status & Control Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (runtime.isOnline) StatusOnlineGreen
                                            else MaterialTheme.colorScheme.outline
                                        )
                                )
                                Text(
                                    text = if (runtime.isOnline) "SERVER ONLINE" else "SERVER OFFLINE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (runtime.isOnline) StatusOnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "${backedUpMedia.size} items stored",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = settings.serverName,
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Text(
                            text = if (runtime.isOnline) {
                                "Foreground service active. Receiving automatic backups over pinned TLS on port ${runtime.port}."
                            } else {
                                "Start the PhotoNest foreground service to receive backups and serve original photos & videos."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (runtime.isOnline) {
                                Button(
                                    onClick = onStopServer,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("stop_server_button")
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Stop Server")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            onStartServer()
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("start_server_button")
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Start Server")
                                }
                            }

                            FilledTonalButton(
                                onClick = {
                                    mediaPicker.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Add Local Media")
                            }
                        }
                    }
                }
            }

            // 2. Storage & microSD Management Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (selectedStorage.isRemovableSdCard) Icons.Rounded.SdCard else Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Vault Storage Location", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = selectedStorage.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { selectedStorage.usageFraction },
                            color = if (selectedStorage.isNearCapacity) StatusWarningAmber else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Used: ${StorageInspector.formatBytes(selectedStorage.usedBytes)}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "Free: ${StorageInspector.formatBytes(selectedStorage.availableBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Total: ${StorageInspector.formatBytes(selectedStorage.totalBytes)}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (selectedStorage.isNearCapacity) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Text(
                                        text = "Storage is over 85% full. Select a larger microSD card folder.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        if (availableVolumes.any { it.isRemovableSdCard }) {
                            Text(
                                text = "External microSD card detected — tap below to grant persistent SAF folder access.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusOnlineGreen
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { folderPicker.launch(null) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("select_saf_folder_btn")
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Select Folder / SD")
                            }
                            if (settings.serverStorageUri.isNotBlank()) {
                                TextButton(
                                    onClick = { onSelectSafFolder(null) },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Use Internal")
                                }
                            }
                        }
                    }
                }
            }

            // 3. QR Pairing Code Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Secure QR Device Pairing", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = onRotatePairingCode) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Rotate pairing secret")
                            }
                        }

                        if (runtime.isOnline && runtime.pairingQrContent.isNotBlank()) {
                            val qrBmp = remember(runtime.pairingQrContent) {
                                PairingQrCodec.generateQrBitmap(runtime.pairingQrContent, 480)
                            }
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Image(
                                    bitmap = qrBmp.asImageBitmap(),
                                    contentDescription = "PhotoNest Server Pairing QR Code",
                                    modifier = Modifier
                                        .size(210.dp)
                                        .padding(12.dp)
                                )
                            }
                            Text(
                                text = "TLS Pin: ${runtime.certFingerprint.take(24)}…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("PhotoNest Pairing", runtime.pairingQrContent))
                                    onShowMessage("Copied encrypted pairing payload")
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Copy Pairing Payload")
                            }
                        } else {
                            Text(
                                text = "Start the server above to generate a live TLS-pinned QR pairing code for your client phones.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. Connected & Paired Devices
            item {
                Text(
                    text = "Authorized Devices (${pairedDevices.count { it.status == "active" }})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (pairedDevices.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No client phones paired yet. Open PhotoNest in Access My Photos mode on your daily phone and scan the QR code above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(pairedDevices, key = { it.id }) { dev ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.deviceName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${dev.deviceModel} • ${dev.status.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (dev.status == "active") StatusOnlineGreen else MaterialTheme.colorScheme.error
                                )
                            }
                            if (dev.status == "active") {
                                IconButton(onClick = { onRevokeDevice(dev.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Revoke device")
                                }
                            }
                        }
                    }
                }
            }

            // 5. Server Always-On Notice & Recent Activity
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text(
                            text = stringResource(R.string.server_always_on_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (runtime.recentLogs.isNotEmpty()) {
                item {
                    Text("Live Server Log", style = MaterialTheme.typography.titleSmall)
                }
                items(runtime.recentLogs) { logLine ->
                    Text(
                        text = "• $logLine",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
