package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.client.DiscoveredLanServer
import com.example.core.data.BackupJobEntity
import com.example.core.media.StorageInspector
import com.example.core.model.BackupScope
import com.example.core.model.BackupSettings
import com.example.core.model.BackupStatus
import com.example.core.model.PairingPayload
import com.example.core.model.PhotoServerDoc
import com.example.core.model.UploadQuality
import com.example.core.security.PairingQrCodec
import com.example.server.ServerRuntimeSnapshot
import com.example.ui.theme.StatusOnlineGreen
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    settings: BackupSettings,
    backupQueue: List<BackupJobEntity>,
    onBack: () -> Unit,
    onUpdateSettings: (
        autoBackupEnabled: Boolean?,
        backupScope: BackupScope?,
        wifiOnly: Boolean?,
        mobileDataAllowed: Boolean?,
        chargingOnly: Boolean?,
        screenOffBackup: Boolean?,
        isPaused: Boolean?,
        uploadQuality: UploadQuality?
    ) -> Unit,
    onAddSourceFolder: (Uri) -> Unit,
    onRemoveSourceFolder: (String) -> Unit,
    onAddExcludedFolder: (Uri) -> Unit,
    onRemoveExcludedFolder: (String) -> Unit,
    onTriggerBackupNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onClearCompleted: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    val sourceFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onAddSourceFolder(uri)
    }

    val excludedFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onAddExcludedFolder(uri)
    }

    val pendingCount = backupQueue.count {
        it.status == BackupStatus.PENDING.name || it.status == BackupStatus.UPLOADING.name
    }
    val failedCount = backupQueue.count { it.status == BackupStatus.FAILED.name }
    val completedCount = backupQueue.count { it.status == BackupStatus.COMPLETED.name }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Automatic Backup", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onTriggerBackupNow, modifier = Modifier.testTag("backup_sync_now_btn")) {
                        Icon(Icons.Rounded.Sync, contentDescription = "Run backup now")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Master Toggle & Pause/Resume
            item {
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automatic Backup", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (settings.autoBackupEnabled) {
                                        if (settings.isPaused) "Paused by user" else "Monitoring folders & WorkManager queue"
                                    } else {
                                        "Manual backup only"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.autoBackupEnabled,
                                onCheckedChange = {
                                    onUpdateSettings(it, null, null, null, null, null, null, null)
                                },
                                modifier = Modifier.testTag("auto_backup_switch")
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    onUpdateSettings(null, null, null, null, null, null, !settings.isPaused, null)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pause_resume_backup_btn")
                            ) {
                                Icon(
                                    imageVector = if (settings.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(if (settings.isPaused) "Resume Backup" else "Pause Backup")
                            }
                            Button(
                                onClick = onTriggerBackupNow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("run_backup_now_btn")
                            ) {
                                Text("Back Up Now")
                            }
                        }
                    }
                }
            }

            // Backup Option 1 vs Option 2
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Backup Scope", style = MaterialTheme.typography.titleMedium)

                        BackupScopeOptionRow(
                            title = "Option 1: Auto Backup All",
                            description = "Camera photos & videos, screenshots, screen recordings, downloads, WhatsApp media, and accessible gallery folders.",
                            selected = settings.backupScope == BackupScope.AUTO_BACKUP_ALL,
                            onClick = {
                                onUpdateSettings(null, BackupScope.AUTO_BACKUP_ALL, null, null, null, null, null, null)
                            }
                        )

                        BackupScopeOptionRow(
                            title = "Option 2: Backup Gallery Only",
                            description = "Only photos and videos from your selected gallery/media collections. Ignores unrelated documents and files.",
                            selected = settings.backupScope == BackupScope.GALLERY_ONLY,
                            onClick = {
                                onUpdateSettings(null, BackupScope.GALLERY_ONLY, null, null, null, null, null, null)
                            }
                        )
                    }
                }
            }

            // Upload Quality & Constraints
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Upload Quality & Conditions", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UploadQuality.entries.forEach { q ->
                                FilterChip(
                                    selected = settings.uploadQuality == q,
                                    onClick = {
                                        onUpdateSettings(null, null, null, null, null, null, null, q)
                                    },
                                    label = { Text(q.label) }
                                )
                            }
                        }

                        SettingSwitchRow(
                            title = "Wi-Fi only",
                            subtitle = "Prevent cellular uploads unless mobile data is enabled",
                            checked = settings.wifiOnly,
                            onCheckedChange = {
                                onUpdateSettings(null, null, it, null, null, null, null, null)
                            }
                        )
                        SettingSwitchRow(
                            title = "Allow mobile data backup",
                            subtitle = "Back up over cellular if Wi-Fi is unavailable",
                            checked = settings.mobileDataAllowed,
                            onCheckedChange = {
                                onUpdateSettings(null, null, null, it, null, null, null, null)
                            }
                        )
                        SettingSwitchRow(
                            title = "Charging only",
                            subtitle = "Wait until device is connected to power",
                            checked = settings.chargingOnly,
                            onCheckedChange = {
                                onUpdateSettings(null, null, null, null, it, null, null, null)
                            }
                        )
                        SettingSwitchRow(
                            title = "Backup while screen is off",
                            subtitle = "Scheduled via Android WorkManager subject to OS battery optimizations",
                            checked = settings.screenOffBackup,
                            onCheckedChange = {
                                onUpdateSettings(null, null, null, null, null, it, null, null)
                            }
                        )
                    }
                }
            }

            // Selected & Excluded Folders (SAF)
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Selected Folders", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { sourceFolderPicker.launch(null) }) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Add Folder")
                            }
                        }
                        if (settings.selectedSourceFolderUris.isEmpty()) {
                            Text(
                                text = "No SAF source folders added yet. Tap Add Folder (e.g., DCIM/Camera, Pictures/Screenshots, WhatsApp Images) or use the + button on Photos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            settings.selectedSourceFolderUris.forEach { uriStr ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = StorageInspector.describeSafUri(context, uriStr),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { onRemoveSourceFolder(uriStr) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove folder")
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Excluded Folders", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { excludedFolderPicker.launch(null) }) {
                                Icon(Icons.Rounded.FolderOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Exclude")
                            }
                        }
                        if (settings.excludedFolderUris.isEmpty()) {
                            Text(
                                text = "No excluded folders configured.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            settings.excludedFolderUris.forEach { uriStr ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Rounded.FolderOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Text(
                                        text = StorageInspector.describeSafUri(context, uriStr),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { onRemoveExcludedFolder(uriStr) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove exclusion")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Queue Summary & Controls
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Backup Queue", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "$pendingCount pending • $failedCount failed • $completedCount completed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (pendingCount > 0 && !settings.isPaused) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (failedCount > 0) {
                                Button(onClick = onRetryFailed, shape = RoundedCornerShape(12.dp)) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Retry Failed ($failedCount)")
                                }
                            }
                            if (completedCount > 0) {
                                OutlinedButton(onClick = onClearCompleted, shape = RoundedCornerShape(12.dp)) {
                                    Text("Clear Completed")
                                }
                            }
                        }
                    }
                }
            }

            items(backupQueue.take(40), key = { it.id }) { job ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val icon = when (job.status) {
                            BackupStatus.COMPLETED.name -> Icons.Rounded.CheckCircle
                            BackupStatus.FAILED.name -> Icons.Rounded.ErrorOutline
                            else -> Icons.Rounded.Sync
                        }
                        val tint = when (job.status) {
                            BackupStatus.COMPLETED.name -> StatusOnlineGreen
                            BackupStatus.FAILED.name -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Icon(icon, contentDescription = null, tint = tint)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(job.fileName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${job.status} • ${StorageInspector.formatBytes(job.sizeBytes)}" +
                                    (job.errorMessage?.let { " • $it" } ?: ""),
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
private fun BackupScopeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPairingScreen(
    currentUserId: String,
    serverRuntime: ServerRuntimeSnapshot,
    lanServers: List<DiscoveredLanServer>,
    firestoreServers: List<PhotoServerDoc>,
    isBusy: Boolean,
    onBack: () -> Unit,
    onPairWithPayload: (rawPayload: String, hostOverride: String?) -> Unit,
    onPairLocalServer: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    var showScanner by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraDeniedMessage by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            cameraDeniedMessage = false
            showScanner = true
        } else {
            cameraDeniedMessage = true
        }
    }

    var manualPayload by remember { mutableStateOf("") }
    var hostOverride by remember { mutableStateOf("") }
    var selectedCloudServer by remember { mutableStateOf<PhotoServerDoc?>(null) }
    var cloudPairingSecret by remember { mutableStateOf("") }
    var cloudHostInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Pair PhotoNest Server", style = MaterialTheme.typography.titleLarge) }
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
            if (isBusy) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                    )
                }
            }

            // Same-device loopback testing convenience
            if (serverRuntime.isOnline) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Smartphone, contentDescription = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Local Server Running on This Device", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${serverRuntime.serverName} (${serverRuntime.hostAddress}:${serverRuntime.port})",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Button(
                                onClick = onPairLocalServer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("pair_local_server_btn")
                            ) {
                                Text("Pair With This Device (127.0.0.1)")
                            }
                        }
                    }
                }
            }

            // QR Code Scanner Card
            item {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scan Server QR Code", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Verifies TLS certificate fingerprint & Google Account identity.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (showScanner && hasCameraPermission) {
                            QrCameraPreview(
                                onQrDetected = { raw ->
                                    showScanner = false
                                    onPairWithPayload(raw, hostOverride.ifBlank { null })
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            OutlinedButton(
                                onClick = { showScanner = false },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("Close Camera")
                            }
                        } else {
                            if (cameraDeniedMessage) {
                                Text(
                                    text = "Camera permission is required to scan the server QR code. You can also paste the pairing payload below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Button(
                                onClick = {
                                    if (hasCameraPermission) {
                                        showScanner = true
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("launch_qr_scanner_btn")
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Open QR Scanner")
                            }
                        }
                    }
                }
            }

            // Local Wi-Fi (NSD) Discovery
            item {
                Text(
                    text = "Local Wi-Fi Discovery (mDNS)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                )
            }

            if (lanServers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "Scanning Wi-Fi for _photonest._tcp services…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(lanServers, key = { it.serviceName }) { lan ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                hostOverride = lan.hostAddress
                                val match = firestoreServers.firstOrNull { it.id == lan.serverId }
                                selectedCloudServer = match?.copy(
                                    hostAddress = lan.hostAddress,
                                    port = lan.port.toLong()
                                ) ?: PhotoServerDoc(
                                    id = lan.serverId,
                                    userId = currentUserId,
                                    serverName = lan.serviceName,
                                    hostAddress = lan.hostAddress,
                                    port = lan.port.toLong(),
                                    certFingerprint = ""
                                )
                                cloudHostInput = lan.hostAddress
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = StatusOnlineGreen)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(lan.serviceName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${lan.hostAddress}:${lan.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("Select", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Same-Account Firestore Registered Servers
            if (firestoreServers.isNotEmpty()) {
                item {
                    Text(
                        text = "Your Account Servers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    )
                }
                items(firestoreServers, key = { it.id }) { srv ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                selectedCloudServer = srv
                                cloudHostInput = srv.hostAddress
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (srv.isOnline) Icons.Rounded.CloudDone else Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = if (srv.isOnline) StatusOnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(srv.serverName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${srv.hostAddress}:${srv.port} • ${if (srv.isOnline) "Online" else "Offline"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("Connect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Manual Payload / Remote VPN IP Override
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Manual / Remote VPN Pairing", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = hostOverride,
                            onValueChange = { hostOverride = it },
                            label = { Text("Optional Host / Tailscale / Relay IP") },
                            placeholder = { Text("e.g. 192.168.1.45 or 100.64.x.y") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = manualPayload,
                            onValueChange = { manualPayload = it },
                            label = { Text("Paste Pairing JSON Payload") },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().testTag("manual_pairing_payload_input")
                        )
                        Button(
                            onClick = { onPairWithPayload(manualPayload, hostOverride.ifBlank { null }) },
                            enabled = manualPayload.isNotBlank() && !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pair_manual_submit_btn")
                        ) {
                            Text("Pair Server")
                        }
                    }
                }
            }
        }
    }

    if (selectedCloudServer != null) {
        val srv = selectedCloudServer!!
        AlertDialog(
            onDismissRequest = { selectedCloudServer = null },
            title = { Text("Connect to ${srv.serverName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter the pairing secret shown on your server phone (inside the QR payload \"sec\" field) to complete TLS-pinned authorization.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = cloudHostInput,
                        onValueChange = { cloudHostInput = it },
                        label = { Text("Server Host / IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cloudPairingSecret,
                        onValueChange = { cloudPairingSecret = it },
                        label = { Text("Pairing Secret") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val payload = PairingPayload(
                            serverId = srv.id,
                            ownerUid = currentUserId,
                            serverName = srv.serverName,
                            hostAddress = cloudHostInput.trim().ifBlank { srv.hostAddress },
                            port = srv.port.toInt(),
                            certFingerprint = srv.certFingerprint,
                            pairingSecret = cloudPairingSecret.trim(),
                            expiresAtMillis = System.currentTimeMillis() + 3_600_000L
                        )
                        onPairWithPayload(PairingQrCodec.encodeToString(payload), cloudHostInput.trim().ifBlank { null })
                        cloudPairingSecret = ""
                        selectedCloudServer = null
                    },
                    enabled = cloudPairingSecret.isNotBlank()
                ) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedCloudServer = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun QrCameraPreview(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var alreadyHandled by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { imageProxy ->
                if (!alreadyHandled) {
                    val plane = imageProxy.planes.firstOrNull()
                    if (plane != null) {
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val decoded = PairingQrCodec.decodeQrFromYuv(
                            data = bytes,
                            width = imageProxy.width,
                            height = imageProxy.height
                        )
                        if (!decoded.isNullOrBlank() && PairingQrCodec.decodeFromString(decoded) != null) {
                            alreadyHandled = true
                            mainExecutor.execute { onQrDetected(decoded) }
                        }
                    }
                }
                imageProxy.close()
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }
        }, mainExecutor)

        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}
