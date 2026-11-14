package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.data.BackupJobEntity
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.core.model.BackupSettings
import com.example.core.model.BackupStatus
import com.example.ui.components.MediaThumbnailCard
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    userPhotoUrl: String?,
    userName: String,
    mediaItems: List<MediaItemEntity>,
    backupQueue: List<BackupJobEntity>,
    activeServer: PairedServerEntity?,
    settings: BackupSettings,
    onOpenMedia: (MediaItemEntity) -> Unit,
    onPickMedia: (List<Uri>) -> Unit,
    onSyncNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenBackupSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onFavoriteSelected: (List<MediaItemEntity>) -> Unit,
    onTrashSelected: (List<MediaItemEntity>) -> Unit,
    onDownloadSelected: (List<MediaItemEntity>) -> Unit,
    onCreateAlbumFromSelected: (String, List<MediaItemEntity>) -> Unit
) {
    val selectedIds = remember { mutableStateListOf<String>() }
    val selectionMode = selectedIds.isNotEmpty()
    var showAlbumDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }

    BackHandler(enabled = selectionMode) {
        selectedIds.clear()
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) onPickMedia(uris)
    }

    val groupedByDate = remember(mediaItems) {
        val fmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
        mediaItems.groupBy { fmt.format(Date(it.takenAtMillis)) }
    }

    val monthMarkers = remember(mediaItems) {
        val mFmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val seen = linkedMapOf<String, Int>()
        var indexCursor = 1 // account for top status cards
        groupedByDate.forEach { (_, itemsInDay) ->
            val monthLabel = mFmt.format(Date(itemsInDay.first().takenAtMillis))
            if (!seen.containsKey(monthLabel)) {
                seen[monthLabel] = indexCursor
            }
            indexCursor += 1 + itemsInDay.size
        }
        seen.entries.toList()
    }

    val pendingCount = backupQueue.count { it.status == BackupStatus.PENDING.name || it.status == BackupStatus.UPLOADING.name }
    val failedCount = backupQueue.count { it.status == BackupStatus.FAILED.name }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        val chosen = mediaItems.filter { it.id in selectedIds }
                        IconButton(onClick = {
                            onFavoriteSelected(chosen)
                            selectedIds.clear()
                        }) {
                            Icon(Icons.Rounded.Favorite, contentDescription = "Favorite selected")
                        }
                        IconButton(onClick = {
                            onDownloadSelected(chosen)
                            selectedIds.clear()
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Download originals")
                        }
                        IconButton(onClick = { showAlbumDialog = true }) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Add to album")
                        }
                        IconButton(onClick = {
                            onTrashSelected(chosen)
                            selectedIds.clear()
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Move to trash")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("PhotoNest", style = MaterialTheme.typography.headlineSmall)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (activeServer?.isOnline == true) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            ) {
                                Text(
                                    text = activeServer?.serverName ?: "Unpaired",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onSyncNow, modifier = Modifier.testTag("sync_catalog_btn")) {
                            Icon(Icons.Rounded.Sync, contentDescription = "Sync with server")
                        }
                        IconButton(onClick = onOpenBackupSettings, modifier = Modifier.testTag("backup_status_btn")) {
                            Icon(Icons.Rounded.Backup, contentDescription = "Backup queue & settings")
                        }
                        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings_btn")) {
                            if (!userPhotoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = userPhotoUrl,
                                    contentDescription = userName,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.testTag("pick_media_fab")
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Add photos or videos to backup")
                }
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Status Banner (Server Pairing or Live Backup Progress)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeServer == null) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Rounded.CloudOff, contentDescription = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("No PhotoNest Server Paired", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Pair your home server phone to back up & browse originals.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Button(
                                    onClick = onOpenPairing,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("pair_server_banner_btn")
                                ) {
                                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Pair")
                                }
                            }
                        }
                    }

                    if (pendingCount > 0 || failedCount > 0 || settings.isPaused) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenBackupSettings)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when {
                                            settings.isPaused -> "Backup paused"
                                            pendingCount > 0 -> "Backing up $pendingCount item(s)…"
                                            else -> "$failedCount backup(s) failed"
                                        },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (failedCount > 0) {
                                        TextButton(onClick = onRetryFailed) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text("Retry")
                                        }
                                    }
                                }
                                if (pendingCount > 0 && !settings.isPaused) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    if (monthMarkers.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            monthMarkers.forEach { (month, targetIdx) ->
                                FilterChip(
                                    selected = false,
                                    onClick = { scope.launch { gridState.animateScrollToItem(targetIdx) } },
                                    label = { Text(month, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                    }
                }
            }

            if (mediaItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_empty_nest),
                            contentDescription = "Empty PhotoNest library",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text("Your PhotoNest library is ready", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Tap + to back up photos & videos from this phone, or configure Auto Backup folders to sync automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                            ) {
                                Text("Select Photos & Videos")
                            }
                            if (activeServer == null) {
                                TextButton(onClick = onOpenPairing) {
                                    Text("Pair Server")
                                }
                            }
                        }
                    }
                }
            } else {
                groupedByDate.forEach { (dateLabel, dayItems) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header_$dateLabel") {
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 4.dp)
                        )
                    }
                    items(dayItems, key = { it.id }) { media ->
                        val isSelected = media.id in selectedIds
                        MediaThumbnailCard(
                            item = media,
                            activeServer = activeServer,
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    if (isSelected) selectedIds.remove(media.id) else selectedIds.add(media.id)
                                } else {
                                    onOpenMedia(media)
                                }
                            },
                            onLongClick = {
                                if (!isSelected) selectedIds.add(media.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showAlbumDialog = false },
            title = { Text("Create Album") },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text("Album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = newAlbumName.isNotBlank(),
                    onClick = {
                        val chosen = mediaItems.filter { it.id in selectedIds }
                        onCreateAlbumFromSelected(newAlbumName.trim(), chosen)
                        newAlbumName = ""
                        selectedIds.clear()
                        showAlbumDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlbumDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
