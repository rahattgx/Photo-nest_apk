package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.ui.components.MediaThumbnailCard
import kotlinx.coroutines.delay
import java.io.File

private enum class CreationTool(val title: String, val subtitle: String, val icon: ImageVector) {
    COLLAGE("Collage", "Combine 2–4 photos into a high-res grid", Icons.Rounded.DashboardCustomize),
    ANIMATION("Animation", "Play and loop selected frames smoothly", Icons.Rounded.AutoAwesomeMotion),
    MOVIE("Highlight Movie", "Create a narrated visual reel album", Icons.Rounded.Movie),
    ALBUM("Album", "Organize photos & videos into a collection", Icons.Rounded.PhotoAlbum)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    mediaItems: List<MediaItemEntity>,
    activeServer: PairedServerEntity?,
    onCreateCollage: (List<MediaItemEntity>) -> Unit,
    onCreateAlbum: (String, List<String>) -> Unit,
    onShowMessage: (String) -> Unit
) {
    var activeTool by remember { mutableStateOf<CreationTool?>(null) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showAlbumPrompt by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var showAnimationPreview by remember { mutableStateOf(false) }

    BackHandler(enabled = activeTool != null) {
        activeTool = null
        selectedIds.clear()
    }

    val photosOnly = remember(mediaItems) { mediaItems.filter { !it.isVideo } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    if (activeTool != null && selectedIds.isNotEmpty()) {
                        Button(
                            onClick = {
                                val chosen = mediaItems.filter { it.id in selectedIds }
                                when (activeTool) {
                                    CreationTool.COLLAGE -> {
                                        if (chosen.size < 2) onShowMessage("Pick at least 2 photos for a collage")
                                        else {
                                            onCreateCollage(chosen)
                                            selectedIds.clear()
                                            activeTool = null
                                        }
                                    }
                                    CreationTool.ANIMATION -> {
                                        if (chosen.size < 2) onShowMessage("Pick at least 2 photos to animate")
                                        else showAnimationPreview = true
                                    }
                                    CreationTool.MOVIE, CreationTool.ALBUM -> showAlbumPrompt = true
                                    null -> Unit
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Create (${selectedIds.size})")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CreationTool.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            pair.forEach { tool ->
                                val isChosen = activeTool == tool
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isChosen) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable {
                                            activeTool = if (isChosen) null else tool
                                            selectedIds.clear()
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(tool.title, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = tool.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (activeTool != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Select items below for ${activeTool!!.title}, then tap Create.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            if (activeTool != null) {
                val pool = if (activeTool == CreationTool.COLLAGE || activeTool == CreationTool.ANIMATION) photosOnly else mediaItems
                items(pool, key = { it.id }) { item ->
                    val isSelected = item.id in selectedIds
                    MediaThumbnailCard(
                        item = item,
                        activeServer = activeServer,
                        isSelected = isSelected,
                        selectionMode = true,
                        onClick = {
                            if (isSelected) selectedIds.remove(item.id)
                            else if (activeTool != CreationTool.COLLAGE || selectedIds.size < 4) {
                                selectedIds.add(item.id)
                            }
                        },
                        onLongClick = {}
                    )
                }
            }
        }
    }

    if (showAlbumPrompt) {
        val isMovie = activeTool == CreationTool.MOVIE
        AlertDialog(
            onDismissRequest = { showAlbumPrompt = false },
            title = { Text(if (isMovie) "Name Highlight Movie" else "Name New Album") },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text(if (isMovie) "Movie title" else "Album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val prefix = if (isMovie) "Movie: " else ""
                    onCreateAlbum(prefix + titleInput.ifBlank { "Highlights" }, selectedIds.toList())
                    titleInput = ""
                    selectedIds.clear()
                    activeTool = null
                    showAlbumPrompt = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAlbumPrompt = false }) { Text("Cancel") }
            }
        )
    }

    if (showAnimationPreview) {
        val frames = remember(selectedIds.toList()) { mediaItems.filter { it.id in selectedIds } }
        var frameIndex by remember { mutableIntStateOf(0) }
        var intervalMs by remember { mutableFloatStateOf(450f) }

        LaunchedEffect(frames.size, intervalMs) {
            while (frames.isNotEmpty()) {
                delay(intervalMs.toLong())
                frameIndex = (frameIndex + 1) % frames.size
            }
        }

        AlertDialog(
            onDismissRequest = { showAnimationPreview = false },
            title = { Text("Animation Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val current = frames.getOrNull(frameIndex)
                    if (current != null) {
                        val model: Any = current.thumbLocalPath.takeIf { it.isNotBlank() && File(it).exists() }?.let(::File)
                            ?: if (activeServer != null && current.localFileUri.startsWith("http")) {
                                "https://${activeServer.hostAddress}:${activeServer.port}/api/v1/media/${current.id}/thumb"
                            } else {
                                current.localFileUri
                            }
                        Crossfade(targetState = model, label = "anim_frame") { frameModel ->
                            AsyncImage(
                                model = frameModel,
                                contentDescription = current.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.Black)
                            )
                        }
                    }
                    Text("Frame Speed: ${intervalMs.toInt()} ms", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = intervalMs,
                        onValueChange = { intervalMs = it },
                        valueRange = 150f..1200f
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateAlbum("Animation (${frames.size} frames)", frames.map { it.id })
                    showAnimationPreview = false
                    selectedIds.clear()
                    activeTool = null
                }) { Text("Save Frame Set") }
            },
            dismissButton = {
                TextButton(onClick = { showAnimationPreview = false }) { Text("Close") }
            }
        )
    }
}
